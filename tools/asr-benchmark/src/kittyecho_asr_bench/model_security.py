from __future__ import annotations

import hashlib
import json
import os
import stat
import weakref
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Optional, Sequence

from .artifacts import (
    _commit_reserved_model_security_artifact,
    load_committed_json_artifact,
    validate_private_artifact_input_path,
    validate_private_artifact_output_path,
)
from .contracts import (
    INTERNAL_EVALUATION_RESTRICTIONS,
    ContractError,
    _registry_model,
    canonical_sha256,
    validate_model_registry,
)


TRUSTED_REGISTRY_CANONICAL_SHA256 = (
    "ac813fa0649bbd1a554603ae4c5a5301c48b0d5839611ea59ffe32b6373db860"
)
TRUSTED_REGISTRY_RAW_SHA256 = (
    "7a6d53f211618f6dbdee8f2c89e08b3c1feb928c6368cf8d3a97886647e42636"
)
_HARNESS_ROOT = Path(__file__).resolve().parents[2]
_TRUSTED_REGISTRY_PATH = (
    _HARNESS_ROOT / "model-registry" / "candidates-v1.json"
)
_DOWNLOAD_FREEZE_KIND = "model-artifact-download-freeze"
_RISK_ACCEPTANCE_KIND = "model-internal-risk-acceptance"
_FORMAL_COHORT_CLOSURE_KIND = "formal-model-cohort-closure"
_DOWNLOAD_FREEZE_CONTRACT = "model-artifact-download-freeze-v1"
_RISK_ACCEPTANCE_CONTRACT = "model-internal-risk-acceptance-v1"
_FORMAL_COHORT_CLOSURE_CONTRACT = "formal-model-cohort-closure-v1"
_INTERNAL_SCOPE = "internal_evaluation_download_only"
_MAX_REGISTRY_BYTES = 4 * 1024 * 1024


@dataclass(frozen=True)
class _FileMeasurement:
    path: Path
    st_dev: int
    st_ino: int
    size_bytes: int
    mode: int
    nlink: int
    sha256: str

    def identity_document(self) -> dict[str, int]:
        return {
            "st_dev": self.st_dev,
            "st_ino": self.st_ino,
            "size_bytes": self.size_bytes,
            "mode": self.mode,
            "nlink": self.nlink,
        }


@dataclass(frozen=True)
class TrustedModelRegistrySnapshot:
    """Read-only copy of the one source-controlled registry trust anchor."""

    _canonical_json: bytes
    sha256: str
    raw_sha256: str
    path: Path

    @property
    def document(self) -> dict[str, Any]:
        return json.loads(self._canonical_json.decode("utf-8"))


@dataclass(frozen=True)
class DownloadFreezeEvidence:
    receipt_path: Path
    receipt_commit_sha256: str
    registry_sha256: str
    model_id: str
    model_revision: str
    model_revision_kind: str
    artifact_filename: str
    artifact_component: str
    artifact_source_url: str
    artifact_source_revision: str
    artifact_source_revision_kind: str
    measured_bytes: int
    measured_sha256: str
    measurement: _FileMeasurement


class VerifiedFormalModelCohort:
    """Process-local capability backed by revalidated private receipts."""

    __slots__ = ("__weakref__",)

    def __new__(
        cls,
        *_args: Any,
        **_kwargs: Any,
    ) -> "VerifiedFormalModelCohort":
        raise ContractError(
            "VerifiedFormalModelCohort requires the trusted receipt verifier"
        )

    def __setattr__(self, _name: str, _value: Any) -> None:
        raise ContractError("VerifiedFormalModelCohort is immutable")


@dataclass(frozen=True)
class _FormalModelCohortRecord:
    profile: str
    cohort_id: str
    model_ids: tuple[str, ...]
    baseline_model_id: str
    registry_sha256: str
    repo_root: Path
    artifact_receipt_paths: tuple[Path, ...]
    risk_acceptance_receipt_paths: tuple[tuple[str, Path], ...]
    artifact_set_sha256: str
    risk_acceptance_set_sha256: str
    cohort_commitment_sha256: str


_VERIFIED_FORMAL_MODEL_COHORTS: weakref.WeakKeyDictionary[
    VerifiedFormalModelCohort,
    _FormalModelCohortRecord,
] = weakref.WeakKeyDictionary()


def _decode_json_object(encoded: bytes, *, context: str) -> dict[str, Any]:
    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            if key in value:
                raise ContractError(f"{context} contains duplicate JSON key: {key}")
            value[key] = item
        return value

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


def _measure_regular_file(
    path: Path,
    *,
    context: str,
    collect_bytes: bool = False,
    max_bytes: Optional[int] = None,
    expected: Optional[_FileMeasurement] = None,
) -> tuple[_FileMeasurement, Optional[bytes]]:
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
        if before.st_size < 0:
            raise ContractError(f"{context} has an invalid size")
        if max_bytes is not None and before.st_size > max_bytes:
            raise ContractError(f"{context} exceeds its bounded size")
        if before.st_nlink != 1:
            raise ContractError(f"{context} must not share its inode")
        digest = hashlib.sha256()
        blocks: list[bytes] = []
        total = 0
        while True:
            block = os.read(descriptor, 1024 * 1024)
            if not block:
                break
            total += len(block)
            if max_bytes is not None and total > max_bytes:
                raise ContractError(f"{context} exceeds its bounded size")
            digest.update(block)
            if collect_bytes:
                blocks.append(block)
        after = os.fstat(descriptor)
        if (
            before.st_dev != after.st_dev
            or before.st_ino != after.st_ino
            or before.st_size != after.st_size
            or before.st_mode != after.st_mode
            or before.st_nlink != after.st_nlink
            or total != after.st_size
        ):
            raise ContractError(f"{context} changed while being measured")
        measurement = _FileMeasurement(
            path=candidate,
            st_dev=after.st_dev,
            st_ino=after.st_ino,
            size_bytes=after.st_size,
            mode=stat.S_IMODE(after.st_mode),
            nlink=after.st_nlink,
            sha256=digest.hexdigest(),
        )
        if expected is not None and measurement != expected:
            raise ContractError(f"{context} identity, size, mode, or hash changed")
        return measurement, b"".join(blocks) if collect_bytes else None
    finally:
        os.close(descriptor)


def _canonical_registry_bytes(document: Mapping[str, Any]) -> bytes:
    return json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def load_trusted_model_registry() -> TrustedModelRegistrySnapshot:
    """Load the fixed registry and verify both raw and canonical commitments."""
    measurement, encoded = _measure_regular_file(
        _TRUSTED_REGISTRY_PATH,
        context="trusted registry",
        collect_bytes=True,
        max_bytes=_MAX_REGISTRY_BYTES,
    )
    if measurement.sha256 != TRUSTED_REGISTRY_RAW_SHA256:
        raise ContractError("trusted registry raw fingerprint mismatch")
    assert encoded is not None
    document = validate_model_registry(
        _decode_json_object(encoded, context="trusted registry")
    )
    canonical_digest = canonical_sha256(document)
    if canonical_digest != TRUSTED_REGISTRY_CANONICAL_SHA256:
        raise ContractError("trusted registry canonical fingerprint mismatch")
    return TrustedModelRegistrySnapshot(
        _canonical_json=_canonical_registry_bytes(document),
        sha256=canonical_digest,
        raw_sha256=measurement.sha256,
        path=_TRUSTED_REGISTRY_PATH,
    )


def registry_path_is_trusted(path: Path) -> bool:
    candidate = Path(path)
    if not candidate.is_absolute():
        return False
    try:
        if stat.S_ISLNK(candidate.lstat().st_mode):
            return False
        return candidate.resolve(strict=True) == _TRUSTED_REGISTRY_PATH.resolve(
            strict=True
        )
    except OSError:
        return False


def _registry_matches_trust_anchor(
    registry_data: Any,
    trusted: TrustedModelRegistrySnapshot,
) -> bool:
    try:
        candidate = validate_model_registry(registry_data)
    except ContractError:
        raise
    return canonical_sha256(candidate) == trusted.sha256


def _artifact_for(
    registry: Mapping[str, Any],
    model_id: str,
    artifact_filename: str,
) -> tuple[Mapping[str, Any], Mapping[str, Any]]:
    model = _registry_model(registry, model_id, context="model artifact")
    artifact = next(
        (
            item
            for item in model["artifacts"]
            if item["filename"] == artifact_filename
        ),
        None,
    )
    if artifact is None:
        raise ContractError(
            "artifact filename is not present for the trusted registry model"
        )
    return model, artifact


def _artifact_binding(
    trusted: TrustedModelRegistrySnapshot,
    model: Mapping[str, Any],
    artifact: Mapping[str, Any],
) -> dict[str, Any]:
    source = model["official_source"]
    return {
        "contract_id": _DOWNLOAD_FREEZE_CONTRACT,
        "registry_snapshot_sha256": trusted.sha256,
        "model_id": model["model_id"],
        "model_revision": source["revision"],
        "model_revision_kind": source["revision_kind"],
        "artifact": {
            "filename": artifact["filename"],
            "component": artifact["component"],
            "source_url": artifact["source_url"],
            "source_revision": artifact["source_revision"],
            "source_revision_kind": artifact["source_revision_kind"],
            "registry_expected_bytes": artifact["expected_bytes"],
            "registry_expected_sha256": artifact["sha256"],
        },
    }


def _measurement_document(measurement: _FileMeasurement) -> dict[str, Any]:
    return {
        "path": str(measurement.path),
        "sha256": measurement.sha256,
        **measurement.identity_document(),
    }


def _measurement_from_document(value: Any) -> _FileMeasurement:
    if not isinstance(value, Mapping):
        raise ContractError("download freeze measurement must be an object")
    expected_keys = {
        "path",
        "sha256",
        "st_dev",
        "st_ino",
        "size_bytes",
        "mode",
        "nlink",
    }
    if set(value) != expected_keys:
        raise ContractError("download freeze measurement fields are invalid")
    path = value["path"]
    digest = value["sha256"]
    if not isinstance(path, str) or not Path(path).is_absolute():
        raise ContractError("download freeze artifact path must be absolute")
    if (
        not isinstance(digest, str)
        or len(digest) != 64
        or any(character not in "0123456789abcdef" for character in digest)
    ):
        raise ContractError("download freeze digest must be a lowercase sha256")
    numeric = {}
    for key in ("st_dev", "st_ino", "size_bytes", "mode", "nlink"):
        item = value[key]
        if isinstance(item, bool) or not isinstance(item, int) or item < 0:
            raise ContractError(
                f"download freeze measurement.{key} must be non-negative"
            )
        numeric[key] = item
    if numeric["nlink"] != 1:
        raise ContractError("download freeze artifact must bind one inode")
    return _FileMeasurement(
        path=Path(path),
        sha256=digest,
        **numeric,
    )


def _validate_download_freeze_document(
    document: Any,
    *,
    trusted: TrustedModelRegistrySnapshot,
    expected_model_id: Optional[str] = None,
    expected_artifact_filename: Optional[str] = None,
) -> tuple[
    Mapping[str, Any],
    Mapping[str, Any],
    _FileMeasurement,
    str,
]:
    if not isinstance(document, Mapping):
        raise ContractError("download freeze receipt payload must be an object")
    expected_keys = {
        "schema_version",
        "run_id",
        "receipt_contract_id",
        "scope",
        "registry_snapshot_sha256",
        "model_id",
        "model_revision",
        "model_revision_kind",
        "artifact",
        "measurement",
    }
    if set(document) != expected_keys:
        raise ContractError("download freeze receipt payload fields are invalid")
    if (
        document["schema_version"] != "1.0"
        or document["receipt_contract_id"] != _DOWNLOAD_FREEZE_CONTRACT
        or document["scope"] != _INTERNAL_SCOPE
        or document["registry_snapshot_sha256"] != trusted.sha256
    ):
        raise ContractError("download freeze receipt contract binding is invalid")
    model_id = document["model_id"]
    if not isinstance(model_id, str):
        raise ContractError("download freeze model_id is invalid")
    if expected_model_id is not None and model_id != expected_model_id:
        raise ContractError("download freeze receipt belongs to another model")
    artifact_document = document["artifact"]
    if not isinstance(artifact_document, Mapping):
        raise ContractError("download freeze artifact binding must be an object")
    if set(artifact_document) != {
        "filename",
        "component",
        "source_url",
        "source_revision",
        "source_revision_kind",
        "registry_expected_bytes",
        "registry_expected_sha256",
    }:
        raise ContractError("download freeze artifact binding fields are invalid")
    filename = artifact_document["filename"]
    if not isinstance(filename, str):
        raise ContractError("download freeze artifact filename is invalid")
    if (
        expected_artifact_filename is not None
        and filename != expected_artifact_filename
    ):
        raise ContractError("download freeze receipt belongs to another artifact")
    model, artifact = _artifact_for(trusted.document, model_id, filename)
    source = model["official_source"]
    binding = _artifact_binding(trusted, model, artifact)
    if (
        document["model_revision"] != source["revision"]
        or document["model_revision_kind"] != source["revision_kind"]
        or dict(artifact_document) != binding["artifact"]
    ):
        raise ContractError(
            "download freeze model revision or artifact source binding differs"
        )
    measurement = _measurement_from_document(document["measurement"])
    expected_bytes = artifact["expected_bytes"]
    expected_sha256 = artifact["sha256"]
    if expected_bytes is not None and measurement.size_bytes != expected_bytes:
        raise ContractError("download freeze artifact byte count differs")
    if expected_sha256 is not None and measurement.sha256 != expected_sha256:
        raise ContractError("download freeze artifact digest differs")
    run_material = {
        **binding,
        "measurement": _measurement_document(measurement),
    }
    expected_run_id = "run_" + canonical_sha256(run_material)[:12]
    if document["run_id"] != expected_run_id:
        raise ContractError("download freeze run_id binding is invalid")
    return model, artifact, measurement, canonical_sha256(binding)


def freeze_model_artifact(
    *,
    model_id: str,
    artifact_filename: str,
    artifact_path: Path,
    receipt_path: Path,
    repo_root: Path,
) -> dict[str, Any]:
    """Measure one downloaded artifact and commit a private freeze receipt."""
    trusted = load_trusted_model_registry()
    model, artifact = _artifact_for(
        trusted.document,
        model_id,
        artifact_filename,
    )
    safe_artifact_path = validate_private_artifact_input_path(
        artifact_path,
        repo_root,
    )
    safe_receipt_path = validate_private_artifact_output_path(
        receipt_path,
        repo_root,
    )
    measurement, _unused = _measure_regular_file(
        safe_artifact_path,
        context="downloaded model artifact",
    )
    expected_bytes = artifact["expected_bytes"]
    expected_sha256 = artifact["sha256"]
    if expected_bytes is not None and measurement.size_bytes != expected_bytes:
        raise ContractError("downloaded model artifact byte count differs")
    if expected_sha256 is not None and measurement.sha256 != expected_sha256:
        raise ContractError("downloaded model artifact digest differs")
    binding = _artifact_binding(trusted, model, artifact)
    run_material = {
        **binding,
        "measurement": _measurement_document(measurement),
    }
    run_id = "run_" + canonical_sha256(run_material)[:12]
    document = {
        "schema_version": "1.0",
        "run_id": run_id,
        "receipt_contract_id": _DOWNLOAD_FREEZE_CONTRACT,
        "scope": _INTERNAL_SCOPE,
        "registry_snapshot_sha256": trusted.sha256,
        "model_id": model["model_id"],
        "model_revision": model["official_source"]["revision"],
        "model_revision_kind": model["official_source"]["revision_kind"],
        "artifact": binding["artifact"],
        "measurement": _measurement_document(measurement),
    }

    def validate_document() -> None:
        _validate_download_freeze_document(
            document,
            trusted=load_trusted_model_registry(),
            expected_model_id=model_id,
            expected_artifact_filename=artifact_filename,
        )

    def validate_evidence() -> None:
        current_registry = load_trusted_model_registry()
        if current_registry.sha256 != trusted.sha256:
            raise ContractError("trusted registry changed during artifact freeze")
        _measure_regular_file(
            safe_artifact_path,
            context="downloaded model artifact",
            expected=measurement,
        )

    return _commit_reserved_model_security_artifact(
        safe_receipt_path,
        document,
        artifact_kind=_DOWNLOAD_FREEZE_KIND,
        run_id=run_id,
        evidence_sha256=trusted.sha256,
        plan_sha256=canonical_sha256(binding),
        validate_document=validate_document,
        validate_evidence=validate_evidence,
    )


def load_download_freeze_receipt(
    receipt_path: Path,
    *,
    model_id: Optional[str] = None,
    artifact_filename: Optional[str] = None,
    repo_root: Path,
) -> DownloadFreezeEvidence:
    trusted = load_trusted_model_registry()
    safe_receipt_path = validate_private_artifact_input_path(
        receipt_path,
        repo_root,
    )
    document, receipt = load_committed_json_artifact(
        safe_receipt_path,
        expected_kind=_DOWNLOAD_FREEZE_KIND,
        expected_evidence_sha256=trusted.sha256,
    )
    model, artifact, measurement, binding_sha256 = (
        _validate_download_freeze_document(
            document,
            trusted=trusted,
            expected_model_id=model_id,
            expected_artifact_filename=artifact_filename,
        )
    )
    if receipt["plan_sha256"] != binding_sha256:
        raise ContractError("download freeze receipt plan binding differs")
    safe_artifact_path = validate_private_artifact_input_path(
        measurement.path,
        repo_root,
    )
    if safe_artifact_path != measurement.path:
        raise ContractError("download freeze artifact path changed")
    _measure_regular_file(
        safe_artifact_path,
        context="frozen model artifact",
        expected=measurement,
    )
    return DownloadFreezeEvidence(
        receipt_path=safe_receipt_path,
        receipt_commit_sha256=receipt["commit_sha256"],
        registry_sha256=trusted.sha256,
        model_id=model["model_id"],
        model_revision=model["official_source"]["revision"],
        model_revision_kind=model["official_source"]["revision_kind"],
        artifact_filename=artifact["filename"],
        artifact_component=artifact["component"],
        artifact_source_url=artifact["source_url"],
        artifact_source_revision=artifact["source_revision"],
        artifact_source_revision_kind=artifact["source_revision_kind"],
        measured_bytes=measurement.size_bytes,
        measured_sha256=measurement.sha256,
        measurement=measurement,
    )


def _validate_risk_acceptance_document(
    document: Any,
    *,
    trusted: TrustedModelRegistrySnapshot,
    model_id: str,
) -> str:
    if not isinstance(document, Mapping):
        raise ContractError("risk acceptance receipt payload must be an object")
    expected_keys = {
        "schema_version",
        "run_id",
        "receipt_contract_id",
        "scope",
        "approval_status",
        "registry_snapshot_sha256",
        "model_id",
        "model_revision",
        "model_revision_kind",
        "restrictions",
        "authority_decision_commitment_sha256",
    }
    if set(document) != expected_keys:
        raise ContractError("risk acceptance receipt payload fields are invalid")
    model = _registry_model(
        trusted.document,
        model_id,
        context="risk acceptance",
    )
    source = model["official_source"]
    binding = {
        "contract_id": _RISK_ACCEPTANCE_CONTRACT,
        "scope": _INTERNAL_SCOPE,
        "approval_status": "approved",
        "registry_snapshot_sha256": trusted.sha256,
        "model_id": model_id,
        "model_revision": source["revision"],
        "model_revision_kind": source["revision_kind"],
        "restrictions": list(INTERNAL_EVALUATION_RESTRICTIONS),
        "authority_decision_commitment_sha256": (
            document.get("authority_decision_commitment_sha256")
        ),
    }
    if (
        document.get("schema_version") != "1.0"
        or document.get("receipt_contract_id") != _RISK_ACCEPTANCE_CONTRACT
        or document.get("scope") != _INTERNAL_SCOPE
        or document.get("approval_status") != "approved"
        or document.get("registry_snapshot_sha256") != trusted.sha256
        or document.get("model_id") != model_id
        or document.get("model_revision") != source["revision"]
        or document.get("model_revision_kind") != source["revision_kind"]
        or document.get("restrictions")
        != list(INTERNAL_EVALUATION_RESTRICTIONS)
    ):
        raise ContractError("risk acceptance receipt binding is invalid")
    authority = binding["authority_decision_commitment_sha256"]
    if (
        not isinstance(authority, str)
        or len(authority) != 64
        or any(character not in "0123456789abcdef" for character in authority)
    ):
        raise ContractError(
            "risk acceptance authority commitment must be a lowercase sha256"
        )
    expected_run_id = "run_" + canonical_sha256(binding)[:12]
    if document.get("run_id") != expected_run_id:
        raise ContractError("risk acceptance run_id binding is invalid")
    return canonical_sha256(binding)


def load_internal_risk_acceptance_receipt(
    receipt_path: Path,
    *,
    model_id: str,
    repo_root: Path,
) -> dict[str, Any]:
    """Validate a pre-existing external decision receipt.

    There is intentionally no corresponding issuer in this harness.
    """
    trusted = load_trusted_model_registry()
    safe_path = validate_private_artifact_input_path(receipt_path, repo_root)
    document, receipt = load_committed_json_artifact(
        safe_path,
        expected_kind=_RISK_ACCEPTANCE_KIND,
        expected_evidence_sha256=trusted.sha256,
    )
    binding_sha256 = _validate_risk_acceptance_document(
        document,
        trusted=trusted,
        model_id=model_id,
    )
    if receipt["plan_sha256"] != binding_sha256:
        raise ContractError("risk acceptance receipt plan binding differs")
    return receipt


def trusted_internal_evaluation_download_blockers(
    registry_data: Any,
    model_id: str,
    *,
    risk_acceptance_receipt_path: Optional[Path] = None,
    repo_root: Optional[Path] = None,
) -> list[str]:
    trusted = load_trusted_model_registry()
    blockers: list[str] = []
    if not _registry_matches_trust_anchor(registry_data, trusted):
        blockers.append("untrusted_registry_snapshot")
    model = _registry_model(
        trusted.document,
        model_id,
        context="internal evaluation download",
    )
    policy = model["evaluation_policy"]["internal_evaluation_download"]
    if policy["clearance"] == "requires_user_risk_acceptance":
        if risk_acceptance_receipt_path is None:
            blockers.append("user_risk_acceptance_receipt_required")
        elif repo_root is None:
            blockers.append("user_risk_acceptance_receipt_invalid")
        else:
            try:
                load_internal_risk_acceptance_receipt(
                    risk_acceptance_receipt_path,
                    model_id=model_id,
                    repo_root=repo_root,
                )
            except ContractError:
                blockers.append("user_risk_acceptance_receipt_invalid")
    return list(dict.fromkeys(blockers))


def trusted_formal_benchmark_artifact_blockers(
    registry_data: Any,
    model_id: str,
    *,
    artifact_freeze_receipt_paths: Sequence[Path] = (),
    risk_acceptance_receipt_path: Optional[Path] = None,
    repo_root: Optional[Path] = None,
) -> list[str]:
    trusted = load_trusted_model_registry()
    blockers = trusted_internal_evaluation_download_blockers(
        registry_data,
        model_id,
        risk_acceptance_receipt_path=risk_acceptance_receipt_path,
        repo_root=repo_root,
    )
    model = _registry_model(
        trusted.document,
        model_id,
        context="formal benchmark",
    )
    frozen: dict[tuple[str, str], DownloadFreezeEvidence] = {}
    invalid_receipt = False
    for receipt_path in artifact_freeze_receipt_paths:
        if repo_root is None:
            invalid_receipt = True
            continue
        try:
            evidence = load_download_freeze_receipt(
                receipt_path,
                repo_root=repo_root,
            )
        except ContractError:
            invalid_receipt = True
            continue
        key = (evidence.model_id, evidence.artifact_filename)
        if key in frozen:
            invalid_receipt = True
            continue
        frozen[key] = evidence
    if invalid_receipt:
        blockers.append("artifact_freeze_receipt_invalid")
    for artifact in model["artifacts"]:
        if (model_id, artifact["filename"]) not in frozen:
            blockers.append(
                f"artifact_freeze_receipt_required:{artifact['filename']}"
            )
    compatibility = model["runtime"]["android_runtime_compatibility"]
    if compatibility != "locally_verified":
        blockers.append(f"android_runtime_compatibility_{compatibility}")
    return list(dict.fromkeys(blockers))


def _formal_cohort_for_profile(
    trusted: TrustedModelRegistrySnapshot,
    profile: str,
) -> Mapping[str, Any]:
    if profile not in {"exploration", "production_confirmation"}:
        raise ContractError("formal model cohort profile is invalid")
    cohorts = [
        cohort
        for cohort in trusted.document["formal_cohorts"]
        if profile in cohort["profiles"]
    ]
    if len(cohorts) != 1:
        raise ContractError(
            "trusted registry does not define exactly one formal cohort"
        )
    return cohorts[0]


def _formal_model_record(
    evidence: VerifiedFormalModelCohort,
) -> _FormalModelCohortRecord:
    if not isinstance(evidence, VerifiedFormalModelCohort):
        raise ContractError(
            "formal model cohort requires verified receipt evidence"
        )
    try:
        return _VERIFIED_FORMAL_MODEL_COHORTS[evidence]
    except (KeyError, TypeError) as error:
        raise ContractError(
            "formal model cohort evidence is not registered by the verifier"
        ) from error


def _revalidate_formal_model_cohort(
    evidence: VerifiedFormalModelCohort,
) -> _FormalModelCohortRecord:
    record = _formal_model_record(evidence)
    trusted = load_trusted_model_registry()
    if trusted.sha256 != record.registry_sha256:
        raise ContractError("formal model cohort registry changed")
    cohort = _formal_cohort_for_profile(trusted, record.profile)
    if (
        cohort["cohort_id"] != record.cohort_id
        or tuple(cohort["model_ids"]) != record.model_ids
        or cohort["baseline_model_id"] != record.baseline_model_id
    ):
        raise ContractError("formal model cohort definition changed")
    refreshed = _verify_formal_model_cohort_record(
        profile=record.profile,
        model_ids=record.model_ids,
        artifact_freeze_receipt_paths=record.artifact_receipt_paths,
        risk_acceptance_receipt_paths=dict(
            record.risk_acceptance_receipt_paths
        ),
        repo_root=record.repo_root,
    )
    if refreshed != record:
        raise ContractError("formal model cohort receipt commitments changed")
    return record


def _verify_formal_model_cohort_record(
    *,
    profile: str,
    model_ids: Sequence[str],
    artifact_freeze_receipt_paths: Sequence[Path],
    risk_acceptance_receipt_paths: Mapping[str, Path],
    repo_root: Path,
) -> _FormalModelCohortRecord:
    from .contracts import formal_benchmark_artifact_blockers

    trusted = load_trusted_model_registry()
    cohort = _formal_cohort_for_profile(trusted, profile)
    selected = tuple(model_ids)
    if selected != tuple(cohort["model_ids"]):
        raise ContractError(
            "formal model cohort must exactly equal the trusted registry cohort"
        )
    safe_repo_root = Path(repo_root).resolve()
    freeze_evidence: list[DownloadFreezeEvidence] = []
    seen_artifacts: set[tuple[str, str]] = set()
    for raw_path in artifact_freeze_receipt_paths:
        evidence = load_download_freeze_receipt(
            raw_path,
            repo_root=safe_repo_root,
        )
        key = (evidence.model_id, evidence.artifact_filename)
        if key in seen_artifacts:
            raise ContractError(
                "formal model cohort contains a duplicate artifact receipt"
            )
        seen_artifacts.add(key)
        freeze_evidence.append(evidence)
    expected_artifacts = {
        (model["model_id"], artifact["filename"])
        for model in trusted.document["models"]
        if model["model_id"] in selected
        for artifact in model["artifacts"]
    }
    if seen_artifacts - expected_artifacts:
        raise ContractError(
            "formal model cohort contains an artifact outside the cohort"
        )
    risk_paths = dict(risk_acceptance_receipt_paths)
    if any(model_id not in selected for model_id in risk_paths):
        raise ContractError(
            "formal model cohort contains a risk receipt outside the cohort"
        )
    risk_commits: list[dict[str, str]] = []
    for model_id, path in sorted(risk_paths.items()):
        receipt = load_internal_risk_acceptance_receipt(
            path,
            model_id=model_id,
            repo_root=safe_repo_root,
        )
        risk_commits.append(
            {
                "model_id": model_id,
                "receipt_commit_sha256": receipt["commit_sha256"],
            }
        )
    all_blockers: list[str] = []
    freeze_paths = tuple(
        evidence.receipt_path for evidence in freeze_evidence
    )
    for model_id in selected:
        model_blockers = formal_benchmark_artifact_blockers(
            trusted.document,
            model_id,
            artifact_freeze_receipt_paths=freeze_paths,
            risk_acceptance_receipt_path=risk_paths.get(model_id),
            repo_root=safe_repo_root,
        )
        all_blockers.extend(
            f"{model_id}:{blocker}" for blocker in model_blockers
        )
    if all_blockers:
        raise ContractError(
            "formal benchmark artifact gate blocked: "
            + ",".join(all_blockers)
        )
    artifact_commitments = [
        {
            "model_id": evidence.model_id,
            "model_revision": evidence.model_revision,
            "model_revision_kind": evidence.model_revision_kind,
            "artifact_filename": evidence.artifact_filename,
            "artifact_component": evidence.artifact_component,
            "artifact_source_url": evidence.artifact_source_url,
            "artifact_source_revision": evidence.artifact_source_revision,
            "artifact_source_revision_kind": (
                evidence.artifact_source_revision_kind
            ),
            "measured_bytes": evidence.measured_bytes,
            "measured_sha256": evidence.measured_sha256,
            "receipt_commit_sha256": evidence.receipt_commit_sha256,
        }
        for evidence in sorted(
            freeze_evidence,
            key=lambda item: (item.model_id, item.artifact_filename),
        )
    ]
    artifact_set_sha256 = canonical_sha256(artifact_commitments)
    risk_set_sha256 = canonical_sha256(risk_commits)
    cohort_commitment_sha256 = canonical_sha256(
        {
            "contract_id": _FORMAL_COHORT_CLOSURE_CONTRACT,
            "registry_snapshot_sha256": trusted.sha256,
            "profile": profile,
            "cohort_id": cohort["cohort_id"],
            "cohort_model_ids_sha256": canonical_sha256(
                sorted(cohort["model_ids"])
            ),
            "artifact_set_sha256": artifact_set_sha256,
            "risk_acceptance_set_sha256": risk_set_sha256,
        }
    )
    return _FormalModelCohortRecord(
        profile=profile,
        cohort_id=cohort["cohort_id"],
        model_ids=selected,
        baseline_model_id=cohort["baseline_model_id"],
        registry_sha256=trusted.sha256,
        repo_root=safe_repo_root,
        artifact_receipt_paths=tuple(
            evidence.receipt_path for evidence in freeze_evidence
        ),
        risk_acceptance_receipt_paths=tuple(sorted(risk_paths.items())),
        artifact_set_sha256=artifact_set_sha256,
        risk_acceptance_set_sha256=risk_set_sha256,
        cohort_commitment_sha256=cohort_commitment_sha256,
    )


def verify_formal_model_cohort(
    *,
    profile: str,
    model_ids: Sequence[str],
    artifact_freeze_receipt_paths: Sequence[Path],
    risk_acceptance_receipt_paths: Mapping[str, Path],
    repo_root: Path,
) -> VerifiedFormalModelCohort:
    record = _verify_formal_model_cohort_record(
        profile=profile,
        model_ids=model_ids,
        artifact_freeze_receipt_paths=artifact_freeze_receipt_paths,
        risk_acceptance_receipt_paths=risk_acceptance_receipt_paths,
        repo_root=repo_root,
    )
    evidence = object.__new__(VerifiedFormalModelCohort)
    _VERIFIED_FORMAL_MODEL_COHORTS[evidence] = record
    return evidence


def formal_model_cohort_commitments(
    evidence: VerifiedFormalModelCohort,
) -> dict[str, str]:
    record = _revalidate_formal_model_cohort(evidence)
    return {
        "formal_artifact_set_sha256": record.artifact_set_sha256,
        "formal_risk_acceptance_set_sha256": (
            record.risk_acceptance_set_sha256
        ),
        "formal_model_cohort_sha256": record.cohort_commitment_sha256,
    }


def _formal_closure_document(
    record: _FormalModelCohortRecord,
    *,
    run_id: str,
    public_plan_sha256: str,
    benchmark_evidence_sha256: str,
) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "run_id": run_id,
        "receipt_contract_id": _FORMAL_COHORT_CLOSURE_CONTRACT,
        "scope": _INTERNAL_SCOPE,
        "registry_snapshot_sha256": record.registry_sha256,
        "protocol_profile": record.profile,
        "cohort_id": record.cohort_id,
        "cohort_model_ids_sha256": canonical_sha256(
            sorted(record.model_ids)
        ),
        "model_count": len(record.model_ids),
        "formal_artifact_set_sha256": record.artifact_set_sha256,
        "formal_risk_acceptance_set_sha256": (
            record.risk_acceptance_set_sha256
        ),
        "formal_model_cohort_sha256": record.cohort_commitment_sha256,
        "public_plan_sha256": public_plan_sha256,
        "benchmark_evidence_sha256": benchmark_evidence_sha256,
    }


def commit_formal_model_cohort_closure(
    evidence: VerifiedFormalModelCohort,
    *,
    public_plan: Mapping[str, Any],
    benchmark_evidence_sha256: str,
    receipt_path: Path,
    repo_root: Path,
) -> dict[str, Any]:
    record = _revalidate_formal_model_cohort(evidence)
    if public_plan.get("run_id") is None:
        raise ContractError("formal public plan run_id is missing")
    plan_sha256 = canonical_sha256(public_plan)
    if (
        public_plan.get("protocol_profile") != record.profile
        or public_plan.get("cohort_id") != record.cohort_id
        or public_plan.get("cohort_model_ids_sha256")
        != canonical_sha256(sorted(record.model_ids))
        or public_plan.get("contract_fingerprints", {}).get(
            "registry_sha256"
        )
        != record.registry_sha256
        or public_plan.get("formal_artifact_set_sha256")
        != record.artifact_set_sha256
        or public_plan.get("formal_risk_acceptance_set_sha256")
        != record.risk_acceptance_set_sha256
        or public_plan.get("formal_model_cohort_sha256")
        != record.cohort_commitment_sha256
    ):
        raise ContractError(
            "formal public plan differs from verified model cohort commitments"
        )
    document = _formal_closure_document(
        record,
        run_id=public_plan["run_id"],
        public_plan_sha256=plan_sha256,
        benchmark_evidence_sha256=benchmark_evidence_sha256,
    )
    safe_receipt_path = validate_private_artifact_output_path(
        receipt_path,
        repo_root,
    )

    def validate_document() -> None:
        if document != _formal_closure_document(
            _revalidate_formal_model_cohort(evidence),
            run_id=public_plan["run_id"],
            public_plan_sha256=plan_sha256,
            benchmark_evidence_sha256=benchmark_evidence_sha256,
        ):
            raise ContractError(
                "formal model cohort closure document changed"
            )

    return _commit_reserved_model_security_artifact(
        safe_receipt_path,
        document,
        artifact_kind=_FORMAL_COHORT_CLOSURE_KIND,
        run_id=public_plan["run_id"],
        evidence_sha256=benchmark_evidence_sha256,
        plan_sha256=plan_sha256,
        validate_document=validate_document,
        validate_evidence=lambda: _revalidate_formal_model_cohort(evidence),
    )


def load_formal_model_cohort_closure(
    receipt_path: Path,
    *,
    public_plan: Mapping[str, Any],
    benchmark_evidence_sha256: str,
    repo_root: Path,
) -> dict[str, Any]:
    safe_path = validate_private_artifact_input_path(receipt_path, repo_root)
    plan_sha256 = canonical_sha256(public_plan)
    document, receipt = load_committed_json_artifact(
        safe_path,
        expected_kind=_FORMAL_COHORT_CLOSURE_KIND,
        expected_evidence_sha256=benchmark_evidence_sha256,
        expected_plan_sha256=plan_sha256,
    )
    expected_keys = {
        "schema_version",
        "run_id",
        "receipt_contract_id",
        "scope",
        "registry_snapshot_sha256",
        "protocol_profile",
        "cohort_id",
        "cohort_model_ids_sha256",
        "model_count",
        "formal_artifact_set_sha256",
        "formal_risk_acceptance_set_sha256",
        "formal_model_cohort_sha256",
        "public_plan_sha256",
        "benchmark_evidence_sha256",
    }
    if set(document) != expected_keys:
        raise ContractError(
            "formal model cohort closure payload fields are invalid"
        )
    fingerprints = public_plan.get("contract_fingerprints")
    if not isinstance(fingerprints, Mapping):
        raise ContractError(
            "formal public plan contract fingerprints are invalid"
        )
    expected = {
        "schema_version": "1.0",
        "run_id": public_plan.get("run_id"),
        "receipt_contract_id": _FORMAL_COHORT_CLOSURE_CONTRACT,
        "scope": _INTERNAL_SCOPE,
        "registry_snapshot_sha256": fingerprints.get("registry_sha256"),
        "protocol_profile": public_plan.get("protocol_profile"),
        "cohort_id": public_plan.get("cohort_id"),
        "cohort_model_ids_sha256": public_plan.get(
            "cohort_model_ids_sha256"
        ),
        "model_count": len(public_plan.get("assignments", [])),
        "formal_artifact_set_sha256": public_plan.get(
            "formal_artifact_set_sha256"
        ),
        "formal_risk_acceptance_set_sha256": public_plan.get(
            "formal_risk_acceptance_set_sha256"
        ),
        "formal_model_cohort_sha256": public_plan.get(
            "formal_model_cohort_sha256"
        ),
        "public_plan_sha256": plan_sha256,
        "benchmark_evidence_sha256": benchmark_evidence_sha256,
    }
    if any(value is None for value in expected.values()) or document != expected:
        raise ContractError(
            "formal model cohort closure differs from run, plan, or evidence"
        )
    trusted = load_trusted_model_registry()
    if document["registry_snapshot_sha256"] != trusted.sha256:
        raise ContractError(
            "formal model cohort closure uses an untrusted registry"
        )
    if receipt["run_id"] != public_plan.get("run_id"):
        raise ContractError("formal model cohort closure belongs to another run")
    return receipt
