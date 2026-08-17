from __future__ import annotations

import copy
import tempfile
import unittest
from pathlib import Path
from typing import Optional

from kittyecho_asr_bench.blinding import create_blind_bundle
from kittyecho_asr_bench.contracts import ContractError, canonical_sha256
from kittyecho_asr_bench.metrics import score_model
from kittyecho_asr_bench.normalization import NormalizationConfig
from kittyecho_asr_bench.selection import (
    SelectionConfig,
    evaluate_hard_gates,
    select_winner,
)

from support import (
    dataset_protocol_for,
    manifest_for,
    model_output_for,
    public_plan_for,
    references_for,
    runtime_metrics,
    scored_pair_for,
    write_wav,
)


class BlindingTest(unittest.TestCase):
    fingerprints = {
        "manifest_sha256": "1" * 64,
        "dataset_protocol_sha256": "2" * 64,
        "normalization_sha256": "3" * 64,
        "selection_config_sha256": "4" * 64,
        "registry_sha256": "5" * 64,
    }

    def test_blinding_is_reproducible_and_public_plan_has_no_model_or_answer_mapping(self) -> None:
        manifest = {
            "dataset_id": "dataset_0123456789ab",
            "pcm_contract_id": "pcm16k-mono-s16le-v1",
            "clips": [
                {
                    "clip_id": f"clip_{index:012x}",
                    "audio_sha256": f"{index:064x}",
                    "pcm_payload_sha256": f"{index + 10:064x}",
                }
                for index in range(6)
            ],
        }
        models = ["zipformer", "paraformer", "firered", "funasr_nano"]
        seed = (1 << 127) + 20260729
        public_a, private_a = create_blind_bundle(
            manifest,
            models,
            seed=seed,
            contract_fingerprints=self.fingerprints,
        )
        public_b, private_b = create_blind_bundle(
            manifest,
            models,
            seed=seed,
            contract_fingerprints=self.fingerprints,
        )
        public_c, _ = create_blind_bundle(
            manifest,
            models,
            seed=seed + 1,
            contract_fingerprints=self.fingerprints,
        )

        self.assertEqual(public_a, public_b)
        self.assertEqual(private_a, private_b)
        self.assertNotEqual(public_a, public_c)
        self.assertNotIn("zipformer", str(public_a))
        self.assertNotIn("reference", str(public_a).lower())
        self.assertNotIn("seed", str(public_a).lower())
        self.assertEqual({entry["model_id"] for entry in private_a["alias_map"]}, set(models))

    def test_blinding_rejects_duplicate_models_or_too_few_clips(self) -> None:
        manifest = {
            "dataset_id": "dataset_0123456789ab",
            "pcm_contract_id": "pcm16k-mono-s16le-v1",
            "clips": [
                {
                    "clip_id": "clip_000000000000",
                    "audio_sha256": "0" * 64,
                    "pcm_payload_sha256": "1" * 64,
                }
            ],
        }
        with self.assertRaisesRegex(ValueError, "duplicate"):
            create_blind_bundle(
                manifest,
                ["same", "same"],
                seed=1,
                contract_fingerprints=self.fingerprints,
            )
        with self.assertRaisesRegex(ValueError, "at least two"):
            create_blind_bundle(
                manifest,
                ["only"],
                seed=1,
                contract_fingerprints=self.fingerprints,
            )

    def test_blinding_rejects_guessable_seed(self) -> None:
        manifest = {
            "dataset_id": "dataset_0123456789ab",
            "pcm_contract_id": "pcm16k-mono-s16le-v1",
            "clips": [
                {
                    "clip_id": f"clip_{index:012x}",
                    "audio_sha256": f"{index:064x}",
                    "pcm_payload_sha256": f"{index + 10:064x}",
                }
                for index in range(2)
            ],
        }
        with self.assertRaisesRegex(ValueError, "128-bit"):
            create_blind_bundle(
                manifest,
                ["one", "two"],
                seed=20260729,
                contract_fingerprints=self.fingerprints,
            )


class SelectionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        audio = root / "private" / "clip.wav"
        sha = write_wav(audio)
        self.manifest = manifest_for(audio, sha)
        self.references = references_for()
        self.config = SelectionConfig(bootstrap_samples=200, bootstrap_seed=19)
        self.protocol = dataset_protocol_for(self.manifest)
        self.public_plan = public_plan_for(
            self.manifest,
            model_count=2,
            protocol=self.protocol,
            selection_config=self.config.to_dict(),
        )
        self.engineering_by_report_sha256: dict[str, dict] = {}

    def tearDown(self) -> None:
        self.temp.cleanup()

    def report(
        self,
        alias: str,
        *,
        hypotheses: list[str] | None = None,
        latencies_ms: list[float] | None = None,
        runtime: dict | None = None,
        normalization: NormalizationConfig | None = None,
    ) -> dict:
        _, _, report, engineering = scored_pair_for(
            self.manifest,
            self.references,
            self.public_plan,
            self.protocol,
            self.config.to_dict(),
            alias=alias,
            hypotheses=hypotheses,
            latencies_ms=latencies_ms,
            runtime=runtime,
            normalization=normalization,
        )
        self.engineering_by_report_sha256[canonical_sha256(report)] = engineering
        return report

    def engineering_for(self, report: dict) -> dict:
        return self.engineering_by_report_sha256[canonical_sha256(report)]

    def select(
        self,
        reports: list[dict],
        config: Optional[SelectionConfig] = None,
    ) -> dict:
        engineering_reports = [
            self.engineering_by_report_sha256[digest]
            for report in reports
            if (digest := canonical_sha256(report)) in self.engineering_by_report_sha256
        ]
        return select_winner(
            reports,
            config or self.config,
            public_plan=self.public_plan,
            engineering_reports=engineering_reports,
        )

    def test_hard_gates_fail_closed_for_resource_oom_crash_latency_and_stability(self) -> None:
        base = self.report("M001")
        self.assertEqual(
            evaluate_hard_gates(base, self.config, self.engineering_for(base)),
            [],
        )

        mutations = (
            ("resource", runtime_metrics(resource_bytes=2_000_000_001), None, "resource"),
            ("oom", runtime_metrics(oom_count=1), None, "OOM"),
            ("crash", runtime_metrics(crash_count=1), None, "crash"),
            ("stability duration", runtime_metrics(stability_seconds=595.0), None, "stability"),
            ("stability failure", runtime_metrics(stability_failures=1), None, "stability"),
            ("stability incomplete", runtime_metrics(stability_completed=False), None, "stability"),
            ("latency", runtime_metrics(), [5_001.0, 100.0], "latency"),
        )
        for name, runtime, latency, expected in mutations:
            with self.subTest(name=name):
                report = self.report("M001", runtime=runtime, latencies_ms=latency)
                self.assertTrue(
                    any(
                        expected.lower() in reason.lower()
                        for reason in evaluate_hard_gates(
                            report,
                            self.config,
                            self.engineering_for(report),
                        )
                    )
                )

    def test_hard_gate_can_use_p95_latency_rule(self) -> None:
        config = SelectionConfig(latency_statistic="p95", bootstrap_samples=100)
        report = self.report("M001", latencies_ms=[5_100.0, 100.0])
        reasons = evaluate_hard_gates(report, config, self.engineering_for(report))
        self.assertTrue(any("latency" in reason for reason in reasons))

    def test_lowest_cer_wins_when_difference_is_meaningful(self) -> None:
        exact = self.report("M001", hypotheses=["小猫AI二零二六", "会议在三点开始"])
        worse = self.report("M002", hypotheses=["小狗", "会议"])
        result = self.select([worse, exact])
        self.assertIsNone(result["winner_alias"])
        self.assertEqual(result["screening_leader_alias"], "M001")
        self.assertEqual(result["status"], "development_screening_only")

    def test_general_practical_cer_boundary_uses_integer_totals(self) -> None:
        references = {
            "schema_version": "1.0",
            "dataset_id": self.manifest["dataset_id"],
            "references": [
                {
                    "clip_id": "clip_000000000000",
                    "text": "甲" * 300,
                    "annotations": [],
                },
                {
                    "clip_id": "clip_000000000001",
                    "text": "丙" * 300,
                    "annotations": [],
                },
            ],
        }

        def scored(
            alias: str,
            substitutions: int,
            *,
            latency_ms: float,
        ) -> tuple[dict, dict]:
            _, _, report, engineering = scored_pair_for(
                self.manifest,
                references,
                self.public_plan,
                self.protocol,
                self.config.to_dict(),
                alias=alias,
                hypotheses=[
                    "乙" * substitutions + "甲" * (300 - substitutions),
                    "丙" * 300,
                ],
                latencies_ms=[latency_ms, latency_ms],
            )
            return report, engineering

        best, best_engineering = scored(
            "M001",
            2,
            latency_ms=900.0,
        )
        for substitutions, expected_basis, expected_practical, expected_leader in (
            (4, "secondary_tiebreak", False, "M002"),
            (5, "lowest_cer", True, "M001"),
            (6, "lowest_cer", True, "M001"),
        ):
            with self.subTest(substitutions=substitutions):
                contender, contender_engineering = scored(
                    "M002",
                    substitutions,
                    latency_ms=100.0,
                )
                result = select_winner(
                    [best, contender],
                    self.config,
                    public_plan=self.public_plan,
                    engineering_reports=[
                        best_engineering,
                        contender_engineering,
                    ],
                )
                self.assertEqual(result["decision_basis"], expected_basis)
                self.assertEqual(
                    result["comparisons"][0]["practically_meaningful"],
                    expected_practical,
                )
                self.assertEqual(
                    result["screening_leader_alias"],
                    expected_leader,
                )

    def test_cer_tie_uses_stability_then_latency_rss_and_size(self) -> None:
        slower = self.report(
            "M001",
            runtime=runtime_metrics(stability_seconds=600, peak_rss_bytes=300_000_000),
            latencies_ms=[900.0, 900.0],
        )
        more_stable = self.report(
            "M002",
            runtime=runtime_metrics(stability_seconds=700, peak_rss_bytes=900_000_000),
            latencies_ms=[1_000.0, 1_000.0],
        )
        result = self.select([slower, more_stable])
        self.assertEqual(result["screening_leader_alias"], "M002")
        self.assertEqual(result["decision_basis"], "secondary_tiebreak")

        equally_stable = self.report(
            "M002",
            runtime=runtime_metrics(
                stability_seconds=600,
                peak_rss_bytes=100_000_000,
            ),
            latencies_ms=[200.0, 200.0],
        )
        result = self.select([slower, equally_stable])
        self.assertEqual(result["screening_leader_alias"], "M002")

    def test_failed_models_are_eliminated_before_cer_and_no_eligible_is_explicit(self) -> None:
        best_but_oom = self.report(
            "M001",
            hypotheses=["小猫AI二零二六", "会议在三点开始"],
            runtime=runtime_metrics(oom_count=1),
        )
        eligible = self.report("M002", hypotheses=["小狗", "会议"])
        result = self.select([best_but_oom, eligible])
        self.assertEqual(result["screening_leader_alias"], "M002")
        self.assertIn("M001", result["eliminated"])

        none = self.select(
            [
                best_but_oom,
                self.report("M002", runtime=runtime_metrics(crash_count=1)),
            ]
        )
        self.assertIsNone(none["winner_alias"])
        self.assertEqual(none["status"], "no_eligible_model")

    def test_selection_is_reproducible_and_records_config(self) -> None:
        first = self.report("M001")
        second = self.report("M002")
        result_a = self.select([first, second])
        result_b = self.select([second, first])
        self.assertEqual(result_a, result_b)
        self.assertEqual(result_a["selection_config"]["practical_cer_delta"], 0.005)

    def test_selection_rejects_cross_run_dataset_normalization_and_plan_mixing(self) -> None:
        first = self.report("M001")
        mutations = []

        different_run = copy.deepcopy(self.report("M002"))
        different_run["run_id"] = "run_ffffffffffff"
        mutations.append(("run_id", different_run))

        different_dataset = copy.deepcopy(self.report("M002"))
        different_dataset["dataset_id"] = "dataset_ffffffffffff"
        mutations.append(("dataset_id", different_dataset))

        alternate_normalization = NormalizationConfig(punctuation="preserve")
        alternate_plan = public_plan_for(
            self.manifest,
            model_count=2,
            protocol=self.protocol,
            normalization=alternate_normalization,
            selection_config=self.config.to_dict(),
        )
        different_normalization = score_model(
            self.manifest,
            self.references,
            model_output_for(
                self.manifest,
                alias="M002",
                public_plan=alternate_plan,
            ),
            alternate_normalization,
            alternate_plan,
            self.protocol,
            self.config.to_dict(),
        )
        mutations.append(("normalization", different_normalization))

        different_plan = copy.deepcopy(self.report("M002"))
        different_plan["evaluation_contract"]["public_plan_sha256"] = "f" * 64
        mutations.append(("public plan", different_plan))

        for label, contender in mutations:
            with self.subTest(label=label):
                with self.assertRaisesRegex(ContractError, "same|share|match"):
                    self.select([first, contender])

    def test_selection_rejects_clip_set_reference_length_and_pcm_mixing(self) -> None:
        first = self.report("M001")

        missing_clip = copy.deepcopy(self.report("M002"))
        missing_clip["sentence_errors"].pop()
        with self.assertRaises(ContractError):
            self.select([first, missing_clip])

        changed_length = copy.deepcopy(self.report("M002"))
        changed_length["sentence_errors"][0]["reference_characters"] += 1
        with self.assertRaises(ContractError):
            self.select([first, changed_length])

        changed_pcm = copy.deepcopy(self.report("M002"))
        changed_pcm["sentence_errors"][0]["pcm_sha256"] = "f" * 64
        with self.assertRaises(ContractError):
            self.select([first, changed_pcm])

    def test_selection_returns_no_winner_for_missing_slice_or_non_target_source(self) -> None:
        missing_manifest = copy.deepcopy(self.manifest)
        missing_manifest["clips"][1]["conditions"].remove("accent")
        missing_plan = public_plan_for(missing_manifest, model_count=2)
        missing_protocol = dataset_protocol_for(missing_manifest)
        _, _, missing_report, missing_engineering = scored_pair_for(
            missing_manifest,
            self.references,
            missing_plan,
            missing_protocol,
            SelectionConfig().to_dict(),
        )
        _, _, missing_peer, missing_peer_engineering = scored_pair_for(
            missing_manifest,
            self.references,
            missing_plan,
            missing_protocol,
            SelectionConfig().to_dict(),
            alias="M002",
        )
        missing_result = select_winner(
            [missing_report, missing_peer],
            SelectionConfig(),
            public_plan=missing_plan,
            engineering_reports=[missing_engineering, missing_peer_engineering],
        )
        self.assertEqual(missing_result["status"], "no_eligible_model")
        self.assertTrue(
            any(
                "accent" in reason
                for reason in missing_result["eliminated"]["M001"]
            )
        )

        synthetic_manifest = copy.deepcopy(self.manifest)
        synthetic_manifest["clips"][0]["source"]["kind"] = "synthetic_fixture"
        synthetic_plan = public_plan_for(synthetic_manifest, model_count=2)
        synthetic_protocol = dataset_protocol_for(synthetic_manifest)
        _, _, synthetic_report, synthetic_engineering = scored_pair_for(
            synthetic_manifest,
            self.references,
            synthetic_plan,
            synthetic_protocol,
            SelectionConfig().to_dict(),
        )
        _, _, synthetic_peer, synthetic_peer_engineering = scored_pair_for(
            synthetic_manifest,
            self.references,
            synthetic_plan,
            synthetic_protocol,
            SelectionConfig().to_dict(),
            alias="M002",
        )
        synthetic_result = select_winner(
            [synthetic_report, synthetic_peer],
            SelectionConfig(),
            public_plan=synthetic_plan,
            engineering_reports=[
                synthetic_engineering,
                synthetic_peer_engineering,
            ],
        )
        self.assertEqual(synthetic_result["status"], "no_eligible_model")
        self.assertTrue(
            any(
                "target-device" in reason
                for reason in synthetic_result["eliminated"]["M001"]
            )
        )

    def test_selection_rejects_malformed_extra_and_internally_forged_reports(self) -> None:
        with self.assertRaises(ContractError):
            select_winner(
                [{"model_alias": "M001"}],
                self.config,
                public_plan=self.public_plan,
            )

        extra = copy.deepcopy(self.report("M001"))
        extra["forged_winner"] = True
        with self.assertRaisesRegex(ContractError, "unknown"):
            select_winner([extra], self.config, public_plan=self.public_plan)

        inconsistent = copy.deepcopy(self.report("M001"))
        inconsistent["metrics"]["cer"] = 0.0
        with self.assertRaisesRegex(ContractError, "CER|cer|inconsistent"):
            select_winner([inconsistent], self.config, public_plan=self.public_plan)


if __name__ == "__main__":
    unittest.main()
