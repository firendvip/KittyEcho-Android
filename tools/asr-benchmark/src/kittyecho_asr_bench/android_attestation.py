from __future__ import annotations

import base64
import binascii
import datetime
import hashlib
import json
import os
import re
import stat
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Mapping, Optional, Sequence

try:
    from cryptography import x509
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import (
        dsa,
        ec,
        ed25519,
        ed448,
        padding,
        rsa,
    )
    from cryptography.x509.oid import ObjectIdentifier
except ImportError as error:  # pragma: no cover - exercised by the system-python check
    raise RuntimeError(
        "Android attestation verification requires cryptography==46.0.7; "
        "install only from the pinned local benchmark requirements"
    ) from error

from .artifacts import (
    _commit_reserved_android_artifact,
    load_committed_json_artifact,
    validate_private_artifact_input_path,
    validate_private_artifact_output_path,
)
from .contracts import (
    ContractError,
    canonical_sha256,
    decoder_plan_id,
    decoder_plan_raw_sha256,
)


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_RUN_ID = re.compile(r"^run_[0-9a-f]{12}$")
_DATASET_ID = re.compile(r"^dataset_[0-9a-f]{12}$")
_PLAN_ID = re.compile(r"^plan_[0-9a-f]{12}$")
_CLIP_ID = re.compile(r"^clip_[0-9a-f]{12}$")
_MODEL_ALIAS = re.compile(r"^M[0-9]{3}$")
_ANDROID_ATTESTATION_OID = ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")
_MAX_RAW_JSON_BYTES = 64 * 1024 * 1024
_FORMAL_PROFILES = {"exploration", "production_confirmation"}
_COMPONENT_ROLES = {"weights", "conversion", "runtime", "tokenizer"}
_ERROR_CODES = {None, "oom", "crash", "decode_error"}
_FORBIDDEN_ENGINEERING_KEYS = {
    "reference",
    "reference_text",
    "reference_accessed",
    "hypothesis",
    "transcript",
    "text",
    "model_id",
    "model_name",
    "filename",
    "path",
    "audio_path",
}
_ATTESTATION_DOMAIN = b"kittyecho-asr-attestation-envelope-v1\x00"


@dataclass(frozen=True)
class AndroidAttestationPolicy:
    """Off-device trust inputs.

    Formal eligibility remains deliberately disabled because AndroidKeyStore
    authenticates a key and its authorizations, not the benchmark code's
    execution or measurements. Trusted roots and revocation serials are still
    represented so the cryptographic boundary can be exercised before the
    separate observer contract exists.
    """

    allow_development_self_signed: bool
    trusted_root_spki_sha256: frozenset[str]
    revoked_certificate_serials: frozenset[int]
    expected_package_name: Optional[str]
    expected_version_code: Optional[int]
    expected_host_challenge: bytes

    @classmethod
    def development(
        cls,
        expected_host_challenge: bytes,
    ) -> "AndroidAttestationPolicy":
        challenge = _validate_host_challenge(expected_host_challenge)
        return cls(
            allow_development_self_signed=True,
            trusted_root_spki_sha256=frozenset(),
            revoked_certificate_serials=frozenset(),
            expected_package_name=None,
            expected_version_code=None,
            expected_host_challenge=challenge,
        )

    @classmethod
    def formal_no_go(
        cls,
        *,
        trusted_root_spki_sha256: Sequence[str] = (),
        revoked_certificate_serials: Sequence[int] = (),
        expected_package_name: str = (
            "com.wordtaker.keyboard.asrbenchmark.runner"
        ),
        expected_version_code: Optional[int] = None,
        expected_host_challenge: bytes,
    ) -> "AndroidAttestationPolicy":
        roots = frozenset(trusted_root_spki_sha256)
        for digest in roots:
            _require_sha256(digest, "trusted root SPKI digest")
        serials = frozenset(revoked_certificate_serials)
        if any(
            isinstance(serial, bool)
            or not isinstance(serial, int)
            or serial < 0
            for serial in serials
        ):
            raise ContractError(
                "revoked certificate serials must be non-negative integers"
            )
        if not isinstance(expected_package_name, str) or not expected_package_name:
            raise ContractError("expected Android package name is required")
        if expected_version_code is not None and (
            isinstance(expected_version_code, bool)
            or not isinstance(expected_version_code, int)
            or expected_version_code <= 0
        ):
            raise ContractError(
                "expected Android package version must be a positive integer"
            )
        return cls(
            allow_development_self_signed=False,
            trusted_root_spki_sha256=roots,
            revoked_certificate_serials=serials,
            expected_package_name=expected_package_name,
            expected_version_code=expected_version_code,
            expected_host_challenge=_validate_host_challenge(
                expected_host_challenge
            ),
        )


class AndroidAccuracyLoadPolicy(Enum):
    """Explicitly separates synthetic development reads from formal reads."""

    DEVELOPMENT_SYNTHETIC = "development_synthetic"


@dataclass(frozen=True)
class AndroidBundleVerification:
    signature_verified: bool
    certificate_chain_verified: bool
    hardware_key_verified: bool
    formal_eligible: bool
    product_decision_eligible: bool
    adversarial_same_uid_resistant: bool
    assurance_level: str
    blockers: tuple[str, ...]
    accuracy_receipt_commit_sha256: str
    engineering_receipt_commit_sha256: str
    attestation_receipt_commit_sha256: str


@dataclass(frozen=True)
class _RawIdentity:
    device: int
    inode: int
    size_bytes: int
    mode: int
    link_count: int


@dataclass(frozen=True)
class _RawJson:
    path: Path
    document: dict[str, Any]
    identity: _RawIdentity
    sha256: str


@dataclass(frozen=True)
class _CertificateFacts:
    signature_verified: bool
    chain_verified: bool
    hardware_verified: bool
    security_level: str
    challenge_matches: bool
    application_matches: bool
    verified_boot_matches: bool


def _require_mapping(value: Any, context: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ContractError(f"{context} must be a JSON object")
    return value


def _require_list(value: Any, context: str, *, nonempty: bool = False) -> list[Any]:
    if not isinstance(value, list) or (nonempty and not value):
        suffix = " and non-empty" if nonempty else ""
        raise ContractError(f"{context} must be a JSON array{suffix}")
    return value


def _exact_keys(value: Mapping[str, Any], expected: set[str], context: str) -> None:
    if set(value) != expected:
        unexpected = sorted(set(value) - expected)
        missing = sorted(expected - set(value))
        raise ContractError(
            f"{context} fields are invalid; missing={missing}, unexpected={unexpected}"
        )


def _require_string(
    value: Any,
    context: str,
    *,
    nonempty: bool = True,
) -> str:
    if not isinstance(value, str) or (nonempty and not value):
        raise ContractError(f"{context} must be a string")
    return value


def _require_int(value: Any, context: str, *, minimum: int = 0) -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or value < minimum
    ):
        raise ContractError(
            f"{context} must be an integer greater than or equal to {minimum}"
        )
    return value


def _require_sha256(value: Any, context: str) -> str:
    if not isinstance(value, str) or not _SHA256.fullmatch(value):
        raise ContractError(f"{context} must be a lowercase sha256")
    return value


def _validate_host_challenge(value: Any) -> bytes:
    if not isinstance(value, bytes) or len(value) != 32 or value == bytes(32):
        raise ContractError(
            "host attestation challenge must be 32 non-zero random bytes"
        )
    return bytes(value)


def _canonical_json_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ContractError("attestation value is not canonical JSON") from error


def attestation_message_bytes(signed_payload: Mapping[str, Any]) -> bytes:
    """Domain-separated bytes signed by the Android Keystore key."""
    return _ATTESTATION_DOMAIN + _canonical_json_bytes(
        _require_mapping(signed_payload, "attestation signed payload")
    )


def _decode_json_object(encoded: bytes, context: str) -> dict[str, Any]:
    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ContractError(f"{context} contains duplicate JSON key: {key}")
            result[key] = value
        return result

    def reject_constant(_value: str) -> None:
        raise ContractError(f"{context} contains a non-finite JSON number")

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


def _safe_read_raw_json(
    path: Path,
    repo_root: Path,
    *,
    expected: Optional[_RawJson] = None,
) -> _RawJson:
    candidate = validate_private_artifact_input_path(path, repo_root)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    flags |= nofollow
    if nofollow == 0:
        try:
            if stat.S_ISLNK(candidate.lstat().st_mode):
                raise ContractError("raw Android artifact must not be a symlink")
        except OSError as error:
            raise ContractError("raw Android artifact cannot be inspected") from error
    try:
        descriptor = os.open(str(candidate), flags)
    except OSError as error:
        raise ContractError(
            "raw Android artifact must be a readable non-symlink file"
        ) from error
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise ContractError("raw Android artifact must be a regular file")
        if before.st_nlink != 1:
            raise ContractError("raw Android artifact must not share its inode")
        if before.st_size < 0 or before.st_size > _MAX_RAW_JSON_BYTES:
            raise ContractError("raw Android artifact exceeds the size limit")
        blocks: list[bytes] = []
        total = 0
        while True:
            block = os.read(descriptor, 1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > _MAX_RAW_JSON_BYTES:
                raise ContractError("raw Android artifact exceeds the size limit")
            blocks.append(block)
        after = os.fstat(descriptor)
        identity = _RawIdentity(
            device=after.st_dev,
            inode=after.st_ino,
            size_bytes=after.st_size,
            mode=after.st_mode,
            link_count=after.st_nlink,
        )
        if (
            before.st_dev != after.st_dev
            or before.st_ino != after.st_ino
            or before.st_size != after.st_size
            or before.st_mode != after.st_mode
            or before.st_nlink != after.st_nlink
            or total != after.st_size
        ):
            raise ContractError("raw Android artifact changed while being read")
        encoded = b"".join(blocks)
        digest = hashlib.sha256(encoded).hexdigest()
        result = _RawJson(
            path=candidate,
            document=_decode_json_object(encoded, "raw Android artifact"),
            identity=identity,
            sha256=digest,
        )
        if expected is not None and (
            result.path != expected.path
            or result.identity != expected.identity
            or result.sha256 != expected.sha256
            or result.document != expected.document
        ):
            raise ContractError(
                "raw Android artifact identity or content changed before commit"
            )
        return result
    finally:
        os.close(descriptor)


def _validate_decoder_plan(document: Any) -> dict[str, Any]:
    plan = dict(_require_mapping(document, "Phase A decoder plan"))
    _exact_keys(
        plan,
        {
            "schema_version",
            "plan_id",
            "run_id",
            "dataset_id",
            "model_alias",
            "pcm_contract_id",
            "decoder_contract_id",
            "input_transform_id",
            "public_plan_sha256",
            "manifest_sha256",
            "clips",
        },
        "Phase A decoder plan",
    )
    if plan["schema_version"] != "1.0":
        raise ContractError("Phase A decoder plan version is unsupported")
    if (
        not isinstance(plan["plan_id"], str)
        or not _PLAN_ID.fullmatch(plan["plan_id"])
        or plan["plan_id"] != decoder_plan_id(plan)
    ):
        raise ContractError("Phase A decoder plan_id is invalid")
    if not isinstance(plan["run_id"], str) or not _RUN_ID.fullmatch(plan["run_id"]):
        raise ContractError("Phase A decoder plan run_id is invalid")
    if not isinstance(plan["dataset_id"], str) or not _DATASET_ID.fullmatch(
        plan["dataset_id"]
    ):
        raise ContractError("Phase A decoder plan dataset_id is invalid")
    if not isinstance(plan["model_alias"], str) or not _MODEL_ALIAS.fullmatch(
        plan["model_alias"]
    ):
        raise ContractError("Phase A decoder plan alias is invalid")
    if (
        plan["pcm_contract_id"] != "pcm16k-mono-s16le-v1"
        or plan["decoder_contract_id"] != "first-layer-raw-v1"
        or plan["input_transform_id"] != "canonical-pcm-direct-v1"
    ):
        raise ContractError("Phase A decoder plan contracts are invalid")
    _require_sha256(plan["public_plan_sha256"], "decoder plan public plan hash")
    _require_sha256(plan["manifest_sha256"], "decoder plan manifest hash")
    clips = _require_list(plan["clips"], "decoder plan clips", nonempty=True)
    seen: set[str] = set()
    validated_clips: list[dict[str, Any]] = []
    for index, raw_clip in enumerate(clips):
        context = f"decoder plan clips[{index}]"
        clip = dict(_require_mapping(raw_clip, context))
        _exact_keys(
            clip,
            {
                "clip_id",
                "audio_path",
                "wav_file_sha256",
                "pcm_payload_sha256",
                "pcm_payload_bytes",
            },
            context,
        )
        clip_id = clip["clip_id"]
        if not isinstance(clip_id, str) or not _CLIP_ID.fullmatch(clip_id):
            raise ContractError(f"{context}.clip_id is invalid")
        if clip_id in seen:
            raise ContractError("decoder plan contains duplicate clip_id")
        seen.add(clip_id)
        path = _require_string(clip["audio_path"], f"{context}.audio_path")
        if not path.startswith("/"):
            raise ContractError(f"{context}.audio_path must be absolute")
        _require_sha256(clip["wav_file_sha256"], f"{context}.wav hash")
        _require_sha256(
            clip["pcm_payload_sha256"], f"{context}.PCM payload hash"
        )
        _require_int(
            clip["pcm_payload_bytes"], f"{context}.PCM payload bytes"
        )
        validated_clips.append(clip)
    plan["clips"] = validated_clips
    return plan


def _validate_accuracy(
    document: Any,
    decoder_plan: Mapping[str, Any],
) -> dict[str, Any]:
    accuracy = dict(_require_mapping(document, "Android accuracy payload"))
    _exact_keys(
        accuracy,
        {
            "schema_version",
            "run_id",
            "model_alias",
            "decoder_contract_id",
            "pcm_contract_id",
            "input_transform_id",
            "decoder_plan_sha256",
            "reference_accessed",
            "predictions",
        },
        "Android accuracy payload",
    )
    if accuracy["schema_version"] != "1.0":
        raise ContractError("Android accuracy payload version is unsupported")
    if (
        accuracy["run_id"] != decoder_plan["run_id"]
        or accuracy["model_alias"] != decoder_plan["model_alias"]
    ):
        raise ContractError("Android accuracy run or alias differs from decoder plan")
    if (
        accuracy["decoder_contract_id"] != decoder_plan["decoder_contract_id"]
        or accuracy["pcm_contract_id"] != decoder_plan["pcm_contract_id"]
        or accuracy["input_transform_id"] != decoder_plan["input_transform_id"]
    ):
        raise ContractError("Android accuracy decoding contracts differ")
    expected_decoder_sha256 = canonical_sha256(decoder_plan)
    if accuracy["decoder_plan_sha256"] != expected_decoder_sha256:
        raise ContractError("Android accuracy decoder-plan hash differs")
    if accuracy["reference_accessed"] is not False:
        raise ContractError("Android runner must not access references")
    clips = decoder_plan["clips"]
    predictions = _require_list(
        accuracy["predictions"],
        "Android accuracy predictions",
        nonempty=True,
    )
    if len(predictions) != len(clips):
        raise ContractError(
            "Android accuracy prediction count or order differs from decoder plan"
        )
    validated: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, (raw_prediction, clip) in enumerate(zip(predictions, clips)):
        context = f"Android accuracy predictions[{index}]"
        prediction = dict(_require_mapping(raw_prediction, context))
        _exact_keys(
            prediction,
            {
                "clip_id",
                "pcm_sha256",
                "pcm_payload_sha256",
                "hypothesis",
                "status",
                "error_code",
            },
            context,
        )
        clip_id = prediction["clip_id"]
        if clip_id in seen:
            raise ContractError("Android accuracy contains duplicate clip_id")
        seen.add(clip_id)
        if clip_id != clip["clip_id"]:
            raise ContractError(
                "Android accuracy prediction order differs from decoder plan"
            )
        if (
            prediction["pcm_sha256"] != clip["wav_file_sha256"]
            or prediction["pcm_payload_sha256"] != clip["pcm_payload_sha256"]
        ):
            raise ContractError(
                "Android accuracy PCM hash differs from canonical decoder input"
            )
        hypothesis = _require_string(
            prediction["hypothesis"],
            f"{context}.hypothesis",
            nonempty=False,
        )
        if len(hypothesis) > 100_000:
            raise ContractError("Android accuracy hypothesis is too large")
        if prediction["status"] not in {"ok", "error"}:
            raise ContractError(f"{context}.status is invalid")
        if prediction["error_code"] not in _ERROR_CODES:
            raise ContractError(f"{context}.error_code is invalid")
        if (prediction["status"] == "ok") != (
            prediction["error_code"] is None
        ):
            raise ContractError(f"{context} status and error code conflict")
        validated.append(prediction)
    accuracy["predictions"] = validated
    return accuracy


def _reject_engineering_role_leakage(value: Any, context: str) -> None:
    if isinstance(value, Mapping):
        for key, item in value.items():
            if key.lower() in _FORBIDDEN_ENGINEERING_KEYS:
                raise ContractError(
                    f"{context} contains forbidden role-leakage field: {key}"
                )
            _reject_engineering_role_leakage(item, f"{context}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _reject_engineering_role_leakage(item, f"{context}[{index}]")


def _validate_artifact_measurements(value: Any) -> list[dict[str, Any]]:
    measurements = _require_list(
        value,
        "Android artifact measurements",
        nonempty=True,
    )
    validated: list[dict[str, Any]] = []
    roles: set[str] = set()
    for index, raw_measurement in enumerate(measurements):
        context = f"Android artifact measurements[{index}]"
        measurement = dict(_require_mapping(raw_measurement, context))
        _exact_keys(
            measurement,
            {
                "component_role",
                "sha256",
                "size_bytes",
                "st_dev",
                "st_ino",
                "mode",
                "nlink",
            },
            context,
        )
        role = measurement["component_role"]
        if role not in _COMPONENT_ROLES or role in roles:
            raise ContractError(
                "Android artifact component roles must be valid and unique"
            )
        roles.add(role)
        _require_sha256(measurement["sha256"], f"{context}.sha256")
        _require_int(measurement["size_bytes"], f"{context}.size_bytes")
        _require_int(measurement["st_dev"], f"{context}.st_dev")
        _require_int(measurement["st_ino"], f"{context}.st_ino")
        mode = _require_int(measurement["mode"], f"{context}.mode")
        if not stat.S_ISREG(mode):
            raise ContractError("Android artifact measurement must bind a regular file")
        if measurement["nlink"] != 1:
            raise ContractError("Android artifact measurement must bind one inode")
        validated.append(measurement)
    return validated


def _validate_clip_measurements(
    value: Any,
    decoder_plan: Mapping[str, Any],
) -> list[dict[str, Any]]:
    measurements = _require_list(
        value,
        "Android clip measurements",
        nonempty=True,
    )
    clips = decoder_plan["clips"]
    if len(measurements) != len(clips):
        raise ContractError(
            "Android clip measurement count differs from decoder plan"
        )
    validated: list[dict[str, Any]] = []
    for index, (raw_measurement, clip) in enumerate(zip(measurements, clips)):
        context = f"Android clip measurements[{index}]"
        measurement = dict(_require_mapping(raw_measurement, context))
        _exact_keys(
            measurement,
            {
                "clip_id",
                "wav_file_sha256_before",
                "wav_file_sha256_after",
                "pcm_payload_sha256_before",
                "pcm_payload_sha256_after",
                "pcm_payload_bytes",
                "st_dev_before",
                "st_dev_after",
                "st_ino_before",
                "st_ino_after",
                "mode_before",
                "mode_after",
                "nlink_before",
                "nlink_after",
                "monotonic_stop_ns",
                "monotonic_final_ns",
                "stop_to_final_ns",
                "run_state",
                "status",
                "error_code",
            },
            context,
        )
        if measurement["clip_id"] != clip["clip_id"]:
            raise ContractError(
                "Android clip measurement order differs from decoder plan"
            )
        if (
            measurement["wav_file_sha256_before"]
            != clip["wav_file_sha256"]
            or measurement["wav_file_sha256_after"]
            != clip["wav_file_sha256"]
            or measurement["pcm_payload_sha256_before"]
            != clip["pcm_payload_sha256"]
            or measurement["pcm_payload_sha256_after"]
            != clip["pcm_payload_sha256"]
            or measurement["pcm_payload_bytes"]
            != clip["pcm_payload_bytes"]
        ):
            raise ContractError(
                "Android clip measurement PCM commitment changed during decode"
            )
        for prefix in ("st_dev", "st_ino", "mode", "nlink"):
            before = _require_int(
                measurement[f"{prefix}_before"],
                f"{context}.{prefix}_before",
            )
            after = _require_int(
                measurement[f"{prefix}_after"],
                f"{context}.{prefix}_after",
            )
            if before != after:
                raise ContractError(
                    "Android clip inode or identity changed during decode"
                )
        if (
            measurement["nlink_before"] != 1
            or not stat.S_ISREG(measurement["mode_before"])
        ):
            raise ContractError(
                "Android clip measurement must bind one regular-file inode"
            )
        stop_ns = _require_int(
            measurement["monotonic_stop_ns"], f"{context}.monotonic_stop_ns"
        )
        final_ns = _require_int(
            measurement["monotonic_final_ns"], f"{context}.monotonic_final_ns"
        )
        latency_ns = _require_int(
            measurement["stop_to_final_ns"], f"{context}.stop_to_final_ns"
        )
        if final_ns < stop_ns or final_ns - stop_ns != latency_ns:
            raise ContractError(
                "Android stop-to-final latency is inconsistent with monotonic clocks"
            )
        if measurement["run_state"] not in {"cold", "warm"}:
            raise ContractError(f"{context}.run_state is invalid")
        if measurement["status"] not in {"ok", "error"}:
            raise ContractError(f"{context}.status is invalid")
        if measurement["error_code"] not in _ERROR_CODES:
            raise ContractError(f"{context}.error_code is invalid")
        if (measurement["status"] == "ok") != (
            measurement["error_code"] is None
        ):
            raise ContractError(f"{context} status and error code conflict")
        validated.append(measurement)
    return validated


def _validate_runtime(
    value: Any,
    *,
    artifacts: Sequence[Mapping[str, Any]],
    clip_measurements: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    runtime = dict(_require_mapping(value, "Android runtime measurement"))
    _exact_keys(
        runtime,
        {
            "total_resource_bytes",
            "cold_latency_ns",
            "warm_latency_ns",
            "decode_probe_peak_rss_bytes",
            "decode_probe_peak_pss_bytes",
            "peak_rss_bytes",
            "peak_pss_bytes",
            "oom_count",
            "crash_count",
            "success_count",
        },
        "Android runtime measurement",
    )
    total_resource_bytes = _require_int(
        runtime["total_resource_bytes"], "runtime.total_resource_bytes"
    )
    if total_resource_bytes != sum(item["size_bytes"] for item in artifacts):
        raise ContractError(
            "Android runtime total resource bytes differ from safe-FD artifacts"
        )
    expected_cold = [
        item["stop_to_final_ns"]
        for item in clip_measurements
        if item["run_state"] == "cold"
    ]
    expected_warm = [
        item["stop_to_final_ns"]
        for item in clip_measurements
        if item["run_state"] == "warm"
    ]
    for field, expected in (
        ("cold_latency_ns", expected_cold),
        ("warm_latency_ns", expected_warm),
    ):
        actual = _require_list(runtime[field], f"runtime.{field}")
        if any(
            isinstance(item, bool) or not isinstance(item, int) or item < 0
            for item in actual
        ) or actual != expected:
            raise ContractError(
                f"Android runtime {field} differs from runner clip clocks"
            )
    for field in (
        "decode_probe_peak_rss_bytes",
        "decode_probe_peak_pss_bytes",
        "peak_rss_bytes",
        "peak_pss_bytes",
        "oom_count",
        "crash_count",
        "success_count",
    ):
        _require_int(runtime[field], f"runtime.{field}")
    if (
        runtime["peak_rss_bytes"] < runtime["decode_probe_peak_rss_bytes"]
        or runtime["peak_pss_bytes"] < runtime["decode_probe_peak_pss_bytes"]
    ):
        raise ContractError(
            "Android runtime peak RSS/PSS is below its decode probe peak"
        )
    if runtime["success_count"] != sum(
        item["status"] == "ok" for item in clip_measurements
    ):
        raise ContractError("Android runtime success count is inconsistent")
    if runtime["oom_count"] != sum(
        item["error_code"] == "oom" for item in clip_measurements
    ):
        raise ContractError("Android runtime OOM count is inconsistent")
    if runtime["crash_count"] != sum(
        item["error_code"] == "crash" for item in clip_measurements
    ):
        raise ContractError("Android runtime crash count is inconsistent")
    return runtime


def _validate_telemetry(
    value: Any,
    *,
    decoder_plan: Mapping[str, Any],
    runtime: Mapping[str, Any],
) -> dict[str, Any]:
    telemetry = dict(_require_mapping(value, "Android telemetry"))
    _exact_keys(
        telemetry,
        {
            "sample_interval_ms",
            "duration_ns",
            "samples",
            "summary_sha256",
            "loop_count",
            "successful_loop_count",
            "total_decoded_clip_count",
            "loop_proofs",
        },
        "Android telemetry",
    )
    interval_ms = _require_int(
        telemetry["sample_interval_ms"],
        "telemetry.sample_interval_ms",
        minimum=1,
    )
    duration_ns = _require_int(telemetry["duration_ns"], "telemetry.duration_ns")
    samples = _require_list(telemetry["samples"], "telemetry.samples", nonempty=True)
    expected_delta_ns = interval_ms * 1_000_000
    allowed_delta_ns = min(
        250_000_000,
        expected_delta_ns * 5 // 100,
    )
    previous_timestamp: Optional[int] = None
    previous_progress = -1
    previous_completed_clips = -1
    previous_completed_loops = -1
    previous_successful_loops = -1
    peak_rss = 0
    peak_pss = 0
    validated_samples: list[dict[str, Any]] = []
    loop_proofs_raw = _require_list(
        telemetry["loop_proofs"],
        "telemetry.loop_proofs",
        nonempty=True,
    )
    if len(loop_proofs_raw) < 2:
        raise ContractError("Android stability requires at least two complete loops")
    ordered_clip_ids = [
        clip["clip_id"] for clip in decoder_plan["clips"]
    ]
    clip_count = len(ordered_clip_ids)
    expected_order_sha256 = canonical_sha256(ordered_clip_ids)
    validated_loops: list[dict[str, Any]] = []
    previous_loop_end = -1
    for index, raw_loop in enumerate(loop_proofs_raw, start=1):
        context = f"telemetry.loop_proofs[{index - 1}]"
        loop = dict(_require_mapping(raw_loop, context))
        _exact_keys(
            loop,
            {
                "loop_index",
                "monotonic_start_ns",
                "monotonic_end_ns",
                "ordered_clip_ids_sha256",
                "clip_count",
                "cumulative_decoded_clip_count",
                "successful",
            },
            context,
        )
        if loop["loop_index"] != index:
            raise ContractError("Android loop indices must be contiguous from one")
        start_ns = _require_int(
            loop["monotonic_start_ns"], f"{context}.monotonic_start_ns"
        )
        end_ns = _require_int(
            loop["monotonic_end_ns"], f"{context}.monotonic_end_ns"
        )
        if end_ns < start_ns or start_ns < previous_loop_end:
            raise ContractError("Android loop clocks overlap or go backwards")
        previous_loop_end = end_ns
        if (
            loop["ordered_clip_ids_sha256"] != expected_order_sha256
            or loop["clip_count"] != clip_count
            or loop["cumulative_decoded_clip_count"]
            != index * clip_count
        ):
            raise ContractError(
                "Android loop proof does not cover the frozen clip order"
            )
        if loop["successful"] is not True:
            raise ContractError("Android stability loop must complete successfully")
        validated_loops.append(loop)

    for index, raw_sample in enumerate(samples):
        context = f"telemetry.samples[{index}]"
        sample = dict(_require_mapping(raw_sample, context))
        _exact_keys(
            sample,
            {
                "monotonic_ns",
                "rss_bytes",
                "pss_bytes",
                "thermal_status",
                "heartbeat_index",
                "runner_alive",
                "decoder_active",
                "active_clip_id",
                "active_decode_progress",
                "completed_clip_count",
                "completed_loops",
                "completed_clips_in_current_loop",
                "successful_loops",
                "last_verified_clip_id",
            },
            context,
        )
        timestamp = _require_int(sample["monotonic_ns"], f"{context}.monotonic_ns")
        if previous_timestamp is not None:
            delta = timestamp - previous_timestamp
            if delta <= 0 or abs(delta - expected_delta_ns) > allowed_delta_ns:
                raise ContractError(
                    "Android telemetry interval exceeds ±250ms/±5% jitter"
                )
        previous_timestamp = timestamp
        if sample["heartbeat_index"] != index or sample["runner_alive"] is not True:
            raise ContractError("Android telemetry heartbeat is missing or invalid")
        if not isinstance(sample["decoder_active"], bool):
            raise ContractError(
                "Android stability decoder activity must be a boolean"
            )
        progress = _require_int(
            sample["active_decode_progress"],
            f"{context}.active_decode_progress",
        )
        prior_progress = previous_progress
        if progress < prior_progress:
            raise ContractError(
                "Android stability active decode progress cannot move backwards"
            )
        completed_clips = _require_int(
            sample["completed_clip_count"], f"{context}.completed_clip_count"
        )
        completed_loops = _require_int(
            sample["completed_loops"], f"{context}.completed_loops"
        )
        completed_in_current_loop = _require_int(
            sample["completed_clips_in_current_loop"],
            f"{context}.completed_clips_in_current_loop",
        )
        successful_loops = _require_int(
            sample["successful_loops"], f"{context}.successful_loops"
        )
        if (
            completed_clips < previous_completed_clips
            or completed_loops < previous_completed_loops
            or successful_loops < previous_successful_loops
            or successful_loops > completed_loops
        ):
            raise ContractError("Android telemetry counters are not monotonic")
        if completed_in_current_loop > clip_count:
            raise ContractError(
                "Android current-loop clip count exceeds the frozen order"
            )
        derived_progress = completed_loops * clip_count + completed_in_current_loop
        if progress != derived_progress or completed_clips != derived_progress:
            raise ContractError(
                "Android progress is not uniquely derived from loop and clip state"
            )
        expected_last_clip = (
            None
            if derived_progress == 0
            else ordered_clip_ids[(derived_progress - 1) % clip_count]
        )
        last_verified_clip_id = sample["last_verified_clip_id"]
        if (
            last_verified_clip_id is not None
            and (
                not isinstance(last_verified_clip_id, str)
                or last_verified_clip_id not in ordered_clip_ids
            )
        ) or last_verified_clip_id != expected_last_clip:
            raise ContractError(
                "Android last verified clip differs from the frozen order"
            )
        active_clip_id = sample["active_clip_id"]
        expected_active_clip = (
            ordered_clip_ids[completed_in_current_loop]
            if (
                sample["decoder_active"]
                and completed_in_current_loop < clip_count
            )
            else None
        )
        if (
            active_clip_id is not None
            and (
                not isinstance(active_clip_id, str)
                or active_clip_id not in ordered_clip_ids
            )
        ) or active_clip_id != expected_active_clip:
            raise ContractError(
                "Android active clip differs from the next frozen-order clip"
            )
        if sample["decoder_active"] and completed_in_current_loop == clip_count:
            raise ContractError(
                "Android decoder cannot start before committing its complete loop"
            )
        if index == 0 and (
            sample["decoder_active"]
            or active_clip_id is not None
            or progress != 0
            or completed_clips != 0
            or completed_loops != 0
            or completed_in_current_loop != 0
            or successful_loops != 0
            or last_verified_clip_id is not None
        ):
            raise ContractError(
                "Android first heartbeat must precede every decode with zero progress"
            )
        if (
            index > 0
            and not sample["decoder_active"]
            and progress == prior_progress
        ):
            raise ContractError(
                "Android stability heartbeat was idle without verified progress"
            )
        expected_completed_loops = sum(
            loop["monotonic_end_ns"] <= timestamp for loop in validated_loops
        )
        if (
            completed_loops != expected_completed_loops
            or successful_loops != expected_completed_loops
            or progress < completed_loops * clip_count
        ):
            raise ContractError(
                "Android telemetry counters differ from complete loop proofs"
            )
        previous_completed_clips = completed_clips
        previous_completed_loops = completed_loops
        previous_successful_loops = successful_loops
        previous_progress = progress
        rss = _require_int(sample["rss_bytes"], f"{context}.rss_bytes")
        pss = _require_int(sample["pss_bytes"], f"{context}.pss_bytes")
        thermal = _require_int(sample["thermal_status"], f"{context}.thermal_status")
        if thermal > 6:
            raise ContractError("Android thermal status must be between zero and six")
        peak_rss = max(peak_rss, rss)
        peak_pss = max(peak_pss, pss)
        validated_samples.append(sample)
    if len(validated_samples) < 2:
        raise ContractError("Android telemetry needs at least two heartbeats")
    actual_duration = (
        validated_samples[-1]["monotonic_ns"]
        - validated_samples[0]["monotonic_ns"]
    )
    if abs(actual_duration - duration_ns) > allowed_delta_ns:
        raise ContractError("Android telemetry duration differs from heartbeat clocks")
    if telemetry["loop_count"] != len(validated_loops):
        raise ContractError("Android telemetry loop count is inconsistent")
    if telemetry["successful_loop_count"] != len(validated_loops):
        raise ContractError("Android telemetry successful loop count is inconsistent")
    expected_total = len(validated_loops) * clip_count
    if (
        telemetry["total_decoded_clip_count"] != expected_total
        or previous_completed_loops != len(validated_loops)
        or previous_successful_loops != len(validated_loops)
        or previous_progress < expected_total
        or previous_progress > expected_total + clip_count
        or previous_completed_clips != previous_progress
    ):
        raise ContractError("Android telemetry final loop totals are inconsistent")
    expected_peak_rss = max(
        peak_rss,
        runtime["decode_probe_peak_rss_bytes"],
    )
    expected_peak_pss = max(
        peak_pss,
        runtime["decode_probe_peak_pss_bytes"],
    )
    if (
        expected_peak_rss != runtime["peak_rss_bytes"]
        or expected_peak_pss != runtime["peak_pss_bytes"]
    ):
        raise ContractError(
            "Android runtime peak RSS/PSS differs from the exact maximum "
            "of decode probes and telemetry samples"
        )
    without_hash = dict(telemetry)
    del without_hash["summary_sha256"]
    expected_summary = canonical_sha256(without_hash)
    if telemetry["summary_sha256"] != expected_summary:
        raise ContractError("Android telemetry summary hash differs")
    telemetry["samples"] = validated_samples
    telemetry["loop_proofs"] = validated_loops
    return telemetry


def _validate_engineering(
    document: Any,
    decoder_plan: Mapping[str, Any],
    accuracy: Mapping[str, Any],
    decoder_receipt: Mapping[str, Any],
) -> dict[str, Any]:
    engineering = dict(_require_mapping(document, "Android engineering payload"))
    _reject_engineering_role_leakage(engineering, "Android engineering payload")
    _exact_keys(
        engineering,
        {
            "schema_version",
            "run_id",
            "dataset_id",
            "model_alias",
            "decoder_plan_sha256",
            "accuracy_output_sha256",
            "clock_source",
            "trusted_runner_status",
            "bindings",
            "pcm_set_sha256",
            "artifact_measurements",
            "artifact_set_sha256",
            "clip_measurements",
            "runtime",
            "telemetry",
        },
        "Android engineering payload",
    )
    if engineering["schema_version"] != "2.0":
        raise ContractError("Android engineering payload version is unsupported")
    for field in ("run_id", "dataset_id", "model_alias"):
        if engineering[field] != decoder_plan[field]:
            raise ContractError(
                f"Android engineering {field} differs from decoder plan"
            )
    if engineering["decoder_plan_sha256"] != canonical_sha256(decoder_plan):
        raise ContractError("Android engineering decoder-plan hash differs")
    if engineering["accuracy_output_sha256"] != canonical_sha256(accuracy):
        raise ContractError("Android engineering accuracy payload hash differs")
    if engineering["clock_source"] != "android_elapsed_realtime_nanos":
        raise ContractError("Android runner clock must be elapsedRealtimeNanos")
    if engineering["trusted_runner_status"] not in {
        "phase_b_development_only",
        "phase_b_external_observer_required",
    }:
        raise ContractError("Android trusted runner status is invalid")
    bindings = dict(_require_mapping(engineering["bindings"], "runner bindings"))
    _exact_keys(
        bindings,
        {
            "benchmark_evidence_sha256",
            "public_plan_sha256",
            "registry_sha256",
            "normalization_sha256",
            "selection_config_sha256",
            "runner_build_sha256",
            "apk_sha256",
            "app_signing_cert_sha256",
            "device_identity_commitment_sha256",
        },
        "runner bindings",
    )
    for key, digest in bindings.items():
        _require_sha256(digest, f"runner bindings.{key}")
    if (
        bindings["benchmark_evidence_sha256"]
        != decoder_receipt["evidence_sha256"]
        or bindings["public_plan_sha256"] != decoder_receipt["plan_sha256"]
        or bindings["public_plan_sha256"] != decoder_plan["public_plan_sha256"]
    ):
        raise ContractError("Android runner binding differs from Phase A receipt")
    artifacts = _validate_artifact_measurements(
        engineering["artifact_measurements"]
    )
    if engineering["artifact_set_sha256"] != canonical_sha256(artifacts):
        raise ContractError("Android artifact-set hash differs")
    expected_pcm_set_sha256 = canonical_sha256(
        [
            {
                "clip_id": clip["clip_id"],
                "wav": clip["wav_file_sha256"],
                "payload": clip["pcm_payload_sha256"],
            }
            for clip in decoder_plan["clips"]
        ]
    )
    if engineering["pcm_set_sha256"] != expected_pcm_set_sha256:
        raise ContractError("Android frozen PCM-set hash differs from decoder plan")
    clip_measurements = _validate_clip_measurements(
        engineering["clip_measurements"],
        decoder_plan,
    )
    runtime = _validate_runtime(
        engineering["runtime"],
        artifacts=artifacts,
        clip_measurements=clip_measurements,
    )
    telemetry = _validate_telemetry(
        engineering["telemetry"],
        decoder_plan=decoder_plan,
        runtime=runtime,
    )
    engineering["bindings"] = bindings
    engineering["artifact_measurements"] = artifacts
    engineering["clip_measurements"] = clip_measurements
    engineering["runtime"] = runtime
    engineering["telemetry"] = telemetry
    return engineering


def build_attestation_commitments(
    decoder_receipt: Mapping[str, Any],
    decoder_plan: Mapping[str, Any],
    accuracy_payload: Mapping[str, Any],
    engineering_payload: Mapping[str, Any],
) -> dict[str, str]:
    """Build the exact commitment set implemented by the Android runner."""
    receipt = _require_mapping(decoder_receipt, "Phase A decoder receipt")
    plan = _validate_decoder_plan(decoder_plan)
    accuracy = _validate_accuracy(accuracy_payload, plan)
    bindings = _require_mapping(
        _require_mapping(
            engineering_payload,
            "Android engineering payload",
        ).get("bindings"),
        "Android runner bindings",
    )
    commitments = {
        "phase_a_decoder_plan_receipt_commit_sha256": _require_sha256(
            receipt.get("commit_sha256"),
            "Phase A decoder receipt commit hash",
        ),
        "benchmark_evidence_sha256": _require_sha256(
            receipt.get("evidence_sha256"),
            "Phase A evidence hash",
        ),
        "public_plan_sha256": _require_sha256(
            receipt.get("plan_sha256"),
            "Phase A public-plan hash",
        ),
        "decoder_plan_sha256": canonical_sha256(plan),
        "decoder_plan_raw_sha256": decoder_plan_raw_sha256(plan),
        "decoder_plan_id_sha256": hashlib.sha256(
            plan["plan_id"].encode("utf-8")
        ).hexdigest(),
        "accuracy_payload_sha256": canonical_sha256(accuracy),
        "engineering_payload_sha256": canonical_sha256(engineering_payload),
        "registry_sha256": _require_sha256(
            bindings.get("registry_sha256"), "registry hash"
        ),
        "normalization_sha256": _require_sha256(
            bindings.get("normalization_sha256"), "normalization hash"
        ),
        "selection_config_sha256": _require_sha256(
            bindings.get("selection_config_sha256"), "selection config hash"
        ),
        "runner_build_sha256": _require_sha256(
            bindings.get("runner_build_sha256"), "runner build hash"
        ),
        "apk_sha256": _require_sha256(bindings.get("apk_sha256"), "APK hash"),
        "app_signing_cert_sha256": _require_sha256(
            bindings.get("app_signing_cert_sha256"),
            "app signing certificate hash",
        ),
        "device_identity_commitment_sha256": _require_sha256(
            bindings.get("device_identity_commitment_sha256"),
            "device identity commitment",
        ),
        "pcm_set_sha256": _require_sha256(
            engineering_payload.get("pcm_set_sha256"), "PCM-set hash"
        ),
        "artifact_set_sha256": _require_sha256(
            engineering_payload.get("artifact_set_sha256"), "artifact-set hash"
        ),
        "telemetry_summary_sha256": _require_sha256(
            _require_mapping(
                engineering_payload.get("telemetry"),
                "Android telemetry",
            ).get("summary_sha256"),
            "telemetry summary hash",
        ),
    }
    if commitments["benchmark_evidence_sha256"] != bindings.get(
        "benchmark_evidence_sha256"
    ) or commitments["public_plan_sha256"] != bindings.get(
        "public_plan_sha256"
    ):
        raise ContractError("Android commitments differ from Phase A bindings")
    return commitments


def _validate_signed_payload(
    value: Any,
    *,
    expected_commitments: Mapping[str, str],
    decoder_plan: Mapping[str, Any],
    expected_host_challenge: bytes,
) -> dict[str, Any]:
    payload = dict(_require_mapping(value, "attestation signed payload"))
    _exact_keys(
        payload,
        {
            "schema_version",
            "run_id",
            "model_alias",
            "protocol_profile",
            "commitments",
            "host_challenge_sha256",
            "attestation_challenge_sha256",
            "runtime_claims",
        },
        "attestation signed payload",
    )
    if payload["schema_version"] != "1.0":
        raise ContractError("attestation signed payload version is unsupported")
    if (
        payload["run_id"] != decoder_plan["run_id"]
        or payload["model_alias"] != decoder_plan["model_alias"]
    ):
        raise ContractError("attestation run or alias differs from decoder plan")
    if payload["protocol_profile"] not in {
        "development_fixture",
        "exploration",
        "production_confirmation",
    }:
        raise ContractError("attestation protocol profile is invalid")
    commitments = dict(
        _require_mapping(payload["commitments"], "attestation commitments")
    )
    if commitments != dict(expected_commitments):
        raise ContractError(
            "attestation commitment differs from measured Android bundle"
        )
    host_challenge_sha256 = hashlib.sha256(expected_host_challenge).hexdigest()
    if payload["host_challenge_sha256"] != host_challenge_sha256:
        raise ContractError("attestation host challenge differs from verifier nonce")
    challenge_sha256 = hashlib.sha256(
        b"kittyecho-asr-keystore-challenge-v1\x00"
        + expected_host_challenge
        + _canonical_json_bytes(commitments)
    ).hexdigest()
    if payload["attestation_challenge_sha256"] != challenge_sha256:
        raise ContractError("attestation challenge hash differs from commitments")
    claims = dict(_require_mapping(payload["runtime_claims"], "runtime claims"))
    _exact_keys(
        claims,
        {
            "physical_device",
            "emulator",
            "local_key_security_level",
        },
        "runtime claims",
    )
    if not isinstance(claims["physical_device"], bool) or not isinstance(
        claims["emulator"], bool
    ):
        raise ContractError("runtime device claims must be booleans")
    if claims["physical_device"] == claims["emulator"]:
        raise ContractError("runtime physical-device and emulator claims conflict")
    if claims["local_key_security_level"] not in {
        "software",
        "trusted_environment",
        "strongbox",
        "unknown",
    }:
        raise ContractError("runtime local key security claim is invalid")
    payload["commitments"] = commitments
    payload["runtime_claims"] = claims
    return payload


class _DerReader:
    def __init__(self, encoded: bytes) -> None:
        self.encoded = encoded
        self.offset = 0

    def read(self) -> tuple[int, bool, int, bytes]:
        if self.offset >= len(self.encoded):
            raise ContractError("Android attestation extension is truncated")
        first = self.encoded[self.offset]
        self.offset += 1
        tag_class = first >> 6
        constructed = bool(first & 0x20)
        tag_number = first & 0x1F
        if tag_number == 0x1F:
            tag_number = 0
            groups = 0
            while True:
                if self.offset >= len(self.encoded) or groups >= 5:
                    raise ContractError(
                        "Android attestation extension has invalid high tag"
                    )
                byte = self.encoded[self.offset]
                self.offset += 1
                groups += 1
                tag_number = (tag_number << 7) | (byte & 0x7F)
                if not byte & 0x80:
                    break
        if self.offset >= len(self.encoded):
            raise ContractError("Android attestation extension lacks length")
        length_byte = self.encoded[self.offset]
        self.offset += 1
        if length_byte & 0x80:
            count = length_byte & 0x7F
            if count == 0 or count > 4 or self.offset + count > len(self.encoded):
                raise ContractError(
                    "Android attestation extension has invalid DER length"
                )
            length = int.from_bytes(
                self.encoded[self.offset : self.offset + count],
                "big",
            )
            if length < 128:
                raise ContractError(
                    "Android attestation extension has non-canonical DER length"
                )
            self.offset += count
        else:
            length = length_byte
        end = self.offset + length
        if end > len(self.encoded):
            raise ContractError("Android attestation extension value is truncated")
        value = self.encoded[self.offset : end]
        self.offset = end
        return tag_class, constructed, tag_number, value

    def exhausted(self) -> bool:
        return self.offset == len(self.encoded)


def _single_der(
    encoded: bytes,
    *,
    tag_class: int,
    tag_number: int,
    constructed: Optional[bool] = None,
    context: str,
) -> bytes:
    reader = _DerReader(encoded)
    actual_class, actual_constructed, actual_tag, value = reader.read()
    if (
        actual_class != tag_class
        or actual_tag != tag_number
        or (
            constructed is not None
            and actual_constructed is not constructed
        )
        or not reader.exhausted()
    ):
        raise ContractError(f"{context} has unexpected DER structure")
    return value


def _der_integer(encoded_value: bytes, context: str) -> int:
    if not encoded_value or encoded_value[0] & 0x80:
        raise ContractError(f"{context} must be a non-negative DER integer")
    return int.from_bytes(encoded_value, "big")


def _authorization_tag(sequence_value: bytes, tag_number: int) -> Optional[bytes]:
    reader = _DerReader(sequence_value)
    found: Optional[bytes] = None
    while not reader.exhausted():
        tag_class, constructed, actual_tag, value = reader.read()
        if tag_class == 2 and actual_tag == tag_number:
            if not constructed or found is not None:
                raise ContractError(
                    "Android authorization tag is duplicated or not explicit"
                )
            found = value
    return found


def _parse_attestation_application_id(
    explicit_value: bytes,
) -> tuple[set[tuple[str, int]], set[str]]:
    octets = _single_der(
        explicit_value,
        tag_class=0,
        tag_number=4,
        constructed=False,
        context="AttestationApplicationId wrapper",
    )
    sequence = _single_der(
        octets,
        tag_class=0,
        tag_number=16,
        constructed=True,
        context="AttestationApplicationId",
    )
    reader = _DerReader(sequence)
    package_set_class, package_set_constructed, package_set_tag, package_set = (
        reader.read()
    )
    digest_set_class, digest_set_constructed, digest_set_tag, digest_set = (
        reader.read()
    )
    if (
        not reader.exhausted()
        or (package_set_class, package_set_constructed, package_set_tag)
        != (0, True, 17)
        or (digest_set_class, digest_set_constructed, digest_set_tag)
        != (0, True, 17)
    ):
        raise ContractError("AttestationApplicationId sets are invalid")
    packages: set[tuple[str, int]] = set()
    package_reader = _DerReader(package_set)
    while not package_reader.exhausted():
        tag_class, constructed, tag, package_value = package_reader.read()
        if (tag_class, constructed, tag) != (0, True, 16):
            raise ContractError("attested package info is malformed")
        fields = _DerReader(package_value)
        name_class, name_constructed, name_tag, name_value = fields.read()
        version_class, version_constructed, version_tag, version_value = fields.read()
        if (
            not fields.exhausted()
            or (name_class, name_constructed, name_tag) != (0, False, 4)
            or (version_class, version_constructed, version_tag)
            != (0, False, 2)
        ):
            raise ContractError("attested package info fields are malformed")
        try:
            package_name = name_value.decode("utf-8")
        except UnicodeError as error:
            raise ContractError("attested package name is not UTF-8") from error
        packages.add(
            (package_name, _der_integer(version_value, "attested package version"))
        )
    digests: set[str] = set()
    digest_reader = _DerReader(digest_set)
    while not digest_reader.exhausted():
        tag_class, constructed, tag, digest = digest_reader.read()
        if (tag_class, constructed, tag) != (0, False, 4) or len(digest) != 32:
            raise ContractError("attested app signing digest is malformed")
        digests.add(digest.hex())
    if not packages or not digests:
        raise ContractError("attested application ID must not be empty")
    return packages, digests


def _parse_root_of_trust(explicit_value: bytes) -> tuple[bool, int]:
    sequence = _single_der(
        explicit_value,
        tag_class=0,
        tag_number=16,
        constructed=True,
        context="RootOfTrust",
    )
    reader = _DerReader(sequence)
    _boot_key = reader.read()
    device_locked = reader.read()
    boot_state = reader.read()
    _boot_hash = reader.read()
    if not reader.exhausted():
        raise ContractError("RootOfTrust contains unexpected fields")
    if (
        (_boot_key[0], _boot_key[1], _boot_key[2]) != (0, False, 4)
        or (device_locked[0], device_locked[1], device_locked[2])
        != (0, False, 1)
        or (boot_state[0], boot_state[1], boot_state[2]) != (0, False, 10)
        or (_boot_hash[0], _boot_hash[1], _boot_hash[2]) != (0, False, 4)
        or device_locked[3] not in {b"\x00", b"\xff"}
    ):
        raise ContractError("RootOfTrust fields are malformed")
    return (
        device_locked[3] == b"\xff",
        _der_integer(boot_state[3], "verified boot state"),
    )


def _parse_android_attestation_extension(
    encoded: bytes,
    *,
    expected_challenge: bytes,
    expected_package_name: Optional[str],
    expected_version_code: Optional[int],
    expected_signing_cert_sha256: str,
) -> tuple[str, bool, bool, bool]:
    sequence = _single_der(
        encoded,
        tag_class=0,
        tag_number=16,
        constructed=True,
        context="Android key attestation extension",
    )
    reader = _DerReader(sequence)
    fields = [reader.read() for _ in range(8)]
    if not reader.exhausted():
        raise ContractError("Android key attestation extension has extra fields")
    expected_tags = (2, 10, 2, 10, 4, 4, 16, 16)
    if any(
        tag_class != 0
        or tag != expected_tag
        or constructed != (expected_tag == 16)
        for (tag_class, constructed, tag, _value), expected_tag in zip(
            fields, expected_tags
        )
    ):
        raise ContractError("Android key attestation fields are malformed")
    security_value = _der_integer(fields[1][3], "attestation security level")
    keymint_security_value = _der_integer(
        fields[3][3],
        "KeyMint security level",
    )
    levels = {0: "software", 1: "trusted_environment", 2: "strongbox"}
    security_level = levels.get(security_value, "unknown")
    challenge_matches = fields[4][3] == expected_challenge
    software_authorizations = fields[6][3]
    hardware_authorizations = fields[7][3]
    application_explicit = _authorization_tag(
        software_authorizations,
        709,
    ) or _authorization_tag(hardware_authorizations, 709)
    application_matches = False
    if application_explicit is not None:
        packages, digests = _parse_attestation_application_id(
            application_explicit
        )
        application_matches = (
            expected_package_name is not None
            and expected_version_code is not None
            and packages == {
                (expected_package_name, expected_version_code)
            }
            and expected_signing_cert_sha256 in digests
        )
    root_explicit = _authorization_tag(hardware_authorizations, 704)
    verified_boot_matches = False
    if root_explicit is not None:
        locked, boot_state = _parse_root_of_trust(root_explicit)
        verified_boot_matches = locked and boot_state == 0
    hardware = (
        security_value in {1, 2}
        and keymint_security_value in {1, 2}
    )
    return (
        security_level,
        hardware,
        challenge_matches,
        application_matches and verified_boot_matches,
    )


def _verify_certificate_signature(
    certificate: x509.Certificate,
    issuer_public_key: Any,
) -> None:
    try:
        if isinstance(issuer_public_key, rsa.RSAPublicKey):
            issuer_public_key.verify(
                certificate.signature,
                certificate.tbs_certificate_bytes,
                padding.PKCS1v15(),
                certificate.signature_hash_algorithm,
            )
        elif isinstance(issuer_public_key, ec.EllipticCurvePublicKey):
            issuer_public_key.verify(
                certificate.signature,
                certificate.tbs_certificate_bytes,
                ec.ECDSA(certificate.signature_hash_algorithm),
            )
        elif isinstance(issuer_public_key, dsa.DSAPublicKey):
            issuer_public_key.verify(
                certificate.signature,
                certificate.tbs_certificate_bytes,
                certificate.signature_hash_algorithm,
            )
        elif isinstance(
            issuer_public_key,
            (ed25519.Ed25519PublicKey, ed448.Ed448PublicKey),
        ):
            issuer_public_key.verify(
                certificate.signature,
                certificate.tbs_certificate_bytes,
            )
        else:
            raise ContractError("attestation certificate key type is unsupported")
    except InvalidSignature as error:
        raise ContractError("attestation certificate-chain signature is invalid") from error


def _verify_report_signature(
    leaf: x509.Certificate,
    signed_payload: Mapping[str, Any],
    signature: bytes,
) -> None:
    public_key = leaf.public_key()
    if not isinstance(public_key, ec.EllipticCurvePublicKey):
        raise ContractError("Android runner report key must be EC")
    try:
        public_key.verify(
            signature,
            attestation_message_bytes(signed_payload),
            ec.ECDSA(hashes.SHA256()),
        )
    except InvalidSignature as error:
        raise ContractError("Android runner report signature is invalid") from error


def _decode_base64(value: Any, context: str, *, max_bytes: int) -> bytes:
    if not isinstance(value, str) or len(value) > max_bytes * 2:
        raise ContractError(f"{context} is invalid")
    try:
        decoded = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ContractError(f"{context} is not strict base64") from error
    if not decoded or len(decoded) > max_bytes:
        raise ContractError(f"{context} size is invalid")
    return decoded


def _verify_certificates(
    certificate_chain_der: Sequence[bytes],
    *,
    signed_payload: Mapping[str, Any],
    signature: bytes,
    policy: AndroidAttestationPolicy,
) -> _CertificateFacts:
    try:
        certificates = [
            x509.load_der_x509_certificate(encoded)
            for encoded in certificate_chain_der
        ]
    except ValueError as error:
        raise ContractError("Android attestation certificate is malformed") from error
    leaf = certificates[0]
    _verify_report_signature(leaf, signed_payload, signature)
    chain_signatures_valid = True
    try:
        for certificate, issuer in zip(certificates, certificates[1:]):
            if certificate.issuer != issuer.subject:
                raise ContractError(
                    "Android attestation certificate issuer chain is invalid"
                )
            _verify_certificate_signature(certificate, issuer.public_key())
        _verify_certificate_signature(certificates[-1], certificates[-1].public_key())
    except ContractError:
        chain_signatures_valid = False

    now = datetime.datetime.now(datetime.timezone.utc)
    validity_ok = all(
        certificate.not_valid_before_utc <= now <= certificate.not_valid_after_utc
        for certificate in certificates
    )
    revocation_ok = all(
        certificate.serial_number not in policy.revoked_certificate_serials
        for certificate in certificates
    )
    root_spki = certificates[-1].public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    root_spki_sha256 = hashlib.sha256(root_spki).hexdigest()
    trusted_root = root_spki_sha256 in policy.trusted_root_spki_sha256
    chain_verified = (
        chain_signatures_valid
        and validity_ok
        and revocation_ok
        and trusted_root
    )
    challenge = bytes.fromhex(signed_payload["attestation_challenge_sha256"])
    extension_status = "software"
    hardware = False
    challenge_matches = False
    application_and_boot_match = False
    try:
        extension = leaf.extensions.get_extension_for_oid(
            _ANDROID_ATTESTATION_OID
        ).value
        if not isinstance(extension, x509.UnrecognizedExtension):
            raise ContractError("Android attestation extension type is unexpected")
        (
            extension_status,
            hardware,
            challenge_matches,
            application_and_boot_match,
        ) = _parse_android_attestation_extension(
            extension.value,
            expected_challenge=challenge,
            expected_package_name=policy.expected_package_name,
            expected_version_code=policy.expected_version_code,
            expected_signing_cert_sha256=signed_payload["commitments"][
                "app_signing_cert_sha256"
            ],
        )
    except x509.ExtensionNotFound:
        pass
    hardware_verified = (
        chain_verified
        and hardware
        and challenge_matches
        and application_and_boot_match
    )
    return _CertificateFacts(
        signature_verified=True,
        chain_verified=chain_verified,
        hardware_verified=hardware_verified,
        security_level=extension_status,
        challenge_matches=challenge_matches,
        application_matches=application_and_boot_match,
        verified_boot_matches=application_and_boot_match,
    )


def _validate_envelope(
    document: Any,
    *,
    expected_commitments: Mapping[str, str],
    decoder_plan: Mapping[str, Any],
    policy: AndroidAttestationPolicy,
) -> tuple[dict[str, Any], _CertificateFacts]:
    envelope = dict(_require_mapping(document, "Android attestation envelope"))
    _exact_keys(
        envelope,
        {
            "schema_version",
            "attestation_format",
            "signed_payload",
            "signature_algorithm",
            "signature_base64",
            "certificate_chain_der_base64",
        },
        "Android attestation envelope",
    )
    if (
        envelope["schema_version"] != "1.0"
        or envelope["attestation_format"]
        != "android-keystore-key-attestation-v1"
        or envelope["signature_algorithm"] != "SHA256withECDSA"
    ):
        raise ContractError("Android attestation envelope contract is invalid")
    signed_payload = _validate_signed_payload(
        envelope["signed_payload"],
        expected_commitments=expected_commitments,
        decoder_plan=decoder_plan,
        expected_host_challenge=policy.expected_host_challenge,
    )
    signature = _decode_base64(
        envelope["signature_base64"],
        "Android report signature",
        max_bytes=1024,
    )
    encoded_chain = _require_list(
        envelope["certificate_chain_der_base64"],
        "Android attestation certificate chain",
        nonempty=True,
    )
    if len(encoded_chain) > 8:
        raise ContractError("Android attestation certificate chain is too long")
    certificate_chain = [
        _decode_base64(
            item,
            f"Android attestation certificate[{index}]",
            max_bytes=64 * 1024,
        )
        for index, item in enumerate(encoded_chain)
    ]
    certificate_facts = _verify_certificates(
        certificate_chain,
        signed_payload=signed_payload,
        signature=signature,
        policy=policy,
    )
    envelope["signed_payload"] = signed_payload
    return envelope, certificate_facts


def _verification_blockers(
    *,
    profile: str,
    signed_payload: Mapping[str, Any],
    engineering: Mapping[str, Any],
    certificate_facts: _CertificateFacts,
) -> tuple[str, ...]:
    blockers = {"trusted_execution_measurement_attestation_required"}
    claims = signed_payload["runtime_claims"]
    if not certificate_facts.chain_verified:
        blockers.add("trusted_attestation_certificate_chain_required")
    if not certificate_facts.hardware_verified:
        blockers.add("hardware_key_attestation_required")
    if claims["physical_device"] is not True or claims["emulator"] is not False:
        blockers.add("physical_device_required")
    if profile in _FORMAL_PROFILES:
        telemetry = engineering["telemetry"]
        if telemetry["duration_ns"] < 600_000_000_000:
            blockers.add("formal_stability_duration_required")
        blockers.add("external_adb_observer_receipt_required")
        blockers.add("two_clean_process_repetitions_required")
    return tuple(sorted(blockers))


def _verify_documents(
    *,
    decoder_plan: Mapping[str, Any],
    decoder_receipt: Mapping[str, Any],
    accuracy_document: Mapping[str, Any],
    engineering_document: Mapping[str, Any],
    envelope_document: Mapping[str, Any],
    policy: AndroidAttestationPolicy,
) -> tuple[
    dict[str, Any],
    dict[str, Any],
    dict[str, Any],
    _CertificateFacts,
    tuple[str, ...],
]:
    plan = _validate_decoder_plan(decoder_plan)
    if (
        decoder_receipt.get("run_id") != plan["run_id"]
        or decoder_receipt.get("plan_sha256") != plan["public_plan_sha256"]
    ):
        raise ContractError("Phase A decoder receipt differs from decoder plan")
    accuracy = _validate_accuracy(accuracy_document, plan)
    engineering = _validate_engineering(
        engineering_document,
        plan,
        accuracy,
        decoder_receipt,
    )
    commitments = build_attestation_commitments(
        decoder_receipt,
        plan,
        accuracy,
        engineering,
    )
    envelope, certificate_facts = _validate_envelope(
        envelope_document,
        expected_commitments=commitments,
        decoder_plan=plan,
        policy=policy,
    )
    profile = envelope["signed_payload"]["protocol_profile"]
    blockers = _verification_blockers(
        profile=profile,
        signed_payload=envelope["signed_payload"],
        engineering=engineering,
        certificate_facts=certificate_facts,
    )
    return accuracy, engineering, envelope, certificate_facts, blockers


def _validate_verification_document(
    document: Any,
    *,
    decoder_plan: Mapping[str, Any],
    expected_commitments: Mapping[str, str],
    decoder_receipt_commit_sha256: str,
    accuracy_receipt_commit_sha256: str,
    engineering_receipt_commit_sha256: str,
    expected_protocol_profile: Optional[str] = None,
    expected_blockers: Optional[Sequence[str]] = None,
) -> dict[str, Any]:
    verification = dict(
        _require_mapping(document, "Android attestation verification")
    )
    _exact_keys(
        verification,
        {
            "schema_version",
            "run_id",
            "model_alias",
            "protocol_profile",
            "signature_verified",
            "certificate_chain_verified",
            "hardware_key_verified",
            "attestation_security_level",
            "assurance_level",
            "formal_eligible",
            "product_decision_eligible",
            "adversarial_same_uid_resistant",
            "blockers",
            "commitments",
            "accuracy_payload_receipt_commit_sha256",
            "engineering_receipt_commit_sha256",
            "phase_a_decoder_plan_receipt_commit_sha256",
        },
        "Android attestation verification",
    )
    if verification["schema_version"] != "1.0":
        raise ContractError("Android verification version is unsupported")
    if (
        verification["run_id"] != decoder_plan["run_id"]
        or verification["model_alias"] != decoder_plan["model_alias"]
    ):
        raise ContractError(
            "Android verification run or alias differs from decoder plan"
        )
    protocol_profile = verification["protocol_profile"]
    if protocol_profile not in {
        "development_fixture",
        "exploration",
        "production_confirmation",
    }:
        raise ContractError("Android verification protocol profile is invalid")
    if (
        expected_protocol_profile is not None
        and protocol_profile != expected_protocol_profile
    ):
        raise ContractError("Android verification protocol profile is not permitted")
    for field in (
        "signature_verified",
        "certificate_chain_verified",
        "hardware_key_verified",
        "formal_eligible",
        "product_decision_eligible",
        "adversarial_same_uid_resistant",
    ):
        if not isinstance(verification[field], bool):
            raise ContractError(f"Android verification {field} must be boolean")
    if verification["signature_verified"] is not True:
        raise ContractError("Android verification receipt lacks a valid signature")
    if verification["attestation_security_level"] not in {
        "software",
        "trusted_environment",
        "strongbox",
        "unknown",
    }:
        raise ContractError("Android verification security level is invalid")
    if verification["assurance_level"] != "development_signed":
        raise ContractError("Android verification assurance level is invalid")
    for field in (
        "formal_eligible",
        "product_decision_eligible",
        "adversarial_same_uid_resistant",
    ):
        if verification[field] is not False:
            raise ContractError(
                "Android verification eligibility must remain false "
                "without a trusted external observer receipt"
            )

    raw_blockers = _require_list(
        verification["blockers"],
        "Android verification blockers",
        nonempty=True,
    )
    blockers = [
        _require_string(blocker, "Android verification blocker")
        for blocker in raw_blockers
    ]
    if len(blockers) != len(set(blockers)) or blockers != sorted(blockers):
        raise ContractError(
            "Android verification blockers must be unique and sorted"
        )
    required_blockers = {
        "trusted_execution_measurement_attestation_required",
    }
    if verification["certificate_chain_verified"] is False:
        required_blockers.add(
            "trusted_attestation_certificate_chain_required"
        )
    if verification["hardware_key_verified"] is False:
        required_blockers.add("hardware_key_attestation_required")
    if protocol_profile in _FORMAL_PROFILES:
        required_blockers.update(
            {
                "external_adb_observer_receipt_required",
                "two_clean_process_repetitions_required",
            }
        )
    if not required_blockers.issubset(blockers):
        raise ContractError("Android verification blockers are incomplete")
    if expected_blockers is not None and blockers != list(expected_blockers):
        raise ContractError("Android verification blockers changed before commit")

    commitments = dict(
        _require_mapping(
            verification["commitments"],
            "Android verification commitments",
        )
    )
    for key, digest in commitments.items():
        _require_string(key, "Android verification commitment name")
        _require_sha256(digest, f"Android verification commitments.{key}")
    if commitments != dict(expected_commitments):
        raise ContractError(
            "Android verification commitments differ from source payloads"
        )
    receipt_commitments = {
        "phase_a_decoder_plan_receipt_commit_sha256": (
            decoder_receipt_commit_sha256
        ),
        "accuracy_payload_receipt_commit_sha256": (
            accuracy_receipt_commit_sha256
        ),
        "engineering_receipt_commit_sha256": (
            engineering_receipt_commit_sha256
        ),
    }
    for field, expected in receipt_commitments.items():
        _require_sha256(
            verification[field],
            f"Android verification {field}",
        )
        _require_sha256(expected, f"expected Android {field}")
        if verification[field] != expected:
            raise ContractError(
                "Android verification source receipt commitment differs"
            )
    verification["blockers"] = blockers
    verification["commitments"] = commitments
    return verification


def verify_and_commit_android_bundle(
    *,
    decoder_plan_receipt_path: Path,
    accuracy_payload_path: Path,
    engineering_payload_path: Path,
    attestation_envelope_path: Path,
    accuracy_receipt_path: Path,
    engineering_receipt_path: Path,
    attestation_receipt_path: Path,
    repo_root: Path,
    policy: AndroidAttestationPolicy,
) -> AndroidBundleVerification:
    if not isinstance(policy, AndroidAttestationPolicy):
        raise ContractError("Android attestation policy is invalid")
    decoder_receipt_path = validate_private_artifact_input_path(
        decoder_plan_receipt_path,
        repo_root,
    )
    decoder_plan, decoder_receipt = load_committed_json_artifact(
        decoder_receipt_path,
        expected_kind="decoder-plan",
    )
    decoder_plan = _validate_decoder_plan(decoder_plan)
    accuracy_raw = _safe_read_raw_json(accuracy_payload_path, repo_root)
    engineering_raw = _safe_read_raw_json(engineering_payload_path, repo_root)
    envelope_raw = _safe_read_raw_json(attestation_envelope_path, repo_root)
    (
        accuracy,
        engineering,
        envelope,
        certificate_facts,
        blockers,
    ) = _verify_documents(
        decoder_plan=decoder_plan,
        decoder_receipt=decoder_receipt,
        accuracy_document=accuracy_raw.document,
        engineering_document=engineering_raw.document,
        envelope_document=envelope_raw.document,
        policy=policy,
    )
    evidence_sha256 = decoder_receipt["evidence_sha256"]
    plan_sha256 = decoder_receipt["plan_sha256"]
    run_id = decoder_plan["run_id"]

    accuracy_receipt_path = validate_private_artifact_output_path(
        accuracy_receipt_path,
        repo_root,
    )
    engineering_receipt_path = validate_private_artifact_output_path(
        engineering_receipt_path,
        repo_root,
    )
    attestation_receipt_path = validate_private_artifact_output_path(
        attestation_receipt_path,
        repo_root,
    )

    def validate_raw_bundle() -> None:
        current_plan, current_decoder_receipt = load_committed_json_artifact(
            decoder_receipt_path,
            expected_kind="decoder-plan",
            expected_evidence_sha256=evidence_sha256,
            expected_plan_sha256=plan_sha256,
        )
        if (
            current_decoder_receipt["commit_sha256"]
            != decoder_receipt["commit_sha256"]
            or canonical_sha256(current_plan) != canonical_sha256(decoder_plan)
        ):
            raise ContractError("Phase A decoder receipt changed before commit")
        current_accuracy = _safe_read_raw_json(
            accuracy_raw.path,
            repo_root,
            expected=accuracy_raw,
        )
        current_engineering = _safe_read_raw_json(
            engineering_raw.path,
            repo_root,
            expected=engineering_raw,
        )
        current_envelope = _safe_read_raw_json(
            envelope_raw.path,
            repo_root,
            expected=envelope_raw,
        )
        _verify_documents(
            decoder_plan=current_plan,
            decoder_receipt=current_decoder_receipt,
            accuracy_document=current_accuracy.document,
            engineering_document=current_engineering.document,
            envelope_document=current_envelope.document,
            policy=policy,
        )

    def validate_accuracy_document() -> None:
        if _validate_accuracy(accuracy, decoder_plan) != accuracy:
            raise ContractError("Android accuracy changed before commit")

    def validate_engineering_document() -> None:
        if (
            _validate_engineering(
                engineering,
                decoder_plan,
                accuracy,
                decoder_receipt,
            )
            != engineering
        ):
            raise ContractError("Android engineering payload changed before commit")

    accuracy_receipt = _commit_reserved_android_artifact(
        accuracy_receipt_path,
        accuracy,
        artifact_kind="android-accuracy-output",
        run_id=run_id,
        evidence_sha256=evidence_sha256,
        plan_sha256=plan_sha256,
        validate_document=validate_accuracy_document,
        validate_evidence=validate_raw_bundle,
    )
    engineering_receipt = _commit_reserved_android_artifact(
        engineering_receipt_path,
        engineering,
        artifact_kind="android-engineering-output",
        run_id=run_id,
        evidence_sha256=evidence_sha256,
        plan_sha256=plan_sha256,
        validate_document=validate_engineering_document,
        validate_evidence=validate_raw_bundle,
    )
    verification_document = {
        "schema_version": "1.0",
        "run_id": run_id,
        "model_alias": decoder_plan["model_alias"],
        "protocol_profile": envelope["signed_payload"]["protocol_profile"],
        "signature_verified": certificate_facts.signature_verified,
        "certificate_chain_verified": certificate_facts.chain_verified,
        "hardware_key_verified": certificate_facts.hardware_verified,
        "attestation_security_level": certificate_facts.security_level,
        "assurance_level": "development_signed",
        "formal_eligible": False,
        "product_decision_eligible": False,
        "adversarial_same_uid_resistant": False,
        "blockers": list(blockers),
        "commitments": envelope["signed_payload"]["commitments"],
        "accuracy_payload_receipt_commit_sha256": accuracy_receipt[
            "commit_sha256"
        ],
        "engineering_receipt_commit_sha256": engineering_receipt[
            "commit_sha256"
        ],
        "phase_a_decoder_plan_receipt_commit_sha256": decoder_receipt[
            "commit_sha256"
        ],
    }

    def validate_all_receipts() -> None:
        validate_raw_bundle()
        current_accuracy, current_accuracy_receipt = load_committed_json_artifact(
            accuracy_receipt_path,
            expected_kind="android-accuracy-output",
            expected_evidence_sha256=evidence_sha256,
            expected_plan_sha256=plan_sha256,
        )
        current_engineering, current_engineering_receipt = (
            load_committed_json_artifact(
                engineering_receipt_path,
                expected_kind="android-engineering-output",
                expected_evidence_sha256=evidence_sha256,
                expected_plan_sha256=plan_sha256,
            )
        )
        if (
            current_accuracy_receipt["commit_sha256"]
            != accuracy_receipt["commit_sha256"]
            or current_engineering_receipt["commit_sha256"]
            != engineering_receipt["commit_sha256"]
            or current_accuracy != accuracy
            or current_engineering != engineering
        ):
            raise ContractError(
                "Android payload receipt changed before attestation commit"
            )
        _validate_accuracy(current_accuracy, decoder_plan)
        _validate_engineering(
            current_engineering,
            decoder_plan,
            current_accuracy,
            decoder_receipt,
        )

    expected_commitments = build_attestation_commitments(
        decoder_receipt,
        decoder_plan,
        accuracy,
        engineering,
    )

    def validate_verification_document() -> None:
        _validate_verification_document(
            verification_document,
            decoder_plan=decoder_plan,
            expected_commitments=expected_commitments,
            decoder_receipt_commit_sha256=decoder_receipt["commit_sha256"],
            accuracy_receipt_commit_sha256=accuracy_receipt["commit_sha256"],
            engineering_receipt_commit_sha256=engineering_receipt[
                "commit_sha256"
            ],
            expected_protocol_profile=envelope["signed_payload"][
                "protocol_profile"
            ],
            expected_blockers=blockers,
        )

    attestation_receipt = _commit_reserved_android_artifact(
        attestation_receipt_path,
        verification_document,
        artifact_kind="android-attestation-verification",
        run_id=run_id,
        evidence_sha256=evidence_sha256,
        plan_sha256=plan_sha256,
        validate_document=validate_verification_document,
        validate_evidence=validate_all_receipts,
    )
    return AndroidBundleVerification(
        signature_verified=True,
        certificate_chain_verified=certificate_facts.chain_verified,
        hardware_key_verified=certificate_facts.hardware_verified,
        formal_eligible=False,
        product_decision_eligible=False,
        adversarial_same_uid_resistant=False,
        assurance_level="development_signed",
        blockers=blockers,
        accuracy_receipt_commit_sha256=accuracy_receipt["commit_sha256"],
        engineering_receipt_commit_sha256=engineering_receipt["commit_sha256"],
        attestation_receipt_commit_sha256=attestation_receipt["commit_sha256"],
    )


def load_verified_android_accuracy(
    attestation_receipt_path: Path,
    accuracy_receipt_path: Path,
    *,
    expected_run_id: str,
    expected_evidence_sha256: str,
    expected_plan_sha256: str,
) -> dict[str, Any]:
    if not _RUN_ID.fullmatch(expected_run_id):
        raise ContractError("expected Android run_id is invalid")
    _require_sha256(expected_evidence_sha256, "expected evidence hash")
    _require_sha256(expected_plan_sha256, "expected plan hash")
    raise ContractError("trusted_external_observer_receipt_required")


def load_development_synthetic_android_accuracy(
    decoder_plan_receipt_path: Path,
    accuracy_receipt_path: Path,
    engineering_receipt_path: Path,
    attestation_receipt_path: Path,
    *,
    policy: AndroidAccuracyLoadPolicy,
    expected_run_id: str,
    expected_evidence_sha256: str,
    expected_plan_sha256: str,
) -> dict[str, Any]:
    """Load verifier receipts only for explicit synthetic development checks."""
    if policy is not AndroidAccuracyLoadPolicy.DEVELOPMENT_SYNTHETIC:
        raise ContractError("development_synthetic_accuracy_policy_required")
    if not _RUN_ID.fullmatch(expected_run_id):
        raise ContractError("expected Android run_id is invalid")
    _require_sha256(expected_evidence_sha256, "expected evidence hash")
    _require_sha256(expected_plan_sha256, "expected plan hash")

    decoder_plan, decoder_receipt = load_committed_json_artifact(
        decoder_plan_receipt_path,
        expected_kind="decoder-plan",
        expected_evidence_sha256=expected_evidence_sha256,
        expected_plan_sha256=expected_plan_sha256,
    )
    accuracy, accuracy_receipt = load_committed_json_artifact(
        accuracy_receipt_path,
        expected_kind="android-accuracy-output",
        expected_evidence_sha256=expected_evidence_sha256,
        expected_plan_sha256=expected_plan_sha256,
    )
    engineering, engineering_receipt = load_committed_json_artifact(
        engineering_receipt_path,
        expected_kind="android-engineering-output",
        expected_evidence_sha256=expected_evidence_sha256,
        expected_plan_sha256=expected_plan_sha256,
    )
    verification, verification_receipt = load_committed_json_artifact(
        attestation_receipt_path,
        expected_kind="android-attestation-verification",
        expected_evidence_sha256=expected_evidence_sha256,
        expected_plan_sha256=expected_plan_sha256,
    )
    receipts = (
        decoder_receipt,
        accuracy_receipt,
        engineering_receipt,
        verification_receipt,
    )
    if any(receipt["run_id"] != expected_run_id for receipt in receipts):
        raise ContractError(
            "development Android receipts belong to a different run"
        )

    decoder_plan = _validate_decoder_plan(decoder_plan)
    if (
        decoder_plan["run_id"] != expected_run_id
        or decoder_plan["public_plan_sha256"] != expected_plan_sha256
        or decoder_receipt["plan_sha256"]
        != decoder_plan["public_plan_sha256"]
    ):
        raise ContractError(
            "development Android decoder plan differs from expected run"
        )
    accuracy = _validate_accuracy(accuracy, decoder_plan)
    engineering = _validate_engineering(
        engineering,
        decoder_plan,
        accuracy,
        decoder_receipt,
    )
    commitments = build_attestation_commitments(
        decoder_receipt,
        decoder_plan,
        accuracy,
        engineering,
    )
    _validate_verification_document(
        verification,
        decoder_plan=decoder_plan,
        expected_commitments=commitments,
        decoder_receipt_commit_sha256=decoder_receipt["commit_sha256"],
        accuracy_receipt_commit_sha256=accuracy_receipt["commit_sha256"],
        engineering_receipt_commit_sha256=engineering_receipt[
            "commit_sha256"
        ],
        expected_protocol_profile="development_fixture",
    )
    if (
        verification["formal_eligible"] is not False
        or verification["product_decision_eligible"] is not False
        or verification["adversarial_same_uid_resistant"] is not False
    ):
        raise ContractError(
            "development Android receipts must remain ineligible for decisions"
        )
    return accuracy
