from __future__ import annotations

import hashlib
import json
import math
import struct
import wave
from pathlib import Path
from typing import Any

from kittyecho_asr_bench.blinding import create_blind_bundle
from kittyecho_asr_bench.contracts import canonical_sha256, decoder_plan_id
from kittyecho_asr_bench.engineering import build_engineering_report
from kittyecho_asr_bench.metrics import score_model
from kittyecho_asr_bench.normalization import NormalizationConfig
from kittyecho_asr_bench.selection import SelectionConfig


PCM_CONTRACT_ID = "pcm16k-mono-s16le-v1"
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
DEVICE_PROFILE_SHA256 = "d" * 64
RUNNER_BUILD_SHA256 = "e" * 64
ARTIFACT_SHA256 = "a" * 64


def write_wav(
    path: Path,
    *,
    sample_rate: int = 16_000,
    channels: int = 1,
    sample_width: int = 2,
    frame_count: int = 160,
) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    frames = bytearray()
    for index in range(frame_count):
        sample = int(1_000 * math.sin(2 * math.pi * 440 * index / sample_rate))
        encoded = struct.pack("<h", sample)
        frames.extend(encoded * channels if sample_width == 2 else b"\x00" * sample_width * channels)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(channels)
        output.setsampwidth(sample_width)
        output.setframerate(sample_rate)
        output.writeframes(bytes(frames))
    return hashlib.sha256(path.read_bytes()).hexdigest()


def wav_payload_facts(path: Path) -> tuple[str, int]:
    with wave.open(str(path), "rb") as source:
        payload = source.readframes(source.getnframes())
    return hashlib.sha256(payload).hexdigest(), len(payload)


def manifest_for(audio_path: Path, audio_sha256: str, *, clip_count: int = 2) -> dict[str, Any]:
    clips = []
    for index in range(clip_count):
        if index == 0:
            clip_path = audio_path
            clip_sha256 = audio_sha256
        else:
            clip_path = audio_path.with_name(f"{audio_path.stem}-{index}{audio_path.suffix}")
            clip_sha256 = write_wav(clip_path, frame_count=160 + index)
        payload_sha256, payload_bytes = wav_payload_facts(clip_path)
        clips.append(
            {
                "clip_id": f"clip_{index:012x}",
                "audio_path": str(clip_path),
                "audio_sha256": clip_sha256,
                "pcm_payload_sha256": payload_sha256,
                "pcm_payload_bytes": payload_bytes,
                "speaker_cluster_id": f"speaker_{index:012x}",
                "session_cluster_id": f"session_{index:012x}",
                "sentence_id": f"sentence_{index:012x}",
                "prompt_kind": "common_anchor" if index % 2 == 0 else "coverage",
                "session_index": 1,
                "hours_since_previous_session": None,
                "speech_duration_ms": 2_000.0,
                "speech_onset_ms": 100.0,
                "accent_natural": True,
                "capture_attempt": {
                    "attempt_index": 1,
                    "technical_rerecord": False,
                    "replaces_clip_id": None,
                    "exclusion_reason_code": None,
                    "take_selection_policy": "first_valid_take",
                },
                "source": {
                    "kind": "local_target_device_recording",
                    "local_only": True,
                    "cloud_origin": False,
                },
                "consent": {
                    "obtained": True,
                    "scope": "local_asr_benchmark",
                },
                "privacy": {
                    "contains_sensitive_content": False,
                    "upload_permitted": False,
                },
                "conditions": (
                    [
                        "quiet_near",
                        "fast",
                        "immediate_start",
                        "short",
                        "normal_short",
                        "code_switch",
                    ]
                    if index == 0
                    else ["noise", "slow", "long", "proper_name_number", "accent"]
                ),
            }
        )
    return {
        "schema_version": "1.0",
        "dataset_id": "dataset_0123456789ab",
        "pcm_contract_id": PCM_CONTRACT_ID,
        "target_device": {
            "platform": "android",
            "physical_device": True,
            "abi": "arm64-v8a",
            "device_profile_sha256": DEVICE_PROFILE_SHA256,
        },
        "dataset_usage": {
            "recording_purpose": "development_fixture",
            "used_for_model_tuning": False,
        },
        "speaker_cohorts": [],
        "frozen_exploration_pcm_set": None,
        "clips": clips,
    }


def references_for(*, clip_count: int = 2) -> dict[str, Any]:
    references = [
        {
            "clip_id": "clip_000000000000",
            "text": "小猫AI二零二六",
            "annotations": [
                {
                    "annotation_id": "ann_000000000001",
                    "kind": "proper_name",
                    "text": "小猫",
                    "occurrence_index": 0,
                },
                {
                    "annotation_id": "ann_000000000002",
                    "kind": "number",
                    "text": "二零二六",
                    "occurrence_index": 0,
                },
            ],
        },
        {
            "clip_id": "clip_000000000001",
            "text": "会议在三点开始",
            "annotations": [
                {
                    "annotation_id": "ann_000000000003",
                    "kind": "number",
                    "text": "三",
                    "occurrence_index": 0,
                },
            ],
        },
    ]
    return {
        "schema_version": "1.0",
        "dataset_id": "dataset_0123456789ab",
        "references": references[:clip_count],
    }


def runtime_metrics(
    *,
    resource_bytes: int = 200_000_000,
    peak_rss_bytes: int = 400_000_000,
    oom_count: int = 0,
    crash_count: int = 0,
    stability_seconds: float = 600.0,
    stability_failures: int = 0,
    stability_completed: bool = True,
    max_thermal_status: int = 2,
) -> dict[str, Any]:
    return {
        "total_resource_bytes": resource_bytes,
        "peak_rss_bytes": peak_rss_bytes,
        "oom_count": oom_count,
        "crash_count": crash_count,
        "cold_start_ms": [1_200.0],
        "warm_start_ms": [200.0, 220.0],
        "stability": {
            "duration_seconds": stability_seconds,
            "failure_count": stability_failures,
            "completed": stability_completed,
            "max_thermal_status": max_thermal_status,
        },
    }


def _output_pair_for(
    manifest: dict[str, Any],
    *,
    alias: str = "M001",
    hypotheses: list[str] | None = None,
    latencies_ms: list[float] | None = None,
    runtime: dict[str, Any] | None = None,
    public_plan: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    hypotheses = hypotheses or ["小猫AI二零二六", "会议三点开始"]
    latencies_ms = latencies_ms or [400.0, 500.0]
    predictions = []
    if not (len(manifest["clips"]) == len(hypotheses) == len(latencies_ms)):
        raise ValueError("clips, hypotheses, and latencies must have equal lengths")
    values_by_clip = {
        clip["clip_id"]: (clip, hypothesis, latency)
        for clip, hypothesis, latency in zip(manifest["clips"], hypotheses, latencies_ms)
    }
    if public_plan is None:
        clip_order = [clip["clip_id"] for clip in manifest["clips"]]
        run_id = "run_0123456789ab"
    else:
        assignment = next(
            item for item in public_plan["assignments"] if item["model_alias"] == alias
        )
        clip_order = assignment["clip_order"]
        run_id = public_plan["run_id"]
    clips_by_id = {clip["clip_id"]: clip for clip in manifest["clips"]}
    decoder_plan = {
        "schema_version": "1.0",
        "run_id": run_id,
        "dataset_id": manifest["dataset_id"],
        "model_alias": alias,
        "pcm_contract_id": manifest["pcm_contract_id"],
        "decoder_contract_id": "first-layer-raw-v1",
        "input_transform_id": "canonical-pcm-direct-v1",
        "public_plan_sha256": canonical_sha256(public_plan),
        "manifest_sha256": canonical_sha256(manifest),
        "clips": [
            {
                "clip_id": clip_id,
                "audio_path": clips_by_id[clip_id]["audio_path"],
                "wav_file_sha256": clips_by_id[clip_id]["audio_sha256"],
                "pcm_payload_sha256": clips_by_id[clip_id]["pcm_payload_sha256"],
                "pcm_payload_bytes": clips_by_id[clip_id]["pcm_payload_bytes"],
            }
            for clip_id in clip_order
        ],
    }
    decoder_plan["plan_id"] = decoder_plan_id(decoder_plan)
    for clip_id in clip_order:
        clip, hypothesis, latency = values_by_clip[clip_id]
        monotonic_start_ns = 1_000_000_000 + len(predictions) * 10_000_000_000
        monotonic_stop_ns = monotonic_start_ns + 1_000_000_000
        monotonic_final_ns = monotonic_stop_ns + int(latency * 1_000_000)
        predictions.append(
            {
                "clip_id": clip["clip_id"],
                "pcm_sha256": clip["audio_sha256"],
                "pcm_payload_sha256": clip["pcm_payload_sha256"],
                "hypothesis": hypothesis,
                "stop_to_final_ms": latency,
                "runner_evidence": {
                    "wav_file_sha256_before": clip["audio_sha256"],
                    "wav_file_sha256_after": clip["audio_sha256"],
                    "pcm_payload_sha256": clip["pcm_payload_sha256"],
                    "pcm_payload_bytes_read": clip["pcm_payload_bytes"],
                    "monotonic_start_ns": monotonic_start_ns,
                    "monotonic_stop_ns": monotonic_stop_ns,
                    "monotonic_final_ns": monotonic_final_ns,
                },
                "run_state": "warm",
                "status": "ok",
                "error_code": None,
            }
        )
    runtime_value = runtime or runtime_metrics()
    duration_seconds = float(runtime_value["stability"]["duration_seconds"])
    interval_ms = 5_000
    sample_count = math.floor(duration_seconds * 1_000 / interval_ms) + 1
    loop_count = 2
    clip_count = len(clip_order)
    duration_ns = int(duration_seconds * 1_000_000_000)
    total_decoded_clip_count = loop_count * clip_count
    loop_proofs = [
        {
            "loop_index": index,
            "monotonic_start_ns": (
                (index - 1) * duration_ns // loop_count
            ),
            "monotonic_end_ns": index * duration_ns // loop_count,
            "ordered_clip_ids_sha256": canonical_sha256(clip_order),
            "clip_count": clip_count,
            "cumulative_decoded_clip_count": index * clip_count,
            "successful": True,
        }
        for index in range(1, loop_count + 1)
    ]
    samples = [
        {
            "monotonic_ns": index * interval_ms * 1_000_000,
            "rss_bytes": runtime_value["peak_rss_bytes"],
            "thermal_status": runtime_value["stability"]["max_thermal_status"],
            "completed_loops": sum(
                loop["monotonic_end_ns"]
                <= index * interval_ms * 1_000_000
                for loop in loop_proofs
            ),
            "successful_loops": sum(
                loop["monotonic_end_ns"]
                <= index * interval_ms * 1_000_000
                for loop in loop_proofs
            ),
            "heartbeat_index": index,
            "runner_alive": True,
            "active_decode_progress": index * 1_000,
            "completed_clip_count": (
                index * total_decoded_clip_count // (sample_count - 1)
            ),
        }
        for index in range(sample_count)
    ]
    artifact_measurements = [
        {
            "filename": "model.bin",
            "component": "weights",
            "bytes": runtime_value["total_resource_bytes"],
            "sha256": ARTIFACT_SHA256,
        }
    ]
    proof_without_hash = {
        "proof_version": "trusted-android-runner-proof-v1",
        "capture_mode": "trusted_android_runner",
        "trusted_runner_status": "phase_b_required",
        "device_profile_sha256": manifest["target_device"]["device_profile_sha256"],
        "runner_build_sha256": RUNNER_BUILD_SHA256,
        "clock_source": "android_elapsed_realtime_nanos",
        "artifact_measurements": artifact_measurements,
        "artifact_set_sha256": canonical_sha256(artifact_measurements),
        "telemetry": {
            "sample_interval_ms": interval_ms,
            "samples": samples,
            "summary_sha256": canonical_sha256(samples),
            "loop_count": loop_count,
            "successful_loop_count": loop_count,
            "total_decoded_clip_count": total_decoded_clip_count,
            "loop_proofs": loop_proofs,
        },
    }
    accuracy_output = {
        "schema_version": "1.0",
        "run_id": run_id,
        "model_alias": alias,
        "decoder_contract_id": "first-layer-raw-v1",
        "pcm_contract_id": PCM_CONTRACT_ID,
        "input_transform_id": "canonical-pcm-direct-v1",
        "decoder_plan_sha256": canonical_sha256(decoder_plan),
        "reference_accessed": False,
        "predictions": [
            {
                key: prediction[key]
                for key in (
                    "clip_id",
                    "pcm_sha256",
                    "pcm_payload_sha256",
                    "hypothesis",
                    "status",
                    "error_code",
                )
            }
            for prediction in predictions
        ],
    }
    engineering_proof = {
        "schema_version": "1.0",
        "run_id": run_id,
        "dataset_id": manifest["dataset_id"],
        "model_alias": alias,
        "decoder_contract_id": "first-layer-raw-v1",
        "pcm_contract_id": PCM_CONTRACT_ID,
        "input_transform_id": "canonical-pcm-direct-v1",
        "decoder_plan_sha256": canonical_sha256(decoder_plan),
        "accuracy_output_sha256": canonical_sha256(accuracy_output),
        "clip_measurements": [
            {
                key: prediction[key]
                for key in (
                    "clip_id",
                    "pcm_sha256",
                    "pcm_payload_sha256",
                    "stop_to_final_ms",
                    "runner_evidence",
                    "run_state",
                    "status",
                    "error_code",
                )
            }
            for prediction in predictions
        ],
        "runtime": runtime_value,
        "runner_proof": {
            **proof_without_hash,
            "proof_sha256": canonical_sha256(proof_without_hash),
        },
    }
    return accuracy_output, engineering_proof


def model_output_for(
    manifest: dict[str, Any],
    *,
    alias: str = "M001",
    hypotheses: list[str] | None = None,
    latencies_ms: list[float] | None = None,
    runtime: dict[str, Any] | None = None,
    public_plan: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return _output_pair_for(
        manifest,
        alias=alias,
        hypotheses=hypotheses,
        latencies_ms=latencies_ms,
        runtime=runtime,
        public_plan=public_plan,
    )[0]


def engineering_proof_for(
    manifest: dict[str, Any],
    *,
    alias: str = "M001",
    hypotheses: list[str] | None = None,
    latencies_ms: list[float] | None = None,
    runtime: dict[str, Any] | None = None,
    public_plan: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return _output_pair_for(
        manifest,
        alias=alias,
        hypotheses=hypotheses,
        latencies_ms=latencies_ms,
        runtime=runtime,
        public_plan=public_plan,
    )[1]


def scored_pair_for(
    manifest: dict[str, Any],
    references: dict[str, Any],
    public_plan: dict[str, Any],
    protocol: dict[str, Any],
    selection_config: dict[str, Any],
    *,
    alias: str = "M001",
    hypotheses: list[str] | None = None,
    latencies_ms: list[float] | None = None,
    runtime: dict[str, Any] | None = None,
    normalization: NormalizationConfig | None = None,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]]:
    output, proof = _output_pair_for(
        manifest,
        alias=alias,
        hypotheses=hypotheses,
        latencies_ms=latencies_ms,
        runtime=runtime,
        public_plan=public_plan,
    )
    report = score_model(
        manifest,
        references,
        output,
        normalization or NormalizationConfig(),
        public_plan,
        protocol,
        selection_config,
    )
    engineering_report = build_engineering_report(
        proof,
        output,
        report,
        public_plan,
        manifest,
        protocol,
    )
    return output, proof, report, engineering_report


def dataset_protocol_for(
    manifest: dict[str, Any],
    *,
    minimum_unique_clips_per_condition: int = 1,
    minimum_speaker_clusters: int = 1,
    minimum_session_clusters: int = 1,
    minimum_joint_clusters: int = 1,
) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "protocol_id": "protocol_0123456789ab",
        "approval_status": "approved",
        "profile": "development_fixture",
        "selection_authority": "development_screening_only",
        "target_device": dict(manifest["target_device"]),
        "required_conditions": list(REQUIRED_SELECTION_CONDITIONS),
        "minimum_unique_clips_per_condition": {
            condition: minimum_unique_clips_per_condition
            for condition in REQUIRED_SELECTION_CONDITIONS
        },
        "minimum_speaker_clusters": minimum_speaker_clusters,
        "minimum_session_clusters": minimum_session_clusters,
        "minimum_joint_clusters": minimum_joint_clusters,
        "slice_cluster_minimums": {
            condition: {
                "minimum_speakers": 0,
                "minimum_sessions": 0,
            }
            for condition in REQUIRED_SELECTION_CONDITIONS
        },
        "preregistration": {
            "fixture_only": True,
        },
        "exploration_pcm_set_sha256": None,
        "required_source_kind": "local_target_device_recording",
        "unique_pcm_required": True,
        "bootstrap_unit": "speaker_session_cluster",
        "telemetry": {
            "sample_interval_ms": 5_000,
            "sample_interval_tolerance_ms": 250,
            "sample_interval_tolerance_fraction": 0.05,
            "minimum_duration_seconds": 600.0,
            "minimum_decode_loops": 2,
            "active_decode_progress_unit": "trusted_runner_progress_events_v1",
        },
    }


def selection_config_dict() -> dict[str, Any]:
    return SelectionConfig().to_dict()


def registry(
    *,
    model_ids: list[str] | None = None,
    resource_bytes: int = 200_000_000,
    license_state: str = "verified",
    formal_cohort: bool = False,
) -> dict[str, Any]:
    model_ids = model_ids or ["zipformer_baseline"]
    models = []
    for model_id in model_ids:
        models.append(
            {
                "model_id": model_id,
                "display_name": f"{model_id} test model",
                "role": "baseline" if model_id == "zipformer_baseline" else "first_batch_candidate",
                "family": "test_family",
                "official_source": {
                    "publisher": "k2-fsa",
                    "model_url": (
                        "https://huggingface.co/k2-fsa/test-model/tree/"
                        f"{'a' * 40}"
                    ),
                    "revision": "a" * 40,
                    "revision_kind": "commit",
                },
                "licenses": {
                    component: {
                        "spdx_id": "Apache-2.0",
                        "url": "https://www.apache.org/licenses/LICENSE-2.0",
                        "verification_state": license_state,
                        "notice": f"{component} license fixture.",
                    }
                    for component in (
                        "upstream_weights",
                        "conversion",
                        "runtime",
                        "tokenizer",
                        "notice",
                    )
                },
                "estimated_total_resource_bytes": resource_bytes,
                "download_status": "already_present_hash_verified",
                "runtime": {
                    "framework": "sherpa-onnx-test",
                    "format": "onnx-int8",
                    "supported_abis": ["arm64-v8a"],
                    "android_runtime_compatibility": "locally_verified",
                    "preprocessor_contract": (
                        "model-internal features from canonical PCM"
                    ),
                },
                "evaluation_policy": {
                    "internal_evaluation_download": {
                        "clearance": "cleared",
                        "user_risk_acceptance": "not_required",
                        "restrictions": [
                            "repository_external_only",
                            "private_device_only",
                            "no_redistribution",
                            "no_upload",
                        ],
                    },
                    "production_distribution": {
                        "clearance": "blocked_pending_separate_review",
                    },
                },
                "artifacts": [
                    {
                        "filename": "model.bin",
                        "component": "weights",
                        "expected_bytes": resource_bytes,
                        "sha256": ARTIFACT_SHA256,
                        "verification_state": "locally_verified",
                        "source_url": (
                            "https://huggingface.co/k2-fsa/test-model/"
                            f"blob/{'a' * 40}/model.bin"
                        ),
                        "source_revision": "a" * 40,
                        "source_revision_kind": "commit",
                    }
                ],
                "provenance": {
                    "upstream_model_id": "k2-fsa/test-model",
                    "checkpoint_byte_equivalence": "verified",
                    "note": "Test fixture.",
                },
            }
        )
    formal_cohorts = []
    if formal_cohort:
        if "zipformer_baseline" not in model_ids:
            raise ValueError("formal cohort fixture requires zipformer_baseline")
        formal_cohorts.append(
            {
                "cohort_id": "first_batch_android_v1",
                "profiles": ["exploration", "production_confirmation"],
                "model_ids": list(model_ids),
                "baseline_model_id": "zipformer_baseline",
            }
        )
    return {
        "schema_version": "1.0",
        "formal_cohorts": formal_cohorts,
        "models": models,
    }


def blind_bundle_for(
    manifest: dict[str, Any],
    *,
    model_count: int = 4,
    protocol: dict[str, Any] | None = None,
    normalization: NormalizationConfig | None = None,
    selection_config: dict[str, Any] | None = None,
    registry_snapshot: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    model_ids = [f"model_{index}" for index in range(model_count)]
    protocol_value = protocol or dataset_protocol_for(manifest)
    normalization_value = normalization or NormalizationConfig()
    selection_value = selection_config or selection_config_dict()
    registry_value = registry_snapshot or registry(model_ids=model_ids)
    fingerprints = {
        "manifest_sha256": canonical_sha256(manifest),
        "dataset_protocol_sha256": canonical_sha256(protocol_value),
        "normalization_sha256": normalization_value.fingerprint(),
        "selection_config_sha256": canonical_sha256(selection_value),
        "registry_sha256": canonical_sha256(registry_value),
    }
    return create_blind_bundle(
        manifest,
        model_ids,
        seed=(1 << 127) + 123456789,
        contract_fingerprints=fingerprints,
        protocol_profile=protocol_value["profile"],
        cohort_id=(
            registry_value["formal_cohorts"][0]["cohort_id"]
            if protocol_value["profile"] in {"exploration", "production_confirmation"}
            and registry_value["formal_cohorts"]
            else "development_subset"
        ),
    )


def public_plan_for(
    manifest: dict[str, Any],
    *,
    model_count: int = 4,
    protocol: dict[str, Any] | None = None,
    normalization: NormalizationConfig | None = None,
    selection_config: dict[str, Any] | None = None,
    registry_snapshot: dict[str, Any] | None = None,
) -> dict[str, Any]:
    public_plan, _ = blind_bundle_for(
        manifest,
        model_count=model_count,
        protocol=protocol,
        normalization=normalization,
        selection_config=selection_config,
        registry_snapshot=registry_snapshot,
    )
    return public_plan


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
