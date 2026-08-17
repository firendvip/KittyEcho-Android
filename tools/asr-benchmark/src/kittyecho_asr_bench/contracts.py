from __future__ import annotations

import hashlib
import json
import math
import re
import weakref
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any, Iterable, Mapping, Optional, Sequence, Set
from urllib.parse import urlparse

if TYPE_CHECKING:
    from .pcm import _FrozenFileIdentity


SCHEMA_VERSION = "1.0"
PCM_CONTRACT_ID = "pcm16k-mono-s16le-v1"
INPUT_TRANSFORM_ID = "canonical-pcm-direct-v1"
DECODER_CONTRACT_ID = "first-layer-raw-v1"

_ANONYMOUS_ID = re.compile(
    r"^(?:dataset|clip|ann|run|protocol|speaker|session|sentence)_[0-9a-f]{12}$"
)
_MODEL_ID = re.compile(r"^[a-z0-9][a-z0-9_]{1,63}$")
_MODEL_ALIAS = re.compile(r"^M[0-9]{3}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_COMMIT_REVISION = re.compile(r"^[0-9a-f]{40}$")
_RELEASE_ASSET_REVISION = re.compile(
    r"^[A-Za-z0-9][A-Za-z0-9._-]{1,79}/"
    r"[A-Za-z0-9][A-Za-z0-9._/-]{2,175}$"
)
_FORBIDDEN_ANSWER_KEYS = ("reference", "transcript", "answer", "expected_text")
REQUIRED_SELECTION_CONDITIONS = (
    "quiet_near",
    "noise",
    "fast",
    "slow",
    "immediate_start",
    "short",
    "normal_short",
    "long",
    "proper_name_number",
    "code_switch",
    "accent",
)
_KNOWN_CONDITIONS = set(REQUIRED_SELECTION_CONDITIONS)
SOURCE_KINDS = (
    "local_target_device_recording",
    "local_consented_recording",
    "synthetic_fixture",
)
LICENSE_COMPONENTS = (
    "upstream_weights",
    "conversion",
    "runtime",
    "tokenizer",
    "notice",
)
ARTIFACT_COMPONENTS = ("weights", "conversion", "runtime", "tokenizer")
INTERNAL_EVALUATION_RESTRICTIONS = (
    "repository_external_only",
    "private_device_only",
    "no_redistribution",
    "no_upload",
)
CONTRACT_FINGERPRINT_FIELDS = (
    "manifest_sha256",
    "dataset_protocol_sha256",
    "normalization_sha256",
    "selection_config_sha256",
    "registry_sha256",
)
_EXPLORATION_SLICE_MINIMUMS = {
    "quiet_near": 64,
    "noise": 32,
    "fast": 16,
    "slow": 16,
    "immediate_start": 16,
    "short": 24,
    "normal_short": 48,
    "long": 24,
    "proper_name_number": 24,
    "code_switch": 16,
    "accent": 16,
}
_PRODUCTION_SLICE_MINIMUMS = {
    "quiet_near": 320,
    "noise": 160,
    "fast": 80,
    "slow": 80,
    "immediate_start": 80,
    "short": 96,
    "normal_short": 288,
    "long": 96,
    "proper_name_number": 120,
    "code_switch": 80,
    "accent": 80,
}
_CONDITION_DEFINITIONS = {
    "egc_counter_id": "chinese-latin-egc-v1",
    "short_egc": [1, 6],
    "normal_short_egc": [7, 30],
    "long_egc_minimum": 31,
    "immediate_start_maximum_ms": 300,
    "fast_minimum_egc_per_second": 4.5,
    "slow_maximum_egc_per_second": 2.5,
    "code_switch_minimum_chinese_egc": 4,
    "code_switch_minimum_english_words": 2,
    "code_switch_english_word_counter_id": "nfkc-ascii-alpha-runs-v1",
    "accent_must_be_natural_not_imitated": True,
}
_MUTUALLY_EXCLUSIVE_CONDITIONS = [
    ["quiet_near", "noise"],
    ["fast", "slow"],
    ["short", "normal_short", "long"],
]
_TELEMETRY_CONTRACT = {
    "sample_interval_ms": 5_000,
    "sample_interval_tolerance_ms": 250,
    "sample_interval_tolerance_fraction": 0.05,
    "minimum_duration_seconds": 600.0,
    "minimum_decode_loops": 2,
    "active_decode_progress_unit": "trusted_runner_progress_events_v1",
}


def _formal_profile_fields(profile: str) -> dict[str, Any]:
    if profile == "exploration":
        slice_minimums = _EXPLORATION_SLICE_MINIMUMS
        slice_clusters = {
            condition: {
                "minimum_speakers": 1 if condition == "accent" else 0,
                "minimum_sessions": 0,
            }
            for condition in REQUIRED_SELECTION_CONDITIONS
        }
        return {
            "selection_authority": "elimination_only",
            "minimum_unique_clips_per_condition": slice_minimums,
            "minimum_speaker_clusters": 2,
            "minimum_session_clusters": 4,
            "minimum_joint_clusters": 4,
            "slice_cluster_minimums": slice_clusters,
            "preregistration": {
                "initial_unique_pcm": 96,
                "maximum_unique_pcm": 96,
                "initial_speakers": 2,
                "maximum_speakers": 2,
                "sessions_per_speaker": 2,
                "initial_sentences_per_session": 24,
                "new_speaker_sentences_per_session": 0,
                "minimum_session_gap_hours": 12,
                "fresh_unseen_recordings_required": False,
                "common_anchor_fraction": 0.5,
                "coverage_fraction": 0.5,
                "maximum_single_speaker_fraction": 0.5,
                "proper_name_number_minimums": {
                    "number": 12,
                    "proper_name": 12,
                },
                "condition_definitions": _CONDITION_DEFINITIONS,
                "mutually_exclusive_conditions": _MUTUALLY_EXCLUSIVE_CONDITIONS,
                "one_take_per_speaker_sentence": True,
                "technical_rerecord_requires_new_id_and_reason": True,
                "best_take_selection_prohibited": True,
                "bootstrap": {
                    "primary_unit": "speaker",
                    "nested_units": ["session", "clip"],
                    "samples": 10_000,
                    "confidence_level": 0.95,
                    "seed": 20_260_729,
                },
                "sequential_extension": {
                    "enabled": False,
                    "ci_half_width_trigger": 0.005,
                    "increment_speakers": 0,
                    "increment_sessions_per_speaker": 0,
                    "increment_sentences_per_session": 0,
                    "increment_unique_pcm": 0,
                },
                "replacement_rules": {
                    "minimum_absolute_cer_improvement": 0.005,
                    "maximum_slice_evidence_degradation": 0.02,
                    "keep_baseline_if_inconclusive_at_maximum": True,
                },
            },
        }
    if profile == "production_confirmation":
        slice_clusters = {
            condition: {
                "minimum_speakers": 2 if condition == "accent" else 4,
                "minimum_sessions": 4 if condition == "accent" else 8,
            }
            for condition in REQUIRED_SELECTION_CONDITIONS
        }
        return {
            "selection_authority": "production_confirmation",
            "minimum_unique_clips_per_condition": _PRODUCTION_SLICE_MINIMUMS,
            "minimum_speaker_clusters": 6,
            "minimum_session_clusters": 12,
            "minimum_joint_clusters": 12,
            "slice_cluster_minimums": slice_clusters,
            "preregistration": {
                "initial_unique_pcm": 480,
                "maximum_unique_pcm": 720,
                "initial_speakers": 6,
                "maximum_speakers": 10,
                "sessions_per_speaker": 2,
                "initial_sentences_per_session": 40,
                "new_speaker_sentences_per_session": 30,
                "minimum_session_gap_hours": 12,
                "fresh_unseen_recordings_required": True,
                "common_anchor_fraction": 0.5,
                "coverage_fraction": 0.5,
                "maximum_single_speaker_fraction": 0.25,
                "proper_name_number_minimums": {
                    "number": 60,
                    "proper_name": 60,
                },
                "condition_definitions": _CONDITION_DEFINITIONS,
                "mutually_exclusive_conditions": _MUTUALLY_EXCLUSIVE_CONDITIONS,
                "one_take_per_speaker_sentence": True,
                "technical_rerecord_requires_new_id_and_reason": True,
                "best_take_selection_prohibited": True,
                "bootstrap": {
                    "primary_unit": "speaker",
                    "nested_units": ["session", "clip"],
                    "samples": 10_000,
                    "confidence_level": 0.95,
                    "seed": 20_260_729,
                },
                "sequential_extension": {
                    "enabled": True,
                    "ci_half_width_trigger": 0.005,
                    "increment_speakers": 2,
                    "increment_sessions_per_speaker": 2,
                    "increment_sentences_per_session": 30,
                    "increment_unique_pcm": 120,
                },
                "replacement_rules": {
                    "minimum_absolute_cer_improvement": 0.005,
                    "maximum_slice_evidence_degradation": 0.02,
                    "keep_baseline_if_inconclusive_at_maximum": True,
                },
            },
        }
    raise ContractError("dataset protocol profile must be exploration or production_confirmation")


def build_dataset_protocol(
    profile: str,
    *,
    protocol_id: str,
    target_device: Mapping[str, Any],
    approval_status: str = "draft",
    exploration_pcm_set_sha256: Optional[str] = None,
) -> dict[str, Any]:
    """Build one of the two frozen preregistration profiles."""
    expected = _formal_profile_fields(profile)
    return {
        "schema_version": SCHEMA_VERSION,
        "protocol_id": protocol_id,
        "approval_status": approval_status,
        "profile": profile,
        "selection_authority": expected["selection_authority"],
        "target_device": dict(target_device),
        "required_conditions": list(REQUIRED_SELECTION_CONDITIONS),
        "minimum_unique_clips_per_condition": dict(
            expected["minimum_unique_clips_per_condition"]
        ),
        "minimum_speaker_clusters": expected["minimum_speaker_clusters"],
        "minimum_session_clusters": expected["minimum_session_clusters"],
        "minimum_joint_clusters": expected["minimum_joint_clusters"],
        "slice_cluster_minimums": {
            condition: dict(values)
            for condition, values in expected["slice_cluster_minimums"].items()
        },
        "preregistration": json.loads(json.dumps(expected["preregistration"])),
        "exploration_pcm_set_sha256": exploration_pcm_set_sha256,
        "required_source_kind": "local_target_device_recording",
        "unique_pcm_required": True,
        "bootstrap_unit": "speaker_nested_session_clip",
        "telemetry": dict(_TELEMETRY_CONTRACT),
    }
_MIRROR_HOSTS = {"hf-mirror.com", "www.hf-mirror.com"}


class ContractError(ValueError):
    """Raised when benchmark input violates a fail-closed contract."""


@dataclass(frozen=True)
class _DatasetEvidenceRecord:
    repo_root: Path
    snapshot_root: Path
    snapshot_identities: tuple["_FrozenFileIdentity", ...]
    manifest_document_json: str
    manifest_sha256: str
    dataset_id: str
    recording_purpose: str


@dataclass(frozen=True)
class _ExplorationEvidenceRecord:
    dataset_evidence: "VerifiedDatasetEvidence"
    public_plan_path: Path
    protocol_path: Path
    public_plan_sha256: str
    protocol_sha256: str
    commitment_sha256: str


class VerifiedDatasetEvidence:
    """Process-local capability registered by the filesystem verifier."""

    __slots__ = ("__weakref__",)

    def __new__(cls, *_args: Any, **_kwargs: Any) -> "VerifiedDatasetEvidence":
        raise ContractError(
            "VerifiedDatasetEvidence requires the filesystem-backed verifier"
        )

    def __setattr__(self, _name: str, _value: Any) -> None:
        raise ContractError("VerifiedDatasetEvidence is immutable")


class VerifiedExplorationEvidence:
    """Process-local capability for one verified exploration contract."""

    __slots__ = ("__weakref__",)

    def __new__(
        cls,
        *_args: Any,
        **_kwargs: Any,
    ) -> "VerifiedExplorationEvidence":
        raise ContractError(
            "VerifiedExplorationEvidence requires the filesystem-backed verifier"
        )

    def __setattr__(self, _name: str, _value: Any) -> None:
        raise ContractError("VerifiedExplorationEvidence is immutable")


_VERIFIED_DATASET_RECORDS: weakref.WeakKeyDictionary[
    VerifiedDatasetEvidence,
    _DatasetEvidenceRecord,
] = weakref.WeakKeyDictionary()
_VERIFIED_EXPLORATION_RECORDS: weakref.WeakKeyDictionary[
    VerifiedExplorationEvidence,
    _ExplorationEvidenceRecord,
] = weakref.WeakKeyDictionary()


def canonical_sha256(value: Any) -> str:
    """Hash canonical JSON without exposing private values in reports."""
    try:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ContractError("value is not canonical JSON") from error
    return hashlib.sha256(encoded).hexdigest()


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _require_mapping(value: Any, context: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ContractError(f"{context} must be a JSON object")
    return value


def _require_list(value: Any, context: str, *, non_empty: bool = False) -> list[Any]:
    if not isinstance(value, list):
        raise ContractError(f"{context} must be a JSON array")
    if non_empty and not value:
        raise ContractError(f"{context} must not be empty")
    return value


def _require_string(value: Any, context: str, *, non_empty: bool = True) -> str:
    if not isinstance(value, str):
        raise ContractError(f"{context} must be a string")
    if non_empty and not value:
        raise ContractError(f"{context} must not be empty")
    return value


def _exact_keys(
    value: Mapping[str, Any],
    required: Iterable[str],
    *,
    optional: Iterable[str] = (),
    context: str,
) -> None:
    required_set = set(required)
    optional_set = set(optional)
    missing = sorted(required_set - set(value))
    if missing:
        raise ContractError(f"{context} missing required field: {missing[0]}")
    unknown = sorted(set(value) - required_set - optional_set)
    if unknown:
        raise ContractError(f"{context} has unknown field: {unknown[0]}")


def _require_version(value: Mapping[str, Any], context: str) -> None:
    if value.get("schema_version") != SCHEMA_VERSION:
        raise ContractError(f"{context}.schema_version must be {SCHEMA_VERSION}")


def _require_anonymous_id(value: Any, prefix: str, context: str) -> str:
    identifier = _require_string(value, context)
    if not _ANONYMOUS_ID.fullmatch(identifier) or not identifier.startswith(prefix + "_"):
        raise ContractError(f"{context} must be an anonymous {prefix}_ plus 12 lowercase hex characters")
    return identifier


def _require_sha256(value: Any, context: str) -> str:
    digest = _require_string(value, context)
    if not _SHA256.fullmatch(digest):
        raise ContractError(f"{context} must be a lowercase 64-character sha256")
    return digest


def _require_nonnegative_number(value: Any, context: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractError(f"{context} must be a number")
    result = float(value)
    if not math.isfinite(result) or result < 0:
        raise ContractError(f"{context} must be a finite non-negative number")
    return result


def _require_nonnegative_int(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ContractError(f"{context} must be a non-negative integer")
    return value


def _reject_answer_keys(value: Mapping[str, Any], context: str) -> None:
    for key in value:
        lowered = key.lower()
        if any(forbidden in lowered for forbidden in _FORBIDDEN_ANSWER_KEYS):
            raise ContractError(f"{context} must not contain answer or reference fields")


def load_json(path: Path, *, max_bytes: int = 10 * 1024 * 1024) -> Any:
    """Load bounded JSON while rejecting duplicate keys."""
    file_path = Path(path)
    try:
        size = file_path.stat().st_size
    except OSError as error:
        raise ContractError("JSON input is not a readable local file") from error
    if size > max_bytes:
        raise ContractError("JSON input is too large")

    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ContractError(f"duplicate JSON key: {key}")
            result[key] = value
        return result

    try:
        with file_path.open("r", encoding="utf-8") as source:
            return json.load(source, object_pairs_hook=unique_object)
    except ContractError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ContractError("JSON input is malformed or unreadable") from error


def validate_private_input_path(path: Path, repo_root: Path) -> Path:
    candidate = Path(path)
    if not candidate.is_absolute():
        raise ContractError("private input path must be absolute")
    resolved = candidate.resolve()
    if _is_relative_to(resolved, Path(repo_root).resolve()):
        raise ContractError("private reference and hypothesis files must remain outside the repository")
    if not resolved.is_file():
        raise ContractError("private input must be a readable local file")
    return resolved


def validate_private_output_path(path: Path, repo_root: Path) -> Path:
    candidate = Path(path)
    if not candidate.is_absolute():
        raise ContractError("private output path must be absolute")
    resolved = candidate.resolve()
    if _is_relative_to(resolved, Path(repo_root).resolve()):
        raise ContractError("private map and report outputs must remain outside the repository")
    return resolved


def _validate_target_device(value: Any, context: str) -> Mapping[str, Any]:
    device = _require_mapping(value, context)
    _exact_keys(
        device,
        {"platform", "physical_device", "abi", "device_profile_sha256"},
        context=context,
    )
    if device["platform"] != "android":
        raise ContractError(f"{context}.platform must be android")
    if not isinstance(device["physical_device"], bool):
        raise ContractError(f"{context}.physical_device must be boolean")
    if device["abi"] != "arm64-v8a":
        raise ContractError(f"{context}.abi must be arm64-v8a")
    _require_sha256(device["device_profile_sha256"], f"{context}.device_profile_sha256")
    return device


def _validate_speaker_cohorts(value: Any) -> list[Mapping[str, Any]]:
    cohorts = _require_list(value, "recording manifest.speaker_cohorts")
    seen_rounds: Set[int] = set()
    seen_speakers: Set[str] = set()
    validated: list[Mapping[str, Any]] = []
    for index, raw_cohort in enumerate(cohorts):
        context = f"recording manifest.speaker_cohorts[{index}]"
        cohort = _require_mapping(raw_cohort, context)
        _exact_keys(
            cohort,
            {"round_index", "speaker_cluster_ids", "sentences_per_session"},
            context=context,
        )
        round_index = _require_nonnegative_int(
            cohort["round_index"], f"{context}.round_index"
        )
        if round_index in seen_rounds:
            raise ContractError("recording manifest speaker cohort rounds must be unique")
        seen_rounds.add(round_index)
        speakers = _require_list(
            cohort["speaker_cluster_ids"],
            f"{context}.speaker_cluster_ids",
            non_empty=True,
        )
        if len(speakers) != len(set(speakers)):
            raise ContractError("recording manifest speaker cohort contains duplicate speakers")
        for speaker_index, speaker_id in enumerate(speakers):
            validated_id = _require_anonymous_id(
                speaker_id,
                "speaker",
                f"{context}.speaker_cluster_ids[{speaker_index}]",
            )
            if validated_id in seen_speakers:
                raise ContractError("recording manifest speaker may belong to only one cohort")
            seen_speakers.add(validated_id)
        sentences = _require_nonnegative_int(
            cohort["sentences_per_session"],
            f"{context}.sentences_per_session",
        )
        if sentences < 1:
            raise ContractError("speaker cohort sentences_per_session must be positive")
        validated.append(cohort)
    return validated


def _validate_exploration_commitment_reference(
    value: Any,
) -> Optional[Mapping[str, Any]]:
    if value is None:
        return None
    commitment = _require_mapping(
        value,
        "recording manifest.frozen_exploration_pcm_set",
    )
    _exact_keys(
        commitment,
        {
            "dataset_id",
            "manifest_sha256",
            "public_plan_sha256",
            "dataset_protocol_sha256",
            "commitment_sha256",
        },
        context="recording manifest.frozen_exploration_pcm_set",
    )
    _require_anonymous_id(
        commitment["dataset_id"],
        "dataset",
        "frozen exploration PCM set.dataset_id",
    )
    for field in (
        "manifest_sha256",
        "public_plan_sha256",
        "dataset_protocol_sha256",
        "commitment_sha256",
    ):
        _require_sha256(
            commitment[field],
            f"frozen exploration PCM set.{field}",
        )
    return commitment


def validate_recording_manifest(data: Any, repo_root: Path) -> dict[str, Any]:
    manifest = dict(_require_mapping(data, "recording manifest"))
    _reject_answer_keys(manifest, "recording manifest")
    _exact_keys(
        manifest,
        {
            "schema_version",
            "dataset_id",
            "pcm_contract_id",
            "target_device",
            "dataset_usage",
            "speaker_cohorts",
            "frozen_exploration_pcm_set",
            "clips",
        },
        context="recording manifest",
    )
    _require_version(manifest, "recording manifest")
    _require_anonymous_id(manifest["dataset_id"], "dataset", "dataset_id")
    if manifest["pcm_contract_id"] != PCM_CONTRACT_ID:
        raise ContractError(f"pcm_contract_id must be {PCM_CONTRACT_ID}")
    _validate_target_device(manifest["target_device"], "recording manifest.target_device")
    dataset_usage = _require_mapping(
        manifest["dataset_usage"], "recording manifest.dataset_usage"
    )
    _exact_keys(
        dataset_usage,
        {"recording_purpose", "used_for_model_tuning"},
        context="recording manifest.dataset_usage",
    )
    if dataset_usage["recording_purpose"] not in {
        "development_fixture",
        "exploration",
        "production_confirmation",
    }:
        raise ContractError("recording manifest.dataset_usage.recording_purpose is invalid")
    if not isinstance(dataset_usage["used_for_model_tuning"], bool):
        raise ContractError(
            "recording manifest.dataset_usage.used_for_model_tuning must be boolean"
        )
    _validate_speaker_cohorts(manifest["speaker_cohorts"])
    _validate_exploration_commitment_reference(
        manifest["frozen_exploration_pcm_set"]
    )

    clips = _require_list(manifest["clips"], "recording manifest.clips", non_empty=True)
    seen: Set[str] = set()
    seen_audio_hashes: Set[str] = set()
    seen_payload_hashes: Set[str] = set()
    seen_speaker_sentences: Set[tuple[str, str]] = set()
    session_metadata: dict[str, tuple[str, int, Optional[float]]] = {}
    repository = Path(repo_root).resolve()
    for index, raw_clip in enumerate(clips):
        context = f"recording manifest.clips[{index}]"
        clip = _require_mapping(raw_clip, context)
        _reject_answer_keys(clip, context)
        _exact_keys(
            clip,
            {
                "clip_id",
                "audio_path",
                "audio_sha256",
                "pcm_payload_sha256",
                "pcm_payload_bytes",
                "speaker_cluster_id",
                "session_cluster_id",
                "sentence_id",
                "prompt_kind",
                "session_index",
                "hours_since_previous_session",
                "speech_duration_ms",
                "speech_onset_ms",
                "accent_natural",
                "capture_attempt",
                "source",
                "consent",
                "privacy",
                "conditions",
            },
            context=context,
        )
        clip_id = _require_anonymous_id(clip["clip_id"], "clip", f"{context}.clip_id")
        if clip_id in seen:
            raise ContractError("recording manifest contains duplicate clip_id")
        seen.add(clip_id)

        audio_path = Path(_require_string(clip["audio_path"], f"{context}.audio_path"))
        if not audio_path.is_absolute():
            raise ContractError("real audio path must be an absolute local path")

        source = _require_mapping(clip["source"], f"{context}.source")
        _exact_keys(source, {"kind", "local_only", "cloud_origin"}, context=f"{context}.source")
        kind = source["kind"]
        if kind not in SOURCE_KINDS:
            raise ContractError(f"{context}.source.kind is not an allowed local source")
        if source["cloud_origin"] is not False:
            raise ContractError("cloud-origin audio is rejected")
        if source["local_only"] is not True:
            raise ContractError("audio must be marked local-only")
        if kind != "synthetic_fixture" and _is_relative_to(audio_path.resolve(), repository):
            raise ContractError("real audio must remain outside the repository")

        consent = _require_mapping(clip["consent"], f"{context}.consent")
        _exact_keys(consent, {"obtained", "scope"}, context=f"{context}.consent")
        if consent["obtained"] is not True:
            raise ContractError("recording consent must be obtained")
        if consent["scope"] != "local_asr_benchmark":
            raise ContractError("recording consent scope must be local_asr_benchmark")

        privacy = _require_mapping(clip["privacy"], f"{context}.privacy")
        _exact_keys(
            privacy,
            {"contains_sensitive_content", "upload_permitted"},
            context=f"{context}.privacy",
        )
        if privacy["contains_sensitive_content"] is not False:
            raise ContractError("sensitive recordings are rejected")
        if privacy["upload_permitted"] is not False:
            raise ContractError("benchmark audio must not permit upload")

        audio_sha256 = _require_sha256(clip["audio_sha256"], f"{context}.audio_sha256")
        if audio_sha256 in seen_audio_hashes:
            raise ContractError("recording manifest requires a unique audio_sha256 for every clip")
        seen_audio_hashes.add(audio_sha256)
        payload_sha256 = _require_sha256(
            clip["pcm_payload_sha256"], f"{context}.pcm_payload_sha256"
        )
        if payload_sha256 in seen_payload_hashes:
            raise ContractError(
                "recording manifest requires a unique PCM payload sha256 for every clip"
            )
        seen_payload_hashes.add(payload_sha256)
        _require_nonnegative_int(clip["pcm_payload_bytes"], f"{context}.pcm_payload_bytes")
        speaker_cluster_id = _require_anonymous_id(
            clip["speaker_cluster_id"],
            "speaker",
            f"{context}.speaker_cluster_id",
        )
        session_cluster_id = _require_anonymous_id(
            clip["session_cluster_id"],
            "session",
            f"{context}.session_cluster_id",
        )
        sentence_id = _require_anonymous_id(
            clip["sentence_id"], "sentence", f"{context}.sentence_id"
        )
        speaker_sentence = (speaker_cluster_id, sentence_id)
        if speaker_sentence in seen_speaker_sentences:
            raise ContractError("each speaker and sentence pair may be recorded only once")
        seen_speaker_sentences.add(speaker_sentence)
        if clip["prompt_kind"] not in {"common_anchor", "coverage"}:
            raise ContractError(f"{context}.prompt_kind is invalid")
        session_index = _require_nonnegative_int(
            clip["session_index"], f"{context}.session_index"
        )
        if session_index < 1:
            raise ContractError(f"{context}.session_index must be at least one")
        raw_gap = clip["hours_since_previous_session"]
        if raw_gap is None:
            gap: Optional[float] = None
        else:
            gap = _require_nonnegative_number(
                raw_gap, f"{context}.hours_since_previous_session"
            )
        if (session_index == 1) != (gap is None):
            raise ContractError(
                "first session must have null gap and later sessions must record the gap"
            )
        metadata = (speaker_cluster_id, session_index, gap)
        prior_metadata = session_metadata.setdefault(session_cluster_id, metadata)
        if prior_metadata != metadata:
            raise ContractError("session cluster metadata must be consistent across clips")
        if _require_nonnegative_number(
            clip["speech_duration_ms"], f"{context}.speech_duration_ms"
        ) <= 0:
            raise ContractError(f"{context}.speech_duration_ms must be positive")
        _require_nonnegative_number(
            clip["speech_onset_ms"], f"{context}.speech_onset_ms"
        )
        if not isinstance(clip["accent_natural"], bool):
            raise ContractError(f"{context}.accent_natural must be boolean")
        capture_attempt = _require_mapping(
            clip["capture_attempt"], f"{context}.capture_attempt"
        )
        _exact_keys(
            capture_attempt,
            {
                "attempt_index",
                "technical_rerecord",
                "replaces_clip_id",
                "exclusion_reason_code",
                "take_selection_policy",
            },
            context=f"{context}.capture_attempt",
        )
        attempt_index = _require_nonnegative_int(
            capture_attempt["attempt_index"],
            f"{context}.capture_attempt.attempt_index",
        )
        if attempt_index < 1:
            raise ContractError("capture attempt index must be at least one")
        if capture_attempt["take_selection_policy"] != "first_valid_take":
            raise ContractError("best-take selection is prohibited")
        rerecord = capture_attempt["technical_rerecord"]
        if not isinstance(rerecord, bool):
            raise ContractError("technical_rerecord must be boolean")
        if rerecord:
            if attempt_index < 2:
                raise ContractError("technical rerecord attempt index must be at least two")
            _require_anonymous_id(
                capture_attempt["replaces_clip_id"],
                "clip",
                f"{context}.capture_attempt.replaces_clip_id",
            )
            if capture_attempt["exclusion_reason_code"] not in {
                "capture_device_failure",
                "truncated_audio",
                "corrupt_wav",
            }:
                raise ContractError("technical rerecord exclusion reason is invalid")
        elif (
            attempt_index != 1
            or capture_attempt["replaces_clip_id"] is not None
            or capture_attempt["exclusion_reason_code"] is not None
        ):
            raise ContractError("normal first take must not contain rerecord metadata")
        conditions = _require_list(clip["conditions"], f"{context}.conditions", non_empty=True)
        if len(conditions) != len(set(conditions)):
            raise ContractError(f"{context}.conditions contains duplicates")
        unknown_conditions = sorted(set(conditions) - _KNOWN_CONDITIONS)
        if unknown_conditions or not all(isinstance(item, str) for item in conditions):
            raise ContractError(f"{context}.conditions contains an unknown condition")
    return manifest


def _validate_formal_dataset_structure(
    manifest: Mapping[str, Any],
    protocol: Mapping[str, Any],
) -> None:
    profile = protocol["profile"]
    cohorts = _validate_speaker_cohorts(manifest.get("speaker_cohorts"))
    if profile == "exploration":
        expected_rounds = [(0, 2, 24)]
    else:
        if not 1 <= len(cohorts) <= 3:
            raise ContractError(
                "production speaker cohorts must encode 480, 600, or 720 clips"
            )
        expected_rounds = [(0, 6, 40)] + [
            (round_index, 2, 30)
            for round_index in range(1, len(cohorts))
        ]

    if len(cohorts) != len(expected_rounds):
        raise ContractError("formal speaker cohort count does not match the frozen profile")
    expected_by_speaker: dict[str, tuple[int, int]] = {}
    round_speakers: dict[int, list[str]] = {}
    for cohort, (round_index, speaker_count, sentences_per_session) in zip(
        cohorts,
        expected_rounds,
    ):
        speakers = list(cohort["speaker_cluster_ids"])
        if (
            cohort["round_index"] != round_index
            or len(speakers) != speaker_count
            or cohort["sentences_per_session"] != sentences_per_session
        ):
            raise ContractError(
                "formal speaker cohort must match the frozen round structure"
            )
        round_speakers[round_index] = speakers
        for speaker_id in speakers:
            expected_by_speaker[speaker_id] = (round_index, sentences_per_session)

    clips = _require_list(manifest.get("clips"), "manifest.clips", non_empty=True)
    clips_by_speaker_session: dict[tuple[str, int], list[Mapping[str, Any]]] = {}
    session_ids_by_speaker: dict[str, Set[str]] = {}
    clip_speakers: Set[str] = set()
    for clip in clips:
        speaker_id = clip["speaker_cluster_id"]
        if speaker_id not in expected_by_speaker:
            raise ContractError("formal clip speaker is absent from its speaker cohort")
        clip_speakers.add(speaker_id)
        if clip["source"]["kind"] != protocol["required_source_kind"]:
            raise ContractError("formal dataset contains a non-target-device recording")
        session_index = clip["session_index"]
        clips_by_speaker_session.setdefault((speaker_id, session_index), []).append(clip)
        session_ids_by_speaker.setdefault(speaker_id, set()).add(
            clip["session_cluster_id"]
        )
    if clip_speakers != set(expected_by_speaker):
        raise ContractError("formal speaker cohort does not exactly cover manifest clips")

    anchors_by_round_session: dict[tuple[int, int], list[Set[str]]] = {}
    initial_anchor_sets: dict[int, Set[str]] = {}
    for speaker_id, (round_index, sentences_per_session) in expected_by_speaker.items():
        if set(
            session_index
            for (candidate_speaker, session_index) in clips_by_speaker_session
            if candidate_speaker == speaker_id
        ) != {1, 2} or len(session_ids_by_speaker.get(speaker_id, set())) != 2:
            raise ContractError("each formal speaker must have exactly two sessions")
        for session_index in (1, 2):
            session_clips = clips_by_speaker_session[(speaker_id, session_index)]
            if len(session_clips) != sentences_per_session:
                raise ContractError(
                    f"formal session must contain exactly {sentences_per_session} clips"
                )
            session_ids = {clip["session_cluster_id"] for clip in session_clips}
            if len(session_ids) != 1:
                raise ContractError("formal session index must map to one session cluster")
            anchor_ids = {
                clip["sentence_id"]
                for clip in session_clips
                if clip["prompt_kind"] == "common_anchor"
            }
            if len(anchor_ids) * 2 != sentences_per_session:
                raise ContractError("formal session must use the frozen 50% anchor split")
            anchors_by_round_session.setdefault(
                (round_index, session_index),
                [],
            ).append(anchor_ids)

    for (round_index, session_index), anchor_sets in anchors_by_round_session.items():
        if any(anchor_set != anchor_sets[0] for anchor_set in anchor_sets[1:]):
            raise ContractError(
                "common-anchor sentence IDs must be shared across speakers and sessions"
            )
        if round_index == 0:
            initial_anchor_sets[session_index] = anchor_sets[0]
        elif not anchor_sets[0].issubset(initial_anchor_sets[session_index]):
            raise ContractError(
                "extension-round anchor sentences must be frozen from the initial round"
            )

    expected_clip_count = sum(
        speaker_count * 2 * sentences_per_session
        for _, speaker_count, sentences_per_session in expected_rounds
    )
    if len(clips) != expected_clip_count:
        raise ContractError(
            f"formal dataset must contain exactly {expected_clip_count} clips"
        )

    frozen_exploration = _validate_exploration_commitment_reference(
        manifest.get("frozen_exploration_pcm_set")
    )
    protocol_commitment = protocol["exploration_pcm_set_sha256"]
    if profile == "exploration":
        if frozen_exploration is not None or protocol_commitment is not None:
            raise ContractError("exploration protocol must not import a prior PCM set")
        return
    if frozen_exploration is None:
        raise ContractError(
            "production manifest requires a frozen exploration PCM-set commitment"
        )
    if frozen_exploration["commitment_sha256"] != protocol_commitment:
        raise ContractError(
            "production protocol and manifest exploration PCM-set commitments differ"
        )


def _validate_exploration_manifest_qualification(
    manifest: Mapping[str, Any],
    protocol: Mapping[str, Any],
) -> None:
    clips = _require_list(
        manifest.get("clips"),
        "actual exploration manifest.clips",
        non_empty=True,
    )
    for condition in REQUIRED_SELECTION_CONDITIONS:
        condition_clips = [
            clip for clip in clips if condition in clip["conditions"]
        ]
        minimum = protocol["minimum_unique_clips_per_condition"][condition]
        if len(condition_clips) < minimum:
            raise ContractError(
                "actual exploration manifest is not protocol-qualified: "
                f"{condition} clip shortfall"
            )
        cluster_minimums = protocol["slice_cluster_minimums"][condition]
        speaker_count = len(
            {clip["speaker_cluster_id"] for clip in condition_clips}
        )
        session_count = len(
            {clip["session_cluster_id"] for clip in condition_clips}
        )
        if (
            speaker_count < cluster_minimums["minimum_speakers"]
            or session_count < cluster_minimums["minimum_sessions"]
        ):
            raise ContractError(
                "actual exploration manifest is not protocol-qualified: "
                f"{condition} cluster shortfall"
            )


def _derive_exploration_commitment_documents(
    exploration_manifest: Mapping[str, Any],
    exploration_public_plan: Mapping[str, Any],
    exploration_dataset_protocol: Mapping[str, Any],
    exploration_dataset_evidence: VerifiedDatasetEvidence,
) -> dict[str, Any]:
    verified_manifest = _revalidate_verified_dataset(
        exploration_dataset_evidence
    )
    if verified_manifest != dict(exploration_manifest):
        raise ContractError(
            "actual exploration manifest differs from its filesystem-backed evidence"
        )
    protocol = validate_dataset_protocol(
        exploration_dataset_protocol,
        exploration_dataset_evidence,
    )
    if (
        protocol["profile"] != "exploration"
        or protocol["selection_authority"] != "elimination_only"
    ):
        raise ContractError(
            "actual exploration contract must use the qualified exploration profile"
        )
    _validate_exploration_manifest_qualification(
        exploration_manifest,
        protocol,
    )
    plan = validate_public_plan(
        exploration_public_plan,
        exploration_dataset_evidence,
    )
    protocol_sha256 = canonical_sha256(protocol)
    if (
        plan["protocol_profile"] != "exploration"
        or plan["cohort_id"] == "development_subset"
        or plan["contract_fingerprints"]["dataset_protocol_sha256"]
        != protocol_sha256
    ):
        raise ContractError(
            "actual exploration public plan does not match its frozen contract"
        )
    clips = _require_list(
        exploration_manifest.get("clips"),
        "actual exploration manifest.clips",
        non_empty=True,
    )
    audio_sha256 = sorted(clip["audio_sha256"] for clip in clips)
    payload_sha256 = sorted(clip["pcm_payload_sha256"] for clip in clips)
    if (
        len(clips) != 96
        or len(audio_sha256) != len(set(audio_sha256))
        or len(payload_sha256) != len(set(payload_sha256))
    ):
        raise ContractError(
            "actual exploration manifest must contain 96 unique WAV and payload hashes"
        )
    material = {
        "dataset_id": exploration_manifest["dataset_id"],
        "manifest_sha256": canonical_sha256(exploration_manifest),
        "public_plan_sha256": canonical_sha256(plan),
        "dataset_protocol_sha256": protocol_sha256,
        "audio_sha256": audio_sha256,
        "pcm_payload_sha256": payload_sha256,
    }
    return {
        "dataset_id": material["dataset_id"],
        "manifest_sha256": material["manifest_sha256"],
        "public_plan_sha256": material["public_plan_sha256"],
        "dataset_protocol_sha256": material["dataset_protocol_sha256"],
        "commitment_sha256": canonical_sha256(material),
    }


def verify_dataset_evidence(
    manifest_path: Path,
    repo_root: Path,
    *,
    snapshot_root: Path,
    fixture_root: Optional[Path] = None,
) -> VerifiedDatasetEvidence:
    """Freeze a formal manifest into a content-addressed external PCM snapshot."""
    if snapshot_root is None:
        raise ContractError(
            "formal evidence requires an explicit repository-external snapshot_root"
        )
    resolved_repo = Path(repo_root).resolve()
    resolved_manifest = validate_private_input_path(manifest_path, resolved_repo)
    resolved_fixture = (
        Path(fixture_root).resolve() if fixture_root is not None else None
    )
    manifest = validate_recording_manifest(
        load_json(resolved_manifest),
        resolved_repo,
    )
    usage = _require_mapping(
        manifest.get("dataset_usage"),
        "filesystem-backed manifest.dataset_usage",
    )
    recording_purpose = usage.get("recording_purpose")
    if recording_purpose not in {"exploration", "production_confirmation"}:
        raise ContractError(
            "filesystem-backed evidence is reserved for a formal dataset"
        )
    from .pcm import _snapshot_manifest_pcm_with_evidence

    (
        frozen_manifest,
        resolved_snapshot_root,
        snapshot_identities,
    ) = _snapshot_manifest_pcm_with_evidence(
        manifest,
        resolved_repo,
        snapshot_root,
        fixture_root=resolved_fixture,
    )
    manifest_document_json = json.dumps(
        frozen_manifest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )
    evidence = object.__new__(VerifiedDatasetEvidence)
    _VERIFIED_DATASET_RECORDS[evidence] = _DatasetEvidenceRecord(
        repo_root=resolved_repo,
        snapshot_root=resolved_snapshot_root,
        snapshot_identities=snapshot_identities,
        manifest_document_json=manifest_document_json,
        manifest_sha256=canonical_sha256(frozen_manifest),
        dataset_id=frozen_manifest["dataset_id"],
        recording_purpose=recording_purpose,
    )
    _revalidate_verified_dataset(evidence)
    return evidence


def _revalidate_verified_dataset(
    evidence: Any,
) -> dict[str, Any]:
    if (
        type(evidence) is not VerifiedDatasetEvidence
        or evidence not in _VERIFIED_DATASET_RECORDS
    ):
        raise ContractError(
            "formal validation requires a factory-created snapshot-backed "
            "VerifiedDatasetEvidence"
        )
    record = _VERIFIED_DATASET_RECORDS[evidence]
    try:
        manifest = json.loads(record.manifest_document_json)
    except json.JSONDecodeError as error:
        raise ContractError("registered snapshot manifest is invalid") from error
    manifest = validate_recording_manifest(manifest, record.repo_root)
    from .pcm import _validate_snapshot_manifest_pcm

    _validate_snapshot_manifest_pcm(
        manifest,
        record.repo_root,
        record.snapshot_root,
        expected_identities=record.snapshot_identities,
    )
    purpose = _require_mapping(
        manifest.get("dataset_usage"),
        "snapshot-backed manifest.dataset_usage",
    ).get("recording_purpose")
    if (
        canonical_sha256(manifest) != record.manifest_sha256
        or manifest["dataset_id"] != record.dataset_id
        or purpose != record.recording_purpose
    ):
        raise ContractError(
            "content-addressed dataset snapshot changed after verification"
        )
    return manifest


def verified_dataset_document(
    evidence: VerifiedDatasetEvidence,
) -> dict[str, Any]:
    """Return a fresh snapshot view after re-reading every frozen PCM file."""
    return _revalidate_verified_dataset(evidence)


def verified_dataset_evidence_sha256(
    evidence: VerifiedDatasetEvidence,
) -> str:
    """Bind a formal dataset artifact to snapshot content and inode identity."""
    _revalidate_verified_dataset(evidence)
    record = _VERIFIED_DATASET_RECORDS[evidence]
    return canonical_sha256(
        {
            "dataset_id": record.dataset_id,
            "recording_purpose": record.recording_purpose,
            "manifest_sha256": record.manifest_sha256,
            "snapshot_root": str(record.snapshot_root),
            "snapshot_identities": [
                {
                    "path": str(identity.path),
                    "st_dev": identity.st_dev,
                    "st_ino": identity.st_ino,
                    "sha256": identity.sha256,
                    "size_bytes": identity.size_bytes,
                    "mode": identity.mode,
                    "nlink": identity.nlink,
                    "payload_sha256": identity.payload_sha256,
                    "payload_bytes": identity.payload_bytes,
                }
                for identity in record.snapshot_identities
            ],
        }
    )


def benchmark_evidence_sha256(
    manifest: Any,
    profile: str,
    *,
    exploration_evidence: Optional[VerifiedExplorationEvidence] = None,
) -> str:
    """Fingerprint the exact host evidence a committed artifact may consume."""
    if profile == "development_fixture":
        if exploration_evidence is not None:
            raise ContractError(
                "development evidence must not include exploration evidence"
            )
        document = _manifest_document_for_profile(manifest, profile)
        return canonical_sha256(
            {
                "profile": profile,
                "manifest_sha256": canonical_sha256(document),
            }
        )
    if profile not in {"exploration", "production_confirmation"}:
        raise ContractError("artifact evidence profile is invalid")
    dataset_sha256 = verified_dataset_evidence_sha256(manifest)
    if profile == "exploration":
        if exploration_evidence is not None:
            raise ContractError(
                "exploration artifacts must not include prior exploration evidence"
            )
        exploration_sha256 = None
    else:
        exploration_sha256 = verified_exploration_evidence_sha256(
            exploration_evidence
        )
        _validate_production_exploration_binding(
            _revalidate_verified_dataset(manifest),
            exploration_evidence=exploration_evidence,
        )
    return canonical_sha256(
        {
            "profile": profile,
            "dataset_evidence_sha256": dataset_sha256,
            "exploration_evidence_sha256": exploration_sha256,
        }
    )


def verify_exploration_evidence(
    manifest_path: Path,
    public_plan_path: Path,
    protocol_path: Path,
    repo_root: Path,
    *,
    snapshot_root: Path,
    fixture_root: Optional[Path] = None,
) -> VerifiedExplorationEvidence:
    """Create the sole accepted production comparison evidence bundle."""
    dataset_evidence = verify_dataset_evidence(
        manifest_path,
        repo_root,
        snapshot_root=snapshot_root,
        fixture_root=fixture_root,
    )
    manifest = _revalidate_verified_dataset(dataset_evidence)
    dataset_record = _VERIFIED_DATASET_RECORDS[dataset_evidence]
    if dataset_record.recording_purpose != "exploration":
        raise ContractError(
            "snapshot-backed exploration evidence must use the exploration profile"
        )
    from .artifacts import (
        load_committed_json_artifact,
        validate_private_artifact_input_path,
    )

    resolved_plan = validate_private_artifact_input_path(
        public_plan_path,
        repo_root,
    )
    resolved_protocol = validate_private_input_path(protocol_path, repo_root)

    expected_evidence_sha256 = benchmark_evidence_sha256(
        dataset_evidence,
        "exploration",
    )
    public_plan, _plan_receipt = load_committed_json_artifact(
        resolved_plan,
        expected_kind="blind-public-plan",
        expected_evidence_sha256=expected_evidence_sha256,
    )
    protocol = load_json(resolved_protocol)
    commitment = _derive_exploration_commitment_documents(
        manifest,
        _require_mapping(public_plan, "actual exploration public plan"),
        _require_mapping(protocol, "actual exploration dataset protocol"),
        dataset_evidence,
    )
    evidence = object.__new__(VerifiedExplorationEvidence)
    _VERIFIED_EXPLORATION_RECORDS[evidence] = _ExplorationEvidenceRecord(
        dataset_evidence=dataset_evidence,
        public_plan_path=resolved_plan,
        protocol_path=resolved_protocol,
        public_plan_sha256=canonical_sha256(public_plan),
        protocol_sha256=canonical_sha256(protocol),
        commitment_sha256=commitment["commitment_sha256"],
    )
    _revalidate_verified_exploration(evidence)
    return evidence


def _revalidate_verified_exploration(
    evidence: Any,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]]:
    if (
        type(evidence) is not VerifiedExplorationEvidence
        or evidence not in _VERIFIED_EXPLORATION_RECORDS
    ):
        raise ContractError(
            "production validation requires factory-created snapshot-backed "
            "VerifiedExplorationEvidence"
        )
    record = _VERIFIED_EXPLORATION_RECORDS[evidence]
    manifest = _revalidate_verified_dataset(record.dataset_evidence)
    dataset_record = _VERIFIED_DATASET_RECORDS[record.dataset_evidence]
    repo_root = dataset_record.repo_root
    from .artifacts import (
        load_committed_json_artifact,
        validate_private_artifact_input_path,
    )

    plan_path = validate_private_artifact_input_path(
        record.public_plan_path,
        repo_root,
    )
    protocol_path = validate_private_input_path(
        record.protocol_path,
        repo_root,
    )
    expected_evidence_sha256 = benchmark_evidence_sha256(
        record.dataset_evidence,
        "exploration",
    )
    loaded_plan, _plan_receipt = load_committed_json_artifact(
        plan_path,
        expected_kind="blind-public-plan",
        expected_evidence_sha256=expected_evidence_sha256,
    )
    plan = _require_mapping(loaded_plan, "actual exploration public plan")
    protocol = _require_mapping(
        load_json(protocol_path),
        "actual exploration dataset protocol",
    )
    if (
        canonical_sha256(plan) != record.public_plan_sha256
        or canonical_sha256(protocol) != record.protocol_sha256
    ):
        raise ContractError(
            "filesystem-backed exploration contract changed after verification"
        )
    commitment = _derive_exploration_commitment_documents(
        manifest,
        plan,
        protocol,
        record.dataset_evidence,
    )
    if commitment["commitment_sha256"] != record.commitment_sha256:
        raise ContractError(
            "filesystem-backed exploration commitment changed after verification"
        )
    _revalidate_verified_dataset(record.dataset_evidence)
    return manifest, dict(plan), dict(protocol), commitment


def verified_exploration_evidence_sha256(
    evidence: VerifiedExplorationEvidence,
) -> str:
    """Bind production artifacts to the complete frozen exploration contract."""
    _manifest, _plan, _protocol, commitment = (
        _revalidate_verified_exploration(evidence)
    )
    record = _VERIFIED_EXPLORATION_RECORDS[evidence]
    return canonical_sha256(
        {
            "dataset_evidence_sha256": verified_dataset_evidence_sha256(
                record.dataset_evidence
            ),
            "public_plan_sha256": record.public_plan_sha256,
            "protocol_sha256": record.protocol_sha256,
            "commitment_sha256": commitment["commitment_sha256"],
        }
    )


def derive_exploration_commitment(
    evidence: Any,
    *unverified_documents: Any,
) -> dict[str, Any]:
    """Derive a commitment only from re-read filesystem-backed evidence."""
    if unverified_documents:
        raise ContractError(
            "exploration commitment requires filesystem-backed "
            "VerifiedExplorationEvidence"
        )
    return dict(_revalidate_verified_exploration(evidence)[3])


def _validate_production_exploration_binding(
    manifest: Mapping[str, Any],
    *,
    exploration_evidence: Any,
) -> dict[str, Any]:
    (
        actual_manifest,
        _actual_public_plan,
        _actual_protocol,
        actual_commitment,
    ) = _revalidate_verified_exploration(
        exploration_evidence,
    )
    stored_commitment = _validate_exploration_commitment_reference(
        manifest.get("frozen_exploration_pcm_set")
    )
    if stored_commitment != actual_commitment:
        raise ContractError(
            "production manifest exploration commitment does not match "
            "the actual frozen exploration run"
        )
    if dict(actual_manifest["target_device"]) != dict(manifest["target_device"]):
        raise ContractError(
            "production and actual exploration runs must use the same target device"
        )
    exploration_audio = {
        clip["audio_sha256"] for clip in actual_manifest["clips"]
    }
    exploration_payload = {
        clip["pcm_payload_sha256"] for clip in actual_manifest["clips"]
    }
    production_audio = {clip["audio_sha256"] for clip in manifest["clips"]}
    production_payload = {
        clip["pcm_payload_sha256"] for clip in manifest["clips"]
    }
    if (
        production_audio.intersection(exploration_audio)
        or production_payload.intersection(exploration_payload)
    ):
        raise ContractError(
            "production WAV and payload hashes must be disjoint from "
            "the actual exploration manifest"
        )
    return actual_commitment


def _validate_production_exploration_evidence(
    manifest: Mapping[str, Any],
    protocol: Mapping[str, Any],
    *,
    exploration_evidence: Any,
) -> dict[str, Any]:
    actual_commitment = _validate_production_exploration_binding(
        manifest,
        exploration_evidence=exploration_evidence,
    )
    if protocol["exploration_pcm_set_sha256"] != actual_commitment[
        "commitment_sha256"
    ]:
        raise ContractError(
            "production protocol does not bind the actual exploration commitment"
        )
    return actual_commitment


def _manifest_document_for_profile(
    manifest: Any,
    profile: str,
) -> Mapping[str, Any]:
    if type(manifest) is VerifiedDatasetEvidence:
        document = _revalidate_verified_dataset(manifest)
        if document["dataset_usage"]["recording_purpose"] != profile:
            raise ContractError(
                "filesystem-backed manifest recording purpose does not match "
                "the dataset protocol"
            )
        return document
    if profile in {"exploration", "production_confirmation"}:
        raise ContractError(
            "formal validation requires factory-created filesystem-backed "
            "VerifiedDatasetEvidence"
        )
    return _require_mapping(manifest, "manifest")


def _manifest_document_for_artifact(
    manifest: Any,
    profile: str,
    *,
    exploration_evidence: Optional[VerifiedExplorationEvidence] = None,
) -> Mapping[str, Any]:
    document = _manifest_document_for_profile(manifest, profile)
    if profile == "production_confirmation":
        _validate_production_exploration_binding(
            document,
            exploration_evidence=exploration_evidence,
        )
    elif exploration_evidence is not None:
        raise ContractError(
            "exploration evidence is allowed only for production_confirmation"
        )
    return document


def _revalidate_formal_snapshot_bindings(
    manifest: Any,
    profile: str,
    *,
    exploration_evidence: Optional[VerifiedExplorationEvidence] = None,
) -> None:
    """Re-open every bound snapshot at a formal API's final return checkpoint."""
    if profile in {"exploration", "production_confirmation"}:
        _manifest_document_for_artifact(
            manifest,
            profile,
            exploration_evidence=exploration_evidence,
        )
    elif exploration_evidence is not None:
        raise ContractError(
            "exploration evidence is allowed only for production_confirmation"
        )


def validate_dataset_protocol(
    data: Any,
    manifest: Optional[Any] = None,
    *,
    exploration_evidence: Optional[VerifiedExplorationEvidence] = None,
    exploration_manifest: Optional[Mapping[str, Any]] = None,
    exploration_public_plan: Optional[Mapping[str, Any]] = None,
    exploration_dataset_protocol: Optional[Mapping[str, Any]] = None,
) -> dict[str, Any]:
    protocol = dict(_require_mapping(data, "dataset protocol"))
    _exact_keys(
        protocol,
        {
            "schema_version",
            "protocol_id",
            "approval_status",
            "profile",
            "selection_authority",
            "target_device",
            "required_conditions",
            "minimum_unique_clips_per_condition",
            "minimum_speaker_clusters",
            "minimum_session_clusters",
            "minimum_joint_clusters",
            "slice_cluster_minimums",
            "preregistration",
            "exploration_pcm_set_sha256",
            "required_source_kind",
            "unique_pcm_required",
            "bootstrap_unit",
            "telemetry",
        },
        context="dataset protocol",
    )
    _require_version(protocol, "dataset protocol")
    _require_anonymous_id(protocol["protocol_id"], "protocol", "dataset protocol.protocol_id")
    if protocol["approval_status"] != "approved":
        raise ContractError("dataset protocol must be explicitly approved before a formal run")
    target_device = _validate_target_device(
        protocol["target_device"], "dataset protocol.target_device"
    )
    if target_device["physical_device"] is not True:
        raise ContractError("dataset protocol must require a physical Android target device")
    if (
        protocol["profile"] in {"exploration", "production_confirmation"}
        and target_device["device_profile_sha256"] == "0" * 64
    ):
        raise ContractError(
            "approved formal device profile requires a non-sentinel Phase B commitment"
        )

    conditions = _require_list(
        protocol["required_conditions"],
        "dataset protocol.required_conditions",
        non_empty=True,
    )
    if (
        len(conditions) != len(set(conditions))
        or set(conditions) != set(REQUIRED_SELECTION_CONDITIONS)
        or not all(isinstance(item, str) for item in conditions)
    ):
        raise ContractError("dataset protocol must include every required condition exactly once")

    minimums = _require_mapping(
        protocol["minimum_unique_clips_per_condition"],
        "dataset protocol.minimum_unique_clips_per_condition",
    )
    _exact_keys(
        minimums,
        REQUIRED_SELECTION_CONDITIONS,
        context="dataset protocol.minimum_unique_clips_per_condition",
    )
    for condition in REQUIRED_SELECTION_CONDITIONS:
        minimum = _require_nonnegative_int(
            minimums[condition],
            f"dataset protocol.minimum_unique_clips_per_condition.{condition}",
        )
        if minimum < 1:
            raise ContractError("every condition minimum must be at least one unique clip")
    for field in (
        "minimum_speaker_clusters",
        "minimum_session_clusters",
        "minimum_joint_clusters",
    ):
        if _require_nonnegative_int(protocol[field], f"dataset protocol.{field}") < 1:
            raise ContractError(f"dataset protocol.{field} must be at least one")
    slice_cluster_minimums = _require_mapping(
        protocol["slice_cluster_minimums"],
        "dataset protocol.slice_cluster_minimums",
    )
    _exact_keys(
        slice_cluster_minimums,
        REQUIRED_SELECTION_CONDITIONS,
        context="dataset protocol.slice_cluster_minimums",
    )
    for condition in REQUIRED_SELECTION_CONDITIONS:
        context = f"dataset protocol.slice_cluster_minimums.{condition}"
        minimum = _require_mapping(slice_cluster_minimums[condition], context)
        _exact_keys(
            minimum,
            {"minimum_speakers", "minimum_sessions"},
            context=context,
        )
        _require_nonnegative_int(minimum["minimum_speakers"], f"{context}.minimum_speakers")
        _require_nonnegative_int(minimum["minimum_sessions"], f"{context}.minimum_sessions")
    preregistration = _require_mapping(
        protocol["preregistration"], "dataset protocol.preregistration"
    )
    profile = protocol["profile"]
    if profile == "development_fixture":
        if protocol["selection_authority"] != "development_screening_only":
            raise ContractError(
                "development fixture protocol must be development_screening_only"
            )
        if not preregistration:
            raise ContractError("development fixture preregistration must not be empty")
        if protocol["exploration_pcm_set_sha256"] is not None:
            raise ContractError("development fixture must not bind an exploration PCM set")
    else:
        expected = _formal_profile_fields(profile)
        if protocol["bootstrap_unit"] != "speaker_nested_session_clip":
            raise ContractError(
                "formal dataset protocol bootstrap must use speaker with nested session/clip"
            )
        for field in (
            "selection_authority",
            "minimum_unique_clips_per_condition",
            "minimum_speaker_clusters",
            "minimum_session_clusters",
            "minimum_joint_clusters",
            "slice_cluster_minimums",
            "preregistration",
        ):
            if protocol[field] != expected[field]:
                raise ContractError(
                    f"dataset protocol {field} must match the frozen {profile} profile"
                )
        if profile == "exploration":
            if protocol["exploration_pcm_set_sha256"] is not None:
                raise ContractError("exploration protocol must not bind a prior PCM set")
        else:
            commitment = _require_sha256(
                protocol["exploration_pcm_set_sha256"],
                "dataset protocol.exploration_pcm_set_sha256",
            )
            if commitment == "0" * 64:
                raise ContractError(
                    "production protocol exploration PCM-set commitment is a sentinel"
                )
    if protocol["required_source_kind"] != "local_target_device_recording":
        raise ContractError("formal protocol source must be local target-device recording")
    if protocol["unique_pcm_required"] is not True:
        raise ContractError("dataset protocol must require unique PCM per clip")
    if protocol["bootstrap_unit"] not in {
        "speaker_session_cluster",
        "speaker_nested_session_clip",
    }:
        raise ContractError("dataset protocol bootstrap unit is unsupported")

    telemetry = _require_mapping(protocol["telemetry"], "dataset protocol.telemetry")
    _exact_keys(
        telemetry,
        set(_TELEMETRY_CONTRACT),
        context="dataset protocol.telemetry",
    )
    if dict(telemetry) != _TELEMETRY_CONTRACT:
        raise ContractError(
            "dataset protocol telemetry must match the frozen 5-second schedule, "
            "bounded 250ms/5% jitter, 600-second duration, two-loop minimum, "
            "and trusted progress unit"
        )

    legacy_exploration_documents = (
        exploration_manifest,
        exploration_public_plan,
        exploration_dataset_protocol,
    )
    if manifest is not None:
        if profile == "production_confirmation" and any(
            value is not None for value in legacy_exploration_documents
        ):
            raise ContractError(
                "production validation requires filesystem-backed verified "
                "dataset and exploration evidence"
            )
        manifest_document = _manifest_document_for_profile(manifest, profile)
        if dict(_require_mapping(manifest_document.get("target_device"), "manifest.target_device")) != dict(
            target_device
        ):
            raise ContractError("manifest target device must match the preregistered protocol")
        if profile in {"exploration", "production_confirmation"}:
            usage = _require_mapping(
                manifest_document.get("dataset_usage"),
                "manifest.dataset_usage",
            )
            if usage.get("recording_purpose") != profile:
                raise ContractError("manifest recording purpose must match the formal protocol")
            if profile == "production_confirmation" and usage.get("used_for_model_tuning") is not False:
                raise ContractError(
                    "production-confirmation recordings must be fresh and unused for tuning"
                )
            _validate_formal_dataset_structure(manifest_document, protocol)
            if profile == "production_confirmation":
                _validate_production_exploration_evidence(
                    manifest_document,
                    protocol,
                    exploration_evidence=exploration_evidence,
                )
            elif exploration_evidence is not None or any(
                value is not None for value in legacy_exploration_documents
            ):
                raise ContractError(
                    "only production validation may import exploration evidence"
                )
            clips = _require_list(
                manifest_document.get("clips"),
                "manifest.clips",
                non_empty=True,
            )
            for clip in clips:
                conditions = set(clip["conditions"])
                for exclusive_group in _MUTUALLY_EXCLUSIVE_CONDITIONS:
                    if len(conditions.intersection(exclusive_group)) > 1:
                        raise ContractError(
                            "formal manifest contains mutually exclusive slice labels"
                        )
                if "accent" in conditions and clip["accent_natural"] is not True:
                    raise ContractError("accent slice must be natural and not imitated")
                if (
                    clip["session_index"] > 1
                    and clip["hours_since_previous_session"]
                    < protocol["preregistration"]["minimum_session_gap_hours"]
                ):
                    raise ContractError("formal session gap is below 12 hours")
            anchor_count = sum(clip["prompt_kind"] == "common_anchor" for clip in clips)
            expected_anchor_fraction = protocol["preregistration"]["common_anchor_fraction"]
            if not math.isclose(
                anchor_count / len(clips),
                expected_anchor_fraction,
                rel_tol=0,
                abs_tol=1e-12,
            ):
                raise ContractError("formal dataset must use the frozen 50% anchor split")
        elif exploration_evidence is not None or any(
            value is not None for value in legacy_exploration_documents
        ):
            raise ContractError(
                "exploration evidence is allowed only for production_confirmation"
            )
    elif exploration_evidence is not None or any(
        value is not None for value in legacy_exploration_documents
    ):
        raise ContractError(
            "exploration evidence requires a filesystem-backed production dataset"
        )
    if manifest is not None:
        _revalidate_formal_snapshot_bindings(
            manifest,
            profile,
            exploration_evidence=exploration_evidence,
        )
    return protocol


def validate_reference_set(
    data: Any,
    *,
    expected_dataset_id: Optional[str] = None,
    expected_clip_ids: Optional[Set[str]] = None,
) -> dict[str, Any]:
    reference_set = dict(_require_mapping(data, "reference set"))
    _exact_keys(
        reference_set,
        {"schema_version", "dataset_id", "references"},
        context="reference set",
    )
    _require_version(reference_set, "reference set")
    dataset_id = _require_anonymous_id(reference_set["dataset_id"], "dataset", "reference dataset_id")
    if expected_dataset_id is not None and dataset_id != expected_dataset_id:
        raise ContractError("reference dataset_id does not match the manifest")

    seen_clips: Set[str] = set()
    seen_annotations: Set[str] = set()
    references = _require_list(reference_set["references"], "references", non_empty=True)
    for index, raw_reference in enumerate(references):
        context = f"references[{index}]"
        reference = _require_mapping(raw_reference, context)
        _exact_keys(reference, {"clip_id", "text", "annotations"}, context=context)
        clip_id = _require_anonymous_id(reference["clip_id"], "clip", f"{context}.clip_id")
        if clip_id in seen_clips:
            raise ContractError("reference set contains duplicate clip_id")
        seen_clips.add(clip_id)
        text = _require_string(reference["text"], f"{context}.text", non_empty=False)
        if len(text) > 100_000:
            raise ContractError(f"{context}.text exceeds the local scoring limit")
        annotations = _require_list(reference["annotations"], f"{context}.annotations")
        for annotation_index, raw_annotation in enumerate(annotations):
            annotation_context = f"{context}.annotations[{annotation_index}]"
            annotation = _require_mapping(raw_annotation, annotation_context)
            _exact_keys(
                annotation,
                {"annotation_id", "kind", "text", "occurrence_index"},
                context=annotation_context,
            )
            annotation_id = _require_anonymous_id(
                annotation["annotation_id"], "ann", f"{annotation_context}.annotation_id"
            )
            if annotation_id in seen_annotations:
                raise ContractError("reference set contains duplicate annotation_id")
            seen_annotations.add(annotation_id)
            if annotation["kind"] not in {"number", "proper_name"}:
                raise ContractError(f"{annotation_context}.kind is invalid")
            _require_string(annotation["text"], f"{annotation_context}.text")
            _require_nonnegative_int(
                annotation["occurrence_index"],
                f"{annotation_context}.occurrence_index",
            )

    if expected_clip_ids is not None and seen_clips != set(expected_clip_ids):
        raise ContractError("reference clip set must exactly match the manifest clip set")
    return reference_set


def _validate_contract_fingerprints(value: Any, context: str) -> Mapping[str, Any]:
    fingerprints = _require_mapping(value, context)
    _exact_keys(fingerprints, CONTRACT_FINGERPRINT_FIELDS, context=context)
    for field in CONTRACT_FINGERPRINT_FIELDS:
        _require_sha256(fingerprints[field], f"{context}.{field}")
    return fingerprints


def _validate_public_plan_document(
    data: Any,
    manifest: Optional[Mapping[str, Any]] = None,
) -> dict[str, Any]:
    plan = dict(_require_mapping(data, "public blind plan"))
    _reject_answer_keys(plan, "public blind plan")
    _exact_keys(
        plan,
        {
            "schema_version",
            "run_id",
            "dataset_id",
            "pcm_contract_id",
            "protocol_profile",
            "cohort_id",
            "cohort_model_ids_sha256",
            "formal_artifact_set_sha256",
            "formal_risk_acceptance_set_sha256",
            "formal_model_cohort_sha256",
            "assignments",
            "baseline_commitment_sha256",
            "clip_pcm_sha256",
            "clip_pcm_payload_sha256",
            "randomization",
            "contract_fingerprints",
        },
        context="public blind plan",
    )
    _require_version(plan, "public blind plan")
    _require_anonymous_id(plan["run_id"], "run", "public blind plan.run_id")
    _require_anonymous_id(plan["dataset_id"], "dataset", "public blind plan.dataset_id")
    if plan["pcm_contract_id"] != PCM_CONTRACT_ID:
        raise ContractError(f"public blind plan PCM contract must be {PCM_CONTRACT_ID}")
    if plan["protocol_profile"] not in {
        "development_fixture",
        "exploration",
        "production_confirmation",
    }:
        raise ContractError("public blind plan protocol profile is invalid")
    cohort_id = _require_string(plan["cohort_id"], "public blind plan.cohort_id")
    if not _MODEL_ID.fullmatch(cohort_id):
        raise ContractError("public blind plan cohort_id is invalid")
    _require_sha256(
        plan["cohort_model_ids_sha256"],
        "public blind plan.cohort_model_ids_sha256",
    )
    formal_commitment_fields = (
        "formal_artifact_set_sha256",
        "formal_risk_acceptance_set_sha256",
        "formal_model_cohort_sha256",
    )
    formal_commitments = tuple(
        plan[field] for field in formal_commitment_fields
    )
    if plan["protocol_profile"] == "development_fixture":
        if any(value is not None for value in formal_commitments):
            raise ContractError(
                "development public plan cannot carry formal model commitments"
            )
    elif any(value is None for value in formal_commitments) and any(
        value is not None for value in formal_commitments
    ):
        raise ContractError(
            "formal public plan model commitments must be all present or all absent"
        )
    for field, value in zip(
        formal_commitment_fields,
        formal_commitments,
    ):
        if value is not None:
            _require_sha256(value, f"public blind plan.{field}")
    _require_sha256(
        plan["baseline_commitment_sha256"],
        "public blind plan.baseline_commitment_sha256",
    )
    fingerprints = _validate_contract_fingerprints(
        plan["contract_fingerprints"], "public blind plan.contract_fingerprints"
    )

    assignments = _require_list(
        plan["assignments"], "public blind plan.assignments", non_empty=True
    )
    if len(assignments) < 2:
        raise ContractError("public blind plan requires at least two model aliases")
    aliases: list[str] = []
    expected_clip_set: Optional[Set[str]] = None
    for index, raw_assignment in enumerate(assignments):
        context = f"public blind plan.assignments[{index}]"
        assignment = _require_mapping(raw_assignment, context)
        _exact_keys(assignment, {"model_alias", "clip_order"}, context=context)
        alias = _require_string(assignment["model_alias"], f"{context}.model_alias")
        if not _MODEL_ALIAS.fullmatch(alias):
            raise ContractError(f"{context}.model_alias is invalid")
        aliases.append(alias)
        clip_order = _require_list(assignment["clip_order"], f"{context}.clip_order", non_empty=True)
        clip_ids = [
            _require_anonymous_id(item, "clip", f"{context}.clip_order item")
            for item in clip_order
        ]
        if len(clip_ids) != len(set(clip_ids)):
            raise ContractError(f"{context}.clip_order contains duplicate clip_id")
        current_set = set(clip_ids)
        if expected_clip_set is None:
            expected_clip_set = current_set
        elif current_set != expected_clip_set:
            raise ContractError("every blind assignment must contain the same clip set")
    if len(aliases) != len(set(aliases)):
        raise ContractError("public blind plan contains duplicate model_alias")
    expected_aliases = [f"M{index:03d}" for index in range(1, len(aliases) + 1)]
    if aliases != expected_aliases:
        raise ContractError("public blind plan aliases must be ordered sequentially from M001")
    file_hashes = _require_mapping(plan["clip_pcm_sha256"], "public blind plan.clip_pcm_sha256")
    payload_hashes = _require_mapping(
        plan["clip_pcm_payload_sha256"],
        "public blind plan.clip_pcm_payload_sha256",
    )
    expected_clip_set = expected_clip_set or set()
    if set(file_hashes) != expected_clip_set or set(payload_hashes) != expected_clip_set:
        raise ContractError("public blind plan PCM maps must exactly match the plan clip set")
    for clip_id in sorted(expected_clip_set):
        _require_sha256(file_hashes[clip_id], f"public blind plan clip PCM {clip_id}")
        _require_sha256(
            payload_hashes[clip_id],
            f"public blind plan payload PCM {clip_id}",
        )

    randomization = _require_mapping(plan["randomization"], "public blind plan.randomization")
    _exact_keys(
        randomization,
        {"algorithm", "private_map_sha256"},
        context="public blind plan.randomization",
    )
    if randomization["algorithm"] != "sha256-fisher-yates-v1":
        raise ContractError("public blind plan randomization algorithm is unsupported")
    _require_sha256(
        randomization["private_map_sha256"],
        "public blind plan.randomization.private_map_sha256",
    )

    if manifest is not None:
        manifest_clips = {
            item["clip_id"]: item
            for item in _require_list(manifest.get("clips"), "manifest.clips", non_empty=True)
        }
        if plan["dataset_id"] != manifest.get("dataset_id"):
            raise ContractError("public blind plan dataset_id does not match the manifest")
        if plan["pcm_contract_id"] != manifest.get("pcm_contract_id"):
            raise ContractError("public blind plan PCM contract does not match the manifest")
        if set(manifest_clips) != expected_clip_set:
            raise ContractError("public blind plan clip set does not match the manifest")
        for clip_id, clip in manifest_clips.items():
            if file_hashes[clip_id] != clip.get("audio_sha256"):
                raise ContractError("public blind plan PCM hash does not match the manifest")
            if payload_hashes[clip_id] != clip.get("pcm_payload_sha256"):
                raise ContractError("public blind plan payload PCM hash does not match the manifest")
        if fingerprints["manifest_sha256"] != canonical_sha256(manifest):
            raise ContractError("public blind plan manifest fingerprint does not match")
    return plan


def validate_public_plan(
    data: Any,
    manifest: Optional[Any] = None,
    *,
    exploration_evidence: Optional[VerifiedExplorationEvidence] = None,
) -> dict[str, Any]:
    """Validate a plan; formal manifest binding always re-reads registered evidence."""
    plan = _validate_public_plan_document(data)
    if manifest is None:
        if exploration_evidence is not None:
            raise ContractError(
                "exploration evidence requires a filesystem-backed production manifest"
            )
        return plan
    manifest_document = _manifest_document_for_artifact(
        manifest,
        plan["protocol_profile"],
        exploration_evidence=exploration_evidence,
    )
    validated = _validate_public_plan_document(plan, manifest_document)
    _revalidate_formal_snapshot_bindings(
        manifest,
        plan["protocol_profile"],
        exploration_evidence=exploration_evidence,
    )
    return validated


def decoder_plan_id(document: Mapping[str, Any]) -> str:
    """Return the anonymous ID bound to all semantic decoder-plan fields."""
    identity_document = dict(document)
    identity_document.pop("plan_id", None)
    return f"plan_{canonical_sha256(identity_document)[:12]}"


def decoder_plan_raw_sha256(document: Mapping[str, Any]) -> str:
    """Hash the exact compact UTF-8 bytes accepted by the Android runner."""
    try:
        encoded = (
            json.dumps(
                document,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            )
            + "\n"
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ContractError("decoder plan is not canonical JSON") from error
    return hashlib.sha256(encoded).hexdigest()


def decoder_plan_document(
    manifest: Any,
    public_plan: Mapping[str, Any],
    model_alias: str,
    *,
    exploration_evidence: Optional[VerifiedExplorationEvidence] = None,
) -> dict[str, Any]:
    plan = _validate_public_plan_document(public_plan)
    manifest_document = _manifest_document_for_artifact(
        manifest,
        plan["protocol_profile"],
        exploration_evidence=exploration_evidence,
    )
    plan = _validate_public_plan_document(plan, manifest_document)
    assignment = next(
        (
            item
            for item in plan["assignments"]
            if item["model_alias"] == model_alias
        ),
        None,
    )
    if assignment is None:
        raise ContractError("model alias is not present in the public blind plan")
    clips_by_id = {
        item["clip_id"]: item for item in manifest_document["clips"]
    }
    document = {
        "schema_version": SCHEMA_VERSION,
        "run_id": plan["run_id"],
        "dataset_id": plan["dataset_id"],
        "model_alias": model_alias,
        "pcm_contract_id": PCM_CONTRACT_ID,
        "decoder_contract_id": DECODER_CONTRACT_ID,
        "input_transform_id": INPUT_TRANSFORM_ID,
        "public_plan_sha256": canonical_sha256(plan),
        "manifest_sha256": canonical_sha256(manifest_document),
        "clips": [
            {
                "clip_id": clip_id,
                "audio_path": clips_by_id[clip_id]["audio_path"],
                "wav_file_sha256": clips_by_id[clip_id]["audio_sha256"],
                "pcm_payload_sha256": clips_by_id[clip_id]["pcm_payload_sha256"],
                "pcm_payload_bytes": clips_by_id[clip_id]["pcm_payload_bytes"],
            }
            for clip_id in assignment["clip_order"]
        ],
    }
    document["plan_id"] = decoder_plan_id(document)
    _revalidate_formal_snapshot_bindings(
        manifest,
        plan["protocol_profile"],
        exploration_evidence=exploration_evidence,
    )
    return document


def validate_private_map(
    data: Any,
    public_plan: Mapping[str, Any],
    registry: Optional[Mapping[str, Any]] = None,
) -> dict[str, Any]:
    plan = validate_public_plan(public_plan)
    private_map = dict(_require_mapping(data, "private alias map"))
    _exact_keys(
        private_map,
        {
            "schema_version",
            "run_id",
            "seed",
            "alias_map",
            "baseline_alias",
            "baseline_commitment_nonce",
            "registry_sha256",
        },
        context="private alias map",
    )
    _require_version(private_map, "private alias map")
    if private_map["run_id"] != plan["run_id"]:
        raise ContractError("private alias map run_id does not match the public plan")
    seed = private_map["seed"]
    if (
        isinstance(seed, bool)
        or not isinstance(seed, int)
        or seed < 0
        or seed.bit_length() < 128
    ):
        raise ContractError("private alias map seed must be at least 128-bit")
    registry_sha256 = _require_sha256(
        private_map["registry_sha256"], "private alias map.registry_sha256"
    )
    if registry_sha256 != plan["contract_fingerprints"]["registry_sha256"]:
        raise ContractError("private alias map registry fingerprint does not match the public plan")
    mappings = _require_list(private_map["alias_map"], "private alias map.alias_map", non_empty=True)
    aliases: Set[str] = set()
    model_ids: Set[str] = set()
    for index, raw_mapping in enumerate(mappings):
        context = f"private alias map.alias_map[{index}]"
        mapping = _require_mapping(raw_mapping, context)
        _exact_keys(mapping, {"model_alias", "model_id"}, context=context)
        alias = _require_string(mapping["model_alias"], f"{context}.model_alias")
        model_id = _require_string(mapping["model_id"], f"{context}.model_id")
        if not _MODEL_ALIAS.fullmatch(alias) or not _MODEL_ID.fullmatch(model_id):
            raise ContractError(f"{context} contains an invalid alias or model ID")
        if alias in aliases or model_id in model_ids:
            raise ContractError("private alias map contains duplicate aliases or model IDs")
        aliases.add(alias)
        model_ids.add(model_id)
    plan_aliases = {item["model_alias"] for item in plan["assignments"]}
    if aliases != plan_aliases:
        raise ContractError("private alias map must cover the complete public alias set")
    baseline_alias = _require_string(
        private_map["baseline_alias"],
        "private alias map.baseline_alias",
    )
    if baseline_alias not in aliases:
        raise ContractError("private alias map baseline alias is absent from the alias map")
    nonce = _require_sha256(
        private_map["baseline_commitment_nonce"],
        "private alias map.baseline_commitment_nonce",
    )
    expected_baseline_commitment = canonical_sha256(
        {
            "run_id": plan["run_id"],
            "baseline_alias": baseline_alias,
            "nonce": nonce,
        }
    )
    if expected_baseline_commitment != plan["baseline_commitment_sha256"]:
        raise ContractError("private baseline identity does not match its public commitment")
    if canonical_sha256(private_map) != plan["randomization"]["private_map_sha256"]:
        raise ContractError("private alias map commitment does not match the public plan")
    if registry is not None:
        validated_registry = validate_model_registry(registry)
        if canonical_sha256(validated_registry) != registry_sha256:
            raise ContractError("registry snapshot fingerprint does not match the private map")
        registry_ids = {item["model_id"] for item in validated_registry["models"]}
        if not model_ids.issubset(registry_ids):
            raise ContractError("private alias map contains a model absent from the registry")
        if plan["protocol_profile"] in {"exploration", "production_confirmation"}:
            cohorts = [
                cohort
                for cohort in validated_registry["formal_cohorts"]
                if cohort["cohort_id"] == plan["cohort_id"]
                and plan["protocol_profile"] in cohort["profiles"]
            ]
            if len(cohorts) != 1 or model_ids != set(cohorts[0]["model_ids"]):
                raise ContractError(
                    "private alias map must cover the complete frozen formal cohort"
                )
            baseline_model_id = next(
                mapping["model_id"]
                for mapping in mappings
                if mapping["model_alias"] == baseline_alias
            )
            if baseline_model_id != cohorts[0]["baseline_model_id"]:
                raise ContractError(
                    "private baseline identity differs from the frozen formal cohort"
                )
    if canonical_sha256(sorted(model_ids)) != plan["cohort_model_ids_sha256"]:
        raise ContractError("private alias map does not match the public cohort commitment")
    return private_map


def _require_official_https_url(value: Any, context: str) -> str:
    url = _require_string(value, context)
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.hostname.lower() in _MIRROR_HOSTS:
        raise ContractError(f"{context} must be an official HTTPS URL, not a mirror")
    if parsed.username or parsed.password:
        raise ContractError(f"{context} must not contain credentials")
    return url


def _require_immutable_revision(
    value: Any,
    kind_value: Any,
    context: str,
) -> tuple[str, str]:
    revision = _require_string(value, context)
    revision_kind = _require_string(kind_value, f"{context}_kind")
    if revision_kind == "commit":
        if not _COMMIT_REVISION.fullmatch(revision):
            raise ContractError(
                f"{context} must be a full immutable 40-hex commit"
            )
        return revision, revision_kind
    if revision_kind != "release_asset":
        raise ContractError(f"{context}_kind is invalid")
    lowered_parts = {
        part.lower()
        for part in re.split(r"[/._:-]+", revision)
        if part
    }
    forbidden = {"main", "master", "latest", "head", "pending", "branch", "tag"}
    if (
        not _RELEASE_ASSET_REVISION.fullmatch(revision)
        or ".." in revision
        or lowered_parts.intersection(forbidden)
        or "." not in Path(revision).name
    ):
        raise ContractError(
            f"{context} must identify an exact immutable release asset"
        )
    return revision, revision_kind


def _require_revision_bound_url(
    url: str,
    revision: str,
    revision_kind: str,
    context: str,
) -> None:
    path = urlparse(url).path
    if revision_kind == "commit":
        if revision not in path:
            raise ContractError(
                f"{context} must contain its immutable commit revision"
            )
        return
    if revision not in path:
        raise ContractError(
            f"{context} must contain its exact release asset revision"
        )


def _registry_model(
    registry: Mapping[str, Any],
    model_id: str,
    *,
    context: str,
) -> Mapping[str, Any]:
    requested_model_id = _require_string(model_id, f"{context} model_id")
    if not _MODEL_ID.fullmatch(requested_model_id):
        raise ContractError(f"{context} model_id is invalid")
    model = next(
        (
            item
            for item in registry["models"]
            if item["model_id"] == requested_model_id
        ),
        None,
    )
    if model is None:
        raise ContractError(f"{context} model_id is not present in the registry")
    return model


def validate_model_registry(data: Any) -> dict[str, Any]:
    registry = dict(_require_mapping(data, "model registry"))
    _exact_keys(
        registry,
        {"schema_version", "formal_cohorts", "models"},
        context="model registry",
    )
    _require_version(registry, "model registry")
    cohorts = _require_list(registry["formal_cohorts"], "model registry.formal_cohorts")
    models = _require_list(registry["models"], "model registry.models", non_empty=True)
    seen: Set[str] = set()
    roles: dict[str, str] = {}
    for index, raw_model in enumerate(models):
        context = f"model registry.models[{index}]"
        model = _require_mapping(raw_model, context)
        _exact_keys(
            model,
            {
                "model_id",
                "display_name",
                "role",
                "family",
                "official_source",
                "licenses",
                "estimated_total_resource_bytes",
                "download_status",
                "runtime",
                "evaluation_policy",
                "artifacts",
                "provenance",
            },
            context=context,
        )
        model_id = _require_string(model["model_id"], f"{context}.model_id")
        if not _MODEL_ID.fullmatch(model_id):
            raise ContractError(f"{context}.model_id is invalid")
        if model_id in seen:
            raise ContractError("model registry contains duplicate model_id")
        seen.add(model_id)
        _require_string(model["display_name"], f"{context}.display_name")
        if model["role"] not in {"baseline", "first_batch_candidate", "second_batch_candidate"}:
            raise ContractError(f"{context}.role is invalid")
        roles[model_id] = model["role"]
        _require_string(model["family"], f"{context}.family")

        source = _require_mapping(model["official_source"], f"{context}.official_source")
        _exact_keys(
            source,
            {"publisher", "model_url", "revision", "revision_kind"},
            optional={"artifact_url"},
            context=f"{context}.official_source",
        )
        _require_string(source["publisher"], f"{context}.official_source.publisher")
        model_url = _require_official_https_url(
            source["model_url"],
            f"{context}.official_source.model_url",
        )
        revision, revision_kind = _require_immutable_revision(
            source["revision"],
            source["revision_kind"],
            f"{context}.official_source.revision",
        )
        if revision_kind == "commit":
            _require_revision_bound_url(
                model_url,
                revision,
                revision_kind,
                f"{context}.official_source.model_url",
            )
        if "artifact_url" in source:
            artifact_url = _require_official_https_url(
                source["artifact_url"], f"{context}.official_source.artifact_url"
            )
            _require_revision_bound_url(
                artifact_url,
                revision,
                revision_kind,
                f"{context}.official_source.artifact_url",
            )
        if revision_kind == "release_asset" and "artifact_url" not in source:
            raise ContractError(
                f"{context}.official_source release asset requires artifact_url"
            )

        licenses = _require_mapping(model["licenses"], f"{context}.licenses")
        _exact_keys(
            licenses,
            LICENSE_COMPONENTS,
            context=f"{context}.licenses",
        )
        for component in LICENSE_COMPONENTS:
            license_context = f"{context}.licenses.{component}"
            license_value = _require_mapping(licenses[component], license_context)
            _exact_keys(
                license_value,
                {"spdx_id", "url", "verification_state"},
                optional={"notice"},
                context=license_context,
            )
            _require_string(license_value["spdx_id"], f"{license_context}.spdx_id")
            _require_official_https_url(license_value["url"], f"{license_context}.url")
            if license_value["verification_state"] not in {
                "verified",
                "pending",
                "blocked",
                "not_applicable",
            }:
                raise ContractError(f"{license_context}.verification_state is invalid")
            if "notice" in license_value:
                _require_string(license_value["notice"], f"{license_context}.notice")

        _require_nonnegative_int(
            model["estimated_total_resource_bytes"],
            f"{context}.estimated_total_resource_bytes",
        )
        if model["download_status"] not in {
            "not_downloaded",
            "already_present_hash_verified",
        }:
            raise ContractError(f"{context}.download_status is invalid")

        runtime = _require_mapping(model["runtime"], f"{context}.runtime")
        _exact_keys(
            runtime,
            {
                "framework",
                "format",
                "supported_abis",
                "android_runtime_compatibility",
                "preprocessor_contract",
            },
            context=f"{context}.runtime",
        )
        _require_string(runtime["framework"], f"{context}.runtime.framework")
        _require_string(runtime["format"], f"{context}.runtime.format")
        if runtime["android_runtime_compatibility"] not in {
            "locally_verified",
            "compile_pending",
            "blocked",
        }:
            raise ContractError(
                f"{context}.runtime.android_runtime_compatibility is invalid"
            )
        _require_string(
            runtime["preprocessor_contract"],
            f"{context}.runtime.preprocessor_contract",
        )
        abis = _require_list(runtime["supported_abis"], f"{context}.runtime.supported_abis", non_empty=True)
        if "arm64-v8a" not in abis or not all(isinstance(item, str) for item in abis):
            raise ContractError(f"{context}.runtime must support arm64-v8a")

        evaluation_policy = _require_mapping(
            model["evaluation_policy"],
            f"{context}.evaluation_policy",
        )
        _exact_keys(
            evaluation_policy,
            {"internal_evaluation_download", "production_distribution"},
            context=f"{context}.evaluation_policy",
        )
        internal_policy = _require_mapping(
            evaluation_policy["internal_evaluation_download"],
            f"{context}.evaluation_policy.internal_evaluation_download",
        )
        _exact_keys(
            internal_policy,
            {"clearance", "user_risk_acceptance", "restrictions"},
            context=f"{context}.evaluation_policy.internal_evaluation_download",
        )
        clearance = internal_policy["clearance"]
        if clearance not in {"cleared", "requires_user_risk_acceptance"}:
            raise ContractError(
                f"{context}.evaluation_policy internal clearance is invalid"
            )
        acceptance = internal_policy["user_risk_acceptance"]
        if acceptance not in {"not_required", "pending", "accepted"}:
            raise ContractError(
                f"{context}.evaluation_policy risk acceptance is invalid"
            )
        if (
            clearance == "cleared" and acceptance != "not_required"
        ) or (
            clearance == "requires_user_risk_acceptance"
            and acceptance not in {"pending", "accepted"}
        ):
            raise ContractError(
                f"{context}.evaluation_policy clearance and risk acceptance disagree"
            )
        restrictions = _require_list(
            internal_policy["restrictions"],
            f"{context}.evaluation_policy.internal_evaluation_download.restrictions",
            non_empty=True,
        )
        if tuple(restrictions) != INTERNAL_EVALUATION_RESTRICTIONS:
            raise ContractError(
                f"{context}.evaluation_policy must freeze the internal-only restrictions"
            )
        production_policy = _require_mapping(
            evaluation_policy["production_distribution"],
            f"{context}.evaluation_policy.production_distribution",
        )
        _exact_keys(
            production_policy,
            {"clearance"},
            context=f"{context}.evaluation_policy.production_distribution",
        )
        if production_policy["clearance"] not in {
            "blocked_pending_separate_review",
            "cleared_by_product_decision",
        }:
            raise ContractError(
                f"{context}.evaluation_policy production distribution clearance is invalid"
            )

        artifacts = _require_list(model["artifacts"], f"{context}.artifacts", non_empty=True)
        artifact_names: Set[str] = set()
        for artifact_index, raw_artifact in enumerate(artifacts):
            artifact_context = f"{context}.artifacts[{artifact_index}]"
            artifact = _require_mapping(raw_artifact, artifact_context)
            _exact_keys(
                artifact,
                {
                    "filename",
                    "component",
                    "expected_bytes",
                    "sha256",
                    "verification_state",
                    "source_url",
                    "source_revision",
                    "source_revision_kind",
                },
                context=artifact_context,
            )
            filename = _require_string(artifact["filename"], f"{artifact_context}.filename")
            if Path(filename).name != filename or filename in artifact_names:
                raise ContractError(f"{artifact_context}.filename must be a unique basename")
            artifact_names.add(filename)
            if artifact["component"] not in ARTIFACT_COMPONENTS:
                raise ContractError(f"{artifact_context}.component is invalid")
            state = artifact["verification_state"]
            if state not in {
                "locally_verified",
                "publisher_verified",
                "post_download_freeze_required",
            }:
                raise ContractError(f"{artifact_context}.verification_state is invalid")
            expected_bytes = artifact["expected_bytes"]
            if expected_bytes is not None:
                if (
                    _require_nonnegative_int(
                        expected_bytes,
                        f"{artifact_context}.expected_bytes",
                    )
                    < 1
                ):
                    raise ContractError(
                        f"{artifact_context}.expected_bytes must be positive"
                    )
            elif state != "post_download_freeze_required":
                raise ContractError(
                    f"{artifact_context}.expected_bytes is required for verified artifacts"
                )
            digest = artifact["sha256"]
            if digest is not None:
                _require_sha256(digest, f"{artifact_context}.sha256")
            elif state != "post_download_freeze_required":
                raise ContractError(f"{artifact_context}.sha256 is required for verified artifacts")
            if state == "post_download_freeze_required" and digest is not None:
                raise ContractError(
                    f"{artifact_context}.sha256 must be frozen by the local download verifier"
                )
            artifact_source_url = _require_official_https_url(
                artifact["source_url"],
                f"{artifact_context}.source_url",
            )
            artifact_revision, artifact_revision_kind = _require_immutable_revision(
                artifact["source_revision"],
                artifact["source_revision_kind"],
                f"{artifact_context}.source_revision",
            )
            _require_revision_bound_url(
                artifact_source_url,
                artifact_revision,
                artifact_revision_kind,
                f"{artifact_context}.source_url",
            )

        provenance = _require_mapping(model["provenance"], f"{context}.provenance")
        _exact_keys(
            provenance,
            {"upstream_model_id", "checkpoint_byte_equivalence", "note"},
            context=f"{context}.provenance",
        )
        _require_string(provenance["upstream_model_id"], f"{context}.provenance.upstream_model_id")
        if provenance["checkpoint_byte_equivalence"] not in {
            "verified",
            "not_proven",
            "not_applicable",
        }:
            raise ContractError(f"{context}.provenance.checkpoint_byte_equivalence is invalid")
        _require_string(provenance["note"], f"{context}.provenance.note")

    expected_formal_ids = {
        model_id
        for model_id, role in roles.items()
        if role in {"baseline", "first_batch_candidate"}
    }
    baseline_ids = {
        model_id for model_id, role in roles.items() if role == "baseline"
    }
    seen_cohort_ids: Set[str] = set()
    claimed_profiles: Set[str] = set()
    for index, raw_cohort in enumerate(cohorts):
        context = f"model registry.formal_cohorts[{index}]"
        cohort = _require_mapping(raw_cohort, context)
        _exact_keys(
            cohort,
            {"cohort_id", "profiles", "model_ids", "baseline_model_id"},
            context=context,
        )
        cohort_id = _require_string(cohort["cohort_id"], f"{context}.cohort_id")
        if not _MODEL_ID.fullmatch(cohort_id) or cohort_id in seen_cohort_ids:
            raise ContractError(f"{context}.cohort_id must be a unique stable ID")
        seen_cohort_ids.add(cohort_id)
        profiles = _require_list(cohort["profiles"], f"{context}.profiles", non_empty=True)
        if (
            len(profiles) != len(set(profiles))
            or not all(
                profile in {"exploration", "production_confirmation"}
                for profile in profiles
            )
        ):
            raise ContractError(f"{context}.profiles contains an invalid or duplicate profile")
        if claimed_profiles.intersection(profiles):
            raise ContractError("formal registry profiles must belong to exactly one cohort")
        claimed_profiles.update(profiles)
        model_ids = _require_list(cohort["model_ids"], f"{context}.model_ids", non_empty=True)
        if (
            len(model_ids) != len(set(model_ids))
            or not all(isinstance(model_id, str) and model_id in seen for model_id in model_ids)
        ):
            raise ContractError(f"{context}.model_ids contains an unknown or duplicate model")
        if set(model_ids) != expected_formal_ids:
            raise ContractError(
                f"{context}.model_ids must contain the complete baseline and first-batch cohort"
            )
        baseline_model_id = _require_string(
            cohort["baseline_model_id"],
            f"{context}.baseline_model_id",
        )
        if (
            baseline_model_id not in model_ids
            or baseline_model_id not in baseline_ids
            or len(baseline_ids) != 1
        ):
            raise ContractError(f"{context} must identify the sole current baseline")
    return registry


def production_license_blockers(
    registry_data: Any,
    model_id: str,
) -> list[str]:
    registry = validate_model_registry(registry_data)
    model = _registry_model(registry, model_id, context="production")
    return [
        f"license_component_pending:{component}"
        for component in LICENSE_COMPONENTS
        if model["licenses"][component]["verification_state"]
        not in {"verified", "not_applicable"}
    ]


def download_license_blockers(
    registry_data: Any,
    model_id: str,
) -> list[str]:
    """Return every uncleared license layer before any model download."""
    registry = validate_model_registry(registry_data)
    model = _registry_model(registry, model_id, context="download")
    return [
        (
            "license_component_"
            f"{model['licenses'][component]['verification_state']}:"
            f"{component}"
        )
        for component in LICENSE_COMPONENTS
        if model["licenses"][component]["verification_state"]
        not in {"verified", "not_applicable"}
    ]


def internal_evaluation_download_blockers(
    registry_data: Any,
    model_id: str,
    *,
    risk_acceptance_receipt_path: Optional[Path] = None,
    repo_root: Optional[Path] = None,
) -> list[str]:
    """Gate private downloads against the fixed registry trust anchor."""
    from .model_security import (
        trusted_internal_evaluation_download_blockers,
    )

    return trusted_internal_evaluation_download_blockers(
        registry_data,
        model_id,
        risk_acceptance_receipt_path=risk_acceptance_receipt_path,
        repo_root=repo_root,
    )


def formal_benchmark_artifact_blockers(
    registry_data: Any,
    model_id: str,
    *,
    artifact_freeze_receipt_paths: Sequence[Path] = (),
    risk_acceptance_receipt_path: Optional[Path] = None,
    repo_root: Optional[Path] = None,
) -> list[str]:
    """Require safe-FD freeze receipts before a model enters a formal run."""
    from .model_security import (
        trusted_formal_benchmark_artifact_blockers,
    )

    return trusted_formal_benchmark_artifact_blockers(
        registry_data,
        model_id,
        artifact_freeze_receipt_paths=artifact_freeze_receipt_paths,
        risk_acceptance_receipt_path=risk_acceptance_receipt_path,
        repo_root=repo_root,
    )


def production_distribution_blockers(
    registry_data: Any,
    model_id: str,
) -> list[str]:
    """Fail closed unless review, licenses, frozen artifacts, and runtime are ready."""
    registry = validate_model_registry(registry_data)
    model = _registry_model(registry, model_id, context="production distribution")
    clearance = model["evaluation_policy"]["production_distribution"]["clearance"]
    blockers = production_license_blockers(registry, model_id)
    if clearance == "blocked_pending_separate_review":
        blockers.insert(0, "production_distribution_review_required")
        blockers.extend(formal_benchmark_artifact_blockers(registry, model_id))
        return list(dict.fromkeys(blockers))
    if clearance != "cleared_by_product_decision":
        raise ContractError("production distribution policy is invalid")
    if model["download_status"] != "already_present_hash_verified":
        blockers.append("production_artifacts_not_frozen")
    for artifact in model["artifacts"]:
        if artifact["verification_state"] != "locally_verified":
            blockers.append(
                f"production_artifact_not_locally_verified:{artifact['filename']}"
            )
    compatibility = model["runtime"]["android_runtime_compatibility"]
    if compatibility != "locally_verified":
        blockers.append(f"android_runtime_compatibility_{compatibility}")
    return list(dict.fromkeys(blockers))


def _validate_runtime(runtime: Any) -> None:
    value = _require_mapping(runtime, "model output.runtime")
    _exact_keys(
        value,
        {
            "total_resource_bytes",
            "peak_rss_bytes",
            "oom_count",
            "crash_count",
            "cold_start_ms",
            "warm_start_ms",
            "stability",
        },
        context="model output.runtime",
    )
    _require_nonnegative_int(value["total_resource_bytes"], "runtime.total_resource_bytes")
    _require_nonnegative_int(value["peak_rss_bytes"], "runtime.peak_rss_bytes")
    _require_nonnegative_int(value["oom_count"], "runtime.oom_count")
    _require_nonnegative_int(value["crash_count"], "runtime.crash_count")
    for field in ("cold_start_ms", "warm_start_ms"):
        values = _require_list(value[field], f"runtime.{field}", non_empty=True)
        for item in values:
            _require_nonnegative_number(item, f"runtime.{field} item")
    stability = _require_mapping(value["stability"], "runtime.stability")
    _exact_keys(
        stability,
        {"duration_seconds", "failure_count", "completed", "max_thermal_status"},
        context="runtime.stability",
    )
    _require_nonnegative_number(stability["duration_seconds"], "runtime.stability.duration_seconds")
    _require_nonnegative_int(stability["failure_count"], "runtime.stability.failure_count")
    if not isinstance(stability["completed"], bool):
        raise ContractError("runtime.stability.completed must be boolean")
    thermal = _require_nonnegative_int(
        stability["max_thermal_status"], "runtime.stability.max_thermal_status"
    )
    if thermal > 6:
        raise ContractError("runtime.stability.max_thermal_status must be between 0 and 6")


def _require_nonnegative_monotonic_ns(value: Any, context: str) -> int:
    return _require_nonnegative_int(value, context)


def _validate_runner_evidence(
    evidence: Any,
    *,
    clip: Mapping[str, Any],
    stop_to_final_ms: float,
    context: str,
) -> None:
    value = _require_mapping(evidence, context)
    _exact_keys(
        value,
        {
            "wav_file_sha256_before",
            "wav_file_sha256_after",
            "pcm_payload_sha256",
            "pcm_payload_bytes_read",
            "monotonic_start_ns",
            "monotonic_stop_ns",
            "monotonic_final_ns",
        },
        context=context,
    )
    before = _require_sha256(value["wav_file_sha256_before"], f"{context}.wav_file_sha256_before")
    after = _require_sha256(value["wav_file_sha256_after"], f"{context}.wav_file_sha256_after")
    if before != clip["audio_sha256"] or after != clip["audio_sha256"]:
        raise ContractError("runner WAV file hash before/after read must match the frozen manifest")
    payload = _require_sha256(value["pcm_payload_sha256"], f"{context}.pcm_payload_sha256")
    if payload != clip["pcm_payload_sha256"]:
        raise ContractError("runner PCM payload hash must match the frozen manifest")
    bytes_read = _require_nonnegative_int(
        value["pcm_payload_bytes_read"], f"{context}.pcm_payload_bytes_read"
    )
    if bytes_read != clip["pcm_payload_bytes"]:
        raise ContractError("runner PCM payload byte count must match the frozen manifest")
    start_ns = _require_nonnegative_monotonic_ns(
        value["monotonic_start_ns"], f"{context}.monotonic_start_ns"
    )
    stop_ns = _require_nonnegative_monotonic_ns(
        value["monotonic_stop_ns"], f"{context}.monotonic_stop_ns"
    )
    final_ns = _require_nonnegative_monotonic_ns(
        value["monotonic_final_ns"], f"{context}.monotonic_final_ns"
    )
    if not start_ns <= stop_ns <= final_ns:
        raise ContractError("runner monotonic clock values must be ordered start <= stop <= final")
    measured_ms = (final_ns - stop_ns) / 1_000_000
    if not math.isclose(measured_ms, stop_to_final_ms, rel_tol=0, abs_tol=0.000_001):
        raise ContractError("runner monotonic stop-to-final timing does not match the reported latency")


def _validate_artifact_measurements(
    raw_measurements: Any,
    *,
    runtime: Mapping[str, Any],
) -> list[Mapping[str, Any]]:
    measurements = _require_list(
        raw_measurements,
        "runner proof.artifact_measurements",
        non_empty=True,
    )
    seen: Set[str] = set()
    total_bytes = 0
    validated: list[Mapping[str, Any]] = []
    for index, raw_measurement in enumerate(measurements):
        context = f"runner proof.artifact_measurements[{index}]"
        measurement = _require_mapping(raw_measurement, context)
        _exact_keys(
            measurement,
            {"filename", "component", "bytes", "sha256"},
            context=context,
        )
        filename = _require_string(measurement["filename"], f"{context}.filename")
        if Path(filename).name != filename or filename in seen:
            raise ContractError("runner resource artifact filenames must be unique basenames")
        seen.add(filename)
        if measurement["component"] not in ARTIFACT_COMPONENTS:
            raise ContractError(f"{context}.component is invalid")
        total_bytes += _require_nonnegative_int(measurement["bytes"], f"{context}.bytes")
        _require_sha256(measurement["sha256"], f"{context}.sha256")
        validated.append(measurement)
    if total_bytes != runtime["total_resource_bytes"]:
        raise ContractError("runner resource artifact bytes must equal total_resource_bytes")
    return validated


def _validate_telemetry(
    telemetry: Any,
    *,
    runtime: Mapping[str, Any],
    protocol: Mapping[str, Any],
    expected_clip_order: Sequence[str],
) -> None:
    value = _require_mapping(telemetry, "runner proof.telemetry")
    _exact_keys(
        value,
        {
            "sample_interval_ms",
            "samples",
            "summary_sha256",
            "loop_count",
            "successful_loop_count",
            "total_decoded_clip_count",
            "loop_proofs",
        },
        context="runner proof.telemetry",
    )
    interval_ms = _require_nonnegative_int(
        value["sample_interval_ms"], "runner proof.telemetry.sample_interval_ms"
    )
    if interval_ms != protocol["telemetry"]["sample_interval_ms"]:
        raise ContractError("runner telemetry interval must match the preregistered protocol")
    clip_count = len(expected_clip_order)
    if clip_count < 1:
        raise ContractError("runner loop proof requires a non-empty frozen clip order")
    expected_order_sha256 = canonical_sha256(list(expected_clip_order))
    loop_proofs = _require_list(
        value["loop_proofs"],
        "runner proof.telemetry.loop_proofs",
        non_empty=True,
    )
    loop_end_ns: list[int] = []
    loop_success: list[bool] = []
    previous_loop_end: Optional[int] = None
    for index, raw_loop in enumerate(loop_proofs, start=1):
        context = f"runner proof.telemetry.loop_proofs[{index - 1}]"
        loop = _require_mapping(raw_loop, context)
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
            context=context,
        )
        if _require_nonnegative_int(loop["loop_index"], f"{context}.loop_index") != index:
            raise ContractError("runner loop proof indices must be contiguous from one")
        start_ns = _require_nonnegative_monotonic_ns(
            loop["monotonic_start_ns"],
            f"{context}.monotonic_start_ns",
        )
        end_ns = _require_nonnegative_monotonic_ns(
            loop["monotonic_end_ns"],
            f"{context}.monotonic_end_ns",
        )
        if start_ns >= end_ns or (
            previous_loop_end is not None and start_ns < previous_loop_end
        ):
            raise ContractError("runner loop proof clocks must be ordered and non-overlapping")
        previous_loop_end = end_ns
        loop_end_ns.append(end_ns)
        order_sha256 = _require_sha256(
            loop["ordered_clip_ids_sha256"],
            f"{context}.ordered_clip_ids_sha256",
        )
        if order_sha256 != expected_order_sha256:
            raise ContractError(
                "runner loop proof clip order must match the frozen decoder order"
            )
        if _require_nonnegative_int(loop["clip_count"], f"{context}.clip_count") != clip_count:
            raise ContractError(
                "runner loop proof clip count must cover the complete frozen clip set"
            )
        cumulative = _require_nonnegative_int(
            loop["cumulative_decoded_clip_count"],
            f"{context}.cumulative_decoded_clip_count",
        )
        if cumulative != index * clip_count:
            raise ContractError(
                "runner loop proof cumulative decoded clip count is inconsistent"
            )
        if not isinstance(loop["successful"], bool):
            raise ContractError(f"{context}.successful must be boolean")
        loop_success.append(loop["successful"])

    loop_count = _require_nonnegative_int(
        value["loop_count"], "runner proof.telemetry.loop_count"
    )
    successful_loop_count = _require_nonnegative_int(
        value["successful_loop_count"],
        "runner proof.telemetry.successful_loop_count",
    )
    if loop_count != len(loop_proofs):
        raise ContractError("runner loop proof count must match the loop summary")
    if loop_count < protocol["telemetry"]["minimum_decode_loops"]:
        raise ContractError("runner loop count is below the preregistered non-empty minimum")
    if successful_loop_count != sum(loop_success):
        raise ContractError(
            "runner successful loop count must match the per-loop proof outcomes"
        )
    total_decoded = _require_nonnegative_int(
        value["total_decoded_clip_count"],
        "runner proof.telemetry.total_decoded_clip_count",
    )
    if total_decoded != loop_count * clip_count:
        raise ContractError(
            "runner total decoded clip count must equal complete loops times frozen clips"
        )

    samples = _require_list(value["samples"], "runner proof.telemetry.samples", non_empty=True)
    previous_ns: Optional[int] = None
    previous_completed = 0
    previous_successful = 0
    previous_completed_clips = 0
    previous_active_progress = 0
    peak_rss = 0
    peak_thermal = 0
    expected_delta_ns = interval_ms * 1_000_000
    absolute_tolerance_ns = (
        protocol["telemetry"]["sample_interval_tolerance_ms"] * 1_000_000
    )
    relative_tolerance_ns = int(
        expected_delta_ns
        * protocol["telemetry"]["sample_interval_tolerance_fraction"]
    )
    allowed_delta_ns = min(absolute_tolerance_ns, relative_tolerance_ns)
    first_ns: Optional[int] = None
    last_ns: Optional[int] = None
    for index, raw_sample in enumerate(samples):
        context = f"runner proof.telemetry.samples[{index}]"
        sample = _require_mapping(raw_sample, context)
        _exact_keys(
            sample,
            {
                "monotonic_ns",
                "rss_bytes",
                "thermal_status",
                "completed_loops",
                "successful_loops",
                "heartbeat_index",
                "runner_alive",
                "active_decode_progress",
                "completed_clip_count",
            },
            context=context,
        )
        timestamp = _require_nonnegative_monotonic_ns(
            sample["monotonic_ns"], f"{context}.monotonic_ns"
        )
        if previous_ns is not None:
            actual_delta_ns = timestamp - previous_ns
            if (
                actual_delta_ns <= 0
                or abs(actual_delta_ns - expected_delta_ns)
                > allowed_delta_ns
            ):
                raise ContractError(
                    "runner telemetry interval exceeds the frozen bounded jitter"
                )
        if first_ns is None:
            first_ns = timestamp
        last_ns = timestamp
        previous_ns = timestamp
        rss = _require_nonnegative_int(sample["rss_bytes"], f"{context}.rss_bytes")
        thermal = _require_nonnegative_int(sample["thermal_status"], f"{context}.thermal_status")
        if thermal > 6:
            raise ContractError("runner telemetry thermal status must be between 0 and 6")
        completed = _require_nonnegative_int(
            sample["completed_loops"], f"{context}.completed_loops"
        )
        successful = _require_nonnegative_int(
            sample["successful_loops"], f"{context}.successful_loops"
        )
        heartbeat_index = _require_nonnegative_int(
            sample["heartbeat_index"], f"{context}.heartbeat_index"
        )
        if heartbeat_index != index:
            raise ContractError(
                "runner telemetry heartbeat indices must be contiguous from zero"
            )
        if sample["runner_alive"] is not True:
            raise ContractError(
                "runner telemetry runner_alive must be true at every heartbeat"
            )
        active_progress = _require_nonnegative_int(
            sample["active_decode_progress"],
            f"{context}.active_decode_progress",
        )
        completed_clips = _require_nonnegative_int(
            sample["completed_clip_count"],
            f"{context}.completed_clip_count",
        )
        if (
            completed < previous_completed
            or successful < previous_successful
            or successful > completed
        ):
            raise ContractError("runner telemetry loop counters must be monotonic and consistent")
        if index > 0 and active_progress <= previous_active_progress:
            raise ContractError(
                "every runner telemetry interval must show active decode progress"
            )
        if (
            completed_clips < previous_completed_clips
            or completed_clips > total_decoded
        ):
            raise ContractError(
                "runner telemetry completed clip count must be monotonic "
                "and within the loop proof"
            )
        expected_completed = sum(end_ns <= timestamp for end_ns in loop_end_ns)
        expected_successful = sum(
            end_ns <= timestamp and successful_loop
            for end_ns, successful_loop in zip(loop_end_ns, loop_success)
        )
        if completed != expected_completed or successful != expected_successful:
            raise ContractError(
                "runner telemetry loop counters must match loop completion proofs"
            )
        if completed_clips < completed * clip_count:
            raise ContractError(
                "runner telemetry completed clip count is below completed loop coverage"
            )
        previous_completed = completed
        previous_successful = successful
        previous_completed_clips = completed_clips
        previous_active_progress = active_progress
        peak_rss = max(peak_rss, rss)
        peak_thermal = max(peak_thermal, thermal)
    if canonical_sha256(samples) != value["summary_sha256"]:
        raise ContractError("runner telemetry summary hash does not match its samples")
    _require_sha256(value["summary_sha256"], "runner proof.telemetry.summary_sha256")
    if previous_completed != loop_count or previous_successful != successful_loop_count:
        raise ContractError("runner loop summary must match the final telemetry sample")
    if previous_completed_clips != total_decoded:
        raise ContractError(
            "runner final telemetry completed clip count must match the loop summary"
        )
    if peak_rss != runtime["peak_rss_bytes"]:
        raise ContractError("runner telemetry peak RSS must match runtime peak_rss_bytes")
    if peak_thermal != runtime["stability"]["max_thermal_status"]:
        raise ContractError("runner telemetry thermal peak must match runtime stability")
    if first_ns is None or last_ns is None:
        raise ContractError("runner telemetry samples must not be empty")
    sampled_seconds = (last_ns - first_ns) / 1_000_000_000
    reported_seconds = float(runtime["stability"]["duration_seconds"])
    if not math.isclose(sampled_seconds, reported_seconds, rel_tol=0, abs_tol=1e-9):
        raise ContractError(
            "runner telemetry duration is inconsistent with its monotonic samples"
        )
    if loop_proofs[0]["monotonic_start_ns"] < first_ns or loop_end_ns[-1] > last_ns:
        raise ContractError("runner loop proof clocks must remain within telemetry duration")


def _validate_runner_proof(
    proof: Any,
    *,
    runtime: Mapping[str, Any],
    manifest: Mapping[str, Any],
    protocol: Mapping[str, Any],
    expected_clip_order: Sequence[str],
) -> None:
    value = _require_mapping(proof, "runner proof")
    _exact_keys(
        value,
        {
            "proof_version",
            "capture_mode",
            "trusted_runner_status",
            "device_profile_sha256",
            "runner_build_sha256",
            "clock_source",
            "artifact_measurements",
            "artifact_set_sha256",
            "telemetry",
            "proof_sha256",
        },
        context="runner proof",
    )
    if value["proof_version"] != "trusted-android-runner-proof-v1":
        raise ContractError("runner proof version is unsupported")
    if value["capture_mode"] != "trusted_android_runner":
        raise ContractError("runner proof must come from the trusted Android runner")
    if value["trusted_runner_status"] != "phase_b_required":
        raise ContractError(
            "Phase A cannot verify trusted runner attestation; status must be phase_b_required"
        )
    device_sha256 = _require_sha256(
        value["device_profile_sha256"], "runner proof.device_profile_sha256"
    )
    if (
        device_sha256 != manifest["target_device"]["device_profile_sha256"]
        or device_sha256 != protocol["target_device"]["device_profile_sha256"]
    ):
        raise ContractError("runner proof target device does not match the frozen contracts")
    _require_sha256(value["runner_build_sha256"], "runner proof.runner_build_sha256")
    if value["clock_source"] != "android_elapsed_realtime_nanos":
        raise ContractError("runner monotonic clock source must be android_elapsed_realtime_nanos")
    measurements = _validate_artifact_measurements(
        value["artifact_measurements"],
        runtime=runtime,
    )
    expected_artifact_hash = canonical_sha256(measurements)
    if value["artifact_set_sha256"] != expected_artifact_hash:
        raise ContractError("runner resource artifact-set hash does not match measurements")
    _require_sha256(value["artifact_set_sha256"], "runner proof.artifact_set_sha256")
    _validate_telemetry(
        value["telemetry"],
        runtime=runtime,
        protocol=protocol,
        expected_clip_order=expected_clip_order,
    )
    proof_without_hash = {key: item for key, item in value.items() if key != "proof_sha256"}
    if canonical_sha256(proof_without_hash) != value["proof_sha256"]:
        raise ContractError("runner proof hash does not match the measured proof")
    _require_sha256(value["proof_sha256"], "runner proof.proof_sha256")


def validate_model_output(
    data: Any,
    public_plan: Mapping[str, Any],
    manifest: Any,
    dataset_protocol: Mapping[str, Any],
    *,
    exploration_evidence: Optional[VerifiedExplorationEvidence] = None,
    exploration_manifest: Optional[Mapping[str, Any]] = None,
    exploration_public_plan: Optional[Mapping[str, Any]] = None,
    exploration_dataset_protocol: Optional[Mapping[str, Any]] = None,
) -> dict[str, Any]:
    protocol_document = validate_dataset_protocol(dataset_protocol)
    manifest_document = _manifest_document_for_profile(
        manifest,
        protocol_document["profile"],
    )
    plan = validate_public_plan(
        public_plan,
        manifest,
        exploration_evidence=exploration_evidence,
    )
    protocol = validate_dataset_protocol(
        protocol_document,
        manifest,
        exploration_evidence=exploration_evidence,
        exploration_manifest=exploration_manifest,
        exploration_public_plan=exploration_public_plan,
        exploration_dataset_protocol=exploration_dataset_protocol,
    )
    if plan["protocol_profile"] != protocol["profile"]:
        raise ContractError(
            "public blind plan protocol profile does not match the dataset protocol"
        )
    if (
        canonical_sha256(protocol)
        != plan["contract_fingerprints"]["dataset_protocol_sha256"]
    ):
        raise ContractError("dataset protocol fingerprint does not match the public plan")
    if protocol["profile"] == "production_confirmation":
        (
            actual_exploration_manifest,
            actual_exploration_public_plan,
            _actual_exploration_protocol,
            _actual_exploration_commitment,
        ) = _revalidate_verified_exploration(exploration_evidence)
        actual_exploration_plan = _validate_public_plan_document(
            actual_exploration_public_plan,
            actual_exploration_manifest,
        )
        comparable_fields = (
            "cohort_id",
            "cohort_model_ids_sha256",
            "formal_artifact_set_sha256",
            "formal_risk_acceptance_set_sha256",
            "formal_model_cohort_sha256",
        )
        if any(
            plan[field] != actual_exploration_plan[field]
            for field in comparable_fields
        ) or any(
            plan["contract_fingerprints"][field]
            != actual_exploration_plan["contract_fingerprints"][field]
            for field in (
                "normalization_sha256",
                "selection_config_sha256",
                "registry_sha256",
            )
        ):
            raise ContractError(
                "production and exploration plans must freeze the same "
                "formal cohort, normalization, selection config, and registry"
            )
    output = dict(_require_mapping(data, "model output"))
    _reject_answer_keys(
        {key: value for key, value in output.items() if key != "reference_accessed"},
        "model output",
    )
    _exact_keys(
        output,
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
        context="model output",
    )
    _require_version(output, "model output")
    _require_anonymous_id(output["run_id"], "run", "model output.run_id")
    alias = _require_string(output["model_alias"], "model output.model_alias")
    if not _MODEL_ALIAS.fullmatch(alias):
        raise ContractError("model output.model_alias must match M plus three digits")
    if output["run_id"] != plan["run_id"]:
        raise ContractError("model output run_id does not match the blind plan")
    assignments = plan["assignments"]
    assignment = next(
        (item for item in assignments if isinstance(item, Mapping) and item.get("model_alias") == alias),
        None,
    )
    if assignment is None:
        raise ContractError("model alias is not present in the blind plan")
    if output["decoder_contract_id"] != DECODER_CONTRACT_ID:
        raise ContractError("model output must contain raw first-layer decoder output")
    if output["pcm_contract_id"] != manifest_document.get("pcm_contract_id"):
        raise ContractError("model output PCM contract differs from the manifest")
    if output["input_transform_id"] != INPUT_TRANSFORM_ID:
        raise ContractError("model-specific input transform is forbidden")
    expected_decoder_plan_sha256 = canonical_sha256(
        decoder_plan_document(
            manifest,
            plan,
            alias,
            exploration_evidence=exploration_evidence,
        )
    )
    if output["decoder_plan_sha256"] != expected_decoder_plan_sha256:
        raise ContractError("model output decoder plan fingerprint does not match")
    _require_sha256(output["decoder_plan_sha256"], "model output.decoder_plan_sha256")
    if output["reference_accessed"] is not False:
        raise ContractError("decoder must not access reference answers")

    manifest_clips = {
        clip["clip_id"]: clip for clip in manifest_document["clips"]
    }
    expected_order = assignment["clip_order"]

    predictions = _require_list(output["predictions"], "model output.predictions", non_empty=True)
    seen: Set[str] = set()
    for index, raw_prediction in enumerate(predictions):
        context = f"model output.predictions[{index}]"
        prediction = _require_mapping(raw_prediction, context)
        _reject_answer_keys(prediction, context)
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
            context=context,
        )
        clip_id = _require_anonymous_id(prediction["clip_id"], "clip", f"{context}.clip_id")
        if clip_id in seen:
            raise ContractError("model output contains duplicate clip_id")
        if clip_id not in manifest_clips:
            raise ContractError("model output contains an unknown clip_id")
        seen.add(clip_id)
        digest = _require_sha256(prediction["pcm_sha256"], f"{context}.pcm_sha256")
        if digest != manifest_clips[clip_id]["audio_sha256"]:
            raise ContractError("model output PCM hash differs from the canonical manifest PCM")
        payload_digest = _require_sha256(
            prediction["pcm_payload_sha256"], f"{context}.pcm_payload_sha256"
        )
        if payload_digest != manifest_clips[clip_id]["pcm_payload_sha256"]:
            raise ContractError(
                "model output PCM payload hash differs from the canonical manifest PCM"
            )
        hypothesis = _require_string(prediction["hypothesis"], f"{context}.hypothesis", non_empty=False)
        if len(hypothesis) > 100_000:
            raise ContractError(f"{context}.hypothesis exceeds the local scoring limit")
        if prediction["status"] not in {"ok", "error"}:
            raise ContractError(f"{context}.status is invalid")
        error_code = prediction["error_code"]
        if error_code not in {None, "oom", "crash", "decode_error"}:
            raise ContractError(f"{context}.error_code is invalid")
        if (prediction["status"] == "ok") != (error_code is None):
            raise ContractError(f"{context}.status and error_code are inconsistent")
    if seen != set(manifest_clips):
        raise ContractError("model output clip set must exactly match the manifest")
    actual_order = [prediction["clip_id"] for prediction in predictions]
    if actual_order != expected_order:
        raise ContractError("model output prediction order must exactly match the blind plan order")
    _revalidate_formal_snapshot_bindings(
        manifest,
        protocol["profile"],
        exploration_evidence=exploration_evidence,
    )
    return output


def validate_engineering_proof(
    data: Any,
    accuracy_output: Mapping[str, Any],
    public_plan: Mapping[str, Any],
    manifest: Any,
    dataset_protocol: Mapping[str, Any],
    *,
    exploration_evidence: Optional[VerifiedExplorationEvidence] = None,
    exploration_manifest: Optional[Mapping[str, Any]] = None,
    exploration_public_plan: Optional[Mapping[str, Any]] = None,
    exploration_dataset_protocol: Optional[Mapping[str, Any]] = None,
) -> dict[str, Any]:
    """Validate private runner evidence without exposing it to the scorer."""
    protocol_document = validate_dataset_protocol(dataset_protocol)
    manifest_document = _manifest_document_for_profile(
        manifest,
        protocol_document["profile"],
    )
    plan = validate_public_plan(
        public_plan,
        manifest,
        exploration_evidence=exploration_evidence,
    )
    protocol = validate_dataset_protocol(
        protocol_document,
        manifest,
        exploration_evidence=exploration_evidence,
        exploration_manifest=exploration_manifest,
        exploration_public_plan=exploration_public_plan,
        exploration_dataset_protocol=exploration_dataset_protocol,
    )
    output = validate_model_output(
        accuracy_output,
        plan,
        manifest,
        protocol,
        exploration_evidence=exploration_evidence,
        exploration_manifest=exploration_manifest,
        exploration_public_plan=exploration_public_plan,
        exploration_dataset_protocol=exploration_dataset_protocol,
    )
    proof = dict(_require_mapping(data, "engineering proof"))
    _exact_keys(
        proof,
        {
            "schema_version",
            "run_id",
            "dataset_id",
            "model_alias",
            "decoder_contract_id",
            "pcm_contract_id",
            "input_transform_id",
            "decoder_plan_sha256",
            "accuracy_output_sha256",
            "clip_measurements",
            "runtime",
            "runner_proof",
        },
        context="engineering proof",
    )
    _require_version(proof, "engineering proof")
    if (
        proof["run_id"] != output["run_id"]
        or proof["dataset_id"] != plan["dataset_id"]
        or proof["model_alias"] != output["model_alias"]
    ):
        raise ContractError("engineering proof identity does not match accuracy output")
    if (
        proof["decoder_contract_id"] != DECODER_CONTRACT_ID
        or proof["pcm_contract_id"] != manifest_document["pcm_contract_id"]
        or proof["input_transform_id"] != INPUT_TRANSFORM_ID
        or proof["decoder_plan_sha256"] != output["decoder_plan_sha256"]
    ):
        raise ContractError("engineering proof decoder and PCM contracts do not match")
    _require_sha256(
        proof["decoder_plan_sha256"],
        "engineering proof.decoder_plan_sha256",
    )
    output_sha256 = _require_sha256(
        proof["accuracy_output_sha256"],
        "engineering proof.accuracy_output_sha256",
    )
    if output_sha256 != canonical_sha256(output):
        raise ContractError("engineering proof does not bind the frozen accuracy output")

    assignment = next(
        item
        for item in plan["assignments"]
        if item["model_alias"] == output["model_alias"]
    )
    expected_order = assignment["clip_order"]
    predictions_by_id = {
        prediction["clip_id"]: prediction for prediction in output["predictions"]
    }
    manifest_clips = {
        clip["clip_id"]: clip for clip in manifest_document["clips"]
    }
    measurements = _require_list(
        proof["clip_measurements"],
        "engineering proof.clip_measurements",
        non_empty=True,
    )
    actual_order: list[str] = []
    for index, raw_measurement in enumerate(measurements):
        context = f"engineering proof.clip_measurements[{index}]"
        measurement = _require_mapping(raw_measurement, context)
        _exact_keys(
            measurement,
            {
                "clip_id",
                "pcm_sha256",
                "pcm_payload_sha256",
                "stop_to_final_ms",
                "runner_evidence",
                "run_state",
                "status",
                "error_code",
            },
            context=context,
        )
        clip_id = _require_anonymous_id(
            measurement["clip_id"],
            "clip",
            f"{context}.clip_id",
        )
        if clip_id not in predictions_by_id:
            raise ContractError("engineering proof contains an unknown clip")
        actual_order.append(clip_id)
        prediction = predictions_by_id[clip_id]
        clip = manifest_clips[clip_id]
        for field in ("pcm_sha256", "pcm_payload_sha256", "status", "error_code"):
            if measurement[field] != prediction[field]:
                raise ContractError(
                    "engineering proof clip state does not match accuracy output"
                )
        if measurement["pcm_sha256"] != clip["audio_sha256"] or measurement[
            "pcm_payload_sha256"
        ] != clip["pcm_payload_sha256"]:
            raise ContractError("engineering proof PCM hashes differ from the manifest")
        stop_to_final_ms = _require_nonnegative_number(
            measurement["stop_to_final_ms"],
            f"{context}.stop_to_final_ms",
        )
        _validate_runner_evidence(
            measurement["runner_evidence"],
            clip=clip,
            stop_to_final_ms=stop_to_final_ms,
            context=f"{context}.runner_evidence",
        )
        if measurement["run_state"] not in {"cold", "warm"}:
            raise ContractError(f"{context}.run_state is invalid")
    if actual_order != expected_order:
        raise ContractError(
            "engineering proof clip order must exactly match the frozen decoder order"
        )

    _validate_runtime(proof["runtime"])
    _validate_runner_proof(
        proof["runner_proof"],
        runtime=proof["runtime"],
        manifest=manifest_document,
        protocol=protocol,
        expected_clip_order=expected_order,
    )
    _revalidate_formal_snapshot_bindings(
        manifest,
        protocol["profile"],
        exploration_evidence=exploration_evidence,
    )
    return proof
