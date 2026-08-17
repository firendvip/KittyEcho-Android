from __future__ import annotations

import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from kittyecho_asr_bench.contracts import (
    ContractError,
    build_dataset_protocol,
    canonical_sha256,
)
from kittyecho_asr_bench.metrics import (
    _dataset_summary,
    _egc_count,
    _validate_formal_condition_definitions,
    score_model,
)
from kittyecho_asr_bench.engineering import build_engineering_report
from kittyecho_asr_bench.normalization import NormalizationConfig
from kittyecho_asr_bench.selection import (
    SelectionConfig,
    _paired_bootstrap_interval,
    _production_rule_assessment,
    _quantile,
    _validate_failure_record,
    evaluate_hard_gates,
    select_winner,
)

from support import (
    blind_bundle_for,
    dataset_protocol_for,
    engineering_proof_for,
    manifest_for,
    model_output_for,
    references_for,
    registry,
    write_wav,
)


class FormalMethodologyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        audio = self.root / "private" / "clip.wav"
        audio_sha256 = write_wav(audio)
        self.manifest = manifest_for(audio, audio_sha256)
        self.references = references_for()
        self.protocol = dataset_protocol_for(self.manifest)
        self.config = SelectionConfig(bootstrap_samples=20, bootstrap_seed=23)
        self.registry = registry(model_ids=["model_0", "model_1"])
        self.plan, _ = blind_bundle_for(
            self.manifest,
            model_count=2,
            protocol=self.protocol,
            selection_config=self.config.to_dict(),
            registry_snapshot=self.registry,
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def report(
        self,
        alias: str,
        hypotheses: list[str] | None = None,
        *,
        output: dict | None = None,
    ) -> dict:
        model_output = output or model_output_for(
            self.manifest,
            alias=alias,
            hypotheses=hypotheses,
            public_plan=self.plan,
        )
        return score_model(
            self.manifest,
            self.references,
            model_output,
            NormalizationConfig(),
            self.plan,
            self.protocol,
            self.config.to_dict(),
        )

    def formal_protocol(self, profile: str) -> dict:
        return build_dataset_protocol(
            profile,
            protocol_id=(
                "protocol_111111111111"
                if profile == "exploration"
                else "protocol_222222222222"
            ),
            target_device=self.manifest["target_device"],
            approval_status="approved",
        )

    def test_formal_condition_definitions_are_executable_and_fail_closed(self) -> None:
        protocol = self.formal_protocol("exploration")
        normalization = NormalizationConfig()

        def clip(
            index: int,
            condition: str,
            text: str,
            *,
            duration_ms: float = 1_000.0,
            onset_ms: float = 0.0,
        ) -> tuple[dict, dict]:
            clip_id = f"clip_{index:012x}"
            return (
                {
                    "clip_id": clip_id,
                    "conditions": [condition],
                    "speech_duration_ms": duration_ms,
                    "speech_onset_ms": onset_ms,
                },
                {"clip_id": clip_id, "text": text},
            )

        valid_pairs = [
            clip(1, "short", "小猫"),
            clip(2, "normal_short", "这是一个正常长度句子"),
            clip(3, "long", "长" * 31),
            clip(4, "immediate_start", "马上开始", onset_ms=300),
            clip(5, "fast", "快速语音测试", duration_ms=1_000),
            clip(6, "slow", "慢句", duration_ms=2_000),
            clip(7, "code_switch", "今天小猫 hello world"),
        ]
        clips = [item[0] for item in valid_pairs]
        references = {item[1]["clip_id"]: item[1] for item in valid_pairs}
        _validate_formal_condition_definitions(
            clips,
            references,
            normalization,
            protocol,
        )
        self.assertEqual(_egc_count("a\u0301"), 1)
        self.assertEqual(_egc_count("👩\u200d💻\ufe0f"), 1)

        invalid_pairs = [
            clip(11, "short", "一二三四五六七"),
            clip(12, "normal_short", "太短"),
            clip(13, "long", "长" * 30),
            clip(14, "immediate_start", "马上开始", onset_ms=301),
            clip(15, "fast", "速度慢", duration_ms=2_000),
            clip(16, "slow", "这句话说得太快", duration_ms=1_000),
            clip(17, "code_switch", "三字 one"),
        ]
        for item, reference in invalid_pairs:
            with self.subTest(condition=item["conditions"][0]):
                with self.assertRaises(ContractError):
                    _validate_formal_condition_definitions(
                        [item],
                        {reference["clip_id"]: reference},
                        normalization,
                        protocol,
                    )

    def test_formal_dataset_summary_enforces_preregistered_counts_and_clusters(self) -> None:
        sentence_errors = self.report("M001")["sentence_errors"]
        exploration = _dataset_summary(
            sentence_errors,
            self.formal_protocol("exploration"),
        )
        production = _dataset_summary(
            sentence_errors,
            self.formal_protocol("production_confirmation"),
        )
        self.assertFalse(exploration["protocol_qualification"]["eligible"])
        self.assertFalse(production["protocol_qualification"]["eligible"])
        exploration_reasons = " ".join(
            exploration["protocol_qualification"]["reasons"]
        )
        production_reasons = " ".join(
            production["protocol_qualification"]["reasons"]
        )
        self.assertIn("96", exploration_reasons)
        self.assertIn("480", production_reasons)
        self.assertIn("speaker", production_reasons)
        self.assertIn("annotation", production_reasons)

    def test_production_rule_assessment_expands_or_keeps_baseline_without_selecting(self) -> None:
        def candidate(
            *,
            alias: str,
            edits: int,
            clip_count: int = 480,
            groups: dict | None = None,
        ) -> dict:
            return {
                "model_alias": alias,
                "metrics": {"cer": edits / 1_000},
                "dataset_summary": {
                    "protocol_profile": "production_confirmation",
                    "clip_count": clip_count,
                },
                "groups": groups or {},
                "sentence_errors": [
                    {
                        "clip_id": "clip_000000000001",
                        "substitutions": edits,
                        "deletions": 0,
                        "insertions": 0,
                        "reference_characters": 1_000,
                        "speaker_cluster_id": "speaker_000000000001",
                        "session_cluster_id": "session_000000000001",
                        "conditions": ["noise"],
                    }
                ],
            }

        leader = candidate(alias="M002", edits=100)
        baseline = candidate(alias="M001", edits=110)
        runner_up = candidate(alias="M003", edits=106)
        with patch(
            "kittyecho_asr_bench.selection._paired_bootstrap_interval",
            return_value=(-0.01, 0.02),
        ):
            assessment = _production_rule_assessment(
                leader,
                baseline,
                runner_up,
                self.config,
            )
        self.assertEqual(
            assessment["recommended_action"],
            "expand_by_120_with_2_new_speakers",
        )

        max_size_leader = candidate(
            alias="M002",
            edits=100,
            clip_count=720,
        )
        max_size_baseline = candidate(
            alias="M001",
            edits=110,
            clip_count=720,
        )
        with patch(
            "kittyecho_asr_bench.selection._paired_bootstrap_interval",
            return_value=(0.001, 0.041),
        ):
            assessment = _production_rule_assessment(
                max_size_leader,
                max_size_baseline,
                candidate(alias="M003", edits=106, clip_count=720),
                self.config,
            )
        self.assertEqual(assessment["recommended_action"], "keep_current_baseline")

        narrow_loss = candidate(alias="M002", edits=109)
        with patch(
            "kittyecho_asr_bench.selection._paired_bootstrap_interval",
            return_value=(-0.001, 0.001),
        ):
            assessment = _production_rule_assessment(
                narrow_loss,
                baseline,
                candidate(alias="M003", edits=115),
                self.config,
            )
        self.assertEqual(assessment["recommended_action"], "keep_current_baseline")

        with patch(
            "kittyecho_asr_bench.selection._paired_bootstrap_interval",
            return_value=(0.001, 0.003),
        ):
            assessment = _production_rule_assessment(
                leader,
                baseline,
                runner_up,
                self.config,
            )
        self.assertEqual(
            assessment["recommended_action"],
            "phase_b_runner_attestation_required",
        )

        leader_with_slice = candidate(
            alias="M002",
            edits=100,
            groups={"noise": {}},
        )
        with patch(
            "kittyecho_asr_bench.selection._paired_bootstrap_interval",
            side_effect=[(0.001, 0.003), (0.001, 0.003), (0.02, 0.03)],
        ):
            assessment = _production_rule_assessment(
                leader_with_slice,
                baseline,
                runner_up,
                self.config,
            )
        self.assertEqual(assessment["recommended_action"], "keep_current_baseline")
        self.assertEqual(
            assessment["slice_degradation_evidence"][0]["condition"],
            "noise",
        )

    def test_production_leader_must_clear_runner_up_delta_and_paired_ci(self) -> None:
        def candidate(alias: str, edits: int, clip_count: int) -> dict:
            return {
                "model_alias": alias,
                "metrics": {"cer": edits / 1_000},
                "dataset_summary": {
                    "protocol_profile": "production_confirmation",
                    "clip_count": clip_count,
                },
                "groups": {},
                "sentence_errors": [
                    {
                        "clip_id": "clip_000000000001",
                        "substitutions": edits,
                        "deletions": 0,
                        "insertions": 0,
                        "reference_characters": 1_000,
                        "speaker_cluster_id": "speaker_000000000001",
                        "session_cluster_id": "session_000000000001",
                        "conditions": ["noise"],
                    }
                ],
            }

        for clip_count, expected_action in (
            (480, "expand_by_120_with_2_new_speakers"),
            (720, "keep_current_baseline"),
        ):
            leader = candidate("M002", 100, clip_count)
            baseline = candidate("M001", 110, clip_count)
            runner_up = candidate("M003", 104, clip_count)
            with patch(
                "kittyecho_asr_bench.selection._paired_bootstrap_interval",
                side_effect=[(0.008, 0.012), (0.002, 0.006)],
            ):
                assessment = _production_rule_assessment(
                    leader,
                    baseline,
                    runner_up,
                    self.config,
                )
            self.assertAlmostEqual(assessment["runner_up_improvement"], 0.004)
            self.assertEqual(assessment["recommended_action"], expected_action)

    def test_production_uncertain_ci_extends_at_480_or_600_and_boundary_is_inclusive(self) -> None:
        def candidate(alias: str, edits: int, clip_count: int) -> dict:
            return {
                "model_alias": alias,
                "metrics": {"cer": edits / 1_000},
                "dataset_summary": {
                    "protocol_profile": "production_confirmation",
                    "clip_count": clip_count,
                },
                "groups": {},
                "sentence_errors": [
                    {
                        "clip_id": "clip_000000000001",
                        "substitutions": edits,
                        "deletions": 0,
                        "insertions": 0,
                        "reference_characters": 1_000,
                        "speaker_cluster_id": "speaker_000000000001",
                        "session_cluster_id": "session_000000000001",
                        "conditions": ["noise"],
                    }
                ],
            }

        for clip_count, expected_action in (
            (480, "expand_by_120_with_2_new_speakers"),
            (600, "expand_by_120_with_2_new_speakers"),
            (720, "keep_current_baseline"),
        ):
            leader = candidate("M002", 100, clip_count)
            baseline = candidate("M001", 110, clip_count)
            runner_up = candidate("M003", 106, clip_count)
            with self.subTest(comparison="baseline", clip_count=clip_count):
                with patch(
                    "kittyecho_asr_bench.selection._paired_bootstrap_interval",
                    side_effect=[(-0.001, 0.003), (0.002, 0.010)],
                ):
                    assessment = _production_rule_assessment(
                        leader,
                        baseline,
                        runner_up,
                        self.config,
                    )
                self.assertEqual(
                    assessment["recommended_action"],
                    expected_action,
                )
            with self.subTest(comparison="runner_up", clip_count=clip_count):
                with patch(
                    "kittyecho_asr_bench.selection._paired_bootstrap_interval",
                    side_effect=[(0.002, 0.010), (-0.001, 0.003)],
                ):
                    assessment = _production_rule_assessment(
                        leader,
                        baseline,
                        runner_up,
                        self.config,
                    )
                self.assertEqual(
                    assessment["recommended_action"],
                    expected_action,
                )

        leader = candidate("M002", 100, 480)
        baseline = candidate("M001", 110, 480)
        runner_up = candidate("M003", 106, 480)
        with patch(
            "kittyecho_asr_bench.selection._paired_bootstrap_interval",
            side_effect=[(0.001, 0.011), (0.001, 0.011)],
        ):
            assessment = _production_rule_assessment(
                leader,
                baseline,
                runner_up,
                self.config,
            )
        self.assertAlmostEqual(assessment["ci_half_width"], 0.005)
        self.assertAlmostEqual(
            assessment["runner_up_ci_half_width"],
            0.005,
        )
        self.assertEqual(
            assessment["recommended_action"],
            "phase_b_runner_attestation_required",
        )

    def test_production_point_delta_boundaries_use_integer_totals(self) -> None:
        def candidate(alias: str, edits: int) -> dict:
            return {
                "model_alias": alias,
                "metrics": {"cer": edits / 10_000},
                "dataset_summary": {
                    "protocol_profile": "production_confirmation",
                    "clip_count": 480,
                },
                "groups": {},
                "sentence_errors": [
                    {
                        "clip_id": "clip_000000000001",
                        "substitutions": edits,
                        "deletions": 0,
                        "insertions": 0,
                        "reference_characters": 10_000,
                        "speaker_cluster_id": "speaker_000000000001",
                        "session_cluster_id": "session_000000000001",
                        "conditions": ["noise"],
                    }
                ],
            }

        leader = candidate("M002", 1_000)
        clear = (0.001, 0.003)
        for baseline_edits, expected_action in (
            (1_049, "keep_current_baseline"),
            (1_050, "phase_b_runner_attestation_required"),
            (1_051, "phase_b_runner_attestation_required"),
        ):
            with self.subTest(
                comparison="baseline",
                edits=baseline_edits,
            ):
                with patch(
                    "kittyecho_asr_bench.selection._paired_bootstrap_interval",
                    side_effect=[clear, clear],
                ):
                    assessment = _production_rule_assessment(
                        leader,
                        candidate("M001", baseline_edits),
                        candidate("M003", 1_060),
                        self.config,
                    )
                self.assertEqual(
                    assessment["recommended_action"],
                    expected_action,
                )

        for runner_up_edits, expected_action in (
            (1_049, "expand_by_120_with_2_new_speakers"),
            (1_050, "phase_b_runner_attestation_required"),
            (1_051, "phase_b_runner_attestation_required"),
        ):
            with self.subTest(
                comparison="runner_up",
                edits=runner_up_edits,
            ):
                with patch(
                    "kittyecho_asr_bench.selection._paired_bootstrap_interval",
                    side_effect=[clear, clear],
                ):
                    assessment = _production_rule_assessment(
                        leader,
                        candidate("M001", 1_060),
                        candidate("M003", runner_up_edits),
                        self.config,
                    )
                self.assertEqual(
                    assessment["recommended_action"],
                    expected_action,
                )

    def test_production_point_delta_uses_integer_edits_at_three_of_six_hundred(self) -> None:
        def candidate(alias: str, edits: int) -> dict:
            return {
                "model_alias": alias,
                "metrics": {"cer": edits / 600},
                "dataset_summary": {
                    "protocol_profile": "production_confirmation",
                    "clip_count": 480,
                },
                "groups": {},
                "sentence_errors": [
                    {
                        "clip_id": "clip_000000000001",
                        "substitutions": edits,
                        "deletions": 0,
                        "insertions": 0,
                        "reference_characters": 600,
                        "speaker_cluster_id": "speaker_000000000001",
                        "session_cluster_id": "session_000000000001",
                        "conditions": ["noise"],
                    }
                ],
            }

        leader = candidate("M002", 2)
        clear = (0.001, 0.003)
        for baseline_edits, expected_action in (
            (4, "keep_current_baseline"),
            (5, "phase_b_runner_attestation_required"),
            (6, "phase_b_runner_attestation_required"),
        ):
            with self.subTest(
                comparison="baseline",
                baseline_edits=baseline_edits,
            ):
                with patch(
                    "kittyecho_asr_bench.selection._paired_bootstrap_interval",
                    side_effect=[clear, clear],
                ):
                    assessment = _production_rule_assessment(
                        leader,
                        candidate("M001", baseline_edits),
                        candidate("M003", 8),
                        self.config,
                    )
                self.assertEqual(
                    assessment["recommended_action"],
                    expected_action,
                )

        for runner_up_edits, expected_action in (
            (4, "expand_by_120_with_2_new_speakers"),
            (5, "phase_b_runner_attestation_required"),
            (6, "phase_b_runner_attestation_required"),
        ):
            with self.subTest(
                comparison="runner_up",
                runner_up_edits=runner_up_edits,
            ):
                with patch(
                    "kittyecho_asr_bench.selection._paired_bootstrap_interval",
                    side_effect=[clear, clear],
                ):
                    assessment = _production_rule_assessment(
                        leader,
                        candidate("M001", 8),
                        candidate("M003", runner_up_edits),
                        self.config,
                    )
                self.assertEqual(
                    assessment["recommended_action"],
                    expected_action,
                )

    def test_production_ci_half_width_boundaries_are_decimal_exact(self) -> None:
        def candidate(alias: str, edits: int) -> dict:
            return {
                "model_alias": alias,
                "metrics": {"cer": edits / 1_000},
                "dataset_summary": {
                    "protocol_profile": "production_confirmation",
                    "clip_count": 480,
                },
                "groups": {},
                "sentence_errors": [
                    {
                        "clip_id": "clip_000000000001",
                        "substitutions": edits,
                        "deletions": 0,
                        "insertions": 0,
                        "reference_characters": 1_000,
                        "speaker_cluster_id": "speaker_000000000001",
                        "session_cluster_id": "session_000000000001",
                        "conditions": ["noise"],
                    }
                ],
            }

        leader = candidate("M002", 100)
        baseline = candidate("M001", 106)
        runner_up = candidate("M003", 106)
        for interval, expected_action in (
            ((0.2000, 0.2098), "phase_b_runner_attestation_required"),
            ((0.2900, 0.3000), "phase_b_runner_attestation_required"),
            ((0.2000, 0.2102), "expand_by_120_with_2_new_speakers"),
        ):
            with self.subTest(interval=interval):
                with patch(
                    "kittyecho_asr_bench.selection._paired_bootstrap_interval",
                    side_effect=[interval, interval],
                ):
                    assessment = _production_rule_assessment(
                        leader,
                        baseline,
                        runner_up,
                        self.config,
                    )
                self.assertEqual(
                    assessment["recommended_action"],
                    expected_action,
                )

    def test_bootstrap_selection_and_failure_inputs_are_strict(self) -> None:
        self.assertEqual(_quantile([1.0], 0.5), 1.0)
        empty = {"model_alias": "M001", "sentence_errors": []}
        with self.assertRaisesRegex(ContractError, "non-empty"):
            _paired_bootstrap_interval(
                empty,
                {"model_alias": "M002", "sentence_errors": []},
                self.config,
            )
        mismatch = self.report("M001")
        contender = self.report("M002")
        mismatch["sentence_errors"][0]["speaker_cluster_id"] = (
            "speaker_ffffffffffff"
        )
        with self.assertRaisesRegex(ContractError, "clusters"):
            _paired_bootstrap_interval(mismatch, contender, self.config)

        invalid_configs = (
            {"max_total_resource_bytes": 0},
            {"max_normal_short_stop_to_final_ms": 0},
            {"latency_statistic": "median"},
            {"min_stability_seconds": 0},
            {"practical_cer_delta": -1},
            {"confidence_level": 1},
            {"bootstrap_samples": 0},
            {"bootstrap_seed": True},
        )
        for values in invalid_configs:
            with self.subTest(config=values):
                with self.assertRaises(ValueError):
                    SelectionConfig(**values)

        valid_failure = {
            "schema_version": "1.0",
            "run_id": self.plan["run_id"],
            "dataset_id": self.plan["dataset_id"],
            "model_alias": "M002",
            "public_plan_sha256": canonical_sha256(self.plan),
            "registry_sha256": self.plan["contract_fingerprints"][
                "registry_sha256"
            ],
            "reason_code": "runner_failed",
            "attestation_status": "development_untrusted",
        }
        self.assertEqual(
            _validate_failure_record(valid_failure, plan=self.plan)[
                "reason_code"
            ],
            "runner_failed",
        )
        for path, replacement in (
            ("schema_version", "2.0"),
            ("model_alias", "M999"),
            ("run_id", "run_ffffffffffff"),
            ("reason_code", "timeout"),
        ):
            invalid = copy.deepcopy(valid_failure)
            invalid[path] = replacement
            with self.subTest(failure_field=path):
                with self.assertRaises(ContractError):
                    _validate_failure_record(invalid, plan=self.plan)

        reports = [self.report("M001"), self.report("M002")]
        with self.assertRaisesRegex(ContractError, "at least one"):
            select_winner([], self.config, public_plan=self.plan)
        with self.assertRaisesRegex(ContractError, "duplicate aliases"):
            select_winner(
                [reports[0], copy.deepcopy(reports[0])],
                self.config,
                public_plan=self.plan,
            )
        with self.assertRaisesRegex(ContractError, "both"):
            select_winner(
                reports,
                self.config,
                public_plan=self.plan,
                failure_records=[valid_failure],
            )
        with self.assertRaisesRegex(ContractError, "duplicate aliases"):
            select_winner(
                [reports[0]],
                self.config,
                public_plan=self.plan,
                failure_records=[valid_failure, copy.deepcopy(valid_failure)],
            )

    def test_hard_gates_include_decode_and_runner_loop_failures(self) -> None:
        decode_output = model_output_for(
            self.manifest,
            alias="M001",
            public_plan=self.plan,
        )
        decode_output["predictions"][0]["status"] = "error"
        decode_output["predictions"][0]["error_code"] = "decode_error"
        decode_report = self.report("M001", output=decode_output)
        self.assertIn(
            "one or more decoding attempts failed",
            evaluate_hard_gates(decode_report, self.config),
        )

        loop_output = model_output_for(
            self.manifest,
            alias="M001",
            public_plan=self.plan,
        )
        loop_proof = engineering_proof_for(
            self.manifest,
            alias="M001",
            public_plan=self.plan,
        )
        telemetry = loop_proof["runner_proof"]["telemetry"]
        failed_loop_index = telemetry["loop_count"]
        telemetry["loop_proofs"][failed_loop_index - 1]["successful"] = False
        for sample in telemetry["samples"]:
            if sample["completed_loops"] >= failed_loop_index:
                sample["successful_loops"] = sample["completed_loops"] - 1
        telemetry["successful_loop_count"] = telemetry["loop_count"] - 1
        telemetry["summary_sha256"] = canonical_sha256(telemetry["samples"])
        proof_without_hash = dict(loop_proof["runner_proof"])
        proof_without_hash.pop("proof_sha256")
        loop_proof["runner_proof"]["proof_sha256"] = canonical_sha256(
            proof_without_hash
        )
        loop_report = self.report("M001", output=loop_output)
        engineering_report = build_engineering_report(
            loop_proof,
            loop_output,
            loop_report,
            self.plan,
            self.manifest,
            self.protocol,
        )
        self.assertIn(
            "trusted runner decode loop failures occurred",
            evaluate_hard_gates(
                loop_report,
                self.config,
                engineering_report,
            ),
        )


if __name__ == "__main__":
    unittest.main()
