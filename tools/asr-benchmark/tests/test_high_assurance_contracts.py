from __future__ import annotations

import copy
import tempfile
import unittest
from pathlib import Path

from kittyecho_asr_bench.blinding import build_decoder_plan, create_blind_bundle
from kittyecho_asr_bench.contracts import (
    ContractError,
    canonical_sha256,
    load_json,
    validate_dataset_protocol,
    validate_engineering_proof,
    validate_model_output,
    validate_recording_manifest,
)
from kittyecho_asr_bench.metrics import score_model
from kittyecho_asr_bench.normalization import NormalizationConfig
from kittyecho_asr_bench.reveal import reveal_winner
from kittyecho_asr_bench.selection import SelectionConfig, select_winner

from support import (
    blind_bundle_for,
    dataset_protocol_for,
    engineering_proof_for,
    manifest_for,
    model_output_for,
    references_for,
    registry,
    scored_pair_for,
    write_wav,
)


class HighAssuranceBenchmarkContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.repo_root.mkdir()
        audio = self.root / "private" / "clip.wav"
        sha256 = write_wav(audio)
        self.manifest = manifest_for(audio, sha256)
        self.references = references_for()
        self.protocol = dataset_protocol_for(self.manifest)
        self.config = SelectionConfig(bootstrap_samples=50, bootstrap_seed=11)
        self.registry = registry(model_ids=["model_0", "model_1"])
        self.plan, self.private_map = blind_bundle_for(
            self.manifest,
            model_count=2,
            protocol=self.protocol,
            selection_config=self.config.to_dict(),
            registry_snapshot=self.registry,
        )
        self.engineering_by_report_sha256: dict[str, dict] = {}

    def tearDown(self) -> None:
        self.temp.cleanup()

    def report(self, alias: str, hypotheses: list[str] | None = None) -> dict:
        _, _, report, engineering = scored_pair_for(
            self.manifest,
            self.references,
            self.plan,
            self.protocol,
            self.config.to_dict(),
            alias=alias,
            hypotheses=hypotheses,
        )
        self.engineering_by_report_sha256[canonical_sha256(report)] = engineering
        return report

    def engineering_for(self, report: dict) -> dict:
        return self.engineering_by_report_sha256[canonical_sha256(report)]

    def select(self, reports: list[dict]) -> dict:
        return select_winner(
            reports,
            self.config,
            public_plan=self.plan,
            engineering_reports=[self.engineering_for(report) for report in reports],
        )

    def test_manifest_rejects_duplicate_pcm_and_protocol_is_explicitly_preregistered(self) -> None:
        duplicate = copy.deepcopy(self.manifest)
        duplicate["clips"][1]["audio_sha256"] = duplicate["clips"][0]["audio_sha256"]
        with self.assertRaisesRegex(ContractError, "unique|duplicate"):
            validate_recording_manifest(duplicate, self.repo_root)

        validated = validate_dataset_protocol(self.protocol, self.manifest)
        self.assertEqual(validated["approval_status"], "approved")

        missing_parameter = copy.deepcopy(self.protocol)
        del missing_parameter["minimum_speaker_clusters"]
        with self.assertRaisesRegex(ContractError, "minimum_speaker_clusters"):
            validate_dataset_protocol(missing_parameter, self.manifest)

        unapproved = copy.deepcopy(self.protocol)
        unapproved["approval_status"] = "draft"
        with self.assertRaisesRegex(ContractError, "approved"):
            validate_dataset_protocol(unapproved, self.manifest)

    def test_runner_proof_rejects_hash_clock_artifact_telemetry_and_empty_loop_forgery(self) -> None:
        output = model_output_for(self.manifest, public_plan=self.plan)
        valid = engineering_proof_for(self.manifest, public_plan=self.plan)
        self.assertEqual(
            validate_engineering_proof(
                valid,
                output,
                self.plan,
                self.manifest,
                self.protocol,
            )["model_alias"],
            "M001",
        )
        mutations = []

        wrong_file = copy.deepcopy(valid)
        wrong_file["clip_measurements"][0]["runner_evidence"][
            "wav_file_sha256_before"
        ] = "f" * 64
        mutations.append(("WAV", wrong_file))

        wrong_clock = copy.deepcopy(valid)
        wrong_clock["clip_measurements"][0]["runner_evidence"][
            "monotonic_final_ns"
        ] += 1_000_000
        mutations.append(("monotonic", wrong_clock))

        wrong_resource = copy.deepcopy(valid)
        wrong_resource["runner_proof"]["artifact_measurements"][0]["bytes"] -= 1
        mutations.append(("resource", wrong_resource))

        wrong_interval = copy.deepcopy(valid)
        wrong_interval["runner_proof"]["telemetry"]["samples"][1][
            "monotonic_ns"
        ] += 250_000_001
        telemetry = wrong_interval["runner_proof"]["telemetry"]
        telemetry["summary_sha256"] = canonical_sha256(telemetry["samples"])
        proof_without_hash = dict(wrong_interval["runner_proof"])
        proof_without_hash.pop("proof_sha256")
        wrong_interval["runner_proof"]["proof_sha256"] = canonical_sha256(
            proof_without_hash
        )
        mutations.append(("interval", wrong_interval))

        empty_loop = copy.deepcopy(valid)
        empty_loop["runner_proof"]["telemetry"]["loop_count"] = 0
        empty_loop["runner_proof"]["telemetry"]["successful_loop_count"] = 0
        mutations.append(("loop", empty_loop))

        for label, attacked_proof in mutations:
            with self.subTest(label=label):
                with self.assertRaisesRegex(ContractError, label):
                    validate_engineering_proof(
                        attacked_proof,
                        output,
                        self.plan,
                        self.manifest,
                        self.protocol,
                    )

    def test_decoder_plan_contains_only_ordered_audio_inputs_and_no_scoring_metadata(self) -> None:
        decoder_plan = build_decoder_plan(self.manifest, self.plan, "M001")
        assignment = next(
            item for item in self.plan["assignments"] if item["model_alias"] == "M001"
        )
        self.assertEqual(
            [item["clip_id"] for item in decoder_plan["clips"]],
            assignment["clip_order"],
        )
        plan_identity_document = dict(decoder_plan)
        plan_id = plan_identity_document.pop("plan_id")
        self.assertRegex(plan_id, r"^plan_[0-9a-f]{12}$")
        self.assertEqual(
            plan_id,
            f"plan_{canonical_sha256(plan_identity_document)[:12]}",
        )
        serialized = str(decoder_plan).lower()
        self.assertNotIn("condition", serialized)
        self.assertNotIn("reference", serialized)
        self.assertNotIn("speaker", serialized)
        self.assertNotIn("session", serialized)

    def test_score_freezes_every_snapshot_and_rejects_annotation_outside_reference(self) -> None:
        report = self.report("M001")
        fingerprints = report["evaluation_contract"]
        self.assertEqual(fingerprints["manifest_sha256"], canonical_sha256(self.manifest))
        self.assertEqual(
            fingerprints["reference_set_sha256"],
            canonical_sha256(self.references),
        )
        self.assertEqual(
            fingerprints["dataset_protocol_sha256"],
            canonical_sha256(self.protocol),
        )
        self.assertEqual(
            fingerprints["selection_config_sha256"],
            canonical_sha256(self.config.to_dict()),
        )
        self.assertEqual(
            fingerprints["registry_sha256"],
            self.plan["contract_fingerprints"]["registry_sha256"],
        )
        self.assertEqual(
            fingerprints["decoder_plan_sha256"],
            model_output_for(self.manifest, public_plan=self.plan)["decoder_plan_sha256"],
        )

        invalid_references = copy.deepcopy(self.references)
        invalid_references["references"][0]["annotations"][0]["text"] = "不存在"
        with self.assertRaisesRegex(ContractError, "annotation.*reference"):
            score_model(
                self.manifest,
                invalid_references,
                model_output_for(self.manifest, public_plan=self.plan),
                NormalizationConfig(),
                self.plan,
                self.protocol,
                self.config.to_dict(),
            )

    def test_selection_requires_complete_alias_set_and_two_scored_models(self) -> None:
        registry_value = registry(model_ids=["model_0", "model_1", "model_2"])
        plan, _ = blind_bundle_for(
            self.manifest,
            model_count=3,
            protocol=self.protocol,
            selection_config=self.config.to_dict(),
            registry_snapshot=registry_value,
        )
        pairs = [
            scored_pair_for(
                self.manifest,
                self.references,
                plan,
                self.protocol,
                self.config.to_dict(),
                alias=alias,
            )
            for alias in ("M001", "M002")
        ]
        reports = [pair[2] for pair in pairs]
        engineering_reports = [pair[3] for pair in pairs]
        incomplete = select_winner(
            reports,
            self.config,
            public_plan=plan,
            engineering_reports=engineering_reports,
        )
        self.assertEqual(incomplete["status"], "no_result_incomplete_candidate_set")
        self.assertEqual(incomplete["missing_aliases"], ["M003"])

        failure = {
            "schema_version": "1.0",
            "run_id": plan["run_id"],
            "dataset_id": plan["dataset_id"],
            "model_alias": "M003",
            "public_plan_sha256": canonical_sha256(plan),
            "registry_sha256": plan["contract_fingerprints"]["registry_sha256"],
            "reason_code": "runner_failed",
            "attestation_status": "development_untrusted",
        }
        complete = select_winner(
            reports,
            self.config,
            public_plan=plan,
            failure_records=[failure],
            engineering_reports=engineering_reports,
        )
        self.assertEqual(complete["status"], "development_screening_only")
        self.assertIsNotNone(complete["screening_leader_alias"])
        self.assertIn("M003", complete["eliminated"])

        only_one = select_winner(
            reports[:1],
            self.config,
            public_plan=plan,
            failure_records=[
                {**failure, "model_alias": "M002"},
                failure,
            ],
            engineering_reports=engineering_reports[:1],
        )
        self.assertEqual(only_one["status"], "no_result_insufficient_scored_models")
        self.assertIsNone(only_one["winner_alias"])

    def test_protocol_shortfall_is_no_go_and_bootstrap_is_clustered(self) -> None:
        strict_protocol = dataset_protocol_for(
            self.manifest,
            minimum_unique_clips_per_condition=2,
            minimum_speaker_clusters=3,
            minimum_session_clusters=3,
            minimum_joint_clusters=3,
        )
        strict_plan, _ = blind_bundle_for(
            self.manifest,
            model_count=2,
            protocol=strict_protocol,
            selection_config=self.config.to_dict(),
            registry_snapshot=self.registry,
        )
        pairs = [
            scored_pair_for(
                self.manifest,
                self.references,
                strict_plan,
                strict_protocol,
                self.config.to_dict(),
                alias=alias,
            )
            for alias in ("M001", "M002")
        ]
        reports = [pair[2] for pair in pairs]
        result = select_winner(
            reports,
            self.config,
            public_plan=strict_plan,
            engineering_reports=[pair[3] for pair in pairs],
        )
        self.assertEqual(result["status"], "no_eligible_model")
        reasons = " ".join(result["eliminated"]["M001"])
        self.assertIn("minimum unique clips", reasons)
        self.assertIn("cluster", reasons)
        self.assertFalse(reports[0]["dataset_summary"]["generalizability"]["eligible"])

    def test_frozen_exploration_and_production_profiles_match_confirmed_defaults(self) -> None:
        config_root = Path(__file__).resolve().parents[1] / "config"
        exploration = load_json(config_root / "dataset-protocol-exploration-v1.json")
        production = load_json(config_root / "dataset-protocol-production-v1.json")
        for protocol in (exploration, production):
            protocol["approval_status"] = "approved"
            protocol["target_device"]["device_profile_sha256"] = "d" * 64
            if protocol["profile"] == "production_confirmation":
                protocol["exploration_pcm_set_sha256"] = "a" * 64
            validated = validate_dataset_protocol(protocol)
            self.assertEqual(validated["preregistration"]["bootstrap"]["samples"], 10_000)
            self.assertEqual(
                validated["preregistration"]["bootstrap"]["nested_units"],
                ["session", "clip"],
            )
            definitions = validated["preregistration"]["condition_definitions"]
            self.assertEqual(
                definitions["egc_counter_id"],
                "chinese-latin-egc-v1",
            )
            self.assertEqual(
                definitions["code_switch_english_word_counter_id"],
                "nfkc-ascii-alpha-runs-v1",
            )
        self.assertEqual(exploration["preregistration"]["initial_unique_pcm"], 96)
        self.assertEqual(exploration["selection_authority"], "elimination_only")
        self.assertEqual(production["preregistration"]["initial_unique_pcm"], 480)
        self.assertEqual(production["preregistration"]["maximum_unique_pcm"], 720)
        self.assertEqual(production["minimum_unique_clips_per_condition"]["quiet_near"], 320)
        self.assertEqual(
            production["slice_cluster_minimums"]["accent"],
            {"minimum_speakers": 2, "minimum_sessions": 4},
        )

        weakened = copy.deepcopy(production)
        weakened["minimum_unique_clips_per_condition"]["noise"] -= 1
        with self.assertRaisesRegex(ContractError, "frozen"):
            validate_dataset_protocol(weakened)

        self.assertEqual(self.plan["protocol_profile"], "development_fixture")
        self.assertNotIn("baseline_alias", self.plan)

    def test_selection_marks_phase_b_blocker_and_reveal_stays_closed(self) -> None:
        reports = [
            self.report("M001", ["小猫AI二零二六", "会议在三点开始"]),
            self.report("M002", ["小狗", "会议"]),
        ]
        selection = self.select(reports)
        winner_report = next(
            report
            for report in reports
            if report["model_alias"] == selection["screening_leader_alias"]
        )
        self.assertIsNone(selection["winner_alias"])
        self.assertFalse(selection["production_integration_eligible"])
        self.assertIsNotNone(selection["screening_leader_alias"])
        self.assertEqual(selection["trusted_runner_status"], "phase_b_required")
        self.assertIn(
            "trusted_android_runner_attestation",
            selection["production_blockers"],
        )
        with self.assertRaisesRegex(ContractError, "Phase B|production winner"):
            reveal_winner(
                selection,
                winner_report,
                self.private_map,
                self.plan,
                self.registry,
            )

        forged_map = copy.deepcopy(self.private_map)
        forged_map["seed"] += 1
        with self.assertRaisesRegex(ContractError, "Phase B|production winner"):
            reveal_winner(selection, winner_report, forged_map, self.plan, self.registry)

        pending_registry = registry(
            model_ids=["model_0", "model_1"],
            license_state="pending",
        )
        with self.assertRaisesRegex(ContractError, "Phase B|production winner"):
            reveal_winner(
                selection,
                winner_report,
                self.private_map,
                self.plan,
                pending_registry,
            )

    def test_phase_a_reveal_rejects_forged_verified_runner_and_production_flags(self) -> None:
        reports = [
            self.report("M001", ["小猫AI二零二六", "会议在三点开始"]),
            self.report("M002", ["小狗", "会议"]),
        ]
        selection = self.select(reports)
        winner_report = copy.deepcopy(
            next(
                report
                for report in reports
                if report["model_alias"] == selection["screening_leader_alias"]
            )
        )
        forged_selection = {
            **selection,
            "status": "production_winner_selected",
            "winner_alias": selection["screening_leader_alias"],
            "production_integration_eligible": True,
            "trusted_runner_status": "verified",
            "production_blockers": [],
        }
        with self.assertRaisesRegex(ContractError, "Phase A reveal is disabled"):
            reveal_winner(
                forged_selection,
                winner_report,
                self.private_map,
                self.plan,
                self.registry,
            )


if __name__ == "__main__":
    unittest.main()
