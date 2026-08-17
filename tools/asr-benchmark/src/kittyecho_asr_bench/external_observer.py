from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Optional, Sequence

from .android_attestation import (
    AndroidAttestationPolicy,
    _CertificateFacts,
    _exact_keys,
    _require_int,
    _require_list,
    _require_mapping,
    _require_sha256,
    _require_string,
    _safe_read_raw_json,
    _validate_accuracy,
    _validate_decoder_plan,
    _validate_engineering,
    _validate_envelope,
    _validate_verification_document,
    build_attestation_commitments,
)
from .artifacts import (
    _commit_reserved_android_artifact,
    load_committed_json_artifact,
    validate_private_artifact_input_path,
    validate_private_artifact_output_path,
)
from .contracts import ContractError, canonical_sha256


_OBSERVER_KIND = "android-external-observer-synthetic-verification"
_FORMAL_PROFILES = {"exploration", "production_confirmation"}
_HARDWARE_SECURITY_LEVELS = {"trusted_environment", "strongbox"}
_FORBIDDEN_OBSERVER_KEYS = {
    "answer",
    "audio_path",
    "filename",
    "hypothesis",
    "model_id",
    "model_name",
    "path",
    "reference",
    "reference_text",
    "text",
    "transcript",
}
_ABSOLUTE_TIMING_TOLERANCE_NS = 250_000_000
_RELATIVE_TIMING_TOLERANCE_PERCENT = 5


def _require_nonzero_sha256(value: Any, context: str) -> str:
    digest = _require_sha256(value, context)
    if digest == "0" * 64:
        raise ContractError(f"{context} must not use the all-zero sentinel")
    return digest


def _require_bool(value: Any, context: str) -> bool:
    if not isinstance(value, bool):
        raise ContractError(f"{context} must be boolean")
    return value


def _reject_role_leakage(value: Any, context: str) -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            if not isinstance(key, str):
                raise ContractError(f"{context} contains a non-string field")
            if key.lower() in _FORBIDDEN_OBSERVER_KEYS:
                raise ContractError(
                    f"{context} leaks forbidden role field {key}"
                )
            _reject_role_leakage(child, f"{context}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _reject_role_leakage(child, f"{context}[{index}]")


@dataclass(frozen=True)
class ExternalObserverPolicy:
    """Frozen host inputs for the synthetic observer contract.

    This policy validates the future ADB observer data shape without claiming
    that synthetic JSON represents an ADB observation.
    """

    observer_challenge: bytes
    android_attestation_policy: AndroidAttestationPolicy
    expected_package_name: str
    expected_version_code: int
    expected_apk_sha256: str
    expected_signing_cert_sha256: str
    expected_runner_build_sha256: str
    expected_host_clock_boot_id_sha256: str
    expected_device_physical_ram_bytes: int
    expected_max_process_memory_bytes: int

    @classmethod
    def synthetic_contract(
        cls,
        *,
        observer_challenge: bytes,
        android_attestation_policy: AndroidAttestationPolicy,
        expected_package_name: str,
        expected_version_code: int,
        expected_apk_sha256: str,
        expected_signing_cert_sha256: str,
        expected_runner_build_sha256: str,
        expected_host_clock_boot_id_sha256: str,
        expected_device_physical_ram_bytes: int,
        expected_max_process_memory_bytes: int,
    ) -> "ExternalObserverPolicy":
        if (
            not isinstance(observer_challenge, bytes)
            or len(observer_challenge) != 32
            or observer_challenge == bytes(32)
        ):
            raise ContractError(
                "external observer challenge must be 32 non-zero bytes"
            )
        if not isinstance(
            android_attestation_policy,
            AndroidAttestationPolicy,
        ):
            raise ContractError("Android attestation policy is invalid")
        package_name = _require_string(
            expected_package_name,
            "external observer package name",
        )
        if package_name != android_attestation_policy.expected_package_name:
            raise ContractError(
                "external observer package differs from attestation policy"
            )
        version_code = _require_int(
            expected_version_code,
            "external observer version code",
            minimum=1,
        )
        if version_code != android_attestation_policy.expected_version_code:
            raise ContractError(
                "external observer version differs from attestation policy"
            )
        device_ram_bytes = _require_int(
            expected_device_physical_ram_bytes,
            "external observer device physical RAM",
            minimum=1,
        )
        process_limit_bytes = _require_int(
            expected_max_process_memory_bytes,
            "external observer maximum process memory",
            minimum=1,
        )
        if process_limit_bytes > device_ram_bytes:
            raise ContractError(
                "external observer process memory limit exceeds device RAM"
            )
        return cls(
            observer_challenge=observer_challenge,
            android_attestation_policy=android_attestation_policy,
            expected_package_name=package_name,
            expected_version_code=version_code,
            expected_apk_sha256=_require_nonzero_sha256(
                expected_apk_sha256,
                "external observer APK hash",
            ),
            expected_signing_cert_sha256=_require_nonzero_sha256(
                expected_signing_cert_sha256,
                "external observer signing certificate hash",
            ),
            expected_runner_build_sha256=_require_nonzero_sha256(
                expected_runner_build_sha256,
                "external observer runner build hash",
            ),
            expected_host_clock_boot_id_sha256=_require_nonzero_sha256(
                expected_host_clock_boot_id_sha256,
                "external observer host clock boot ID hash",
            ),
            expected_device_physical_ram_bytes=device_ram_bytes,
            expected_max_process_memory_bytes=process_limit_bytes,
        )


@dataclass(frozen=True)
class ExternalObserverVerification:
    two_clean_process_repetitions_verified: bool
    independent_hash_contract_verified: bool
    formal_eligible: bool
    product_decision_eligible: bool
    adversarial_same_uid_resistant: bool
    assurance_level: str
    blockers: tuple[str, ...]
    observer_receipt_commit_sha256: str


@dataclass(frozen=True)
class _SourceBundle:
    decoder_plan: dict[str, Any]
    decoder_receipt: dict[str, Any]
    accuracy: dict[str, Any]
    accuracy_receipt: dict[str, Any]
    engineering: dict[str, Any]
    engineering_receipt: dict[str, Any]
    verification: dict[str, Any]
    verification_receipt: dict[str, Any]
    commitments: dict[str, str]
    certificate_facts: _CertificateFacts
    envelope_raw_sha256: str
    protocol_profile: str


def _source_receipt_commitments(
    source: _SourceBundle,
) -> dict[str, str]:
    return {
        "phase_a_decoder_plan_receipt_commit_sha256": source.decoder_receipt[
            "commit_sha256"
        ],
        "accuracy_payload_receipt_commit_sha256": source.accuracy_receipt[
            "commit_sha256"
        ],
        "engineering_receipt_commit_sha256": source.engineering_receipt[
            "commit_sha256"
        ],
        "android_attestation_receipt_commit_sha256": (
            source.verification_receipt["commit_sha256"]
        ),
    }


def _load_and_verify_source_bundle(
    *,
    decoder_plan_receipt_path: Path,
    accuracy_receipt_path: Path,
    engineering_receipt_path: Path,
    android_attestation_receipt_path: Path,
    attestation_envelope_path: Path,
    repo_root: Path,
    policy: ExternalObserverPolicy,
) -> _SourceBundle:
    decoder_path = validate_private_artifact_input_path(
        decoder_plan_receipt_path,
        repo_root,
    )
    accuracy_path = validate_private_artifact_input_path(
        accuracy_receipt_path,
        repo_root,
    )
    engineering_path = validate_private_artifact_input_path(
        engineering_receipt_path,
        repo_root,
    )
    verification_path = validate_private_artifact_input_path(
        android_attestation_receipt_path,
        repo_root,
    )
    decoder_plan, decoder_receipt = load_committed_json_artifact(
        decoder_path,
        expected_kind="decoder-plan",
    )
    evidence_sha256 = decoder_receipt["evidence_sha256"]
    plan_sha256 = decoder_receipt["plan_sha256"]
    decoder_plan = _validate_decoder_plan(decoder_plan)
    accuracy, accuracy_receipt = load_committed_json_artifact(
        accuracy_path,
        expected_kind="android-accuracy-output",
        expected_evidence_sha256=evidence_sha256,
        expected_plan_sha256=plan_sha256,
    )
    engineering, engineering_receipt = load_committed_json_artifact(
        engineering_path,
        expected_kind="android-engineering-output",
        expected_evidence_sha256=evidence_sha256,
        expected_plan_sha256=plan_sha256,
    )
    verification, verification_receipt = load_committed_json_artifact(
        verification_path,
        expected_kind="android-attestation-verification",
        expected_evidence_sha256=evidence_sha256,
        expected_plan_sha256=plan_sha256,
    )
    receipts = (
        decoder_receipt,
        accuracy_receipt,
        engineering_receipt,
        verification_receipt,
    )
    if any(
        receipt["run_id"] != decoder_plan["run_id"]
        or receipt["evidence_sha256"] != evidence_sha256
        or receipt["plan_sha256"] != plan_sha256
        for receipt in receipts
    ):
        raise ContractError(
            "external observer source receipts differ by run or contract"
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
    envelope_raw = _safe_read_raw_json(
        attestation_envelope_path,
        repo_root,
    )
    envelope, certificate_facts = _validate_envelope(
        envelope_raw.document,
        expected_commitments=commitments,
        decoder_plan=decoder_plan,
        policy=policy.android_attestation_policy,
    )
    protocol_profile = envelope["signed_payload"]["protocol_profile"]
    if protocol_profile not in _FORMAL_PROFILES:
        raise ContractError(
            "external observer contract requires a formal protocol profile"
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
        expected_protocol_profile=protocol_profile,
    )
    expected_attestation_values = {
        "signature_verified": certificate_facts.signature_verified,
        "certificate_chain_verified": certificate_facts.chain_verified,
        "hardware_key_verified": certificate_facts.hardware_verified,
        "attestation_security_level": certificate_facts.security_level,
    }
    if any(
        verification[field] != expected
        for field, expected in expected_attestation_values.items()
    ):
        raise ContractError(
            "Android verification differs from the revalidated certificate chain"
        )
    return _SourceBundle(
        decoder_plan=decoder_plan,
        decoder_receipt=decoder_receipt,
        accuracy=accuracy,
        accuracy_receipt=accuracy_receipt,
        engineering=engineering,
        engineering_receipt=engineering_receipt,
        verification=verification,
        verification_receipt=verification_receipt,
        commitments=commitments,
        certificate_facts=certificate_facts,
        envelope_raw_sha256=envelope_raw.sha256,
        protocol_profile=protocol_profile,
    )


def _validate_known_release(
    value: Any,
    *,
    policy: ExternalObserverPolicy,
    commitments: Mapping[str, str],
) -> dict[str, Any]:
    release = dict(_require_mapping(value, "external observer known release"))
    _exact_keys(
        release,
        {
            "package_name",
            "version_code",
            "apk_sha256",
            "app_signing_cert_sha256",
            "runner_build_sha256",
        },
        "external observer known release",
    )
    expected = {
        "package_name": policy.expected_package_name,
        "version_code": policy.expected_version_code,
        "apk_sha256": policy.expected_apk_sha256,
        "app_signing_cert_sha256": (
            policy.expected_signing_cert_sha256
        ),
        "runner_build_sha256": policy.expected_runner_build_sha256,
    }
    if release != expected:
        raise ContractError(
            "external observer evidence differs from the frozen release"
        )
    if (
        release["apk_sha256"] != commitments["apk_sha256"]
        or release["app_signing_cert_sha256"]
        != commitments["app_signing_cert_sha256"]
        or release["runner_build_sha256"]
        != commitments["runner_build_sha256"]
    ):
        raise ContractError(
            "external observer release differs from Android commitments"
        )
    return release


def _validate_attestation_observation(
    value: Any,
    *,
    source: _SourceBundle,
) -> dict[str, Any]:
    observation = dict(
        _require_mapping(
            value,
            "external observer attestation observation",
        )
    )
    _exact_keys(
        observation,
        {
            "certificate_chain_verified",
            "hardware_key_verified",
            "attestation_security_level",
            "challenge_matches",
            "application_matches",
            "locked_verified_boot",
            "attestation_envelope_raw_sha256",
        },
        "external observer attestation observation",
    )
    boolean_fields = (
        "certificate_chain_verified",
        "hardware_key_verified",
        "challenge_matches",
        "application_matches",
        "locked_verified_boot",
    )
    if any(
        _require_bool(
            observation[field],
            f"external observer attestation {field}",
        )
        is not True
        for field in boolean_fields
    ):
        raise ContractError(
            "external observer requires verified hardware attestation, "
            "challenge, application, and locked verified boot"
        )
    security_level = _require_string(
        observation["attestation_security_level"],
        "external observer attestation security level",
    )
    if security_level not in _HARDWARE_SECURITY_LEVELS:
        raise ContractError(
            "external observer requires TEE or StrongBox attestation"
        )
    if (
        observation["certificate_chain_verified"]
        != source.certificate_facts.chain_verified
        or observation["hardware_key_verified"]
        != source.certificate_facts.hardware_verified
        or security_level != source.certificate_facts.security_level
        or observation["challenge_matches"]
        != source.certificate_facts.challenge_matches
        or observation["application_matches"]
        != source.certificate_facts.application_matches
        or observation["locked_verified_boot"]
        != source.certificate_facts.verified_boot_matches
    ):
        raise ContractError(
            "external observer attestation claims differ from host revalidation"
        )
    if (
        _require_sha256(
            observation["attestation_envelope_raw_sha256"],
            "external observer attestation envelope hash",
        )
        != source.envelope_raw_sha256
    ):
        raise ContractError(
            "external observer attestation envelope hash differs"
        )
    return observation


def _validate_host_clock(
    value: Any,
    *,
    policy: ExternalObserverPolicy,
) -> dict[str, Any]:
    clock = dict(_require_mapping(value, "external observer host clock"))
    _exact_keys(
        clock,
        {"source", "boot_id_sha256"},
        "external observer host clock",
    )
    if clock["source"] != "host_monotonic_clock_v1":
        raise ContractError(
            "external observer timings must use the frozen host monotonic clock"
        )
    if (
        _require_nonzero_sha256(
            clock["boot_id_sha256"],
            "external observer host clock boot ID hash",
        )
        != policy.expected_host_clock_boot_id_sha256
    ):
        raise ContractError(
            "external observer host clock domain differs from policy"
        )
    return clock


def _validate_host_resource_policy(
    value: Any,
    *,
    policy: ExternalObserverPolicy,
) -> dict[str, Any]:
    resource_policy = dict(
        _require_mapping(value, "external observer host resource policy")
    )
    _exact_keys(
        resource_policy,
        {
            "device_physical_ram_bytes",
            "max_process_memory_bytes",
        },
        "external observer host resource policy",
    )
    device_ram = _require_int(
        resource_policy["device_physical_ram_bytes"],
        "external observer device physical RAM",
        minimum=1,
    )
    process_limit = _require_int(
        resource_policy["max_process_memory_bytes"],
        "external observer maximum process memory",
        minimum=1,
    )
    if (
        device_ram != policy.expected_device_physical_ram_bytes
        or process_limit != policy.expected_max_process_memory_bytes
        or process_limit > device_ram
    ):
        raise ContractError(
            "external observer resource limits differ from frozen host policy"
        )
    return resource_policy


def _validate_pcm_measurements(
    value: Any,
    *,
    source: _SourceBundle,
    context: str,
) -> list[dict[str, Any]]:
    raw_measurements = _require_list(value, context, nonempty=True)
    expected = [
        {
            "clip_id": clip["clip_id"],
            "wav_file_sha256": clip["wav_file_sha256"],
            "pcm_payload_sha256": clip["pcm_payload_sha256"],
            "pcm_payload_bytes": clip["pcm_payload_bytes"],
        }
        for clip in source.decoder_plan["clips"]
    ]
    measurements: list[dict[str, Any]] = []
    for index, raw in enumerate(raw_measurements):
        item_context = f"{context}[{index}]"
        measurement = dict(_require_mapping(raw, item_context))
        _exact_keys(
            measurement,
            {
                "clip_id",
                "wav_file_sha256",
                "pcm_payload_sha256",
                "pcm_payload_bytes",
            },
            item_context,
        )
        _require_string(measurement["clip_id"], f"{item_context}.clip_id")
        _require_sha256(
            measurement["wav_file_sha256"],
            f"{item_context}.wav hash",
        )
        _require_sha256(
            measurement["pcm_payload_sha256"],
            f"{item_context}.PCM payload hash",
        )
        _require_int(
            measurement["pcm_payload_bytes"],
            f"{item_context}.PCM payload bytes",
            minimum=1,
        )
        measurements.append(measurement)
    if measurements != expected:
        raise ContractError(
            "external observer PCM measurements differ from frozen clip order"
        )
    measured_set_sha256 = canonical_sha256(
        [
            {
                "clip_id": item["clip_id"],
                "wav": item["wav_file_sha256"],
                "payload": item["pcm_payload_sha256"],
            }
            for item in measurements
        ]
    )
    if measured_set_sha256 != source.commitments["pcm_set_sha256"]:
        raise ContractError(
            "external observer PCM measurements do not reconstruct PCM-set hash"
        )
    return measurements


def _validate_artifact_measurements(
    value: Any,
    *,
    source: _SourceBundle,
    context: str,
) -> list[dict[str, Any]]:
    raw_measurements = _require_list(value, context, nonempty=True)
    measurements: list[dict[str, Any]] = []
    for index, raw in enumerate(raw_measurements):
        item_context = f"{context}[{index}]"
        measurement = dict(_require_mapping(raw, item_context))
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
            item_context,
        )
        _require_string(
            measurement["component_role"],
            f"{item_context}.component role",
        )
        _require_sha256(
            measurement["sha256"],
            f"{item_context}.sha256",
        )
        for field in (
            "size_bytes",
            "st_dev",
            "st_ino",
            "mode",
            "nlink",
        ):
            _require_int(
                measurement[field],
                f"{item_context}.{field}",
                minimum=1,
            )
        measurements.append(measurement)
    if measurements != source.engineering["artifact_measurements"]:
        raise ContractError(
            "external observer artifact measurements differ from runner evidence"
        )
    if canonical_sha256(measurements) != source.commitments[
        "artifact_set_sha256"
    ]:
        raise ContractError(
            "external observer artifacts do not reconstruct artifact-set hash"
        )
    return measurements


def _validate_external_timings(
    value: Any,
    *,
    source: _SourceBundle,
    repetition_context: str,
    repetition_start_ns: int,
    repetition_end_ns: int,
) -> list[dict[str, Any]]:
    raw_timings = _require_list(
        value,
        f"{repetition_context}.external_clip_timings",
        nonempty=True,
    )
    if len(raw_timings) != len(source.decoder_plan["clips"]):
        raise ContractError(
            f"{repetition_context} external timing count differs from clip order"
        )
    timings: list[dict[str, Any]] = []
    for index, (raw, clip, runner_measurement) in enumerate(
        zip(
            raw_timings,
            source.decoder_plan["clips"],
            source.engineering["clip_measurements"],
        )
    ):
        context = f"{repetition_context}.external_clip_timings[{index}]"
        timing = dict(_require_mapping(raw, context))
        _exact_keys(
            timing,
            {
                "clip_id",
                "monotonic_stop_ns",
                "monotonic_final_ns",
                "stop_to_final_ns",
            },
            context,
        )
        if timing["clip_id"] != clip["clip_id"]:
            raise ContractError(
                f"{repetition_context} external timing order differs"
            )
        stop_ns = _require_int(
            timing["monotonic_stop_ns"],
            f"{context}.monotonic_stop_ns",
        )
        final_ns = _require_int(
            timing["monotonic_final_ns"],
            f"{context}.monotonic_final_ns",
        )
        latency_ns = _require_int(
            timing["stop_to_final_ns"],
            f"{context}.stop_to_final_ns",
        )
        if final_ns <= stop_ns or final_ns - stop_ns != latency_ns:
            raise ContractError(
                f"{context} monotonic timing arithmetic is inconsistent"
            )
        if (
            stop_ns < repetition_start_ns
            or final_ns > repetition_end_ns
        ):
            raise ContractError(
                f"{context} falls outside its host repetition interval"
            )
        runner_latency = runner_measurement["stop_to_final_ns"]
        relative_tolerance = (
            runner_latency * _RELATIVE_TIMING_TOLERANCE_PERCENT
        ) // 100
        tolerance = min(
            _ABSOLUTE_TIMING_TOLERANCE_NS,
            relative_tolerance,
        )
        if abs(latency_ns - runner_latency) > tolerance:
            raise ContractError(
                f"{context} differs from runner timing beyond frozen tolerance"
            )
        timings.append(timing)
    return timings


def _validate_external_memory_samples(
    value: Any,
    *,
    repetition_context: str,
    repetition_start_ns: int,
    repetition_end_ns: int,
    policy: ExternalObserverPolicy,
) -> tuple[list[dict[str, Any]], int, int]:
    raw_samples = _require_list(
        value,
        f"{repetition_context}.external_memory_samples",
        nonempty=True,
    )
    memory_ceiling = min(
        policy.expected_device_physical_ram_bytes,
        policy.expected_max_process_memory_bytes,
    )
    samples: list[dict[str, Any]] = []
    previous_timestamp: Optional[int] = None
    peak_rss = 0
    peak_pss = 0
    for index, raw in enumerate(raw_samples):
        context = (
            f"{repetition_context}.external_memory_samples[{index}]"
        )
        sample = dict(_require_mapping(raw, context))
        _exact_keys(
            sample,
            {"monotonic_ns", "rss_bytes", "pss_bytes"},
            context,
        )
        timestamp = _require_int(
            sample["monotonic_ns"],
            f"{context}.monotonic_ns",
        )
        rss = _require_int(
            sample["rss_bytes"],
            f"{context}.rss_bytes",
            minimum=1,
        )
        pss = _require_int(
            sample["pss_bytes"],
            f"{context}.pss_bytes",
            minimum=1,
        )
        if (
            timestamp < repetition_start_ns
            or timestamp > repetition_end_ns
            or (
                previous_timestamp is not None
                and timestamp <= previous_timestamp
            )
        ):
            raise ContractError(
                f"{context} is outside or not monotonic in the host interval"
            )
        if pss > rss:
            raise ContractError(f"{context} PSS cannot exceed RSS")
        if rss > memory_ceiling or pss > memory_ceiling:
            raise ContractError(
                f"{context} exceeds frozen host or device memory bounds"
            )
        previous_timestamp = timestamp
        peak_rss = max(peak_rss, rss)
        peak_pss = max(peak_pss, pss)
        samples.append(sample)
    return samples, peak_rss, peak_pss


def _validate_repetitions(
    value: Any,
    *,
    source: _SourceBundle,
    policy: ExternalObserverPolicy,
) -> list[dict[str, Any]]:
    raw_repetitions = _require_list(
        value,
        "external observer repetitions",
        nonempty=True,
    )
    if len(raw_repetitions) != 2:
        raise ContractError(
            "external observer requires exactly two clean-process repetitions"
        )
    repetitions: list[dict[str, Any]] = []
    process_instances: set[str] = set()
    process_identities: set[tuple[int, int]] = set()
    previous_repetition_end_ns: Optional[int] = None
    for index, raw in enumerate(raw_repetitions, start=1):
        context = f"external observer repetitions[{index - 1}]"
        repetition = dict(_require_mapping(raw, context))
        _exact_keys(
            repetition,
            {
                "repetition_index",
                "process_instance_sha256",
                "pid",
                "pid_start_ticks",
                "host_clock_boot_id_sha256",
                "clean_process_start",
                "preexisting_process_detected",
                "process_exit_observed",
                "oom_kill_count",
                "crash_count",
                "host_monotonic_start_ns",
                "host_monotonic_end_ns",
                "external_clip_timings",
                "external_memory_samples",
                "observed_peak_rss_bytes",
                "observed_peak_pss_bytes",
                "host_pcm_measurements",
                "host_artifact_measurements",
                "host_pcm_set_sha256",
                "host_artifact_set_sha256",
                "host_accuracy_output_sha256",
                "host_apk_sha256",
                "host_signing_cert_sha256",
                "host_runner_build_sha256",
            },
            context,
        )
        if _require_int(
            repetition["repetition_index"],
            f"{context}.repetition_index",
            minimum=1,
        ) != index:
            raise ContractError(
                "external observer repetition indices must be 1 then 2"
            )
        process_instance = _require_nonzero_sha256(
            repetition["process_instance_sha256"],
            f"{context}.process instance hash",
        )
        pid = _require_int(
            repetition["pid"],
            f"{context}.pid",
            minimum=1,
        )
        pid_start_ticks = _require_int(
            repetition["pid_start_ticks"],
            f"{context}.pid start ticks",
            minimum=1,
        )
        if (
            process_instance in process_instances
            or (pid, pid_start_ticks) in process_identities
        ):
            raise ContractError(
                "external observer repetitions must use distinct clean processes"
            )
        process_instances.add(process_instance)
        process_identities.add((pid, pid_start_ticks))
        if (
            _require_nonzero_sha256(
                repetition["host_clock_boot_id_sha256"],
                f"{context}.host clock boot ID hash",
            )
            != policy.expected_host_clock_boot_id_sha256
        ):
            raise ContractError(
                "external observer repetitions cross host clock domains"
            )
        if (
            _require_bool(
                repetition["clean_process_start"],
                f"{context}.clean_process_start",
            )
            is not True
            or _require_bool(
                repetition["preexisting_process_detected"],
                f"{context}.preexisting_process_detected",
            )
            is not False
            or _require_bool(
                repetition["process_exit_observed"],
                f"{context}.process_exit_observed",
            )
            is not True
            or _require_int(
                repetition["oom_kill_count"],
                f"{context}.oom_kill_count",
            )
            != 0
            or _require_int(
                repetition["crash_count"],
                f"{context}.crash_count",
            )
            != 0
        ):
            raise ContractError(
                "external observer repetition is not a successful clean process"
            )
        start_ns = _require_int(
            repetition["host_monotonic_start_ns"],
            f"{context}.host_monotonic_start_ns",
        )
        end_ns = _require_int(
            repetition["host_monotonic_end_ns"],
            f"{context}.host_monotonic_end_ns",
        )
        if end_ns <= start_ns:
            raise ContractError(
                f"{context} host monotonic interval is invalid"
            )
        if (
            previous_repetition_end_ns is not None
            and start_ns <= previous_repetition_end_ns
        ):
            raise ContractError(
                "external observer repetitions overlap or reuse a timeline"
            )
        previous_repetition_end_ns = end_ns
        _validate_external_timings(
            repetition["external_clip_timings"],
            source=source,
            repetition_context=context,
            repetition_start_ns=start_ns,
            repetition_end_ns=end_ns,
        )
        (
            repetition["external_memory_samples"],
            sampled_peak_rss,
            sampled_peak_pss,
        ) = _validate_external_memory_samples(
            repetition["external_memory_samples"],
            repetition_context=context,
            repetition_start_ns=start_ns,
            repetition_end_ns=end_ns,
            policy=policy,
        )
        declared_peak_rss = _require_int(
            repetition["observed_peak_rss_bytes"],
            f"{context}.observed_peak_rss_bytes",
            minimum=1,
        )
        declared_peak_pss = _require_int(
            repetition["observed_peak_pss_bytes"],
            f"{context}.observed_peak_pss_bytes",
            minimum=1,
        )
        if (
            declared_peak_rss != sampled_peak_rss
            or declared_peak_pss != sampled_peak_pss
        ):
            raise ContractError(
                f"{context} memory peaks are not derived from host samples"
            )
        _validate_pcm_measurements(
            repetition["host_pcm_measurements"],
            source=source,
            context=f"{context}.host_pcm_measurements",
        )
        _validate_artifact_measurements(
            repetition["host_artifact_measurements"],
            source=source,
            context=f"{context}.host_artifact_measurements",
        )
        hash_expectations = {
            "host_pcm_set_sha256": source.commitments["pcm_set_sha256"],
            "host_artifact_set_sha256": source.commitments[
                "artifact_set_sha256"
            ],
            "host_accuracy_output_sha256": source.commitments[
                "accuracy_payload_sha256"
            ],
            "host_apk_sha256": policy.expected_apk_sha256,
            "host_signing_cert_sha256": (
                policy.expected_signing_cert_sha256
            ),
            "host_runner_build_sha256": (
                policy.expected_runner_build_sha256
            ),
        }
        for field, expected in hash_expectations.items():
            if (
                _require_sha256(
                    repetition[field],
                    f"{context}.{field}",
                )
                != expected
            ):
                raise ContractError(
                    f"{context}.{field} differs from frozen source"
                )
        repetitions.append(repetition)
    return repetitions


def _validate_observer_evidence(
    document: Any,
    *,
    source: _SourceBundle,
    policy: ExternalObserverPolicy,
) -> dict[str, Any]:
    evidence = dict(
        _require_mapping(document, "external observer evidence")
    )
    _reject_role_leakage(evidence, "external observer evidence")
    _exact_keys(
        evidence,
        {
            "schema_version",
            "observer_mode",
            "run_id",
            "model_alias",
            "observer_challenge_sha256",
            "host_clock",
            "host_resource_policy",
            "known_release",
            "attestation_observation",
            "device_identity_commitment_sha256",
            "source_receipt_commitments",
            "android_commitments",
            "repetitions",
        },
        "external observer evidence",
    )
    if (
        evidence["schema_version"] != "1.0"
        or evidence["observer_mode"] != "synthetic_fixture_no_adb"
    ):
        raise ContractError(
            "external observer evidence is not a supported synthetic contract"
        )
    if (
        evidence["run_id"] != source.decoder_plan["run_id"]
        or evidence["model_alias"] != source.decoder_plan["model_alias"]
    ):
        raise ContractError(
            "external observer run or alias differs from source bundle"
        )
    expected_challenge_sha256 = hashlib.sha256(
        policy.observer_challenge
    ).hexdigest()
    if (
        _require_sha256(
            evidence["observer_challenge_sha256"],
            "external observer challenge hash",
        )
        != expected_challenge_sha256
    ):
        raise ContractError("external observer challenge is not fresh or frozen")
    evidence["host_clock"] = _validate_host_clock(
        evidence["host_clock"],
        policy=policy,
    )
    evidence["host_resource_policy"] = _validate_host_resource_policy(
        evidence["host_resource_policy"],
        policy=policy,
    )
    evidence["known_release"] = _validate_known_release(
        evidence["known_release"],
        policy=policy,
        commitments=source.commitments,
    )
    evidence["attestation_observation"] = (
        _validate_attestation_observation(
            evidence["attestation_observation"],
            source=source,
        )
    )
    device_identity = _require_nonzero_sha256(
        evidence["device_identity_commitment_sha256"],
        "external observer device identity commitment",
    )
    if (
        device_identity
        != source.commitments["device_identity_commitment_sha256"]
    ):
        raise ContractError(
            "external observer device identity differs from Android commitment"
        )
    source_receipts = dict(
        _require_mapping(
            evidence["source_receipt_commitments"],
            "external observer source receipt commitments",
        )
    )
    expected_source_receipts = _source_receipt_commitments(source)
    if source_receipts != expected_source_receipts:
        raise ContractError(
            "external observer source receipt commitments differ"
        )
    android_commitments = dict(
        _require_mapping(
            evidence["android_commitments"],
            "external observer Android commitments",
        )
    )
    if android_commitments != source.commitments:
        raise ContractError(
            "external observer Android commitments differ from verified bundle"
        )
    evidence["source_receipt_commitments"] = source_receipts
    evidence["android_commitments"] = android_commitments
    evidence["repetitions"] = _validate_repetitions(
        evidence["repetitions"],
        source=source,
        policy=policy,
    )
    return evidence


def _verification_document(
    *,
    evidence: Mapping[str, Any],
    evidence_raw_sha256: str,
    source: _SourceBundle,
) -> dict[str, Any]:
    blockers = [
        "product_decision_authorization_required",
        "real_adb_observer_execution_required",
        "trusted_external_observer_receipt_required",
    ]
    return {
        "schema_version": "1.0",
        "run_id": source.decoder_plan["run_id"],
        "model_alias": source.decoder_plan["model_alias"],
        "protocol_profile": source.protocol_profile,
        "observer_mode": "synthetic_fixture_no_adb",
        "assurance_level": "observer_contract_synthetic",
        "observer_execution_verified": False,
        "two_clean_process_repetitions_verified": True,
        "independent_hash_contract_verified": True,
        "formal_eligible": False,
        "product_decision_eligible": False,
        "adversarial_same_uid_resistant": False,
        "blockers": blockers,
        "observer_evidence_raw_sha256": evidence_raw_sha256,
        "observer_evidence_canonical_sha256": canonical_sha256(evidence),
        "observer_challenge_sha256": evidence[
            "observer_challenge_sha256"
        ],
        "known_release_sha256": canonical_sha256(
            evidence["known_release"]
        ),
        "host_clock_sha256": canonical_sha256(evidence["host_clock"]),
        "host_resource_policy_sha256": canonical_sha256(
            evidence["host_resource_policy"]
        ),
        "device_identity_commitment_sha256": evidence[
            "device_identity_commitment_sha256"
        ],
        "source_receipt_commitments": dict(
            evidence["source_receipt_commitments"]
        ),
        "android_commitments": dict(evidence["android_commitments"]),
    }


def _validate_verification_output(
    document: Any,
    *,
    evidence: Mapping[str, Any],
    evidence_raw_sha256: str,
    source: _SourceBundle,
) -> dict[str, Any]:
    verification = dict(
        _require_mapping(
            document,
            "external observer synthetic verification",
        )
    )
    expected = _verification_document(
        evidence=evidence,
        evidence_raw_sha256=evidence_raw_sha256,
        source=source,
    )
    if verification != expected:
        raise ContractError(
            "external observer verification differs from validated evidence"
        )
    for field in (
        "observer_execution_verified",
        "formal_eligible",
        "product_decision_eligible",
        "adversarial_same_uid_resistant",
    ):
        if verification[field] is not False:
            raise ContractError(
                "synthetic observer verification must remain ineligible"
            )
    if verification["blockers"] != sorted(
        set(verification["blockers"])
    ):
        raise ContractError(
            "external observer blockers must be sorted and unique"
        )
    return verification


def verify_and_commit_synthetic_observer_bundle(
    *,
    decoder_plan_receipt_path: Path,
    accuracy_receipt_path: Path,
    engineering_receipt_path: Path,
    android_attestation_receipt_path: Path,
    attestation_envelope_path: Path,
    observer_evidence_path: Path,
    observer_receipt_path: Path,
    repo_root: Path,
    policy: ExternalObserverPolicy,
) -> ExternalObserverVerification:
    """Validate the future observer contract without running or trusting ADB."""
    if not isinstance(policy, ExternalObserverPolicy):
        raise ContractError("external observer policy is invalid")
    source = _load_and_verify_source_bundle(
        decoder_plan_receipt_path=decoder_plan_receipt_path,
        accuracy_receipt_path=accuracy_receipt_path,
        engineering_receipt_path=engineering_receipt_path,
        android_attestation_receipt_path=android_attestation_receipt_path,
        attestation_envelope_path=attestation_envelope_path,
        repo_root=repo_root,
        policy=policy,
    )
    evidence_raw = _safe_read_raw_json(
        observer_evidence_path,
        repo_root,
    )
    evidence = _validate_observer_evidence(
        evidence_raw.document,
        source=source,
        policy=policy,
    )
    verification = _verification_document(
        evidence=evidence,
        evidence_raw_sha256=evidence_raw.sha256,
        source=source,
    )
    output_path = validate_private_artifact_output_path(
        observer_receipt_path,
        repo_root,
    )
    evidence_sha256 = source.decoder_receipt["evidence_sha256"]
    plan_sha256 = source.decoder_receipt["plan_sha256"]

    def validate_source_and_evidence() -> None:
        current_source = _load_and_verify_source_bundle(
            decoder_plan_receipt_path=decoder_plan_receipt_path,
            accuracy_receipt_path=accuracy_receipt_path,
            engineering_receipt_path=engineering_receipt_path,
            android_attestation_receipt_path=(
                android_attestation_receipt_path
            ),
            attestation_envelope_path=attestation_envelope_path,
            repo_root=repo_root,
            policy=policy,
        )
        if (
            _source_receipt_commitments(current_source)
            != _source_receipt_commitments(source)
            or current_source.commitments != source.commitments
            or current_source.envelope_raw_sha256
            != source.envelope_raw_sha256
        ):
            raise ContractError(
                "external observer source bundle changed before commit"
            )
        current_evidence_raw = _safe_read_raw_json(
            evidence_raw.path,
            repo_root,
            expected=evidence_raw,
        )
        current_evidence = _validate_observer_evidence(
            current_evidence_raw.document,
            source=current_source,
            policy=policy,
        )
        if current_evidence != evidence:
            raise ContractError(
                "external observer evidence changed before commit"
            )

    def validate_output() -> None:
        _validate_verification_output(
            verification,
            evidence=evidence,
            evidence_raw_sha256=evidence_raw.sha256,
            source=source,
        )

    receipt = _commit_reserved_android_artifact(
        output_path,
        verification,
        artifact_kind=_OBSERVER_KIND,
        run_id=source.decoder_plan["run_id"],
        evidence_sha256=evidence_sha256,
        plan_sha256=plan_sha256,
        validate_document=validate_output,
        validate_evidence=validate_source_and_evidence,
    )
    blockers: Sequence[str] = verification["blockers"]
    return ExternalObserverVerification(
        two_clean_process_repetitions_verified=True,
        independent_hash_contract_verified=True,
        formal_eligible=False,
        product_decision_eligible=False,
        adversarial_same_uid_resistant=False,
        assurance_level="observer_contract_synthetic",
        blockers=tuple(blockers),
        observer_receipt_commit_sha256=receipt["commit_sha256"],
    )
