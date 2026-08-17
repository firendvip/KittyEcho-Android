from __future__ import annotations

import copy
import hashlib
import json
import os
import stat
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock

import kittyecho_asr_bench.cli as benchmark_cli
import kittyecho_asr_bench.blinding as benchmark_blinding
import kittyecho_asr_bench.contracts as benchmark_contracts
import kittyecho_asr_bench.pcm as benchmark_pcm
from kittyecho_asr_bench.artifacts import (
    commit_json_artifact,
    load_committed_json_artifact,
)
from kittyecho_asr_bench.blinding import build_decoder_plan, create_blind_bundle
from kittyecho_asr_bench.contracts import (
    ContractError,
    build_dataset_protocol,
    canonical_sha256,
    derive_exploration_commitment,
    download_license_blockers,
    formal_benchmark_artifact_blockers,
    internal_evaluation_download_blockers,
    load_json,
    production_distribution_blockers,
    production_license_blockers,
    validate_dataset_protocol,
    validate_engineering_proof,
    validate_model_registry,
    validate_model_output,
    validate_recording_manifest,
)
from kittyecho_asr_bench.metrics import score_model
from kittyecho_asr_bench.engineering import (
    build_engineering_report,
    validate_engineering_report,
)
from kittyecho_asr_bench.normalization import NormalizationConfig
from kittyecho_asr_bench.pcm import validate_manifest_pcm
from kittyecho_asr_bench.selection import (
    _failure_record_closes_candidate,
    _validate_failure_record,
)

from support import (
    blind_bundle_for,
    dataset_protocol_for,
    engineering_proof_for,
    manifest_for,
    model_output_for,
    references_for,
    registry,
    selection_config_dict,
    wav_payload_facts,
    write_json,
    write_wav,
)


class FormalCohortClosureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        audio = self.root / "private" / "clip.wav"
        audio_sha256 = write_wav(audio)
        self.manifest = manifest_for(audio, audio_sha256)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_committed_registry_freezes_the_complete_first_batch_cohort(self) -> None:
        registry_path = (
            Path(__file__).resolve().parents[1]
            / "model-registry"
            / "candidates-v1.json"
        )
        committed = load_json(registry_path)
        cohorts = committed["formal_cohorts"]
        self.assertEqual(len(cohorts), 1)
        cohort = cohorts[0]
        self.assertEqual(cohort["cohort_id"], "first_batch_android_v1")
        self.assertEqual(
            cohort["profiles"],
            ["exploration", "production_confirmation"],
        )
        self.assertEqual(
            cohort["model_ids"],
            [
                "zipformer_baseline",
                "paraformer_int8",
                "fireredasr2_aed_int8",
                "funasr_nano_onnx_int8",
            ],
        )
        self.assertEqual(cohort["baseline_model_id"], "zipformer_baseline")

    def test_each_license_component_independently_blocks_production(self) -> None:
        components = (
            "upstream_weights",
            "conversion",
            "runtime",
            "tokenizer",
            "notice",
        )
        for component in components:
            candidate_registry = registry()
            candidate_registry["models"][0]["licenses"][component][
                "verification_state"
            ] = "pending"
            self.assertEqual(
                production_license_blockers(
                    candidate_registry,
                    "zipformer_baseline",
                ),
                [f"license_component_pending:{component}"],
            )

        cleared = registry()
        cleared["models"][0]["licenses"]["conversion"][
            "verification_state"
        ] = "not_applicable"
        self.assertEqual(
            production_license_blockers(cleared, "zipformer_baseline"),
            [],
        )

        blocked = registry()
        blocked["models"][0]["licenses"]["conversion"][
            "verification_state"
        ] = "blocked"
        self.assertEqual(
            download_license_blockers(blocked, "zipformer_baseline"),
            ["license_component_blocked:conversion"],
        )

    def test_download_registry_freezes_known_artifacts_and_blocks_uncleared_models(
        self,
    ) -> None:
        registry_path = (
            Path(__file__).resolve().parents[1]
            / "model-registry"
            / "candidates-v1.json"
        )
        committed = validate_model_registry(load_json(registry_path))
        models = {
            model["model_id"]: model for model in committed["models"]
        }
        self.assertEqual(
            models["zipformer_baseline"]["official_source"]["revision"],
            "ac54a23c9d106dfbd178be831329fabe261bac58",
        )
        self.assertEqual(
            models["paraformer_int8"]["official_source"]["revision"],
            "fe3e2bbfa0a0d3789b653c4b6cf3f87a5dbf2b94",
        )
        self.assertEqual(
            models["funasr_nano_onnx_int8"]["official_source"]["revision"],
            "fa5143409ad52755517abd6feccce76a8550228a",
        )
        expected_artifacts = {
            "paraformer_int8": {
                "model.int8.onnx": (
                    223_385_835,
                    "9ada9127ca5b82320385ac12340eb8b05dee64fd45cf8cf593ec693826ec2fd7",
                ),
                "tokens.txt": (
                    75_756,
                    "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6",
                ),
            },
            "fireredasr2_aed_int8": {
                "encoder.int8.onnx": (817_286_833, None),
                "decoder.int8.onnx": (417_291_928, None),
                "tokens.txt": (79_172, None),
            },
            "funasr_nano_onnx_int8": {
                "embedding.int8.onnx": (
                    155_584_380,
                    "95e61cd0c9c3b9543339a4cf973c95c116815e745ccc1e0285cbd81f76d18644",
                ),
                "encoder_adaptor.int8.onnx": (
                    237_792_748,
                    "f36dea2e30fbc33b5db1d7a7265cc976c5e5586c77b042d5adb1ad27c72db422",
                ),
                "llm.int8.onnx": (
                    600_356_593,
                    "dfbf9aa3be41bccc257587f151e15c63fbe1b549f2b517f5ccd5bdce3bf4322a",
                ),
                "tokenizer.json": (
                    11_422_654,
                    "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4",
                ),
                "vocab.json": (
                    2_776_833,
                    "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910",
                ),
                "merges.txt": (1_671_853, None),
            },
        }
        for model_id, artifacts in expected_artifacts.items():
            actual = {
                artifact["filename"]: (
                    artifact["expected_bytes"],
                    artifact["sha256"],
                )
                for artifact in models[model_id]["artifacts"]
            }
            for filename, expected in artifacts.items():
                with self.subTest(model_id=model_id, filename=filename):
                    self.assertEqual(actual[filename], expected)
        for model_id, model in models.items():
            with self.subTest(model_id=model_id, artifact="runtime"):
                runtime = next(
                    artifact
                    for artifact in model["artifacts"]
                    if artifact["component"] == "runtime"
                )
                self.assertEqual(runtime["filename"], "sherpa-onnx-1.13.3.aar")
                self.assertEqual(runtime["expected_bytes"], 57_044_841)
                self.assertEqual(
                    runtime["sha256"],
                    "243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6",
                )
        self.assertEqual(
            models["fireredasr2_aed_int8"]["estimated_total_resource_bytes"],
            1_234_657_933,
        )
        self.assertEqual(
            models["funasr_nano_onnx_int8"]["estimated_total_resource_bytes"],
            1_009_605_061,
        )
        for model_id in (
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ):
            self.assertTrue(download_license_blockers(committed, model_id))
            self.assertEqual(
                models[model_id]["runtime"][
                    "android_runtime_compatibility"
                ],
                "compile_pending",
            )
        self.assertEqual(
            download_license_blockers(committed, "zipformer_baseline"),
            [],
        )
        self.assertEqual(
            download_license_blockers(committed, "paraformer_int8"),
            [],
        )
        self.assertEqual(
            models["paraformer_int8"]["runtime"][
                "android_runtime_compatibility"
            ],
            "locally_verified",
        )

    def test_revisions_and_artifact_freeze_are_real_download_gates(self) -> None:
        committed = registry()
        validate_model_registry(committed)

        for revision, revision_kind in (
            ("main", "commit"),
            ("master", "release_asset"),
            ("latest", "release_asset"),
            ("v1.0.0", "release_asset"),
            ("pending", "release_asset"),
            ("abc123", "commit"),
        ):
            candidate = copy.deepcopy(committed)
            source = candidate["models"][0]["official_source"]
            source["revision"] = revision
            source["revision_kind"] = revision_kind
            with self.subTest(revision=revision, kind=revision_kind):
                with self.assertRaises(ContractError):
                    validate_model_registry(candidate)

        for revision, revision_kind in (
            ("main", "commit"),
            ("latest/model.bin", "release_asset"),
            ("v1.0.0", "release_asset"),
        ):
            candidate = copy.deepcopy(committed)
            artifact = candidate["models"][0]["artifacts"][0]
            artifact["source_revision"] = revision
            artifact["source_revision_kind"] = revision_kind
            with self.subTest(artifact_revision=revision):
                with self.assertRaises(ContractError):
                    validate_model_registry(candidate)

        mutable_model_url = copy.deepcopy(committed)
        mutable_model_url["models"][0]["official_source"][
            "model_url"
        ] = "https://huggingface.co/k2-fsa/test-model/tree/main"
        with self.assertRaises(ContractError):
            validate_model_registry(mutable_model_url)

        mutable_artifact_url = copy.deepcopy(committed)
        mutable_artifact_url["models"][0]["artifacts"][0][
            "source_url"
        ] = "https://huggingface.co/k2-fsa/test-model/blob/main/model.bin"
        with self.assertRaises(ContractError):
            validate_model_registry(mutable_artifact_url)

        pending = copy.deepcopy(committed)
        artifact = pending["models"][0]["artifacts"][0]
        artifact["expected_bytes"] = None
        artifact["sha256"] = None
        artifact["verification_state"] = "post_download_freeze_required"
        validate_model_registry(pending)
        blockers = formal_benchmark_artifact_blockers(
            pending,
            "zipformer_baseline",
        )
        self.assertIn("untrusted_registry_snapshot", blockers)
        self.assertIn(
            "artifact_freeze_receipt_required:tokens.txt",
            blockers,
        )

        for field in ("expected_bytes", "sha256"):
            invalid = copy.deepcopy(committed)
            invalid["models"][0]["artifacts"][0][field] = None
            with self.subTest(verified_missing=field):
                with self.assertRaises(ContractError):
                    validate_model_registry(invalid)

    def test_internal_download_and_production_distribution_are_separate(self) -> None:
        registry_path = (
            Path(__file__).resolve().parents[1]
            / "model-registry"
            / "candidates-v1.json"
        )
        committed = validate_model_registry(load_json(registry_path))
        models = {
            model["model_id"]: model for model in committed["models"]
        }

        self.assertEqual(
            internal_evaluation_download_blockers(
                committed,
                "zipformer_baseline",
            ),
            [],
        )
        self.assertEqual(
            internal_evaluation_download_blockers(
                committed,
                "paraformer_int8",
            ),
            [],
        )
        for model_id in (
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ):
            with self.subTest(model_id=model_id):
                self.assertEqual(
                    internal_evaluation_download_blockers(
                        committed,
                        model_id,
                    ),
                    ["user_risk_acceptance_receipt_required"],
                )

        for model_id in models:
            with self.subTest(production_model_id=model_id):
                blockers = production_distribution_blockers(
                    committed,
                    model_id,
                )
                if model_id == "paraformer_int8":
                    self.assertEqual(blockers, [])
                else:
                    self.assertIn(
                        "production_distribution_review_required",
                        blockers,
                    )

        self.assertEqual(
            models["paraformer_int8"]["official_source"]["revision"],
            "fe3e2bbfa0a0d3789b653c4b6cf3f87a5dbf2b94",
        )
        self.assertEqual(
            models["paraformer_int8"]["evaluation_policy"][
                "production_distribution"
            ]["clearance"],
            "cleared_by_product_decision",
        )
        paraformer = {
            artifact["filename"]: artifact
            for artifact in models["paraformer_int8"]["artifacts"]
        }
        self.assertEqual(
            paraformer["model.int8.onnx"]["expected_bytes"],
            223_385_835,
        )
        self.assertEqual(
            paraformer["model.int8.onnx"]["source_revision"],
            "fe3e2bbfa0a0d3789b653c4b6cf3f87a5dbf2b94",
        )
        self.assertEqual(
            paraformer["tokens.txt"]["sha256"],
            "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6",
        )

        nano = {
            artifact["filename"]: artifact
            for artifact in models["funasr_nano_onnx_int8"]["artifacts"]
        }
        self.assertEqual(nano["merges.txt"]["expected_bytes"], 1_671_853)
        self.assertIsNone(nano["merges.txt"]["sha256"])
        self.assertEqual(
            nano["merges.txt"]["verification_state"],
            "post_download_freeze_required",
        )
        for model_id in (
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ):
            self.assertEqual(
                models[model_id]["licenses"]["notice"]["verification_state"],
                "not_applicable",
            )
        self.assertEqual(
            models["paraformer_int8"]["licenses"]["notice"][
                "verification_state"
            ],
            "verified",
        )

    def test_model_gate_cli_reports_only_internal_zipformer_and_paraformer_go(
        self,
    ) -> None:
        registry_path = (
            Path(__file__).resolve().parents[1]
            / "model-registry"
            / "candidates-v1.json"
        )
        for purpose, model_id, expected_status in (
            (
                "internal_evaluation_download",
                "zipformer_baseline",
                "GO",
            ),
            (
                "internal_evaluation_download",
                "paraformer_int8",
                "GO",
            ),
            (
                "internal_evaluation_download",
                "fireredasr2_aed_int8",
                "NO_GO",
            ),
            (
                "production_distribution",
                "zipformer_baseline",
                "NO_GO",
            ),
            (
                "production_distribution",
                "paraformer_int8",
                "GO",
            ),
        ):
            arguments = benchmark_cli._parser().parse_args(
                [
                    "model-gate",
                    "--registry",
                    str(registry_path),
                    "--model-id",
                    model_id,
                    "--purpose",
                    purpose,
                ]
            )
            result = json.loads(benchmark_cli._execute(arguments))
            with self.subTest(purpose=purpose, model_id=model_id):
                self.assertEqual(result["status"], expected_status)
                self.assertEqual(result["model_id"], model_id)
                self.assertEqual(result["purpose"], purpose)

    def test_formal_blind_model_input_must_exactly_equal_frozen_cohort(self) -> None:
        formal_registry = registry(
            model_ids=[
                "zipformer_baseline",
                "paraformer_int8",
                "fireredasr2_aed_int8",
                "funasr_nano_onnx_int8",
            ],
            formal_cohort=True,
        )
        exploration = dataset_protocol_for(self.manifest)
        exploration["profile"] = "exploration"
        exact_models = [
            "zipformer_baseline",
            "paraformer_int8",
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ]

        selected, baseline, cohort_id = (
            benchmark_cli._validate_blind_model_selection(
                exact_models,
                formal_registry,
                exploration,
            )
        )
        self.assertEqual(selected, exact_models)
        self.assertEqual(baseline, "zipformer_baseline")
        self.assertEqual(cohort_id, "first_batch_android_v1")

        with self.assertRaisesRegex(ContractError, "complete frozen formal cohort"):
            benchmark_cli._validate_blind_model_selection(
                exact_models[:-1],
                formal_registry,
                exploration,
            )

        development = dataset_protocol_for(self.manifest)
        selected, baseline, cohort_id = (
            benchmark_cli._validate_blind_model_selection(
                exact_models[:2],
                formal_registry,
                development,
            )
        )
        self.assertEqual(selected, exact_models[:2])
        self.assertEqual(baseline, "zipformer_baseline")
        self.assertEqual(cohort_id, "development_subset")

    def test_public_plan_hides_baseline_identity_behind_private_commitment(self) -> None:
        formal_registry = registry(
            model_ids=[
                "zipformer_baseline",
                "paraformer_int8",
                "fireredasr2_aed_int8",
                "funasr_nano_onnx_int8",
            ],
            formal_cohort=True,
        )
        protocol = dataset_protocol_for(self.manifest)
        plan, private_map = blind_bundle_for(
            self.manifest,
            model_count=4,
            protocol=protocol,
            registry_snapshot=formal_registry,
        )
        self.assertNotIn("baseline_alias", plan)
        self.assertIn("baseline_commitment_sha256", plan)
        self.assertIn("baseline_alias", private_map)
        self.assertIn("baseline_commitment_nonce", private_map)
        expected_commitment = canonical_sha256(
            {
                "run_id": plan["run_id"],
                "baseline_alias": private_map["baseline_alias"],
                "nonce": private_map["baseline_commitment_nonce"],
            }
        )
        self.assertEqual(plan["baseline_commitment_sha256"], expected_commitment)
        public_text = str(plan).lower()
        self.assertNotIn("zipformer", public_text)
        self.assertNotIn("baseline_alias", public_text)

    def test_formal_failure_record_remains_unresolved_until_phase_b_attestation(self) -> None:
        protocol = dataset_protocol_for(self.manifest)
        plan, _ = blind_bundle_for(
            self.manifest,
            model_count=2,
            protocol=protocol,
        )
        formal_plan = copy.deepcopy(plan)
        formal_plan["protocol_profile"] = "exploration"
        failure = {
            "schema_version": "1.0",
            "run_id": formal_plan["run_id"],
            "dataset_id": formal_plan["dataset_id"],
            "model_alias": "M002",
            "public_plan_sha256": canonical_sha256(formal_plan),
            "registry_sha256": formal_plan["contract_fingerprints"][
                "registry_sha256"
            ],
            "reason_code": "runner_failed",
            "attestation_status": "phase_b_attestation_required",
        }
        validated = _validate_failure_record(failure, plan=formal_plan)
        self.assertFalse(
            _failure_record_closes_candidate(validated, plan=formal_plan)
        )

        forged = copy.deepcopy(failure)
        forged["attestation_status"] = "development_untrusted"
        with self.assertRaisesRegex(ContractError, "Phase B attestation"):
            _validate_failure_record(forged, plan=formal_plan)


class FrozenDatasetStructureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.repo_root.mkdir()
        audio = self.root / "private" / "seed.wav"
        audio_sha256 = write_wav(audio)
        self.base_manifest = manifest_for(audio, audio_sha256)

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def _digest(label: str) -> str:
        return hashlib.sha256(label.encode("utf-8")).hexdigest()

    def _formal_manifest(
        self,
        profile: str,
        *,
        speaker_count: int,
    ) -> dict:
        if profile == "exploration":
            rounds = [(0, 2, 24)]
        else:
            rounds = [(0, 6, 40)]
            if speaker_count >= 8:
                rounds.append((1, 2, 30))
            if speaker_count >= 10:
                rounds.append((2, 2, 30))
        template = self.base_manifest["clips"][0]
        clips = []
        speaker_cohorts = []
        speaker_offset = 0
        initial_anchor_ids: dict[int, list[str]] = {}
        slice_counts = (
            {
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
            if profile == "exploration"
            else {
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
        )
        for round_index, round_speakers, sentences_per_session in rounds:
            speaker_ids = [
                f"speaker_{speaker_offset + index + 1:012x}"
                for index in range(round_speakers)
            ]
            speaker_offset += round_speakers
            speaker_cohorts.append(
                {
                    "round_index": round_index,
                    "speaker_cluster_ids": speaker_ids,
                    "sentences_per_session": sentences_per_session,
                }
            )
            for session_index in (1, 2):
                anchor_count = sentences_per_session // 2
                if round_index == 0:
                    anchor_ids = [
                        f"sentence_{0x100000 + session_index * 0x1000 + index:012x}"
                        for index in range(anchor_count)
                    ]
                    initial_anchor_ids[session_index] = anchor_ids
                else:
                    anchor_ids = initial_anchor_ids[session_index][:anchor_count]
                for speaker_position, speaker_id in enumerate(speaker_ids):
                    session_id = (
                        f"session_{round_index * 0x10000 + session_index * 0x1000 + speaker_position + 1:012x}"
                    )
                    for sentence_position in range(sentences_per_session):
                        clip_index = len(clips) + 1
                        clip = copy.deepcopy(template)
                        clip["clip_id"] = f"clip_{clip_index:012x}"
                        clip["audio_path"] = str(
                            self.root / "private" / f"formal-{clip_index}.wav"
                        )
                        clip["audio_sha256"] = self._digest(f"wav-{profile}-{clip_index}")
                        clip["pcm_payload_sha256"] = self._digest(
                            f"pcm-{profile}-{clip_index}"
                        )
                        clip["speaker_cluster_id"] = speaker_id
                        clip["session_cluster_id"] = session_id
                        clip["session_index"] = session_index
                        clip["hours_since_previous_session"] = (
                            None if session_index == 1 else 12.0
                        )
                        if sentence_position < anchor_count:
                            clip["sentence_id"] = anchor_ids[sentence_position]
                            clip["prompt_kind"] = "common_anchor"
                        else:
                            clip["sentence_id"] = (
                                f"sentence_{0x200000 + clip_index:012x}"
                            )
                            clip["prompt_kind"] = "coverage"
                        position = clip_index - 1
                        conditions = [
                            (
                                "quiet_near"
                                if position < slice_counts["quiet_near"]
                                or position
                                >= slice_counts["quiet_near"]
                                + slice_counts["noise"]
                                else "noise"
                            )
                        ]
                        if position < slice_counts["fast"]:
                            conditions.append("fast")
                        elif position < (
                            slice_counts["fast"] + slice_counts["slow"]
                        ):
                            conditions.append("slow")
                        if position < slice_counts["immediate_start"]:
                            conditions.append("immediate_start")
                        if position < slice_counts["short"]:
                            conditions.append("short")
                        elif position < (
                            slice_counts["short"]
                            + slice_counts["normal_short"]
                        ):
                            conditions.append("normal_short")
                        elif position < (
                            slice_counts["short"]
                            + slice_counts["normal_short"]
                            + slice_counts["long"]
                        ):
                            conditions.append("long")
                        else:
                            conditions.append("normal_short")
                        for condition in (
                            "proper_name_number",
                            "code_switch",
                            "accent",
                        ):
                            if position < slice_counts[condition]:
                                conditions.append(condition)
                        clip["conditions"] = conditions
                        clips.append(clip)

        manifest = {
            **self.base_manifest,
            "dataset_id": (
                "dataset_111111111111"
                if profile == "exploration"
                else "dataset_222222222222"
            ),
            "dataset_usage": {
                "recording_purpose": profile,
                "used_for_model_tuning": False,
            },
            "speaker_cohorts": speaker_cohorts,
            "frozen_exploration_pcm_set": None,
            "clips": clips,
        }
        return manifest

    def _formal_public_plan(
        self,
        manifest: dict,
        protocol: dict,
        *,
        dataset_evidence: object | None = None,
    ) -> dict:
        model_ids = [
            "zipformer_baseline",
            "paraformer_int8",
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ]
        registry_snapshot = registry(
            model_ids=model_ids,
            formal_cohort=True,
        )
        fingerprint_manifest = (
            benchmark_contracts.verified_dataset_document(dataset_evidence)
            if dataset_evidence is not None
            else manifest
        )
        fingerprints = {
            "manifest_sha256": canonical_sha256(fingerprint_manifest),
            "dataset_protocol_sha256": canonical_sha256(protocol),
            "normalization_sha256": NormalizationConfig().fingerprint(),
            "selection_config_sha256": canonical_sha256(
                selection_config_dict()
            ),
            "registry_sha256": canonical_sha256(registry_snapshot),
        }
        plan = create_blind_bundle(
            dataset_evidence if dataset_evidence is not None else manifest,
            model_ids,
            seed=(1 << 127) + 42,
            contract_fingerprints=fingerprints,
            baseline_model_id="zipformer_baseline",
            protocol_profile=(
                protocol["profile"]
                if dataset_evidence is not None
                else "development_fixture"
            ),
            cohort_id=(
                "first_batch_android_v1"
                if dataset_evidence is not None
                else "development_subset"
            ),
        )[0]
        if dataset_evidence is None:
            plan["protocol_profile"] = protocol["profile"]
            plan["cohort_id"] = "first_batch_android_v1"
        return plan

    def _materialize_formal_pcm(
        self,
        manifest: dict,
        *,
        prefix: str,
        frame_offset: int,
    ) -> None:
        for index, clip in enumerate(manifest["clips"], start=1):
            path = (
                self.root
                / "private"
                / "formal-audio"
                / f"{prefix}-{index}.wav"
            )
            clip["audio_path"] = str(path)
            clip["audio_sha256"] = write_wav(
                path,
                frame_count=frame_offset + index,
            )
            (
                clip["pcm_payload_sha256"],
                clip["pcm_payload_bytes"],
            ) = wav_payload_facts(path)

    def _exploration_evidence(
        self,
        exploration: dict,
    ) -> tuple[dict, dict, dict, object]:
        if not all(
            Path(clip["audio_path"]).is_file()
            for clip in exploration["clips"]
        ):
            self._materialize_formal_pcm(
                exploration,
                prefix="frozen-exploration",
                frame_offset=10,
            )
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        evidence_root = self.root / "private" / "frozen-exploration"
        snapshot_root = evidence_root / "snapshots"
        manifest_path = evidence_root / "manifest.json"
        plan_path = evidence_root / "public-plan.json"
        protocol_path = evidence_root / "protocol.json"
        write_json(manifest_path, exploration)
        write_json(protocol_path, protocol)
        dataset_evidence = benchmark_contracts.verify_dataset_evidence(
            manifest_path,
            self.repo_root,
            snapshot_root=snapshot_root,
        )
        plan = self._formal_public_plan(
            exploration,
            protocol,
            dataset_evidence=dataset_evidence,
        )
        commit_json_artifact(
            plan_path,
            plan,
            artifact_kind="blind-public-plan",
            run_id=plan["run_id"],
            evidence_sha256=benchmark_contracts.benchmark_evidence_sha256(
                dataset_evidence,
                "exploration",
            ),
            plan_sha256=canonical_sha256(plan),
            validate_evidence=lambda: (
                benchmark_contracts.verified_dataset_document(
                    dataset_evidence
                )
                and None
            ),
        )
        evidence = benchmark_contracts.verify_exploration_evidence(
            manifest_path,
            plan_path,
            protocol_path,
            self.repo_root,
            snapshot_root=snapshot_root,
        )
        commitment = derive_exploration_commitment(evidence)
        return protocol, plan, commitment, evidence

    def _verified_production_manifest(
        self,
        manifest: dict,
        *,
        label: str,
    ) -> object:
        path = self.root / "private" / "production-evidence" / f"{label}.json"
        write_json(path, manifest)
        return benchmark_contracts.verify_dataset_evidence(
            path,
            self.repo_root,
            snapshot_root=(
                self.root
                / "private"
                / "production-evidence"
                / f"{label}-snapshots"
            ),
        )

    def test_approved_formal_protocol_rejects_zero_device_sentinel(self) -> None:
        zero_device = {
            **self.base_manifest["target_device"],
            "device_profile_sha256": "0" * 64,
        }
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=zero_device,
            approval_status="approved",
        )
        with self.assertRaisesRegex(ContractError, "device profile.*Phase B"):
            validate_dataset_protocol(protocol)

    def test_exploration_enforces_two_speakers_two_sessions_and_24_each(self) -> None:
        manifest = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            manifest,
            prefix="exploration-structure",
            frame_offset=10,
        )
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=manifest["target_device"],
            approval_status="approved",
        )
        validate_recording_manifest(manifest, self.repo_root)
        manifest_evidence = self._verified_production_manifest(
            manifest,
            label="exploration-structure-valid",
        )
        validate_dataset_protocol(protocol, manifest_evidence)

        missing_clip = copy.deepcopy(manifest)
        missing_clip["clips"].pop()
        missing_evidence = self._verified_production_manifest(
            missing_clip,
            label="exploration-structure-missing",
        )
        with self.assertRaisesRegex(ContractError, "24 clips"):
            validate_dataset_protocol(protocol, missing_evidence)

        wrong_cohort = copy.deepcopy(manifest)
        wrong_cohort["speaker_cohorts"][0]["speaker_cluster_ids"].pop()
        wrong_cohort_evidence = self._verified_production_manifest(
            wrong_cohort,
            label="exploration-structure-cohort",
        )
        with self.assertRaisesRegex(ContractError, "speaker cohort"):
            validate_dataset_protocol(protocol, wrong_cohort_evidence)

    def test_production_extensions_are_exact_480_600_or_720_structures(self) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        (
            _exploration_protocol,
            _exploration_plan,
            commitment,
            exploration_evidence,
        ) = self._exploration_evidence(exploration)
        for speaker_count, expected_clips in ((6, 480), (8, 600), (10, 720)):
            with self.subTest(speaker_count=speaker_count):
                manifest = self._formal_manifest(
                    "production_confirmation",
                    speaker_count=speaker_count,
                )
                manifest["frozen_exploration_pcm_set"] = commitment
                self._materialize_formal_pcm(
                    manifest,
                    prefix=f"production-{speaker_count}",
                    frame_offset=200,
                )
                protocol = build_dataset_protocol(
                    "production_confirmation",
                    protocol_id="protocol_222222222222",
                    target_device=manifest["target_device"],
                    approval_status="approved",
                    exploration_pcm_set_sha256=commitment["commitment_sha256"],
                )
                self.assertEqual(len(manifest["clips"]), expected_clips)
                validate_recording_manifest(manifest, self.repo_root)
                production_evidence = self._verified_production_manifest(
                    manifest,
                    label=f"production-{speaker_count}",
                )
                validate_dataset_protocol(
                    protocol,
                    production_evidence,
                    exploration_evidence=exploration_evidence,
                )

        invalid = self._formal_manifest(
            "production_confirmation",
            speaker_count=8,
        )
        invalid["frozen_exploration_pcm_set"] = commitment
        self._materialize_formal_pcm(
            invalid,
            prefix="production-invalid-extension",
            frame_offset=200,
        )
        extension_speaker = invalid["speaker_cohorts"][1]["speaker_cluster_ids"][0]
        target_clip_id = next(
            clip["clip_id"]
            for clip in reversed(invalid["clips"])
            if clip["speaker_cluster_id"] == extension_speaker
            and clip["session_index"] == 2
        )
        invalid["clips"] = [
            clip
            for clip in invalid["clips"]
            if clip["clip_id"] != target_clip_id
        ]
        protocol = build_dataset_protocol(
            "production_confirmation",
            protocol_id="protocol_222222222222",
            target_device=invalid["target_device"],
            approval_status="approved",
            exploration_pcm_set_sha256=commitment["commitment_sha256"],
        )
        invalid_evidence = self._verified_production_manifest(
            invalid,
            label="production-invalid-extension",
        )
        with self.assertRaisesRegex(ContractError, "30 clips"):
            validate_dataset_protocol(
                protocol,
                invalid_evidence,
                exploration_evidence=exploration_evidence,
            )

    def test_anchor_labels_must_reuse_the_same_sentence_ids_across_sessions(self) -> None:
        manifest = self._formal_manifest("exploration", speaker_count=2)
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=manifest["target_device"],
            approval_status="approved",
        )
        forged = copy.deepcopy(manifest)
        second_speaker = forged["speaker_cohorts"][0]["speaker_cluster_ids"][1]
        for clip in forged["clips"]:
            if (
                clip["speaker_cluster_id"] == second_speaker
                and clip["prompt_kind"] == "common_anchor"
            ):
                clip["sentence_id"] = f"sentence_{0x900000 + int(clip['clip_id'][5:], 16):012x}"
        self._materialize_formal_pcm(
            forged,
            prefix="exploration-anchor-forgery",
            frame_offset=10,
        )
        forged_evidence = self._verified_production_manifest(
            forged,
            label="exploration-anchor-forgery",
        )
        with self.assertRaisesRegex(ContractError, "anchor sentence"):
            validate_dataset_protocol(protocol, forged_evidence)

    def test_production_binds_and_is_disjoint_from_frozen_exploration_pcm(self) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        (
            _exploration_protocol,
            _exploration_plan,
            commitment,
            exploration_evidence,
        ) = self._exploration_evidence(exploration)
        production = self._formal_manifest(
            "production_confirmation",
            speaker_count=6,
        )
        production["frozen_exploration_pcm_set"] = commitment
        self._materialize_formal_pcm(
            production,
            prefix="production-disjoint",
            frame_offset=200,
        )
        protocol = build_dataset_protocol(
            "production_confirmation",
            protocol_id="protocol_222222222222",
            target_device=production["target_device"],
            approval_status="approved",
            exploration_pcm_set_sha256=commitment["commitment_sha256"],
        )
        production_evidence = self._verified_production_manifest(
            production,
            label="production-disjoint",
        )
        validate_dataset_protocol(
            protocol,
            production_evidence,
            exploration_evidence=exploration_evidence,
        )

        overlap = copy.deepcopy(production)
        overlap["clips"][0]["audio_path"] = exploration["clips"][0][
            "audio_path"
        ]
        for field in (
            "audio_sha256",
            "pcm_payload_sha256",
            "pcm_payload_bytes",
        ):
            overlap["clips"][0][field] = exploration["clips"][0][field]
        overlap_evidence = self._verified_production_manifest(
            overlap,
            label="production-overlap",
        )
        with self.assertRaisesRegex(ContractError, "actual exploration"):
            validate_dataset_protocol(
                protocol,
                overlap_evidence,
                exploration_evidence=exploration_evidence,
            )

        forged_commitment = copy.deepcopy(production)
        forged_commitment["frozen_exploration_pcm_set"]["commitment_sha256"] = "f" * 64
        forged_evidence = self._verified_production_manifest(
            forged_commitment,
            label="production-forged-commitment",
        )
        with self.assertRaisesRegex(ContractError, "exploration.*commitment"):
            validate_dataset_protocol(
                protocol,
                forged_evidence,
                exploration_evidence=exploration_evidence,
            )

    def test_production_rejects_forged_hash_list_when_actual_exploration_pcm_is_reused(self) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        (
            _exploration_protocol,
            _exploration_plan,
            actual_commitment,
            exploration_evidence,
        ) = self._exploration_evidence(exploration)
        forged_material = {
            "dataset_id": exploration["dataset_id"],
            "manifest_sha256": actual_commitment["manifest_sha256"],
            "public_plan_sha256": actual_commitment["public_plan_sha256"],
            "dataset_protocol_sha256": actual_commitment[
                "dataset_protocol_sha256"
            ],
            "audio_sha256": [
                self._digest(f"forged-wav-{index}") for index in range(96)
            ],
            "pcm_payload_sha256": [
                self._digest(f"forged-pcm-{index}") for index in range(96)
            ],
        }
        forged_commitment = {
            "dataset_id": forged_material["dataset_id"],
            "manifest_sha256": forged_material["manifest_sha256"],
            "public_plan_sha256": forged_material["public_plan_sha256"],
            "dataset_protocol_sha256": forged_material[
                "dataset_protocol_sha256"
            ],
            "commitment_sha256": canonical_sha256(forged_material),
        }
        production = self._formal_manifest(
            "production_confirmation",
            speaker_count=6,
        )
        self._materialize_formal_pcm(
            production,
            prefix="production-forged-list",
            frame_offset=200,
        )
        production["frozen_exploration_pcm_set"] = forged_commitment
        production["clips"][0]["audio_path"] = exploration["clips"][0][
            "audio_path"
        ]
        for field in (
            "audio_sha256",
            "pcm_payload_sha256",
            "pcm_payload_bytes",
        ):
            production["clips"][0][field] = exploration["clips"][0][field]
        production_protocol = build_dataset_protocol(
            "production_confirmation",
            protocol_id="protocol_222222222222",
            target_device=production["target_device"],
            approval_status="approved",
            exploration_pcm_set_sha256=forged_commitment[
                "commitment_sha256"
            ],
        )
        production_evidence = self._verified_production_manifest(
            production,
            label="production-forged-list",
        )

        with self.assertRaisesRegex(ContractError, "actual frozen exploration"):
            validate_dataset_protocol(
                production_protocol,
                production_evidence,
                exploration_evidence=exploration_evidence,
            )

    def test_plain_mappings_with_missing_formal_wavs_cannot_be_verified_evidence(self) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        exploration_protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        exploration_plan = self._formal_public_plan(
            exploration,
            exploration_protocol,
        )
        with self.assertRaisesRegex(ContractError, "filesystem-backed"):
            derive_exploration_commitment(
                exploration,
                exploration_plan,
                exploration_protocol,
            )

        commitment_material = {
            "dataset_id": exploration["dataset_id"],
            "manifest_sha256": canonical_sha256(exploration),
            "public_plan_sha256": canonical_sha256(exploration_plan),
            "dataset_protocol_sha256": canonical_sha256(
                exploration_protocol
            ),
            "audio_sha256": sorted(
                clip["audio_sha256"] for clip in exploration["clips"]
            ),
            "pcm_payload_sha256": sorted(
                clip["pcm_payload_sha256"]
                for clip in exploration["clips"]
            ),
        }
        commitment = {
            "dataset_id": commitment_material["dataset_id"],
            "manifest_sha256": commitment_material["manifest_sha256"],
            "public_plan_sha256": commitment_material[
                "public_plan_sha256"
            ],
            "dataset_protocol_sha256": commitment_material[
                "dataset_protocol_sha256"
            ],
            "commitment_sha256": canonical_sha256(commitment_material),
        }
        production = self._formal_manifest(
            "production_confirmation",
            speaker_count=6,
        )
        production["frozen_exploration_pcm_set"] = commitment
        production_protocol = build_dataset_protocol(
            "production_confirmation",
            protocol_id="protocol_222222222222",
            target_device=production["target_device"],
            approval_status="approved",
            exploration_pcm_set_sha256=commitment["commitment_sha256"],
        )
        with self.assertRaisesRegex(ContractError, "filesystem-backed"):
            validate_dataset_protocol(
                production_protocol,
                production,
                exploration_manifest=exploration,
                exploration_public_plan=exploration_plan,
                exploration_dataset_protocol=exploration_protocol,
            )

        production_entry_points = (
            lambda: validate_model_output(
                {},
                {},
                production,
                production_protocol,
            ),
            lambda: validate_engineering_proof(
                {},
                {},
                {},
                production,
                production_protocol,
            ),
            lambda: score_model(
                production,
                {},
                {},
                NormalizationConfig(),
                {},
                production_protocol,
                selection_config_dict(),
            ),
            lambda: build_engineering_report(
                {},
                {},
                {},
                {},
                production,
                production_protocol,
            ),
        )
        for entry_point in production_entry_points:
            with self.subTest(entry_point=entry_point):
                with self.assertRaisesRegex(ContractError, "filesystem-backed"):
                    entry_point()

    def test_formal_artifact_entry_points_reject_plain_manifest_mappings(self) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        plan = self._formal_public_plan(exploration, protocol)
        model_ids = [
            "zipformer_baseline",
            "paraformer_int8",
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ]
        entry_points = (
            lambda: create_blind_bundle(
                exploration,
                model_ids,
                seed=(1 << 127) + 43,
                contract_fingerprints=plan["contract_fingerprints"],
                baseline_model_id="zipformer_baseline",
                protocol_profile="exploration",
                cohort_id="first_batch_android_v1",
            ),
            lambda: build_decoder_plan(exploration, plan, "M001"),
            lambda: benchmark_contracts.decoder_plan_document(
                exploration,
                plan,
                "M001",
            ),
            lambda: benchmark_contracts.validate_public_plan(
                plan,
                exploration,
            ),
        )
        for entry_point in entry_points:
            with self.subTest(entry_point=entry_point):
                with self.assertRaisesRegex(
                    ContractError,
                    "filesystem-backed",
                ):
                    entry_point()

    def test_formal_artifact_entry_points_use_frozen_evidence_after_source_changes(
        self,
    ) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="formal-artifact-entry",
            frame_offset=10,
        )
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        manifest_path = (
            self.root
            / "private"
            / "formal-artifact-entry"
            / "manifest.json"
        )
        write_json(manifest_path, exploration)
        evidence = benchmark_contracts.verify_dataset_evidence(
            manifest_path,
            self.repo_root,
            snapshot_root=(
                self.root
                / "private"
                / "formal-artifact-entry"
                / "snapshots"
            ),
        )
        model_ids = [
            "zipformer_baseline",
            "paraformer_int8",
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ]
        fingerprints = {
            "manifest_sha256": canonical_sha256(
                benchmark_contracts.verified_dataset_document(evidence)
            ),
            "dataset_protocol_sha256": canonical_sha256(protocol),
            "normalization_sha256": NormalizationConfig().fingerprint(),
            "selection_config_sha256": canonical_sha256(
                selection_config_dict()
            ),
            "registry_sha256": canonical_sha256(
                registry(model_ids=model_ids, formal_cohort=True)
            ),
        }
        plan, _private_map = create_blind_bundle(
            evidence,
            model_ids,
            seed=(1 << 127) + 44,
            contract_fingerprints=fingerprints,
            baseline_model_id="zipformer_baseline",
            protocol_profile="exploration",
            cohort_id="first_batch_android_v1",
        )
        self.assertEqual(
            build_decoder_plan(evidence, plan, "M001")["model_alias"],
            "M001",
        )
        self.assertEqual(
            benchmark_contracts.decoder_plan_document(
                evidence,
                plan,
                "M001",
            )["model_alias"],
            "M001",
        )
        self.assertEqual(
            benchmark_contracts.validate_public_plan(
                plan,
                evidence,
            ),
            plan,
        )

        first_wav = Path(exploration["clips"][0]["audio_path"])
        write_wav(first_wav, frame_count=999)
        entry_points = (
            lambda: create_blind_bundle(
                evidence,
                model_ids,
                seed=(1 << 127) + 44,
                contract_fingerprints=fingerprints,
                baseline_model_id="zipformer_baseline",
                protocol_profile="exploration",
                cohort_id="first_batch_android_v1",
            ),
            lambda: build_decoder_plan(evidence, plan, "M001"),
            lambda: benchmark_contracts.decoder_plan_document(
                evidence,
                plan,
                "M001",
            ),
            lambda: benchmark_contracts.validate_public_plan(
                plan,
                evidence,
            ),
        )
        for entry_point in entry_points:
            with self.subTest(entry_point=entry_point):
                entry_point()

    def test_production_artifact_entries_require_both_registered_evidences(self) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        (
            _exploration_protocol,
            _exploration_plan,
            commitment,
            exploration_evidence,
        ) = self._exploration_evidence(exploration)
        production = self._formal_manifest(
            "production_confirmation",
            speaker_count=6,
        )
        production["frozen_exploration_pcm_set"] = commitment
        self._materialize_formal_pcm(
            production,
            prefix="production-artifact-entry",
            frame_offset=200,
        )
        production_protocol = build_dataset_protocol(
            "production_confirmation",
            protocol_id="protocol_222222222222",
            target_device=production["target_device"],
            approval_status="approved",
            exploration_pcm_set_sha256=commitment["commitment_sha256"],
        )
        production_evidence = self._verified_production_manifest(
            production,
            label="production-artifact-entry",
        )
        model_ids = [
            "zipformer_baseline",
            "paraformer_int8",
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ]
        fingerprints = {
            "manifest_sha256": canonical_sha256(
                benchmark_contracts.verified_dataset_document(
                    production_evidence
                )
            ),
            "dataset_protocol_sha256": canonical_sha256(production_protocol),
            "normalization_sha256": NormalizationConfig().fingerprint(),
            "selection_config_sha256": canonical_sha256(
                selection_config_dict()
            ),
            "registry_sha256": canonical_sha256(
                registry(model_ids=model_ids, formal_cohort=True)
            ),
        }
        plan, _private_map = create_blind_bundle(
            production_evidence,
            model_ids,
            seed=(1 << 127) + 45,
            contract_fingerprints=fingerprints,
            baseline_model_id="zipformer_baseline",
            protocol_profile="production_confirmation",
            cohort_id="first_batch_android_v1",
            exploration_evidence=exploration_evidence,
        )
        self.assertEqual(
            benchmark_contracts.validate_public_plan(
                plan,
                production_evidence,
                exploration_evidence=exploration_evidence,
            ),
            plan,
        )
        self.assertEqual(
            build_decoder_plan(
                production_evidence,
                plan,
                "M001",
                exploration_evidence=exploration_evidence,
            )["model_alias"],
            "M001",
        )
        self.assertEqual(
            benchmark_contracts.decoder_plan_document(
                production_evidence,
                plan,
                "M001",
                exploration_evidence=exploration_evidence,
            )["model_alias"],
            "M001",
        )

        without_exploration = (
            lambda: create_blind_bundle(
                production_evidence,
                model_ids,
                seed=(1 << 127) + 45,
                contract_fingerprints=fingerprints,
                baseline_model_id="zipformer_baseline",
                protocol_profile="production_confirmation",
                cohort_id="first_batch_android_v1",
            ),
            lambda: build_decoder_plan(
                production_evidence,
                plan,
                "M001",
            ),
            lambda: benchmark_contracts.decoder_plan_document(
                production_evidence,
                plan,
                "M001",
            ),
            lambda: benchmark_contracts.validate_public_plan(
                plan,
                production_evidence,
            ),
        )
        for entry_point in without_exploration:
            with self.subTest(missing_exploration=entry_point):
                with self.assertRaisesRegex(
                    ContractError,
                    "VerifiedExplorationEvidence",
                ):
                    entry_point()

        with self.assertRaisesRegex(ContractError, "filesystem-backed"):
            create_blind_bundle(
                production,
                model_ids,
                seed=(1 << 127) + 45,
                contract_fingerprints=fingerprints,
                baseline_model_id="zipformer_baseline",
                protocol_profile="production_confirmation",
                cohort_id="first_batch_android_v1",
                exploration_evidence=exploration_evidence,
            )

        production_first = Path(production["clips"][0]["audio_path"])
        write_wav(production_first, frame_count=999)
        build_decoder_plan(
            production_evidence,
            plan,
            "M001",
            exploration_evidence=exploration_evidence,
        )

        exploration_first = Path(exploration["clips"][0]["audio_path"])
        write_wav(exploration_first, frame_count=999)
        benchmark_contracts.decoder_plan_document(
            production_evidence,
            plan,
            "M001",
            exploration_evidence=exploration_evidence,
        )

    def test_unregistered_evidence_object_cannot_impersonate_factory_result(self) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="registered-evidence",
            frame_offset=10,
        )
        manifest_path = (
            self.root / "private" / "registered-evidence" / "manifest.json"
        )
        write_json(manifest_path, exploration)
        evidence = benchmark_contracts.verify_dataset_evidence(
            manifest_path,
            self.repo_root,
            snapshot_root=(
                self.root
                / "private"
                / "registered-evidence"
                / "snapshots"
            ),
        )
        forged = object.__new__(benchmark_contracts.VerifiedDatasetEvidence)
        for field in (
            "_manifest_path",
            "_repo_root",
            "_fixture_root",
            "_manifest_sha256",
            "_dataset_id",
            "_recording_purpose",
        ):
            if hasattr(evidence, field):
                object.__setattr__(forged, field, getattr(evidence, field))
        with self.assertRaisesRegex(ContractError, "factory-created"):
            benchmark_contracts.verified_dataset_document(forged)

    def test_production_rejects_cross_file_exploration_fingerprint_mismatch(self) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        (
            _exploration_protocol,
            exploration_plan,
            commitment,
            exploration_evidence,
        ) = self._exploration_evidence(exploration)
        production = self._formal_manifest(
            "production_confirmation",
            speaker_count=6,
        )
        production["frozen_exploration_pcm_set"] = commitment
        self._materialize_formal_pcm(
            production,
            prefix="production-plan-mismatch",
            frame_offset=200,
        )
        production_protocol = build_dataset_protocol(
            "production_confirmation",
            protocol_id="protocol_222222222222",
            target_device=production["target_device"],
            approval_status="approved",
            exploration_pcm_set_sha256=commitment["commitment_sha256"],
        )
        mismatched_plan = copy.deepcopy(exploration_plan)
        mismatched_plan["contract_fingerprints"][
            "dataset_protocol_sha256"
        ] = "f" * 64
        frozen_plan_path = (
            self.root
            / "private"
            / "frozen-exploration"
            / "public-plan.json"
        )
        replacement = frozen_plan_path.with_name(
            "replacement-public-plan.json"
        )
        write_json(replacement, mismatched_plan)
        replacement.chmod(0o400)
        os.replace(replacement, frozen_plan_path)
        production_evidence = self._verified_production_manifest(
            production,
            label="production-plan-mismatch",
        )

        with self.assertRaisesRegex(
            ContractError,
            "contract changed|receipt|identity|inode",
        ):
            validate_dataset_protocol(
                production_protocol,
                production_evidence,
                exploration_evidence=exploration_evidence,
            )

    def test_actual_verified_exploration_pcm_reuse_is_rejected(self) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        production = self._formal_manifest(
            "production_confirmation",
            speaker_count=6,
        )
        self._materialize_formal_pcm(
            exploration,
            prefix="exploration",
            frame_offset=10,
        )
        self._materialize_formal_pcm(
            production,
            prefix="production",
            frame_offset=200,
        )
        (
            _exploration_protocol,
            _exploration_plan,
            commitment,
            exploration_evidence,
        ) = self._exploration_evidence(exploration)
        production["frozen_exploration_pcm_set"] = commitment
        production["clips"][0]["audio_path"] = exploration["clips"][0][
            "audio_path"
        ]
        for field in (
            "audio_sha256",
            "pcm_payload_sha256",
            "pcm_payload_bytes",
        ):
            production["clips"][0][field] = exploration["clips"][0][field]
        production_protocol = build_dataset_protocol(
            "production_confirmation",
            protocol_id="protocol_222222222222",
            target_device=production["target_device"],
            approval_status="approved",
            exploration_pcm_set_sha256=commitment["commitment_sha256"],
        )

        validate_recording_manifest(exploration, self.repo_root)
        validate_manifest_pcm(exploration, self.repo_root)
        validate_recording_manifest(production, self.repo_root)
        validate_manifest_pcm(production, self.repo_root)
        production_evidence = self._verified_production_manifest(
            production,
            label="production-actual-overlap",
        )
        with self.assertRaisesRegex(ContractError, "actual exploration"):
            validate_dataset_protocol(
                production_protocol,
                production_evidence,
                exploration_evidence=exploration_evidence,
            )

    def test_snapshot_evidence_rejects_missing_sources_and_corrupt_frozen_wavs(
        self,
    ) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        exploration_protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        exploration_plan = self._formal_public_plan(
            exploration,
            exploration_protocol,
        )
        evidence_root = self.root / "private" / "verified-evidence"
        snapshot_root = evidence_root / "snapshots"
        exploration_manifest_path = evidence_root / "exploration-manifest.json"
        exploration_plan_path = evidence_root / "exploration-plan.json"
        exploration_protocol_path = evidence_root / "exploration-protocol.json"
        write_json(exploration_manifest_path, exploration)
        write_json(exploration_plan_path, exploration_plan)
        write_json(exploration_protocol_path, exploration_protocol)

        with self.assertRaisesRegex(ContractError, "PCM|WAV|audio"):
            benchmark_contracts.verify_exploration_evidence(
                exploration_manifest_path,
                exploration_plan_path,
                exploration_protocol_path,
                self.repo_root,
                snapshot_root=snapshot_root,
            )

        production = self._formal_manifest(
            "production_confirmation",
            speaker_count=6,
        )
        production_manifest_path = evidence_root / "production-manifest.json"
        write_json(production_manifest_path, production)
        with self.assertRaisesRegex(ContractError, "PCM|WAV|audio"):
            benchmark_contracts.verify_dataset_evidence(
                production_manifest_path,
                self.repo_root,
                snapshot_root=snapshot_root,
            )

        self._materialize_formal_pcm(
            exploration,
            prefix="verified-exploration",
            frame_offset=10,
        )
        write_json(exploration_manifest_path, exploration)
        exploration_dataset_evidence = (
            benchmark_contracts.verify_dataset_evidence(
                exploration_manifest_path,
                self.repo_root,
                snapshot_root=snapshot_root,
            )
        )
        exploration_plan = self._formal_public_plan(
            exploration,
            exploration_protocol,
            dataset_evidence=exploration_dataset_evidence,
        )
        exploration_plan_path = (
            evidence_root / "exploration-plan-verified.json"
        )
        commit_json_artifact(
            exploration_plan_path,
            exploration_plan,
            artifact_kind="blind-public-plan",
            run_id=exploration_plan["run_id"],
            evidence_sha256=benchmark_contracts.benchmark_evidence_sha256(
                exploration_dataset_evidence,
                "exploration",
            ),
            plan_sha256=canonical_sha256(exploration_plan),
            validate_evidence=lambda: (
                benchmark_contracts.verified_dataset_document(
                    exploration_dataset_evidence
                )
            ),
        )
        verified_exploration = (
            benchmark_contracts.verify_exploration_evidence(
                exploration_manifest_path,
                exploration_plan_path,
                exploration_protocol_path,
                self.repo_root,
                snapshot_root=snapshot_root,
            )
        )
        commitment = derive_exploration_commitment(verified_exploration)

        self._materialize_formal_pcm(
            production,
            prefix="verified-production",
            frame_offset=200,
        )
        production["frozen_exploration_pcm_set"] = commitment
        production_protocol = build_dataset_protocol(
            "production_confirmation",
            protocol_id="protocol_222222222222",
            target_device=production["target_device"],
            approval_status="approved",
            exploration_pcm_set_sha256=commitment["commitment_sha256"],
        )
        write_json(production_manifest_path, production)
        verified_production = benchmark_contracts.verify_dataset_evidence(
            production_manifest_path,
            self.repo_root,
            snapshot_root=snapshot_root,
        )
        validate_dataset_protocol(
            production_protocol,
            verified_production,
            exploration_evidence=verified_exploration,
        )

        exploration_first = Path(exploration["clips"][0]["audio_path"])
        write_wav(exploration_first, frame_count=999)
        validate_dataset_protocol(
            production_protocol,
            verified_production,
            exploration_evidence=verified_exploration,
        )

        production_first = Path(production["clips"][0]["audio_path"])
        write_wav(production_first, frame_count=999)
        validate_dataset_protocol(
            production_protocol,
            verified_production,
            exploration_evidence=verified_exploration,
        )

        frozen_production = benchmark_contracts.verified_dataset_document(
            verified_production
        )
        production_snapshot = Path(
            frozen_production["clips"][0]["audio_path"]
        )
        replacement = evidence_root / "corrupt-production-snapshot.wav"
        write_wav(replacement, frame_count=999)
        os.replace(replacement, production_snapshot)
        with self.assertRaisesRegex(ContractError, "hash|changed"):
            validate_dataset_protocol(
                production_protocol,
                verified_production,
                exploration_evidence=verified_exploration,
            )

    def test_dataset_evidence_freezes_source_bytes_in_external_content_addressed_snapshot(
        self,
    ) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="snapshot-source",
            frame_offset=10,
        )
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        manifest_path = self.root / "private" / "snapshot-source-manifest.json"
        snapshot_root = self.root / "private" / "snapshots"
        write_json(manifest_path, exploration)
        source_path = Path(exploration["clips"][0]["audio_path"])
        expected_hash = exploration["clips"][0]["audio_sha256"]

        evidence = benchmark_contracts.verify_dataset_evidence(
            manifest_path,
            self.repo_root,
            snapshot_root=snapshot_root,
        )
        frozen = benchmark_contracts.verified_dataset_document(evidence)
        frozen_path = Path(frozen["clips"][0]["audio_path"])
        self.assertNotEqual(frozen_path, source_path)
        self.assertEqual(frozen_path.parent, snapshot_root.resolve())
        self.assertEqual(frozen_path.name, f"{expected_hash}.wav")
        self.assertEqual(stat.S_IMODE(frozen_path.stat().st_mode), 0o400)

        plan = self._formal_public_plan(
            frozen,
            protocol,
            dataset_evidence=evidence,
        )
        write_wav(source_path, frame_count=999)
        write_json(manifest_path, {})
        public_plan, _private_map = create_blind_bundle(
            evidence,
            [
                "zipformer_baseline",
                "paraformer_int8",
                "fireredasr2_aed_int8",
                "funasr_nano_onnx_int8",
            ],
            seed=(1 << 127) + 50,
            contract_fingerprints=plan["contract_fingerprints"],
            baseline_model_id="zipformer_baseline",
            protocol_profile="exploration",
            cohort_id="first_batch_android_v1",
        )
        decoder = build_decoder_plan(evidence, public_plan, "M001")
        decoder_first = decoder["clips"][0]
        self.assertEqual(Path(decoder_first["audio_path"]).parent, snapshot_root.resolve())
        self.assertEqual(
            Path(decoder_first["audio_path"]).name,
            f"{decoder_first['wav_file_sha256']}.wav",
        )
        self.assertNotEqual(Path(decoder_first["audio_path"]), source_path)

    def test_formal_artifact_rejects_snapshot_replaced_after_factory(self) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="snapshot-corruption",
            frame_offset=10,
        )
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        manifest_path = self.root / "private" / "snapshot-corruption-manifest.json"
        snapshot_root = self.root / "private" / "snapshot-corruption-store"
        write_json(manifest_path, exploration)
        evidence = benchmark_contracts.verify_dataset_evidence(
            manifest_path,
            self.repo_root,
            snapshot_root=snapshot_root,
        )
        frozen = benchmark_contracts.verified_dataset_document(evidence)
        plan = self._formal_public_plan(
            frozen,
            protocol,
            dataset_evidence=evidence,
        )
        snapshot_path = Path(frozen["clips"][0]["audio_path"])
        replacement = self.root / "private" / "replacement.wav"
        write_wav(replacement, frame_count=999)
        os.replace(replacement, snapshot_path)

        entry_points = (
            lambda: create_blind_bundle(
                evidence,
                [
                    "zipformer_baseline",
                    "paraformer_int8",
                    "fireredasr2_aed_int8",
                    "funasr_nano_onnx_int8",
                ],
                seed=(1 << 127) + 51,
                contract_fingerprints=plan["contract_fingerprints"],
                baseline_model_id="zipformer_baseline",
                protocol_profile="exploration",
                cohort_id="first_batch_android_v1",
            ),
            lambda: build_decoder_plan(evidence, plan, "M001"),
            lambda: benchmark_contracts.decoder_plan_document(
                evidence,
                plan,
                "M001",
            ),
        )
        for entry_point in entry_points:
            with self.subTest(entry_point=entry_point):
                with self.assertRaisesRegex(ContractError, "snapshot|hash"):
                    entry_point()

    def test_snapshot_factory_rejects_symlink_and_preexisting_wrong_digest_content(
        self,
    ) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="snapshot-safe-open",
            frame_offset=10,
        )
        manifest_path = self.root / "private" / "snapshot-safe-open-manifest.json"
        write_json(manifest_path, exploration)
        with self.assertRaisesRegex(ContractError, "outside the repository"):
            benchmark_contracts.verify_dataset_evidence(
                manifest_path,
                self.repo_root,
                snapshot_root=self.repo_root / "forbidden-snapshots",
            )
        source_path = Path(exploration["clips"][0]["audio_path"])
        real_path = source_path.with_name("symlink-target.wav")
        source_path.rename(real_path)
        source_path.symlink_to(real_path)
        with self.assertRaisesRegex(ContractError, "symlink|regular|WAV"):
            benchmark_contracts.verify_dataset_evidence(
                manifest_path,
                self.repo_root,
                snapshot_root=self.root / "private" / "symlink-snapshots",
            )

        source_path.unlink()
        real_path.rename(source_path)
        non_regular_manifest = copy.deepcopy(exploration)
        non_regular_path = self.root / "private" / "directory.wav"
        non_regular_path.mkdir()
        non_regular_manifest["clips"][0]["audio_path"] = str(
            non_regular_path
        )
        non_regular_manifest_path = (
            self.root / "private" / "non-regular-manifest.json"
        )
        write_json(non_regular_manifest_path, non_regular_manifest)
        with self.assertRaisesRegex(ContractError, "regular WAV"):
            benchmark_contracts.verify_dataset_evidence(
                non_regular_manifest_path,
                self.repo_root,
                snapshot_root=(
                    self.root / "private" / "non-regular-snapshots"
                ),
            )

        snapshot_root = self.root / "private" / "wrong-existing-snapshot"
        snapshot_root.mkdir()
        expected_snapshot = (
            snapshot_root / f"{exploration['clips'][0]['audio_sha256']}.wav"
        )
        wrong_hash = write_wav(expected_snapshot, frame_count=999)
        with self.assertRaisesRegex(ContractError, "snapshot|digest|hash"):
            benchmark_contracts.verify_dataset_evidence(
                manifest_path,
                self.repo_root,
                snapshot_root=snapshot_root,
            )
        self.assertEqual(
            hashlib.sha256(expected_snapshot.read_bytes()).hexdigest(),
            wrong_hash,
        )
        self.assertEqual(list(snapshot_root.glob(".snapshot-*.tmp")), [])

    def test_same_fd_snapshot_bytes_survive_source_replacement_during_publish(
        self,
    ) -> None:
        for profile, speaker_count, frame_offset in (
            ("exploration", 2, 10),
            ("production_confirmation", 6, 200),
        ):
            with self.subTest(profile=profile):
                manifest = self._formal_manifest(
                    profile,
                    speaker_count=speaker_count,
                )
                self._materialize_formal_pcm(
                    manifest,
                    prefix=f"publish-race-{profile}",
                    frame_offset=frame_offset,
                )
                manifest_path = (
                    self.root
                    / "private"
                    / f"publish-race-{profile}-manifest.json"
                )
                snapshot_root = (
                    self.root
                    / "private"
                    / f"publish-race-{profile}-snapshots"
                )
                write_json(manifest_path, manifest)
                source_path = Path(manifest["clips"][0]["audio_path"])
                expected_hash = manifest["clips"][0]["audio_sha256"]
                original_publish = benchmark_pcm._publish_snapshot_bytes
                replaced = False

                def replace_source_after_read(
                    root: Path,
                    wav_bytes: bytes,
                    info: object,
                ) -> Path:
                    nonlocal replaced
                    if not replaced:
                        write_wav(source_path, frame_count=999)
                        replaced = True
                    return original_publish(root, wav_bytes, info)

                with mock.patch.object(
                    benchmark_pcm,
                    "_publish_snapshot_bytes",
                    side_effect=replace_source_after_read,
                ):
                    evidence = benchmark_contracts.verify_dataset_evidence(
                        manifest_path,
                        self.repo_root,
                        snapshot_root=snapshot_root,
                    )
                frozen = benchmark_contracts.verified_dataset_document(
                    evidence
                )
                snapshot_path = Path(frozen["clips"][0]["audio_path"])
                self.assertTrue(replaced)
                self.assertEqual(
                    hashlib.sha256(snapshot_path.read_bytes()).hexdigest(),
                    expected_hash,
                )
                self.assertNotEqual(
                    hashlib.sha256(source_path.read_bytes()).hexdigest(),
                    expected_hash,
                )

    def test_cli_decoder_commit_uses_snapshot_if_source_changes_after_generation(
        self,
    ) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="snapshot-cli",
            frame_offset=10,
        )
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        private_root = self.root / "private" / "snapshot-cli-contract"
        manifest_path = private_root / "manifest.json"
        plan_path = private_root / "public-plan.json"
        output_path = private_root / "decoder-plan.json"
        snapshot_root = self.root / "private" / "snapshot-cli-store"
        write_json(manifest_path, exploration)
        evidence = benchmark_contracts.verify_dataset_evidence(
            manifest_path,
            self.repo_root,
            snapshot_root=snapshot_root,
        )
        frozen = benchmark_contracts.verified_dataset_document(evidence)
        plan = self._formal_public_plan(
            frozen,
            protocol,
            dataset_evidence=evidence,
        )
        plan_receipt = commit_json_artifact(
            plan_path,
            plan,
            artifact_kind="blind-public-plan",
            run_id=plan["run_id"],
            evidence_sha256=benchmark_contracts.benchmark_evidence_sha256(
                evidence,
                "exploration",
            ),
            plan_sha256=canonical_sha256(plan),
            validate_evidence=lambda: (
                benchmark_contracts.verified_dataset_document(evidence)
            ),
        )
        for invalid_plan_path in (
            private_root / "raw-formal-plan.json",
            Path(plan_receipt["artifact"]["path"]),
        ):
            if invalid_plan_path.name == "raw-formal-plan.json":
                write_json(invalid_plan_path, plan)
            invalid_output = private_root / (
                f"invalid-{invalid_plan_path.name}.json"
            )
            stderr = StringIO()
            with redirect_stdout(StringIO()), redirect_stderr(stderr):
                code = benchmark_cli.main(
                    [
                        "make-decoder-plan",
                        "--manifest",
                        str(manifest_path),
                        "--public-plan",
                        str(invalid_plan_path),
                        "--model-alias",
                        "M001",
                        "--repo-root",
                        str(self.repo_root),
                        "--snapshot-root",
                        str(snapshot_root),
                        "--plan",
                        str(invalid_output),
                    ]
                )
            self.assertEqual(code, 2)
            self.assertIn("valid commit receipt", stderr.getvalue())
            self.assertFalse(invalid_output.exists())
        source_path = Path(exploration["clips"][0]["audio_path"])
        original_writer = benchmark_cli._write_json_exclusive
        mutated = False

        def mutate_source_then_commit(
            path: Path,
            value: object,
            *,
            private: bool = False,
            **kwargs: object,
        ) -> None:
            nonlocal mutated
            if not mutated:
                write_wav(source_path, frame_count=999)
                mutated = True
            original_writer(path, value, private=private, **kwargs)

        stderr = StringIO()
        with mock.patch.object(
            benchmark_cli,
            "_write_json_exclusive",
            side_effect=mutate_source_then_commit,
        ), redirect_stdout(StringIO()), redirect_stderr(stderr):
            code = benchmark_cli.main(
                [
                    "make-decoder-plan",
                    "--manifest",
                    str(manifest_path),
                    "--public-plan",
                    str(plan_path),
                    "--model-alias",
                    "M001",
                    "--repo-root",
                    str(self.repo_root),
                    "--snapshot-root",
                    str(snapshot_root),
                    "--plan",
                    str(output_path),
                ]
            )
        self.assertEqual(code, 2)
        self.assertFalse(mutated)
        self.assertIn(
            "formal model cohort closure receipt",
            stderr.getvalue(),
        )
        self.assertFalse(output_path.exists())

    def test_cli_blind_commit_uses_snapshot_if_source_changes_before_write(
        self,
    ) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="snapshot-cli-blind",
            frame_offset=10,
        )
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        model_ids = [
            "zipformer_baseline",
            "paraformer_int8",
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ]
        private_root = self.root / "private" / "snapshot-cli-blind"
        manifest_path = private_root / "manifest.json"
        protocol_path = private_root / "protocol.json"
        registry_path = private_root / "registry.json"
        public_path = private_root / "public-plan.json"
        private_path = private_root / "private-map.json"
        snapshot_root = private_root / "snapshots"
        write_json(manifest_path, exploration)
        write_json(protocol_path, protocol)
        write_json(
            registry_path,
            registry(model_ids=model_ids, formal_cohort=True),
        )
        source_path = Path(exploration["clips"][0]["audio_path"])
        expected_hash = exploration["clips"][0]["audio_sha256"]
        original_writer = benchmark_cli._write_json_exclusive
        mutated = False

        def mutate_source_then_commit(
            path: Path,
            value: object,
            *,
            private: bool = False,
            **kwargs: object,
        ) -> None:
            nonlocal mutated
            if not mutated:
                write_wav(source_path, frame_count=999)
                mutated = True
            original_writer(path, value, private=private, **kwargs)

        stderr = StringIO()
        with mock.patch.object(
            benchmark_cli,
            "_write_json_exclusive",
            side_effect=mutate_source_then_commit,
        ), redirect_stdout(StringIO()), redirect_stderr(stderr):
            code = benchmark_cli.main(
                [
                    "blind",
                    "--manifest",
                    str(manifest_path),
                    "--repo-root",
                    str(self.repo_root),
                    "--snapshot-root",
                    str(snapshot_root),
                    "--dataset-protocol",
                    str(protocol_path),
                    "--registry",
                    str(registry_path),
                    "--models",
                    ",".join(model_ids),
                    "--seed",
                    str((1 << 127) + 52),
                    "--public-plan",
                    str(public_path),
                    "--private-map",
                    str(private_path),
                ]
            )
        self.assertEqual(code, 2)
        self.assertFalse(mutated)
        self.assertIn(
            "formal model cohort closure receipt",
            stderr.getvalue(),
        )
        self.assertFalse(public_path.exists())
        self.assertFalse(private_path.exists())

    def test_evidence_rejects_same_content_snapshot_replaced_with_new_inode(
        self,
    ) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="same-content-replacement",
            frame_offset=10,
        )
        manifest_path = (
            self.root / "private" / "same-content-replacement-manifest.json"
        )
        snapshot_root = (
            self.root / "private" / "same-content-replacement-snapshots"
        )
        write_json(manifest_path, exploration)
        evidence = benchmark_contracts.verify_dataset_evidence(
            manifest_path,
            self.repo_root,
            snapshot_root=snapshot_root,
        )
        frozen = benchmark_contracts.verified_dataset_document(evidence)
        snapshot = Path(frozen["clips"][0]["audio_path"])
        original_inode = snapshot.stat().st_ino
        replacement = self.root / "private" / "same-content-new-inode.wav"
        replacement.write_bytes(snapshot.read_bytes())
        replacement.chmod(0o400)
        self.assertNotEqual(replacement.stat().st_ino, original_inode)
        os.replace(replacement, snapshot)

        with self.assertRaisesRegex(
            ContractError,
            "identity|inode|snapshot",
        ):
            benchmark_contracts.verified_dataset_document(evidence)

    def test_evidence_factory_rejects_source_that_is_snapshot_inode(
        self,
    ) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="source-is-snapshot",
            frame_offset=10,
        )
        snapshot_root = self.root / "private" / "source-is-snapshot-store"
        snapshot_root.mkdir(parents=True)
        source = Path(exploration["clips"][0]["audio_path"])
        snapshot = (
            snapshot_root / f"{exploration['clips'][0]['audio_sha256']}.wav"
        )
        os.replace(source, snapshot)
        snapshot.chmod(0o400)
        exploration["clips"][0]["audio_path"] = str(snapshot)
        manifest_path = (
            self.root / "private" / "source-is-snapshot-manifest.json"
        )
        write_json(manifest_path, exploration)

        with self.assertRaisesRegex(
            ContractError,
            "source.*snapshot|shared inode|same inode",
        ):
            benchmark_contracts.verify_dataset_evidence(
                manifest_path,
                self.repo_root,
                snapshot_root=snapshot_root,
            )

    def test_formal_api_rechecks_bound_inode_after_document_construction(
        self,
    ) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="api-final-identity",
            frame_offset=10,
        )
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        manifest_path = self.root / "private" / "api-final-identity.json"
        snapshot_root = self.root / "private" / "api-final-identity-snapshots"
        write_json(manifest_path, exploration)
        evidence = benchmark_contracts.verify_dataset_evidence(
            manifest_path,
            self.repo_root,
            snapshot_root=snapshot_root,
        )
        frozen = benchmark_contracts.verified_dataset_document(evidence)
        plan = self._formal_public_plan(
            frozen,
            protocol,
            dataset_evidence=evidence,
        )
        snapshot = Path(frozen["clips"][0]["audio_path"])
        replacement = self.root / "private" / "api-final-replacement.wav"
        replacement.write_bytes(snapshot.read_bytes())
        replacement.chmod(0o400)
        original_shuffle = benchmark_blinding._HashRandom.shuffle
        replaced = False

        def replace_after_initial_check(
            randomizer: object,
            values: list[object],
        ) -> None:
            nonlocal replaced
            if not replaced:
                os.replace(replacement, snapshot)
                replaced = True
            original_shuffle(randomizer, values)

        with mock.patch.object(
            benchmark_blinding._HashRandom,
            "shuffle",
            autospec=True,
            side_effect=replace_after_initial_check,
        ):
            with self.assertRaisesRegex(
                ContractError,
                "identity|inode|snapshot",
            ):
                create_blind_bundle(
                    evidence,
                    [
                        "zipformer_baseline",
                        "paraformer_int8",
                        "fireredasr2_aed_int8",
                        "funasr_nano_onnx_int8",
                    ],
                    seed=(1 << 127) + 53,
                    contract_fingerprints=plan["contract_fingerprints"],
                    baseline_model_id="zipformer_baseline",
                    protocol_profile="exploration",
                    cohort_id="first_batch_android_v1",
                )
        self.assertTrue(replaced)

    def test_cli_precommit_rejects_same_content_snapshot_inode_replacement(
        self,
    ) -> None:
        exploration = self._formal_manifest("exploration", speaker_count=2)
        self._materialize_formal_pcm(
            exploration,
            prefix="cli-precommit-identity",
            frame_offset=10,
        )
        protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device=exploration["target_device"],
            approval_status="approved",
        )
        private_root = self.root / "private" / "cli-precommit-identity"
        manifest_path = private_root / "manifest.json"
        plan_path = private_root / "public-plan.json"
        output_path = private_root / "decoder-plan.json"
        snapshot_root = private_root / "snapshots"
        write_json(manifest_path, exploration)
        evidence = benchmark_contracts.verify_dataset_evidence(
            manifest_path,
            self.repo_root,
            snapshot_root=snapshot_root,
        )
        frozen = benchmark_contracts.verified_dataset_document(evidence)
        plan = self._formal_public_plan(
            frozen,
            protocol,
            dataset_evidence=evidence,
        )
        commit_json_artifact(
            plan_path,
            plan,
            artifact_kind="blind-public-plan",
            run_id=plan["run_id"],
            evidence_sha256=benchmark_contracts.benchmark_evidence_sha256(
                evidence,
                "exploration",
            ),
            plan_sha256=canonical_sha256(plan),
            validate_evidence=lambda: (
                benchmark_contracts.verified_dataset_document(evidence)
            ),
        )
        snapshot = Path(frozen["clips"][0]["audio_path"])
        replacement = private_root / "same-content-replacement.wav"
        replacement.write_bytes(snapshot.read_bytes())
        replacement.chmod(0o400)
        original_writer = benchmark_cli._write_json_exclusive
        replaced = False

        def replace_snapshot_then_enter_writer(
            path: Path,
            value: object,
            **kwargs: object,
        ) -> None:
            nonlocal replaced
            if not replaced:
                os.replace(replacement, snapshot)
                replaced = True
            original_writer(path, value, **kwargs)

        stderr = StringIO()
        with mock.patch.object(
            benchmark_cli,
            "_write_json_exclusive",
            side_effect=replace_snapshot_then_enter_writer,
        ), redirect_stdout(StringIO()), redirect_stderr(stderr):
            code = benchmark_cli.main(
                [
                    "make-decoder-plan",
                    "--manifest",
                    str(manifest_path),
                    "--public-plan",
                    str(plan_path),
                    "--model-alias",
                    "M001",
                    "--repo-root",
                    str(self.repo_root),
                    "--snapshot-root",
                    str(snapshot_root),
                    "--plan",
                    str(output_path),
                ]
            )
        self.assertEqual(code, 2)
        self.assertFalse(replaced)
        self.assertIn(
            "formal model cohort closure receipt",
            stderr.getvalue(),
        )
        self.assertFalse(output_path.exists())
        self.assertEqual(
            list(private_root.glob(f".{output_path.name}.tmp-*")),
            [],
        )


class ContinuousDecodeProofTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        audio = self.root / "private" / "clip.wav"
        audio_sha256 = write_wav(audio)
        self.manifest = manifest_for(audio, audio_sha256)
        self.protocol = dataset_protocol_for(self.manifest)
        self.registry = registry(model_ids=["model_0", "model_1"])
        self.plan, _ = blind_bundle_for(
            self.manifest,
            model_count=2,
            protocol=self.protocol,
            registry_snapshot=self.registry,
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def _rehash(proof: dict) -> None:
        telemetry = proof["runner_proof"]["telemetry"]
        telemetry["summary_sha256"] = canonical_sha256(telemetry["samples"])
        proof_without_hash = {
            key: value
            for key, value in proof["runner_proof"].items()
            if key != "proof_sha256"
        }
        proof["runner_proof"]["proof_sha256"] = canonical_sha256(
            proof_without_hash
        )

    def _use_heartbeat_progress_contract(self, proof: dict) -> None:
        self.protocol["telemetry"] = {
            "sample_interval_ms": 5_000,
            "sample_interval_tolerance_ms": 250,
            "sample_interval_tolerance_fraction": 0.05,
            "minimum_duration_seconds": 600.0,
            "minimum_decode_loops": 2,
            "active_decode_progress_unit": "trusted_runner_progress_events_v1",
        }
        telemetry = proof["runner_proof"]["telemetry"]
        samples = telemetry["samples"]
        clip_order = next(
            assignment["clip_order"]
            for assignment in self.plan["assignments"]
            if assignment["model_alias"] == proof["model_alias"]
        )
        clip_count = len(clip_order)
        loop_count = 2
        duration_ns = int(
            proof["runtime"]["stability"]["duration_seconds"]
            * 1_000_000_000
        )
        loop_proofs = []
        for loop_index in range(1, loop_count + 1):
            loop_proofs.append(
                {
                    "loop_index": loop_index,
                    "monotonic_start_ns": (
                        (loop_index - 1) * duration_ns // loop_count
                    ),
                    "monotonic_end_ns": (
                        loop_index * duration_ns // loop_count
                    ),
                    "ordered_clip_ids_sha256": canonical_sha256(clip_order),
                    "clip_count": clip_count,
                    "cumulative_decoded_clip_count": (
                        loop_index * clip_count
                    ),
                    "successful": True,
                }
            )
        total_completed = loop_count * clip_count
        last_sample_index = len(samples) - 1
        for index, sample in enumerate(samples):
            sample["heartbeat_index"] = index
            sample["runner_alive"] = True
            sample["active_decode_progress"] = index * 1_000
            sample["completed_clip_count"] = (
                index * total_completed // last_sample_index
            )
            sample["completed_loops"] = sum(
                loop["monotonic_end_ns"] <= sample["monotonic_ns"]
                for loop in loop_proofs
            )
            sample["successful_loops"] = sample["completed_loops"]
            sample.pop("decoded_clip_count", None)
        telemetry["loop_count"] = loop_count
        telemetry["successful_loop_count"] = loop_count
        telemetry["total_decoded_clip_count"] = total_completed
        telemetry["loop_proofs"] = loop_proofs
        self._rehash(proof)

    def test_telemetry_allows_bounded_jitter_and_long_decode_across_windows(self) -> None:
        output = model_output_for(self.manifest, public_plan=self.plan)
        proof = engineering_proof_for(self.manifest, public_plan=self.plan)
        self._use_heartbeat_progress_contract(proof)
        samples = proof["runner_proof"]["telemetry"]["samples"]
        samples[30]["monotonic_ns"] += 1
        samples[60]["monotonic_ns"] += 200_000_000
        self._rehash(proof)

        self.assertEqual(
            samples[1]["completed_clip_count"],
            samples[2]["completed_clip_count"],
        )
        validate_engineering_proof(
            proof,
            output,
            self.plan,
            self.manifest,
            self.protocol,
        )

    def test_telemetry_rejects_missing_heartbeat_dead_runner_and_idle_decode(self) -> None:
        output = model_output_for(self.manifest, public_plan=self.plan)
        valid = engineering_proof_for(self.manifest, public_plan=self.plan)
        self._use_heartbeat_progress_contract(valid)

        attacks: list[tuple[str, dict]] = []
        missing_heartbeat = copy.deepcopy(valid)
        missing_heartbeat["runner_proof"]["telemetry"]["samples"][2][
            "heartbeat_index"
        ] = 1
        attacks.append(("heartbeat", missing_heartbeat))

        dead_runner = copy.deepcopy(valid)
        dead_runner["runner_proof"]["telemetry"]["samples"][2][
            "runner_alive"
        ] = False
        attacks.append(("runner_alive", dead_runner))

        idle_decode = copy.deepcopy(valid)
        idle_decode["runner_proof"]["telemetry"]["samples"][2][
            "active_decode_progress"
        ] = idle_decode["runner_proof"]["telemetry"]["samples"][1][
            "active_decode_progress"
        ]
        attacks.append(("active decode progress", idle_decode))

        excessive_jitter = copy.deepcopy(valid)
        excessive_jitter["runner_proof"]["telemetry"]["samples"][30][
            "monotonic_ns"
        ] += 250_000_001
        attacks.append(("bounded jitter", excessive_jitter))

        for expected_error, attacked in attacks:
            with self.subTest(expected_error=expected_error):
                self._rehash(attacked)
                with self.assertRaisesRegex(ContractError, expected_error):
                    validate_engineering_proof(
                        attacked,
                        output,
                        self.plan,
                        self.manifest,
                        self.protocol,
                    )

    def test_two_decode_loop_minimum_is_consistent_for_development_and_runtime(self) -> None:
        one_loop_protocol = copy.deepcopy(self.protocol)
        one_loop_protocol["telemetry"]["minimum_decode_loops"] = 1
        with self.assertRaisesRegex(ContractError, "two-loop minimum"):
            validate_dataset_protocol(one_loop_protocol)

        schema_path = (
            Path(__file__).resolve().parents[1]
            / "contracts"
            / "dataset-protocol.schema.json"
        )
        schema = load_json(schema_path)
        self.assertEqual(
            schema["properties"]["telemetry"]["properties"][
                "minimum_decode_loops"
            ]["const"],
            2,
        )

        output = model_output_for(self.manifest, public_plan=self.plan)
        one_loop_proof = engineering_proof_for(
            self.manifest,
            public_plan=self.plan,
        )
        telemetry = one_loop_proof["runner_proof"]["telemetry"]
        telemetry["loop_count"] = 1
        telemetry["successful_loop_count"] = 1
        telemetry["loop_proofs"] = telemetry["loop_proofs"][:1]
        self._rehash(one_loop_proof)
        with self.assertRaisesRegex(ContractError, "loop count.*below"):
            validate_engineering_proof(
                one_loop_proof,
                output,
                self.plan,
                self.manifest,
                self.protocol,
            )

    def test_full_loop_proofs_bind_order_count_progress_and_duration(self) -> None:
        output = model_output_for(self.manifest, public_plan=self.plan)
        proof = engineering_proof_for(self.manifest, public_plan=self.plan)
        validate_engineering_proof(
            proof,
            output,
            self.plan,
            self.manifest,
            self.protocol,
        )
        telemetry = proof["runner_proof"]["telemetry"]
        expected_order = next(
            assignment["clip_order"]
            for assignment in self.plan["assignments"]
            if assignment["model_alias"] == output["model_alias"]
        )
        self.assertGreaterEqual(len(telemetry["loop_proofs"]), 2)
        self.assertEqual(
            telemetry["loop_proofs"][0]["ordered_clip_ids_sha256"],
            canonical_sha256(expected_order),
        )
        self.assertEqual(
            telemetry["total_decoded_clip_count"],
            telemetry["loop_count"] * len(expected_order),
        )

        attacks: list[tuple[str, dict]] = []
        wrong_order = copy.deepcopy(proof)
        wrong_order["runner_proof"]["telemetry"]["loop_proofs"][0][
            "ordered_clip_ids_sha256"
        ] = "f" * 64
        attacks.append(("clip order", wrong_order))

        wrong_count = copy.deepcopy(proof)
        wrong_count["runner_proof"]["telemetry"]["loop_proofs"][0][
            "clip_count"
        ] -= 1
        attacks.append(("clip count", wrong_count))

        wrong_cumulative = copy.deepcopy(proof)
        wrong_cumulative["runner_proof"]["telemetry"]["loop_proofs"][1][
            "cumulative_decoded_clip_count"
        ] -= 1
        attacks.append(("cumulative", wrong_cumulative))

        missing_loop = copy.deepcopy(proof)
        missing_loop["runner_proof"]["telemetry"]["loop_proofs"].pop()
        attacks.append(("loop proof", missing_loop))

        stalled = copy.deepcopy(proof)
        samples = stalled["runner_proof"]["telemetry"]["samples"]
        samples[2]["active_decode_progress"] = samples[1][
            "active_decode_progress"
        ]
        attacks.append(("decode progress", stalled))

        wrong_total = copy.deepcopy(proof)
        wrong_total["runner_proof"]["telemetry"]["total_decoded_clip_count"] -= 1
        attacks.append(("decoded clip", wrong_total))

        wrong_duration = copy.deepcopy(proof)
        wrong_duration["runtime"]["stability"]["duration_seconds"] += 0.1
        attacks.append(("duration", wrong_duration))

        for expected_error, attacked in attacks:
            with self.subTest(expected_error=expected_error):
                self._rehash(attacked)
                with self.assertRaisesRegex(ContractError, expected_error):
                    validate_engineering_proof(
                        attacked,
                        output,
                        self.plan,
                        self.manifest,
                        self.protocol,
                    )

    def test_accuracy_output_and_score_report_exclude_engineering_identity(self) -> None:
        output = model_output_for(self.manifest, public_plan=self.plan)
        report = score_model(
            self.manifest,
            references_for(),
            output,
            NormalizationConfig(),
            self.plan,
            self.protocol,
            selection_config_dict(),
        )
        self.assertNotIn("runtime", output)
        self.assertNotIn("runner_proof", output)
        forbidden_keys = {
            "runtime",
            "runner_proof",
            "runner_proof_sha256",
            "artifact_set_sha256",
            "artifact_measurements",
            "total_resource_bytes",
            "peak_rss_bytes",
            "stop_to_final_ms",
            "latency",
        }

        def all_keys(value: object) -> set[str]:
            if isinstance(value, dict):
                return set(value).union(
                    *(all_keys(item) for item in value.values())
                )
            if isinstance(value, list):
                return set().union(*(all_keys(item) for item in value))
            return set()

        self.assertFalse(forbidden_keys.intersection(all_keys(report)))

    def test_sanitized_engineering_report_hides_model_fingerprints_and_binds_accuracy(self) -> None:
        output = model_output_for(self.manifest, public_plan=self.plan)
        proof = engineering_proof_for(self.manifest, public_plan=self.plan)
        accuracy_report = score_model(
            self.manifest,
            references_for(),
            output,
            NormalizationConfig(),
            self.plan,
            self.protocol,
            selection_config_dict(),
        )
        engineering_report = build_engineering_report(
            proof,
            output,
            accuracy_report,
            self.plan,
            self.manifest,
            self.protocol,
        )

        def all_keys(value: object) -> set[str]:
            if isinstance(value, dict):
                return set(value).union(
                    *(all_keys(item) for item in value.values())
                )
            if isinstance(value, list):
                return set().union(*(all_keys(item) for item in value))
            return set()

        forbidden_keys = {
            "filename",
            "component",
            "artifact_measurements",
            "artifact_set_sha256",
            "runner_build_sha256",
            "target_device_profile_sha256",
            "tokenizer_sha256",
        }
        self.assertFalse(forbidden_keys.intersection(all_keys(engineering_report)))

        changed_accuracy = copy.deepcopy(accuracy_report)
        changed_accuracy["evaluation_contract"]["selection_config_sha256"] = "f" * 64
        with self.assertRaisesRegex(ContractError, "frozen accuracy report"):
            validate_engineering_report(
                engineering_report,
                accuracy_report=changed_accuracy,
            )

    def test_annotation_recall_requires_exact_aligned_occurrence_without_duplicates(self) -> None:
        references = references_for()
        inserted = model_output_for(
            self.manifest,
            hypotheses=["小白猫AI二零二六", "会议在三点开始"],
            public_plan=self.plan,
        )
        report = score_model(
            self.manifest,
            references,
            inserted,
            NormalizationConfig(),
            self.plan,
            self.protocol,
            selection_config_dict(),
        )
        self.assertEqual(report["metrics"]["proper_name_recall"], 0.0)

        duplicated = copy.deepcopy(references)
        repeated = copy.deepcopy(duplicated["references"][0]["annotations"][0])
        repeated["annotation_id"] = "ann_ffffffffffff"
        duplicated["references"][0]["annotations"].append(repeated)
        with self.assertRaisesRegex(ContractError, "duplicate normalized annotation span"):
            score_model(
                self.manifest,
                duplicated,
                model_output_for(self.manifest, public_plan=self.plan),
                NormalizationConfig(),
                self.plan,
                self.protocol,
                selection_config_dict(),
            )


if __name__ == "__main__":
    unittest.main()
