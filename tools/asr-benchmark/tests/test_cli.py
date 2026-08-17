from __future__ import annotations

import copy
import hashlib
import json
import stat
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock

import kittyecho_asr_bench.cli as benchmark_cli
import kittyecho_asr_bench.artifacts as benchmark_artifacts
from kittyecho_asr_bench.artifacts import (
    commit_json_artifact,
    load_committed_json_artifact,
)
from kittyecho_asr_bench.cli import (
    _load_exploration_evidence,
    _normalization_config,
    _selection_config,
    _write_json_exclusive,
    main,
)
from kittyecho_asr_bench.contracts import (
    ContractError,
    benchmark_evidence_sha256,
    build_dataset_protocol,
    canonical_sha256,
)
from kittyecho_asr_bench.metrics import score_model
from kittyecho_asr_bench.normalization import NormalizationConfig

from support import (
    dataset_protocol_for,
    engineering_proof_for,
    manifest_for,
    model_output_for,
    public_plan_for,
    references_for,
    registry,
    selection_config_dict,
    write_json,
    write_wav,
)


class CliIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.private_root = self.root / "private"
        self.repo_root.mkdir()
        self.audio = self.private_root / "clip.wav"
        sha = write_wav(self.audio)
        self.manifest = manifest_for(self.audio, sha)
        self.protocol = dataset_protocol_for(self.manifest)
        self.manifest_path = self.private_root / "manifest.json"
        self.public_plan = public_plan_for(
            self.manifest,
            model_count=2,
            protocol=self.protocol,
        )
        self.public_plan_path = self.private_root / "public-plan.json"
        self.protocol_path = self.private_root / "dataset-protocol.json"
        self.registry_path = self.repo_root / "registry.json"
        write_json(self.manifest_path, self.manifest)
        write_json(self.public_plan_path, self.public_plan)
        write_json(self.protocol_path, self.protocol)
        write_json(
            self.registry_path,
            registry(model_ids=["zipformer_baseline", "paraformer_int8"]),
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_cli(self, argv: list[str]) -> tuple[int, str, str]:
        stdout = StringIO()
        stderr = StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = main(argv)
        return code, stdout.getvalue(), stderr.getvalue()

    def test_validate_and_blind_commands_create_separated_plans(self) -> None:
        code, output, error = self.run_cli(
            [
                "validate-manifest",
                "--manifest",
                str(self.manifest_path),
                "--repo-root",
                str(self.repo_root),
            ]
        )
        self.assertEqual((code, error), (0, ""))
        self.assertIn("2 clips", output)

        public = self.private_root / "generated-public-plan.json"
        private = self.private_root / "private-map.json"
        code, _, error = self.run_cli(
            [
                "blind",
                "--manifest",
                str(self.manifest_path),
                "--repo-root",
                str(self.repo_root),
                "--dataset-protocol",
                str(self.protocol_path),
                "--registry",
                str(self.registry_path),
                "--models",
                "zipformer_baseline,paraformer_int8",
                "--seed",
                str((1 << 127) + 17),
                "--public-plan",
                str(public),
                "--private-map",
                str(private),
            ]
        )
        self.assertEqual((code, error), (0, ""))
        public_value, public_receipt = load_committed_json_artifact(
            public,
            expected_kind="blind-public-plan",
        )
        private_value, private_receipt = load_committed_json_artifact(
            private,
            expected_kind="blind-private-map",
        )
        self.assertNotIn("zipformer_baseline", str(public_value))
        self.assertIn("zipformer_baseline", str(private_value))
        self.assertEqual(
            public_receipt["plan_sha256"],
            private_receipt["plan_sha256"],
        )
        self.assertEqual(stat.S_IMODE(private.stat().st_mode), 0o400)

    def test_score_command_never_copies_reference_or_hypothesis_into_report(self) -> None:
        references = self.private_root / "references.json"
        output = self.private_root / "model-output.json"
        report = self.private_root / "report.json"
        write_json(references, references_for())
        write_json(
            output,
            model_output_for(self.manifest, public_plan=self.public_plan),
        )

        code, _, error = self.run_cli(
            [
                "score",
                "--manifest",
                str(self.manifest_path),
                "--references",
                str(references),
                "--model-output",
                str(output),
                "--public-plan",
                str(self.public_plan_path),
                "--dataset-protocol",
                str(self.protocol_path),
                "--repo-root",
                str(self.repo_root),
                "--report",
                str(report),
            ]
        )
        self.assertEqual((code, error), (0, ""))
        report_value, report_receipt = load_committed_json_artifact(
            report,
            expected_kind="score-report",
        )
        report_text = json.dumps(report_value, ensure_ascii=False)
        self.assertNotIn("会议在三点开始", report_text)
        self.assertNotIn("会议三点开始", report_text)
        self.assertEqual(
            report_receipt["plan_sha256"],
            canonical_sha256(self.public_plan),
        )
        self.assertEqual(stat.S_IMODE(report.stat().st_mode), 0o400)

    def test_production_commands_require_all_external_exploration_evidence(self) -> None:
        production_protocol = build_dataset_protocol(
            "production_confirmation",
            protocol_id="protocol_abcdefabcdef",
            target_device=self.manifest["target_device"],
            approval_status="approved",
            exploration_pcm_set_sha256="a" * 64,
        )
        production_protocol_path = self.private_root / "production-protocol.json"
        write_json(production_protocol_path, production_protocol)
        public = self.private_root / "production-public-plan.json"
        private = self.private_root / "production-private-map.json"

        code, _, error = self.run_cli(
            [
                "blind",
                "--manifest",
                str(self.manifest_path),
                "--repo-root",
                str(self.repo_root),
                "--dataset-protocol",
                str(production_protocol_path),
                "--registry",
                str(self.registry_path),
                "--models",
                "zipformer_baseline,paraformer_int8",
                "--seed",
                "17",
                "--public-plan",
                str(public),
                "--private-map",
                str(private),
            ]
        )

        self.assertEqual(code, 2)
        self.assertIn("formal model cohort closure receipt", error)
        self.assertFalse(public.exists())
        self.assertFalse(private.exists())

    def test_production_exploration_evidence_files_must_remain_outside_repo(self) -> None:
        inside_paths = []
        for name in (
            "exploration-manifest.json",
            "exploration-public-plan.json",
            "exploration-dataset-protocol.json",
        ):
            path = self.repo_root / name
            write_json(path, {})
            inside_paths.append(path)
        arguments = type(
            "Arguments",
            (),
            {
                "exploration_manifest": inside_paths[0],
                "exploration_public_plan": inside_paths[1],
                "exploration_dataset_protocol": inside_paths[2],
                "repo_root": self.repo_root,
                "fixture_root": None,
            },
        )()

        with self.assertRaisesRegex(ContractError, "outside the repository"):
            _load_exploration_evidence(
                arguments,
                "production_confirmation",
            )

    def test_registry_pcm_and_selection_commands_cover_the_complete_host_flow(self) -> None:
        code, output, error = self.run_cli(
            ["validate-registry", "--registry", str(self.registry_path)]
        )
        self.assertEqual((code, error), (0, ""))
        self.assertIn("2 models", output)

        code, output, error = self.run_cli(["inspect-pcm", str(self.audio)])
        self.assertEqual((code, error), (0, ""))
        pcm = json.loads(output)
        self.assertEqual((pcm["sample_rate_hz"], pcm["channels"]), (16_000, 1))

        references = references_for()
        first_output = model_output_for(
            self.manifest,
            alias="M001",
            hypotheses=["小猫AI二零二六", "会议在三点开始"],
            public_plan=self.public_plan,
        )
        second_output = model_output_for(
            self.manifest,
            alias="M002",
            hypotheses=["小狗", "会议"],
            public_plan=self.public_plan,
        )
        first_report = score_model(
            self.manifest,
            references,
            first_output,
            NormalizationConfig(),
            self.public_plan,
            self.protocol,
            selection_config_dict(),
        )
        second_report = score_model(
            self.manifest,
            references,
            second_output,
            NormalizationConfig(),
            self.public_plan,
            self.protocol,
            selection_config_dict(),
        )
        first_report_path = self.private_root / "first-report.json"
        second_report_path = self.private_root / "second-report.json"
        first_output_path = self.private_root / "first-output.json"
        second_output_path = self.private_root / "second-output.json"
        first_proof_path = self.private_root / "first-engineering-proof.json"
        second_proof_path = self.private_root / "second-engineering-proof.json"
        first_engineering_path = self.private_root / "first-engineering-report.json"
        second_engineering_path = self.private_root / "second-engineering-report.json"
        selection_config = self.private_root / "selection-config.json"
        selection_report = self.private_root / "selection-report.json"
        evidence_sha256 = benchmark_evidence_sha256(
            self.manifest,
            "development_fixture",
        )
        plan_sha256 = canonical_sha256(self.public_plan)
        commit_json_artifact(
            first_report_path,
            first_report,
            artifact_kind="score-report",
            run_id=first_report["run_id"],
            evidence_sha256=evidence_sha256,
            plan_sha256=plan_sha256,
            validate_evidence=lambda: None,
        )
        commit_json_artifact(
            second_report_path,
            second_report,
            artifact_kind="score-report",
            run_id=second_report["run_id"],
            evidence_sha256=evidence_sha256,
            plan_sha256=plan_sha256,
            validate_evidence=lambda: None,
        )
        write_json(first_output_path, first_output)
        write_json(second_output_path, second_output)
        write_json(
            first_proof_path,
            engineering_proof_for(
                self.manifest,
                alias="M001",
                hypotheses=["小猫AI二零二六", "会议在三点开始"],
                public_plan=self.public_plan,
            ),
        )
        write_json(
            second_proof_path,
            engineering_proof_for(
                self.manifest,
                alias="M002",
                hypotheses=["小狗", "会议"],
                public_plan=self.public_plan,
            ),
        )
        for alias_output, alias_report, proof, engineering_report in (
            (
                first_output_path,
                first_report_path,
                first_proof_path,
                first_engineering_path,
            ),
            (
                second_output_path,
                second_report_path,
                second_proof_path,
                second_engineering_path,
            ),
        ):
            code, _, error = self.run_cli(
                [
                    "sanitize-engineering",
                    "--manifest",
                    str(self.manifest_path),
                    "--accuracy-output",
                    str(alias_output),
                    "--accuracy-report",
                    str(alias_report),
                    "--engineering-proof",
                    str(proof),
                    "--public-plan",
                    str(self.public_plan_path),
                    "--dataset-protocol",
                    str(self.protocol_path),
                    "--repo-root",
                    str(self.repo_root),
                    "--report",
                    str(engineering_report),
                ]
            )
            self.assertEqual((code, error), (0, ""))
        write_json(selection_config, selection_config_dict())
        code, output, error = self.run_cli(
            [
                "select",
                "--reports",
                str(first_report_path),
                str(second_report_path),
                "--engineering-reports",
                str(first_engineering_path),
                str(second_engineering_path),
                "--selection-config",
                str(selection_config),
                "--public-plan",
                str(self.public_plan_path),
                "--repo-root",
                str(self.repo_root),
                "--report",
                str(selection_report),
            ]
        )
        self.assertEqual((code, error), (0, ""))
        self.assertIn("development_screening_only", output)
        selected, _selection_receipt = load_committed_json_artifact(
            selection_report,
            expected_kind="selection-report",
            expected_evidence_sha256=evidence_sha256,
            expected_plan_sha256=plan_sha256,
        )
        self.assertIsNone(selected["winner_alias"])
        self.assertEqual(selected["screening_leader_alias"], "M001")

    def test_custom_normalization_config_is_loaded_and_output_is_never_overwritten(self) -> None:
        references = self.private_root / "references.json"
        output = self.private_root / "model-output.json"
        report = self.private_root / "report.json"
        normalization = self.private_root / "normalization.json"
        write_json(references, references_for())
        write_json(
            output,
            model_output_for(self.manifest, public_plan=self.public_plan),
        )
        write_json(
            normalization,
            {
                "profile_id": "zh-normalization-v1",
                "unicode_form": "NFKC",
                "whitespace": "collapse",
                "punctuation": "preserve",
                "latin_case": "preserve",
                "numbers": "preserve",
            },
        )
        normalization_config = NormalizationConfig(
            whitespace="collapse",
            punctuation="preserve",
            latin_case="preserve",
            numbers="preserve",
        )
        alternate_plan = public_plan_for(
            self.manifest,
            protocol=self.protocol,
            normalization=normalization_config,
        )
        alternate_plan_path = self.private_root / "alternate-plan.json"
        write_json(alternate_plan_path, alternate_plan)
        write_json(
            output,
            model_output_for(self.manifest, public_plan=alternate_plan),
        )
        arguments = [
            "score",
            "--manifest",
            str(self.manifest_path),
            "--references",
            str(references),
            "--model-output",
            str(output),
            "--public-plan",
            str(alternate_plan_path),
            "--dataset-protocol",
            str(self.protocol_path),
            "--repo-root",
            str(self.repo_root),
            "--normalization-config",
            str(normalization),
            "--report",
            str(report),
        ]
        first_code, _, first_error = self.run_cli(arguments)
        second_code, _, second_error = self.run_cli(arguments)
        self.assertEqual((first_code, first_error), (0, ""))
        self.assertEqual(second_code, 2)
        self.assertIn("refusing to overwrite", second_error)

    def test_cli_returns_nonzero_without_leaking_reference_text(self) -> None:
        bad = self.private_root / "bad.json"
        bad.write_text('{"references":[{"text":"不要泄漏这句话"}]}', encoding="utf-8")
        code, _, error = self.run_cli(
            [
                "score",
                "--manifest",
                str(self.manifest_path),
                "--references",
                str(bad),
                "--model-output",
                str(bad),
                "--public-plan",
                str(self.public_plan_path),
                "--dataset-protocol",
                str(self.protocol_path),
                "--repo-root",
                str(self.repo_root),
                "--report",
                str(self.private_root / "report.json"),
            ]
        )
        self.assertEqual(code, 2)
        self.assertNotIn("不要泄漏这句话", error)

    def test_private_map_and_reports_are_rejected_inside_repository(self) -> None:
        public = self.private_root / "generated-plan.json"
        private_in_repo = self.repo_root / "private-map.json"
        code, _, error = self.run_cli(
            [
                "blind",
                "--manifest",
                str(self.manifest_path),
                "--repo-root",
                str(self.repo_root),
                "--dataset-protocol",
                str(self.protocol_path),
                "--registry",
                str(self.registry_path),
                "--models",
                "zipformer_baseline,paraformer_int8",
                "--seed",
                str((1 << 127) + 99),
                "--public-plan",
                str(public),
                "--private-map",
                str(private_in_repo),
            ]
        )
        self.assertEqual(code, 2)
        self.assertIn("outside", error)
        self.assertFalse(private_in_repo.exists())

        references = self.private_root / "references.json"
        output = self.private_root / "model-output.json"
        write_json(references, references_for())
        write_json(
            output,
            model_output_for(self.manifest, public_plan=self.public_plan),
        )
        report_in_repo = self.repo_root / "score-report.json"
        code, _, error = self.run_cli(
            [
                "score",
                "--manifest",
                str(self.manifest_path),
                "--references",
                str(references),
                "--model-output",
                str(output),
                "--public-plan",
                str(self.public_plan_path),
                "--dataset-protocol",
                str(self.protocol_path),
                "--repo-root",
                str(self.repo_root),
                "--report",
                str(report_in_repo),
            ]
        )
        self.assertEqual(code, 2)
        self.assertIn("outside", error)
        self.assertFalse(report_in_repo.exists())

    def test_formal_score_requires_public_plan(self) -> None:
        references = self.private_root / "references.json"
        output = self.private_root / "model-output.json"
        write_json(references, references_for())
        write_json(
            output,
            model_output_for(self.manifest, public_plan=self.public_plan),
        )
        with self.assertRaises(SystemExit) as raised:
            self.run_cli(
                [
                    "score",
                    "--manifest",
                    str(self.manifest_path),
                    "--references",
                    str(references),
                    "--model-output",
                    str(output),
                    "--repo-root",
                    str(self.repo_root),
                    "--report",
                    str(self.private_root / "report.json"),
                ]
            )
        self.assertEqual(raised.exception.code, 2)

    def test_decoder_plan_is_private_and_phase_a_reveal_is_cli_no_go(self) -> None:
        decoder_plan = self.private_root / "decoder-plan.json"
        code, output, error = self.run_cli(
            [
                "make-decoder-plan",
                "--manifest",
                str(self.manifest_path),
                "--public-plan",
                str(self.public_plan_path),
                "--model-alias",
                "M001",
                "--repo-root",
                str(self.repo_root),
                "--plan",
                str(decoder_plan),
            ]
        )
        self.assertEqual((code, error), (0, ""))
        self.assertIn("M001", output)
        self.assertEqual(stat.S_IMODE(decoder_plan.stat().st_mode), 0o400)
        decoder_value, _decoder_receipt = load_committed_json_artifact(
            decoder_plan,
            expected_kind="decoder-plan",
        )
        decoder_text = json.dumps(decoder_value, ensure_ascii=False).lower()
        self.assertNotIn("reference", decoder_text)
        self.assertNotIn("condition", decoder_text)

        selection = self.private_root / "selection.json"
        winner_report = self.private_root / "winner-report.json"
        private_map = self.private_root / "private-map.json"
        reveal_report = self.private_root / "reveal-report.json"
        for path in (selection, winner_report, private_map):
            write_json(path, {})
        code, _, error = self.run_cli(
            [
                "reveal",
                "--selection",
                str(selection),
                "--winner-report",
                str(winner_report),
                "--private-map",
                str(private_map),
                "--public-plan",
                str(self.public_plan_path),
                "--registry",
                str(self.registry_path),
                "--repo-root",
                str(self.repo_root),
                "--report",
                str(reveal_report),
            ]
        )
        self.assertEqual(code, 2)
        self.assertIn("Phase A reveal is disabled", error)
        self.assertFalse(reveal_report.exists())

    def test_formal_decoder_plan_cli_rejects_480_nonexistent_wavs(self) -> None:
        template = self.manifest["clips"][0]
        clips = []
        for index in range(480):
            clip = copy.deepcopy(template)
            clip["clip_id"] = f"clip_{index:012x}"
            clip["audio_path"] = str(
                self.private_root / "missing-production" / f"{index}.wav"
            )
            clip["audio_sha256"] = hashlib.sha256(
                f"missing-wav-{index}".encode("utf-8")
            ).hexdigest()
            clip["pcm_payload_sha256"] = hashlib.sha256(
                f"missing-pcm-{index}".encode("utf-8")
            ).hexdigest()
            clip["speaker_cluster_id"] = f"speaker_{index:012x}"
            clip["session_cluster_id"] = f"session_{index:012x}"
            clip["sentence_id"] = f"sentence_{index:012x}"
            clips.append(clip)
        manifest = {
            **self.manifest,
            "dataset_id": "dataset_222222222222",
            "dataset_usage": {
                "recording_purpose": "production_confirmation",
                "used_for_model_tuning": False,
            },
            "clips": clips,
        }
        plan = public_plan_for(manifest, model_count=2)
        plan["protocol_profile"] = "production_confirmation"
        plan["cohort_id"] = "first_batch_android_v1"
        plan["contract_fingerprints"]["manifest_sha256"] = canonical_sha256(
            manifest
        )
        manifest_path = self.private_root / "missing-production-manifest.json"
        public_plan_path = self.private_root / "missing-production-plan.json"
        decoder_plan_path = self.private_root / "missing-decoder-plan.json"
        write_json(manifest_path, manifest)
        write_json(public_plan_path, plan)

        code, _output, error = self.run_cli(
            [
                "make-decoder-plan",
                "--manifest",
                str(manifest_path),
                "--public-plan",
                str(public_plan_path),
                "--model-alias",
                "M001",
                "--repo-root",
                str(self.repo_root),
                "--snapshot-root",
                str(self.private_root / "formal-snapshots"),
                "--plan",
                str(decoder_plan_path),
            ]
        )
        self.assertEqual(code, 2)
        self.assertRegex(
            error,
            "filesystem-backed|PCM|WAV|audio|valid commit receipt",
        )
        self.assertFalse(decoder_plan_path.exists())

    def test_config_loaders_and_exclusive_writer_fail_closed(self) -> None:
        normalization_values = (
            [],
            {},
            {
                "profile_id": "zh-normalization-v1",
                "unicode_form": "NFKC",
                "whitespace": "remove",
                "punctuation": "rewrite",
                "latin_case": "lower",
                "numbers": "decimal_ascii",
            },
        )
        for index, value in enumerate(normalization_values):
            path = self.private_root / f"bad-normalization-{index}.json"
            write_json(path, value)
            with self.subTest(normalization=index):
                with self.assertRaises(ContractError):
                    _normalization_config(path)

        selection_values = (
            [],
            {},
            {
                **selection_config_dict(),
                "max_total_resource_bytes": 0,
            },
        )
        for index, value in enumerate(selection_values):
            path = self.private_root / f"bad-selection-{index}.json"
            write_json(path, value)
            with self.subTest(selection=index):
                with self.assertRaises(ContractError):
                    _selection_config(path)

        with self.assertRaisesRegex(ContractError, "absolute"):
            _write_json_exclusive(Path("relative.json"), {})
        unserializable = self.private_root / "unserializable.json"
        with self.assertRaises(ContractError):
            _write_json_exclusive(unserializable, {"value": object()})
        self.assertFalse(unserializable.exists())

        interrupted = self.private_root / "interrupted.json"
        with mock.patch.object(
            benchmark_artifacts.os,
            "link",
            side_effect=OSError("simulated atomic commit failure"),
        ):
            with self.assertRaisesRegex(OSError, "atomic commit failure"):
                _write_json_exclusive(interrupted, {"status": "prepared"})
        self.assertFalse(interrupted.exists())
        self.assertEqual(
            list(self.private_root.rglob(".artifact-*"))
            + list(self.private_root.rglob(".receipt-*")),
            [],
        )

    def test_exclusive_writer_runs_precommit_guard_after_fsync_and_cleans_temp(
        self,
    ) -> None:
        target = self.private_root / "guarded.json"
        callback_observations: list[tuple[bool, int]] = []

        def reject_changed_snapshot() -> None:
            temporary_files = list(
                self.private_root.rglob(".artifact-*")
            )
            callback_observations.append(
                (target.exists(), len(temporary_files))
            )
            raise ContractError("snapshot identity changed before commit")

        with self.assertRaisesRegex(ContractError, "identity changed"):
            _write_json_exclusive(
                target,
                {"status": "prepared"},
                precommit_validate=reject_changed_snapshot,
            )
        self.assertEqual(callback_observations, [(False, 0)])
        self.assertFalse(target.exists())
        self.assertEqual(
            list(self.private_root.rglob(".artifact-*"))
            + list(self.private_root.rglob(".receipt-*")),
            [],
        )

    def test_exclusive_writer_never_rolls_back_public_postvalidation_paths(
        self,
    ) -> None:
        orphaned = self.private_root / "orphaned.json"
        calls = 0

        def reject_after_payload() -> None:
            nonlocal calls
            calls += 1
            if calls == 2:
                self.assertFalse(orphaned.exists())
                raise ContractError("snapshot identity changed after payload")

        with self.assertRaisesRegex(ContractError, "identity changed"):
            _write_json_exclusive(
                orphaned,
                {"status": "committed"},
                postcommit_validate=reject_after_payload,
            )
        self.assertFalse(orphaned.exists())
        self.assertEqual(
            len(
                list(
                    (
                        self.private_root / ".kittyecho-asr-artifacts"
                    ).glob("*.json")
                )
            ),
            1,
        )

        replaced = self.private_root / "replacement-preserved.json"
        replacement_calls = 0

        def replace_then_reject() -> None:
            nonlocal replacement_calls
            replacement_calls += 1
            if replacement_calls == 2:
                replaced.write_text('{"owner":"user"}\n', encoding="utf-8")
                raise ContractError("snapshot identity changed after payload")

        with self.assertRaisesRegex(ContractError, "identity changed"):
            _write_json_exclusive(
                replaced,
                {"status": "committed"},
                postcommit_validate=replace_then_reject,
            )
        self.assertEqual(
            replaced.read_text(encoding="utf-8"),
            '{"owner":"user"}\n',
        )
        self.assertEqual(
            list(self.private_root.rglob(".artifact-*"))
            + list(self.private_root.rglob(".receipt-*")),
            [],
        )


if __name__ == "__main__":
    unittest.main()
