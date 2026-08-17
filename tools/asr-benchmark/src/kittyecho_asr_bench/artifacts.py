from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Optional

from .contracts import ContractError, canonical_sha256


_RECEIPT_TYPE = "kittyecho-asr-json-artifact-v1"
_ARTIFACT_DIRECTORY = ".kittyecho-asr-artifacts"
_READ_ONLY_MODE = 0o400
_MAX_JSON_BYTES = 64 * 1024 * 1024
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_RUN_ID = re.compile(r"^run_[0-9a-f]{12}$")
_ARTIFACT_KIND = re.compile(r"^[a-z][a-z0-9-]{1,63}$")
_RESERVED_ANDROID_ARTIFACT_KINDS = frozenset(
    {
        "android-accuracy-output",
        "android-engineering-output",
        "android-attestation-verification",
        "android-external-observer-synthetic-verification",
    }
)
_RESERVED_MODEL_SECURITY_ARTIFACT_KINDS = frozenset(
    {
        "model-internal-risk-acceptance",
        "model-artifact-download-freeze",
        "formal-model-cohort-closure",
    }
)
_HARNESS_COMMITTABLE_MODEL_SECURITY_KINDS = frozenset(
    {
        "model-artifact-download-freeze",
        "formal-model-cohort-closure",
    }
)


@dataclass(frozen=True)
class _FileIdentity:
    st_dev: int
    st_ino: int
    size_bytes: int
    mode: int
    nlink: int

    def to_document(self) -> dict[str, int]:
        return {
            "st_dev": self.st_dev,
            "st_ino": self.st_ino,
            "size_bytes": self.size_bytes,
            "mode": self.mode,
            "nlink": self.nlink,
        }


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _validate_private_artifact_path(
    path: Path,
    repo_root: Path,
    *,
    context: str,
) -> Path:
    """Validate the parent boundary without dereferencing the final path."""
    candidate = Path(path)
    if not candidate.is_absolute() or not candidate.name:
        raise ContractError(f"{context} must be an absolute file path")
    try:
        resolved_parent = candidate.parent.resolve()
        resolved_repo = Path(repo_root).resolve()
    except OSError as error:
        raise ContractError(f"{context} parent cannot be resolved") from error
    if _is_relative_to(resolved_parent, resolved_repo):
        raise ContractError(f"{context} must remain outside the repository")
    return resolved_parent / candidate.name


def validate_private_artifact_input_path(
    path: Path,
    repo_root: Path,
) -> Path:
    return _validate_private_artifact_path(
        path,
        repo_root,
        context="private artifact receipt input",
    )


def validate_private_artifact_output_path(
    path: Path,
    repo_root: Path,
) -> Path:
    return _validate_private_artifact_path(
        path,
        repo_root,
        context="private artifact receipt output",
    )


def _fsync_directory(directory: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_DIRECTORY", 0)
    descriptor = os.open(str(directory), flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _private_temp_unlink(path: Path) -> None:
    """Remove only a never-published random temp path owned by this call."""
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def _resolved_output_path(path: Path, *, create_parent: bool) -> Path:
    candidate = Path(path)
    if not candidate.is_absolute():
        raise ContractError("artifact receipt path must be absolute")
    try:
        if create_parent:
            candidate.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        parent_metadata = candidate.parent.lstat()
    except OSError as error:
        raise ContractError("artifact receipt directory is unavailable") from error
    if stat.S_ISLNK(parent_metadata.st_mode) or not stat.S_ISDIR(
        parent_metadata.st_mode
    ):
        raise ContractError(
            "artifact receipt directory must be a non-symlink directory"
        )
    return candidate.parent.resolve() / candidate.name


def _prepare_artifact_directory(receipt_path: Path) -> Path:
    directory = receipt_path.parent / _ARTIFACT_DIRECTORY
    created = False
    try:
        try:
            directory.mkdir(mode=0o700)
            created = True
        except FileExistsError:
            pass
        metadata = directory.lstat()
    except OSError as error:
        raise ContractError("artifact transaction store is unavailable") from error
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        raise ContractError(
            "artifact transaction store must be a non-symlink directory"
        )
    if created:
        try:
            directory.chmod(0o700)
            _fsync_directory(receipt_path.parent)
        except OSError as error:
            raise ContractError(
                "artifact transaction store permissions cannot be tightened"
            ) from error
    elif stat.S_IMODE(metadata.st_mode) != 0o700:
        raise ContractError(
            "artifact transaction store permissions must be 0700"
        )
    return directory


def _safe_read_file(
    path: Path,
    *,
    context: str,
    expected_identity: Optional[_FileIdentity] = None,
    expected_mode: int = _READ_ONLY_MODE,
    expected_nlink: int = 1,
) -> tuple[bytes, _FileIdentity]:
    candidate = Path(path)
    if not candidate.is_absolute():
        raise ContractError(f"{context} path must be absolute")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    flags |= nofollow
    if nofollow == 0:
        try:
            if stat.S_ISLNK(candidate.lstat().st_mode):
                raise ContractError(f"{context} must not be a symlink")
        except FileNotFoundError as error:
            raise ContractError(f"{context} is missing") from error
        except OSError as error:
            raise ContractError(f"{context} cannot be inspected safely") from error
    try:
        descriptor = os.open(str(candidate), flags)
    except OSError as error:
        raise ContractError(
            f"{context} must be a readable non-symlink regular file"
        ) from error
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise ContractError(f"{context} must be a regular file")
        if before.st_size < 0 or before.st_size > _MAX_JSON_BYTES:
            raise ContractError(f"{context} exceeds the bounded JSON size")
        blocks: list[bytes] = []
        total = 0
        while True:
            block = os.read(descriptor, 1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > _MAX_JSON_BYTES:
                raise ContractError(f"{context} exceeds the bounded JSON size")
            blocks.append(block)
        after = os.fstat(descriptor)
        identity = _FileIdentity(
            st_dev=after.st_dev,
            st_ino=after.st_ino,
            size_bytes=after.st_size,
            mode=stat.S_IMODE(after.st_mode),
            nlink=after.st_nlink,
        )
        if (
            before.st_dev != after.st_dev
            or before.st_ino != after.st_ino
            or before.st_size != after.st_size
            or before.st_mode != after.st_mode
            or before.st_nlink != after.st_nlink
            or total != after.st_size
        ):
            raise ContractError(f"{context} changed while being read")
        if identity.mode != expected_mode:
            raise ContractError(f"{context} permissions are not read-only")
        if identity.nlink != expected_nlink:
            raise ContractError(f"{context} must not share its inode")
        if expected_identity is not None and identity != expected_identity:
            raise ContractError(f"{context} identity or inode changed")
        return b"".join(blocks), identity
    finally:
        os.close(descriptor)


def _encode_json(value: Any) -> bytes:
    try:
        return (
            json.dumps(
                value,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
                allow_nan=False,
            )
            + "\n"
        ).encode("utf-8")
    except (TypeError, ValueError):
        raise


def _decode_json_object(encoded: bytes, *, context: str) -> dict[str, Any]:
    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ContractError(f"{context} contains duplicate JSON key: {key}")
            result[key] = value
        return result

    def reject_constant(_value: str) -> None:
        raise ContractError(f"{context} contains a non-finite number")

    try:
        value = json.loads(
            encoded.decode("utf-8"),
            object_pairs_hook=unique_object,
            parse_constant=reject_constant,
        )
    except ContractError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ContractError(f"{context} is malformed JSON") from error
    if not isinstance(value, dict):
        raise ContractError(f"{context} must be a JSON object")
    return value


def _require_sha256(value: Any, *, context: str) -> str:
    if not isinstance(value, str) or not _SHA256.fullmatch(value):
        raise ContractError(f"{context} must be a lowercase sha256")
    return value


def _identity_from_document(value: Any, *, context: str) -> _FileIdentity:
    if not isinstance(value, Mapping):
        raise ContractError(f"{context} must be an object")
    expected = {"st_dev", "st_ino", "size_bytes", "mode", "nlink"}
    if set(value) != expected:
        raise ContractError(f"{context} fields are invalid")
    for key in expected:
        if (
            isinstance(value[key], bool)
            or not isinstance(value[key], int)
            or value[key] < 0
        ):
            raise ContractError(f"{context}.{key} must be a non-negative integer")
    identity = _FileIdentity(
        st_dev=value["st_dev"],
        st_ino=value["st_ino"],
        size_bytes=value["size_bytes"],
        mode=value["mode"],
        nlink=value["nlink"],
    )
    if identity.mode != _READ_ONLY_MODE or identity.nlink != 1:
        raise ContractError(f"{context} does not bind one read-only inode")
    return identity


def _write_temp(
    directory: Path,
    *,
    prefix: str,
    encoded: bytes,
) -> tuple[Path, _FileIdentity]:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=prefix,
        suffix=".tmp",
        dir=str(directory),
    )
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            descriptor = -1
            output.write(encoded)
            output.flush()
            os.fsync(output.fileno())
            os.fchmod(output.fileno(), _READ_ONLY_MODE)
            os.fsync(output.fileno())
            metadata = os.fstat(output.fileno())
        identity = _FileIdentity(
            st_dev=metadata.st_dev,
            st_ino=metadata.st_ino,
            size_bytes=metadata.st_size,
            mode=stat.S_IMODE(metadata.st_mode),
            nlink=metadata.st_nlink,
        )
        if identity.mode != _READ_ONLY_MODE or identity.nlink != 1:
            raise ContractError("private artifact temp identity is invalid")
        return temporary, identity
    except BaseException:
        if descriptor >= 0:
            os.close(descriptor)
        _private_temp_unlink(temporary)
        raise


def _publish_payload(
    artifact_directory: Path,
    encoded: bytes,
) -> tuple[Path, str, _FileIdentity]:
    digest = hashlib.sha256(encoded).hexdigest()
    target = artifact_directory / f"{digest}.json"
    if target.parent != artifact_directory:
        raise ContractError("content-addressed artifact path escaped its store")
    temporary, _temporary_identity = _write_temp(
        artifact_directory,
        prefix=".artifact-",
        encoded=encoded,
    )
    try:
        try:
            os.link(str(temporary), str(target), follow_symlinks=False)
            _private_temp_unlink(temporary)
            _fsync_directory(artifact_directory)
        except FileExistsError:
            _private_temp_unlink(temporary)
        actual, identity = _safe_read_file(
            target,
            context="content-addressed artifact",
        )
        if hashlib.sha256(actual).hexdigest() != digest or actual != encoded:
            raise ContractError(
                "existing content-addressed artifact does not match its digest"
            )
        return target, digest, identity
    finally:
        _private_temp_unlink(temporary)


def _validate_commit_arguments(
    document: Any,
    *,
    artifact_kind: str,
    run_id: str,
    evidence_sha256: str,
    plan_sha256: str,
) -> Mapping[str, Any]:
    if not isinstance(document, Mapping):
        raise ContractError("artifact payload must be a JSON object")
    if not _ARTIFACT_KIND.fullmatch(artifact_kind):
        raise ContractError("artifact_kind is invalid")
    if not _RUN_ID.fullmatch(run_id):
        raise ContractError("artifact run_id is invalid")
    _require_sha256(evidence_sha256, context="artifact evidence_sha256")
    _require_sha256(plan_sha256, context="artifact plan_sha256")
    payload_run_id = document.get("run_id")
    if payload_run_id is not None and payload_run_id != run_id:
        raise ContractError("artifact payload run_id does not match receipt")
    return document


def _commit_json_artifact(
    receipt_path: Path,
    document: Mapping[str, Any],
    *,
    artifact_kind: str,
    run_id: str,
    evidence_sha256: str,
    plan_sha256: str,
    validate_evidence: Callable[[], None],
) -> dict[str, Any]:
    """Publish a JSON blob first and its validity receipt last.

    Public blobs and receipts are never deleted by failure recovery. Only
    unpublished random temp files created by this invocation are removed.
    """
    document = _validate_commit_arguments(
        document,
        artifact_kind=artifact_kind,
        run_id=run_id,
        evidence_sha256=evidence_sha256,
        plan_sha256=plan_sha256,
    )
    if not callable(validate_evidence):
        raise ContractError("artifact evidence validator must be callable")
    target = _resolved_output_path(receipt_path, create_parent=True)
    artifact_directory = _prepare_artifact_directory(target)
    encoded_payload = _encode_json(document)

    validate_evidence()
    payload_path, payload_sha256, payload_identity = _publish_payload(
        artifact_directory,
        encoded_payload,
    )
    validate_evidence()
    payload_bytes, payload_identity = _safe_read_file(
        payload_path,
        context="content-addressed artifact",
        expected_identity=payload_identity,
    )
    if (
        hashlib.sha256(payload_bytes).hexdigest() != payload_sha256
        or payload_bytes != encoded_payload
    ):
        raise ContractError("content-addressed artifact changed before receipt")

    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{target.name}.receipt-",
        suffix=".tmp",
        dir=str(target.parent),
    )
    temporary = Path(temporary_name)
    try:
        metadata = os.fstat(descriptor)
        receipt_identity = _FileIdentity(
            st_dev=metadata.st_dev,
            st_ino=metadata.st_ino,
            size_bytes=0,
            mode=_READ_ONLY_MODE,
            nlink=1,
        )
        receipt_without_commit: dict[str, Any] = {
            "schema_version": "1.0",
            "receipt_type": _RECEIPT_TYPE,
            "artifact_kind": artifact_kind,
            "run_id": run_id,
            "evidence_sha256": evidence_sha256,
            "plan_sha256": plan_sha256,
            "artifact": {
                "path": str(payload_path),
                "sha256": payload_sha256,
                "canonical_sha256": canonical_sha256(document),
                **payload_identity.to_document(),
            },
            "receipt_identity": receipt_identity.to_document(),
        }
        receipt_without_commit["receipt_identity"]["size_bytes"] = 0
        provisional = {
            **receipt_without_commit,
            "commit_sha256": canonical_sha256(receipt_without_commit),
        }
        encoded_receipt = _encode_json(provisional)
        receipt_identity = _FileIdentity(
            st_dev=metadata.st_dev,
            st_ino=metadata.st_ino,
            size_bytes=len(encoded_receipt),
            mode=_READ_ONLY_MODE,
            nlink=1,
        )
        receipt_without_commit["receipt_identity"] = (
            receipt_identity.to_document()
        )
        receipt = {
            **receipt_without_commit,
            "commit_sha256": canonical_sha256(receipt_without_commit),
        }
        encoded_receipt = _encode_json(receipt)
        if len(encoded_receipt) != receipt_identity.size_bytes:
            receipt_identity = _FileIdentity(
                st_dev=metadata.st_dev,
                st_ino=metadata.st_ino,
                size_bytes=len(encoded_receipt),
                mode=_READ_ONLY_MODE,
                nlink=1,
            )
            receipt_without_commit["receipt_identity"] = (
                receipt_identity.to_document()
            )
            receipt = {
                **receipt_without_commit,
                "commit_sha256": canonical_sha256(receipt_without_commit),
            }
            encoded_receipt = _encode_json(receipt)
        if len(encoded_receipt) != receipt_identity.size_bytes:
            raise ContractError("artifact receipt size binding did not stabilize")

        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            descriptor = -1
            output.write(encoded_receipt)
            output.flush()
            os.fsync(output.fileno())
            os.fchmod(output.fileno(), _READ_ONLY_MODE)
            os.fsync(output.fileno())

        validate_evidence()
        final_payload, _final_payload_identity = _safe_read_file(
            payload_path,
            context="content-addressed artifact",
            expected_identity=payload_identity,
        )
        if (
            hashlib.sha256(final_payload).hexdigest() != payload_sha256
            or final_payload != encoded_payload
        ):
            raise ContractError(
                "content-addressed artifact changed before receipt publication"
            )
        _safe_read_file(
            temporary,
            context="private artifact receipt temp",
            expected_identity=receipt_identity,
        )
        try:
            os.link(str(temporary), str(target), follow_symlinks=False)
        except FileExistsError as error:
            raise ContractError(
                "artifact receipt already exists; refusing to overwrite"
            ) from error
        _private_temp_unlink(temporary)
        _fsync_directory(target.parent)
        committed_bytes, _committed_identity = _safe_read_file(
            target,
            context="artifact commit receipt",
            expected_identity=receipt_identity,
        )
        if committed_bytes != encoded_receipt:
            raise ContractError("artifact commit receipt changed during publication")
        return receipt
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        _private_temp_unlink(temporary)


def commit_json_artifact(
    receipt_path: Path,
    document: Mapping[str, Any],
    *,
    artifact_kind: str,
    run_id: str,
    evidence_sha256: str,
    plan_sha256: str,
    validate_evidence: Callable[[], None],
) -> dict[str, Any]:
    """Commit a non-reserved JSON artifact through the public API."""
    if artifact_kind in _RESERVED_ANDROID_ARTIFACT_KINDS:
        raise ContractError(
            "reserved Android artifact kind requires trusted verifier commit"
        )
    if artifact_kind in _RESERVED_MODEL_SECURITY_ARTIFACT_KINDS:
        raise ContractError(
            "reserved model security artifact kind requires a trusted private commit"
        )
    return _commit_json_artifact(
        receipt_path,
        document,
        artifact_kind=artifact_kind,
        run_id=run_id,
        evidence_sha256=evidence_sha256,
        plan_sha256=plan_sha256,
        validate_evidence=validate_evidence,
    )


def _commit_reserved_android_artifact(
    receipt_path: Path,
    document: Mapping[str, Any],
    *,
    artifact_kind: str,
    run_id: str,
    evidence_sha256: str,
    plan_sha256: str,
    validate_document: Callable[[], None],
    validate_evidence: Callable[[], None],
) -> dict[str, Any]:
    """Private commit path restricted to Android verifier-owned kinds."""
    if artifact_kind not in _RESERVED_ANDROID_ARTIFACT_KINDS:
        raise ContractError("trusted Android commit requires a reserved kind")
    if not callable(validate_document):
        raise ContractError("Android artifact document validator must be callable")
    if not callable(validate_evidence):
        raise ContractError("Android artifact evidence validator must be callable")

    def validate_reserved_artifact() -> None:
        validate_document()
        validate_evidence()

    return _commit_json_artifact(
        receipt_path,
        document,
        artifact_kind=artifact_kind,
        run_id=run_id,
        evidence_sha256=evidence_sha256,
        plan_sha256=plan_sha256,
        validate_evidence=validate_reserved_artifact,
    )


def _commit_reserved_model_security_artifact(
    receipt_path: Path,
    document: Mapping[str, Any],
    *,
    artifact_kind: str,
    run_id: str,
    evidence_sha256: str,
    plan_sha256: str,
    validate_document: Callable[[], None],
    validate_evidence: Callable[[], None],
) -> dict[str, Any]:
    """Private commit path for verifier-derived model security evidence.

    User risk acceptance is deliberately excluded: this host harness has no
    authority to approve that decision and therefore has no issuer for its
    reserved receipt kind.
    """
    if artifact_kind not in _HARNESS_COMMITTABLE_MODEL_SECURITY_KINDS:
        raise ContractError(
            "model security kind is not issuable by this host harness"
        )
    if not callable(validate_document):
        raise ContractError(
            "model security artifact document validator must be callable"
        )
    if not callable(validate_evidence):
        raise ContractError(
            "model security artifact evidence validator must be callable"
        )

    def validate_reserved_artifact() -> None:
        validate_document()
        validate_evidence()

    return _commit_json_artifact(
        receipt_path,
        document,
        artifact_kind=artifact_kind,
        run_id=run_id,
        evidence_sha256=evidence_sha256,
        plan_sha256=plan_sha256,
        validate_evidence=validate_reserved_artifact,
    )


def _validate_receipt_document(
    receipt: Mapping[str, Any],
    *,
    receipt_path: Path,
    receipt_identity: _FileIdentity,
) -> tuple[dict[str, Any], _FileIdentity]:
    expected_keys = {
        "schema_version",
        "receipt_type",
        "artifact_kind",
        "run_id",
        "evidence_sha256",
        "plan_sha256",
        "artifact",
        "receipt_identity",
        "commit_sha256",
    }
    if set(receipt) != expected_keys:
        raise ContractError("artifact receipt fields are invalid")
    if (
        receipt["schema_version"] != "1.0"
        or receipt["receipt_type"] != _RECEIPT_TYPE
    ):
        raise ContractError("artifact receipt type or version is invalid")
    if (
        not isinstance(receipt["artifact_kind"], str)
        or not _ARTIFACT_KIND.fullmatch(receipt["artifact_kind"])
    ):
        raise ContractError("artifact receipt kind is invalid")
    if (
        not isinstance(receipt["run_id"], str)
        or not _RUN_ID.fullmatch(receipt["run_id"])
    ):
        raise ContractError("artifact receipt run_id is invalid")
    _require_sha256(
        receipt["evidence_sha256"],
        context="artifact receipt evidence_sha256",
    )
    _require_sha256(
        receipt["plan_sha256"],
        context="artifact receipt plan_sha256",
    )
    commit_sha256 = _require_sha256(
        receipt["commit_sha256"],
        context="artifact receipt commit_sha256",
    )
    without_commit = dict(receipt)
    del without_commit["commit_sha256"]
    if canonical_sha256(without_commit) != commit_sha256:
        raise ContractError("artifact receipt commit hash mismatch")
    bound_receipt_identity = _identity_from_document(
        receipt["receipt_identity"],
        context="artifact receipt identity",
    )
    if receipt_identity != bound_receipt_identity:
        raise ContractError("artifact receipt identity or inode changed")

    artifact = receipt["artifact"]
    if not isinstance(artifact, Mapping):
        raise ContractError("artifact receipt payload binding must be an object")
    artifact_keys = {
        "path",
        "sha256",
        "canonical_sha256",
        "st_dev",
        "st_ino",
        "size_bytes",
        "mode",
        "nlink",
    }
    if set(artifact) != artifact_keys:
        raise ContractError("artifact receipt payload binding fields are invalid")
    payload_sha256 = _require_sha256(
        artifact["sha256"],
        context="artifact receipt payload sha256",
    )
    _require_sha256(
        artifact["canonical_sha256"],
        context="artifact receipt canonical sha256",
    )
    if not isinstance(artifact["path"], str):
        raise ContractError("artifact receipt payload path is invalid")
    expected_path = (
        receipt_path.parent
        / _ARTIFACT_DIRECTORY
        / f"{payload_sha256}.json"
    )
    payload_path = Path(artifact["path"])
    if not payload_path.is_absolute() or payload_path != expected_path:
        raise ContractError(
            "artifact receipt payload path is not the bound content-addressed path"
        )
    payload_identity = _identity_from_document(
        {key: artifact[key] for key in _FileIdentity.__dataclass_fields__},
        context="artifact receipt payload identity",
    )
    return dict(artifact), payload_identity


def load_committed_json_artifact(
    receipt_path: Path,
    *,
    expected_kind: Optional[str] = None,
    expected_evidence_sha256: Optional[str] = None,
    expected_plan_sha256: Optional[str] = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Load an artifact only through its self-bound valid commit receipt."""
    target = _resolved_output_path(receipt_path, create_parent=False)
    encoded_receipt, receipt_identity = _safe_read_file(
        target,
        context="artifact commit receipt",
    )
    receipt = _decode_json_object(
        encoded_receipt,
        context="artifact commit receipt",
    )
    artifact, payload_identity = _validate_receipt_document(
        receipt,
        receipt_path=target,
        receipt_identity=receipt_identity,
    )
    if expected_kind is not None and receipt["artifact_kind"] != expected_kind:
        raise ContractError("artifact receipt kind does not match its consumer")
    if (
        expected_evidence_sha256 is not None
        and receipt["evidence_sha256"] != expected_evidence_sha256
    ):
        raise ContractError("artifact receipt evidence fingerprint mismatch")
    if (
        expected_plan_sha256 is not None
        and receipt["plan_sha256"] != expected_plan_sha256
    ):
        raise ContractError("artifact receipt plan fingerprint mismatch")

    payload_path = Path(artifact["path"])
    encoded_payload, actual_payload_identity = _safe_read_file(
        payload_path,
        context="committed artifact payload",
        expected_identity=payload_identity,
    )
    if actual_payload_identity.size_bytes != artifact["size_bytes"]:
        raise ContractError("committed artifact payload size changed")
    if hashlib.sha256(encoded_payload).hexdigest() != artifact["sha256"]:
        raise ContractError("committed artifact payload hash mismatch")
    document = _decode_json_object(
        encoded_payload,
        context="committed artifact payload",
    )
    if canonical_sha256(document) != artifact["canonical_sha256"]:
        raise ContractError("committed artifact canonical hash mismatch")
    if document.get("run_id") not in {None, receipt["run_id"]}:
        raise ContractError("committed artifact run_id does not match its receipt")
    return document, receipt
