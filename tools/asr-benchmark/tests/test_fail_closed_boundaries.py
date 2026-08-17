from __future__ import annotations

import copy
import math
import tempfile
import unittest
from pathlib import Path

from kittyecho_asr_bench.contracts import (
    ContractError,
    _require_list,
    _require_mapping,
    _require_nonnegative_int,
    _require_nonnegative_number,
    _require_string,
    build_dataset_protocol,
    canonical_sha256,
    load_json,
    validate_dataset_protocol,
    validate_engineering_proof,
    validate_model_output,
    validate_model_registry,
    validate_private_input_path,
    validate_private_map,
    validate_private_output_path,
    validate_public_plan,
    validate_recording_manifest,
    validate_reference_set,
)
from kittyecho_asr_bench.metrics import score_model, validate_score_report
from kittyecho_asr_bench.normalization import NormalizationConfig
from kittyecho_asr_bench.selection import SelectionConfig

from support import (
    blind_bundle_for,
    dataset_protocol_for,
    engineering_proof_for,
    manifest_for,
    model_output_for,
    references_for,
    registry,
    write_json,
    write_wav,
)


def changed(value: object, path: tuple[object, ...], replacement: object) -> object:
    result = copy.deepcopy(value)
    target = result
    for part in path[:-1]:
        target = target[part]  # type: ignore[index]
    target[path[-1]] = replacement  # type: ignore[index]
    return result


class FailClosedBoundaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.private_root = self.root / "private"
        self.repo_root.mkdir()
        audio = self.private_root / "clip.wav"
        audio_sha256 = write_wav(audio)
        self.manifest = manifest_for(audio, audio_sha256)
        self.references = references_for()
        self.protocol = dataset_protocol_for(self.manifest)
        self.selection_config = SelectionConfig(
            bootstrap_samples=20,
            bootstrap_seed=17,
        )
        self.registry = registry(model_ids=["model_0", "model_1"])
        self.plan, self.private_map = blind_bundle_for(
            self.manifest,
            model_count=2,
            protocol=self.protocol,
            selection_config=self.selection_config.to_dict(),
            registry_snapshot=self.registry,
        )
        self.output = model_output_for(self.manifest, public_plan=self.plan)
        self.engineering_proof = engineering_proof_for(
            self.manifest,
            public_plan=self.plan,
        )
        self.report = score_model(
            self.manifest,
            self.references,
            self.output,
            NormalizationConfig(),
            self.plan,
            self.protocol,
            self.selection_config.to_dict(),
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def assert_contract_error(self, value: object, validator) -> None:
        with self.assertRaises(ContractError):
            validator(value)

    def test_json_helpers_and_private_paths_fail_closed(self) -> None:
        with self.assertRaisesRegex(ContractError, "canonical JSON"):
            canonical_sha256({"not_finite": math.nan})
        for function, value in (
            (_require_mapping, []),
            (_require_list, {}),
            (_require_string, 1),
            (_require_nonnegative_number, True),
            (_require_nonnegative_number, -1),
            (_require_nonnegative_int, -1),
        ):
            with self.subTest(function=function.__name__, value=value):
                with self.assertRaises(ContractError):
                    function(value, "attack")
        with self.assertRaisesRegex(ContractError, "empty"):
            _require_list([], "attack", non_empty=True)
        with self.assertRaisesRegex(ContractError, "empty"):
            _require_string("", "attack")

        missing = self.private_root / "missing.json"
        malformed = self.private_root / "malformed.json"
        malformed.parent.mkdir(parents=True, exist_ok=True)
        malformed.write_text("{", encoding="utf-8")
        with self.assertRaisesRegex(ContractError, "readable"):
            load_json(missing)
        with self.assertRaisesRegex(ContractError, "malformed"):
            load_json(malformed)

        outside = self.private_root / "input.json"
        write_json(outside, {"ok": True})
        self.assertEqual(
            validate_private_input_path(outside, self.repo_root),
            outside.resolve(),
        )
        for path in (
            Path("relative.json"),
            self.repo_root / "inside.json",
            missing,
        ):
            if path.is_absolute() and path.parent == self.repo_root:
                write_json(path, {"inside": True})
            with self.subTest(private_input=str(path)):
                with self.assertRaises(ContractError):
                    validate_private_input_path(path, self.repo_root)
        self.assertEqual(
            validate_private_output_path(
                self.private_root / "future.json",
                self.repo_root,
            ),
            (self.private_root / "future.json").resolve(),
        )
        for path in (Path("relative.json"), self.repo_root / "inside-output.json"):
            with self.subTest(private_output=str(path)):
                with self.assertRaises(ContractError):
                    validate_private_output_path(path, self.repo_root)

    def test_recording_manifest_metadata_attack_table(self) -> None:
        cases = (
            (("pcm_contract_id",), "other"),
            (("target_device", "platform"), "desktop"),
            (("target_device", "physical_device"), "yes"),
            (("target_device", "abi"), "x86_64"),
            (("dataset_usage", "recording_purpose"), "production"),
            (("dataset_usage", "used_for_model_tuning"), "no"),
            (("clips", 0, "source", "kind"), "remote"),
            (("clips", 0, "consent", "scope"), "anything"),
            (("clips", 0, "prompt_kind"), "cherry_picked"),
            (("clips", 0, "session_index"), 0),
            (("clips", 0, "hours_since_previous_session"), 1),
            (("clips", 0, "speech_duration_ms"), 0),
            (("clips", 0, "accent_natural"), "yes"),
            (("clips", 0, "capture_attempt", "attempt_index"), 0),
            (
                ("clips", 0, "capture_attempt", "take_selection_policy"),
                "best_take",
            ),
            (("clips", 0, "capture_attempt", "technical_rerecord"), "yes"),
            (("clips", 0, "conditions"), ["quiet_near", "quiet_near"]),
            (("clips", 0, "conditions"), ["unknown"]),
        )
        for path, replacement in cases:
            with self.subTest(path=path):
                self.assert_contract_error(
                    changed(self.manifest, path, replacement),
                    lambda value: validate_recording_manifest(value, self.repo_root),
                )

        duplicate_payload = copy.deepcopy(self.manifest)
        duplicate_payload["clips"][1]["pcm_payload_sha256"] = duplicate_payload[
            "clips"
        ][0]["pcm_payload_sha256"]
        self.assert_contract_error(
            duplicate_payload,
            lambda value: validate_recording_manifest(value, self.repo_root),
        )

        duplicate_speaker_sentence = copy.deepcopy(self.manifest)
        duplicate_speaker_sentence["clips"][1]["speaker_cluster_id"] = (
            duplicate_speaker_sentence["clips"][0]["speaker_cluster_id"]
        )
        duplicate_speaker_sentence["clips"][1]["sentence_id"] = (
            duplicate_speaker_sentence["clips"][0]["sentence_id"]
        )
        self.assert_contract_error(
            duplicate_speaker_sentence,
            lambda value: validate_recording_manifest(value, self.repo_root),
        )

        inconsistent_session = copy.deepcopy(self.manifest)
        inconsistent_session["clips"][1]["session_cluster_id"] = (
            inconsistent_session["clips"][0]["session_cluster_id"]
        )
        self.assert_contract_error(
            inconsistent_session,
            lambda value: validate_recording_manifest(value, self.repo_root),
        )

        missing_gap = copy.deepcopy(self.manifest)
        missing_gap["clips"][1]["session_index"] = 2
        self.assert_contract_error(
            missing_gap,
            lambda value: validate_recording_manifest(value, self.repo_root),
        )

        rerecord = copy.deepcopy(self.manifest)
        attempt = rerecord["clips"][1]["capture_attempt"]
        attempt.update(
            {
                "attempt_index": 2,
                "technical_rerecord": True,
                "replaces_clip_id": rerecord["clips"][0]["clip_id"],
                "exclusion_reason_code": "truncated_audio",
            }
        )
        validate_recording_manifest(rerecord, self.repo_root)

        bad_rerecord_attempt = copy.deepcopy(rerecord)
        bad_rerecord_attempt["clips"][1]["capture_attempt"]["attempt_index"] = 1
        self.assert_contract_error(
            bad_rerecord_attempt,
            lambda value: validate_recording_manifest(value, self.repo_root),
        )
        bad_rerecord_reason = copy.deepcopy(rerecord)
        bad_rerecord_reason["clips"][1]["capture_attempt"][
            "exclusion_reason_code"
        ] = "preferred_take"
        self.assert_contract_error(
            bad_rerecord_reason,
            lambda value: validate_recording_manifest(value, self.repo_root),
        )
        polluted_first_take = copy.deepcopy(self.manifest)
        polluted_first_take["clips"][0]["capture_attempt"]["attempt_index"] = 2
        self.assert_contract_error(
            polluted_first_take,
            lambda value: validate_recording_manifest(value, self.repo_root),
        )

    def test_frozen_protocol_builder_and_manifest_binding_attacks(self) -> None:
        exploration = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=self.manifest["target_device"],
            approval_status="approved",
        )
        production = build_dataset_protocol(
            "production_confirmation",
            protocol_id="protocol_222222222222",
            target_device=self.manifest["target_device"],
            approval_status="approved",
            exploration_pcm_set_sha256="a" * 64,
        )
        self.assertEqual(
            validate_dataset_protocol(exploration)["selection_authority"],
            "elimination_only",
        )
        self.assertEqual(
            validate_dataset_protocol(production)["selection_authority"],
            "production_confirmation",
        )
        with self.assertRaisesRegex(ContractError, "profile"):
            build_dataset_protocol(
                "unregistered",
                protocol_id="protocol_333333333333",
                target_device=self.manifest["target_device"],
            )

        protocol_cases = (
            (("target_device", "physical_device"), False),
            (("required_conditions",), exploration["required_conditions"][:-1]),
            (("minimum_unique_clips_per_condition", "noise"), 0),
            (("minimum_speaker_clusters",), 0),
            (("minimum_session_clusters",), 0),
            (("minimum_joint_clusters",), 0),
            (("selection_authority",), "production_confirmation"),
            (("bootstrap_unit",), "speaker_session_cluster"),
            (("required_source_kind",), "synthetic_fixture"),
            (("unique_pcm_required",), False),
            (("telemetry", "sample_interval_ms"), 0),
            (("telemetry", "minimum_duration_seconds"), 0),
            (("telemetry", "minimum_decode_loops"), 0),
        )
        for path, replacement in protocol_cases:
            with self.subTest(path=path):
                self.assert_contract_error(
                    changed(exploration, path, replacement),
                    validate_dataset_protocol,
                )

        invalid_fixture = changed(
            self.protocol,
            ("selection_authority",),
            "elimination_only",
        )
        self.assert_contract_error(invalid_fixture, validate_dataset_protocol)
        empty_fixture = changed(self.protocol, ("preregistration",), {})
        self.assert_contract_error(empty_fixture, validate_dataset_protocol)
        unsupported_fixture = changed(
            self.protocol,
            ("bootstrap_unit",),
            "clip",
        )
        self.assert_contract_error(unsupported_fixture, validate_dataset_protocol)

        formal_manifest = copy.deepcopy(self.manifest)
        formal_manifest["dataset_usage"]["recording_purpose"] = "exploration"
        formal_manifest["clips"][0]["conditions"] = [
            "quiet_near",
            "fast",
            "immediate_start",
            "short",
            "code_switch",
        ]
        formal_manifest["clips"][1]["conditions"] = [
            "noise",
            "slow",
            "long",
            "proper_name_number",
            "accent",
        ]
        with self.assertRaisesRegex(ContractError, "filesystem-backed"):
            validate_dataset_protocol(exploration, formal_manifest)

        production_manifest = copy.deepcopy(formal_manifest)
        production_manifest["dataset_usage"]["recording_purpose"] = (
            "production_confirmation"
        )
        production_manifest["dataset_usage"]["used_for_model_tuning"] = True
        self.assert_contract_error(
            production_manifest,
            lambda value: validate_dataset_protocol(production, value),
        )

    def test_reference_public_plan_and_private_map_attacks(self) -> None:
        validate_reference_set(
            self.references,
            expected_dataset_id=self.manifest["dataset_id"],
            expected_clip_ids={clip["clip_id"] for clip in self.manifest["clips"]},
        )
        reference_cases = (
            (("dataset_id",), "dataset_ffffffffffff"),
            (
                ("references", 1, "clip_id"),
                self.references["references"][0]["clip_id"],
            ),
            (("references", 0, "text"), "字" * 100_001),
            (
                ("references", 1, "annotations", 0, "annotation_id"),
                self.references["references"][0]["annotations"][0]["annotation_id"],
            ),
            (("references", 0, "annotations", 0, "kind"), "person"),
        )
        for path, replacement in reference_cases:
            with self.subTest(reference_path=path):
                value = changed(self.references, path, replacement)
                self.assert_contract_error(
                    value,
                    lambda item: validate_reference_set(
                        item,
                        expected_dataset_id=self.manifest["dataset_id"],
                    ),
                )

        validate_public_plan(self.plan, self.manifest)
        first_clip = self.plan["assignments"][0]["clip_order"][0]
        plan_cases = (
            (("pcm_contract_id",), "other"),
            (("assignments",), self.plan["assignments"][:1]),
            (("assignments", 0, "model_alias"), "model"),
            (
                ("assignments", 0, "clip_order"),
                [first_clip, first_clip],
            ),
            (
                ("assignments", 1, "clip_order"),
                self.plan["assignments"][1]["clip_order"][:1],
            ),
            (("assignments", 1, "model_alias"), "M001"),
            (("assignments", 1, "model_alias"), "M003"),
            (("baseline_alias",), "M999"),
            (
                ("clip_pcm_sha256",),
                {first_clip: self.plan["clip_pcm_sha256"][first_clip]},
            ),
            (("randomization", "algorithm"), "random"),
            (("dataset_id",), "dataset_ffffffffffff"),
            (
                ("clip_pcm_payload_sha256", first_clip),
                "f" * 64,
            ),
            (("contract_fingerprints", "manifest_sha256"), "f" * 64),
        )
        for path, replacement in plan_cases:
            with self.subTest(plan_path=path):
                self.assert_contract_error(
                    changed(self.plan, path, replacement),
                    lambda item: validate_public_plan(item, self.manifest),
                )

        self.assertEqual(
            validate_private_map(
                self.private_map,
                self.plan,
                self.registry,
            )["run_id"],
            self.plan["run_id"],
        )
        private_cases = (
            (("run_id",), "run_ffffffffffff"),
            (("seed",), 1),
            (("registry_sha256",), "f" * 64),
            (("alias_map", 0, "model_alias"), "bad"),
            (
                ("alias_map", 1, "model_alias"),
                self.private_map["alias_map"][0]["model_alias"],
            ),
            (("alias_map",), self.private_map["alias_map"][:1]),
            (("seed",), self.private_map["seed"] + 1),
        )
        for path, replacement in private_cases:
            with self.subTest(private_path=path):
                self.assert_contract_error(
                    changed(self.private_map, path, replacement),
                    lambda item: validate_private_map(
                        item,
                        self.plan,
                        self.registry,
                    ),
                )

        other_registry = registry(
            model_ids=["model_0", "model_1"],
            license_state="pending",
        )
        with self.assertRaisesRegex(ContractError, "registry snapshot"):
            validate_private_map(self.private_map, self.plan, other_registry)

        absent_map = copy.deepcopy(self.private_map)
        absent_map["alias_map"][0]["model_id"] = "model_absent"
        absent_plan = copy.deepcopy(self.plan)
        absent_plan["randomization"]["private_map_sha256"] = canonical_sha256(absent_map)
        with self.assertRaisesRegex(ContractError, "absent"):
            validate_private_map(absent_map, absent_plan, self.registry)

    def test_registry_component_license_and_artifact_attacks(self) -> None:
        validate_model_registry(self.registry)
        with_artifact_url = copy.deepcopy(self.registry)
        with_artifact_url["models"][0]["official_source"][
            "artifact_url"
        ] = (
            "https://huggingface.co/k2-fsa/test-model/resolve/"
            f"{'a' * 40}/model.bin"
        )
        validate_model_registry(with_artifact_url)

        cases = (
            (("models", 0, "model_id"), "INVALID-ID"),
            (("models", 0, "role"), "winner"),
            (
                ("models", 0, "official_source", "model_url"),
                "https://user:password@example.com/model",
            ),
            (
                (
                    "models",
                    0,
                    "licenses",
                    "runtime",
                    "verification_state",
                ),
                "trusted",
            ),
            (("models", 0, "download_status"), "downloaded"),
            (("models", 0, "runtime", "supported_abis"), ["x86_64"]),
            (
                (
                    "models",
                    0,
                    "runtime",
                    "android_runtime_compatibility",
                ),
                "assumed",
            ),
            (("models", 0, "artifacts", 0, "filename"), "../model.bin"),
            (("models", 0, "artifacts", 0, "component"), "prompt"),
            (
                ("models", 0, "artifacts", 0, "verification_state"),
                "unverified",
            ),
            (("models", 0, "artifacts", 0, "sha256"), None),
            (
                (
                    "models",
                    0,
                    "provenance",
                    "checkpoint_byte_equivalence",
                ),
                "assumed",
            ),
        )
        for path, replacement in cases:
            with self.subTest(registry_path=path):
                self.assert_contract_error(
                    changed(self.registry, path, replacement),
                    validate_model_registry,
                )

        pending = copy.deepcopy(self.registry)
        artifact = pending["models"][0]["artifacts"][0]
        artifact["verification_state"] = "post_download_freeze_required"
        artifact["sha256"] = None
        validate_model_registry(pending)

    def test_model_output_runner_proof_attack_table(self) -> None:
        validate_model_output(
            self.output,
            self.plan,
            self.manifest,
            self.protocol,
        )
        cases = (
            (("model_alias",), "bad"),
            (("model_alias",), "M999"),
            (("run_id",), "run_ffffffffffff"),
            (("decoder_contract_id",), "polished"),
            (("pcm_contract_id",), "other"),
            (("decoder_plan_sha256",), "f" * 64),
            (
                ("predictions", 1, "clip_id"),
                self.output["predictions"][0]["clip_id"],
            ),
            (("predictions", 0, "clip_id"), "clip_ffffffffffff"),
            (("predictions", 0, "pcm_payload_sha256"), "f" * 64),
            (("predictions", 0, "hypothesis"), "字" * 100_001),
            (("predictions", 0, "status"), "unknown"),
            (("predictions", 0, "error_code"), "timeout"),
            (("predictions", 0, "status"), "error"),
        )
        for path, replacement in cases:
            with self.subTest(output_path=path):
                self.assert_contract_error(
                    changed(self.output, path, replacement),
                    lambda item: validate_model_output(
                        item,
                        self.plan,
                        self.manifest,
                        self.protocol,
                    ),
                )

        proof_cases = (
            (
                (
                    "clip_measurements",
                    0,
                    "runner_evidence",
                    "pcm_payload_sha256",
                ),
                "f" * 64,
            ),
            (
                (
                    "clip_measurements",
                    0,
                    "runner_evidence",
                    "pcm_payload_bytes_read",
                ),
                1,
            ),
            (
                (
                    "clip_measurements",
                    0,
                    "runner_evidence",
                    "monotonic_stop_ns",
                ),
                0,
            ),
            (("runner_proof", "proof_version"), "v0"),
            (("runner_proof", "capture_mode"), "host"),
            (("runner_proof", "trusted_runner_status"), "verified"),
            (("runner_proof", "device_profile_sha256"), "f" * 64),
            (("runner_proof", "clock_source"), "wall_clock"),
            (
                ("runner_proof", "artifact_measurements", 0, "component"),
                "prompt",
            ),
            (("runner_proof", "artifact_set_sha256"), "f" * 64),
            (
                ("runner_proof", "telemetry", "sample_interval_ms"),
                1_000,
            ),
            (
                ("runner_proof", "telemetry", "samples", 0, "thermal_status"),
                7,
            ),
            (("runner_proof", "telemetry", "summary_sha256"), "f" * 64),
            (("runner_proof", "proof_sha256"), "f" * 64),
        )
        for path, replacement in proof_cases:
            with self.subTest(engineering_proof_path=path):
                self.assert_contract_error(
                    changed(self.engineering_proof, path, replacement),
                    lambda item: validate_engineering_proof(
                        item,
                        self.output,
                        self.plan,
                        self.manifest,
                        self.protocol,
                    ),
                )

        missing_clip = copy.deepcopy(self.output)
        missing_clip["predictions"].pop()
        self.assert_contract_error(
            missing_clip,
            lambda item: validate_model_output(
                item,
                self.plan,
                self.manifest,
                self.protocol,
            ),
        )
        duplicate_artifact = copy.deepcopy(self.engineering_proof)
        duplicate_artifact["runner_proof"]["artifact_measurements"].append(
            copy.deepcopy(
                duplicate_artifact["runner_proof"]["artifact_measurements"][0]
            )
        )
        self.assert_contract_error(
            duplicate_artifact,
            lambda item: validate_engineering_proof(
                item,
                self.output,
                self.plan,
                self.manifest,
                self.protocol,
            ),
        )
        bad_loop_sample = copy.deepcopy(self.engineering_proof)
        sample = bad_loop_sample["runner_proof"]["telemetry"]["samples"][1]
        sample["successful_loops"] = sample["completed_loops"] + 1
        self.assert_contract_error(
            bad_loop_sample,
            lambda item: validate_engineering_proof(
                item,
                self.output,
                self.plan,
                self.manifest,
                self.protocol,
            ),
        )
        for field, replacement in (
            ("successful_loop_count", 11),
            ("loop_count", 9),
        ):
            mismatched_loop = copy.deepcopy(self.engineering_proof)
            mismatched_loop["runner_proof"]["telemetry"][field] = replacement
            self.assert_contract_error(
                mismatched_loop,
                lambda item: validate_engineering_proof(
                    item,
                    self.output,
                    self.plan,
                    self.manifest,
                    self.protocol,
                ),
            )
        for path, replacement in (
            (("runtime", "peak_rss_bytes"), 400_000_001),
            (("runtime", "stability", "max_thermal_status"), 3),
            (("runtime", "stability", "duration_seconds"), 606.0),
        ):
            self.assert_contract_error(
                changed(self.engineering_proof, path, replacement),
                lambda item: validate_engineering_proof(
                    item,
                    self.output,
                    self.plan,
                    self.manifest,
                    self.protocol,
                ),
            )

    def test_score_report_external_json_attack_table(self) -> None:
        validate_score_report(self.report)
        cases = (
            (("schema_version",), "2.0"),
            (("model_alias",), "model"),
            (("normalization", "profile_id"), "other"),
            (("normalization", "sha256"), "f" * 64),
            (("evaluation_contract", "blind_evaluation"), False),
            (("evaluation_contract", "decoder_contract_id"), "polished"),
            (("evaluation_contract", "randomization_algorithm"), "random"),
            (("evaluation_contract", "normalization_sha256"), "f" * 64),
            (
                ("sentence_errors", 1, "clip_id"),
                self.report["sentence_errors"][0]["clip_id"],
            ),
            (
                ("sentence_errors", 0, "conditions"),
                ["quiet_near", "quiet_near"],
            ),
            (("sentence_errors", 0, "source_kind"), "cloud"),
            (("sentence_errors", 0, "status"), "unknown"),
            (
                ("sentence_errors", 0, "number_hits"),
                self.report["sentence_errors"][0]["number_total"] + 1,
            ),
            (("dataset_summary", "clip_count"), 999),
            (("dataset_summary", "condition_counts", "noise"), 999),
            (
                ("dataset_summary", "condition_speaker_counts", "noise"),
                999,
            ),
            (
                ("dataset_summary", "condition_session_counts", "noise"),
                999,
            ),
            (
                (
                    "dataset_summary",
                    "source_kind_counts",
                    "local_target_device_recording",
                ),
                999,
            ),
            (("dataset_summary", "annotation_counts", "number"), 999),
            (("dataset_summary", "protocol_profile"), "informal"),
            (("dataset_summary", "selection_authority"), "winner"),
            (("dataset_summary", "speaker_cluster_count"), 999),
            (("dataset_summary", "session_cluster_count"), 999),
            (("dataset_summary", "joint_cluster_count"), 999),
            (("evaluation_contract", "prediction_order_sha256"), "f" * 64),
            (
                ("dataset_summary", "protocol_qualification", "eligible"),
                "yes",
            ),
            (
                ("dataset_summary", "protocol_qualification", "reasons"),
                [1],
            ),
            (
                ("dataset_summary", "protocol_qualification", "eligible"),
                False,
            ),
            (
                ("dataset_summary", "generalizability", "bootstrap_unit"),
                "clip",
            ),
        )
        for path, replacement in cases:
            with self.subTest(report_path=path):
                self.assert_contract_error(
                    changed(self.report, path, replacement),
                    validate_score_report,
                )

        missing_group = copy.deepcopy(self.report)
        missing_group["groups"].pop(next(iter(missing_group["groups"])))
        self.assert_contract_error(missing_group, validate_score_report)
        leaked_engineering = copy.deepcopy(self.report)
        leaked_engineering["runtime"] = {}
        self.assert_contract_error(leaked_engineering, validate_score_report)


if __name__ == "__main__":
    unittest.main()
