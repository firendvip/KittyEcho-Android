from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from kittyecho_asr_bench.contracts import (
    ContractError,
    load_json,
    validate_model_output,
    validate_model_registry,
    validate_recording_manifest,
    validate_reference_set,
)

from support import (
    dataset_protocol_for,
    manifest_for,
    model_output_for,
    public_plan_for,
    references_for,
    registry,
    write_json,
    write_wav,
)


class RecordingManifestContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.private_root = self.root / "private"
        self.repo_root.mkdir()
        self.audio = self.private_root / "clip.wav"
        self.sha = write_wav(self.audio)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_accepts_local_consented_non_sensitive_anonymous_manifest(self) -> None:
        value = validate_recording_manifest(manifest_for(self.audio, self.sha), self.repo_root)
        self.assertEqual(value["dataset_id"], "dataset_0123456789ab")

    def test_rejects_relative_or_repo_internal_real_audio_path(self) -> None:
        relative = manifest_for(self.audio, self.sha)
        relative["clips"][0]["audio_path"] = "private/clip.wav"
        with self.assertRaisesRegex(ContractError, "absolute"):
            validate_recording_manifest(relative, self.repo_root)

        internal_audio = self.repo_root / "recording.wav"
        internal_sha = write_wav(internal_audio)
        internal = manifest_for(internal_audio, internal_sha)
        with self.assertRaisesRegex(ContractError, "outside the repository"):
            validate_recording_manifest(internal, self.repo_root)

    def test_rejects_sensitive_unconsented_or_cloud_origin_by_default(self) -> None:
        mutations = (
            ("consent", "obtained", False, "consent"),
            ("privacy", "contains_sensitive_content", True, "sensitive"),
            ("privacy", "upload_permitted", True, "upload"),
            ("source", "cloud_origin", True, "cloud"),
            ("source", "local_only", False, "local-only"),
        )
        for parent, key, value, message in mutations:
            with self.subTest(field=f"{parent}.{key}"):
                manifest = manifest_for(self.audio, self.sha)
                manifest["clips"][0][parent][key] = value
                with self.assertRaisesRegex(ContractError, message):
                    validate_recording_manifest(manifest, self.repo_root)

    def test_rejects_non_anonymous_ids_duplicate_clips_and_hidden_answers(self) -> None:
        bad_id = manifest_for(self.audio, self.sha)
        bad_id["clips"][0]["clip_id"] = "alice-meeting"
        with self.assertRaisesRegex(ContractError, "anonymous"):
            validate_recording_manifest(bad_id, self.repo_root)

        duplicate = manifest_for(self.audio, self.sha)
        duplicate["clips"][1]["clip_id"] = duplicate["clips"][0]["clip_id"]
        with self.assertRaisesRegex(ContractError, "duplicate"):
            validate_recording_manifest(duplicate, self.repo_root)

        leaked = manifest_for(self.audio, self.sha)
        leaked["clips"][0]["reference_text"] = "答案"
        with self.assertRaisesRegex(ContractError, "answer"):
            validate_recording_manifest(leaked, self.repo_root)

    def test_rejects_missing_and_unknown_fields(self) -> None:
        missing = manifest_for(self.audio, self.sha)
        del missing["clips"][0]["audio_sha256"]
        with self.assertRaisesRegex(ContractError, "audio_sha256"):
            validate_recording_manifest(missing, self.repo_root)

        unknown = manifest_for(self.audio, self.sha)
        unknown["unexpected"] = True
        with self.assertRaisesRegex(ContractError, "unknown"):
            validate_recording_manifest(unknown, self.repo_root)


class OtherContractTest(unittest.TestCase):
    def test_reference_contract_is_separate_and_complete(self) -> None:
        references = validate_reference_set(
            references_for(),
            expected_dataset_id="dataset_0123456789ab",
            expected_clip_ids={"clip_000000000000", "clip_000000000001"},
        )
        self.assertEqual(len(references["references"]), 2)

        incomplete = references_for(clip_count=1)
        with self.assertRaisesRegex(ContractError, "clip set"):
            validate_reference_set(
                incomplete,
                expected_dataset_id="dataset_0123456789ab",
                expected_clip_ids={"clip_000000000000", "clip_000000000001"},
            )

    def test_model_registry_rejects_mirror_bad_hash_and_duplicate_model(self) -> None:
        self.assertEqual(len(validate_model_registry(registry())["models"]), 1)

        mirror = registry()
        mirror["models"][0]["official_source"]["model_url"] = "https://hf-mirror.com/foo"
        with self.assertRaisesRegex(ContractError, "official HTTPS"):
            validate_model_registry(mirror)

        bad_hash = registry()
        bad_hash["models"][0]["artifacts"][0]["sha256"] = "unknown"
        with self.assertRaisesRegex(ContractError, "sha256"):
            validate_model_registry(bad_hash)

        duplicate = registry()
        duplicate["models"].append(dict(duplicate["models"][0]))
        with self.assertRaisesRegex(ContractError, "duplicate"):
            validate_model_registry(duplicate)

    def test_model_output_rejects_answer_access_and_pcm_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            audio = root / "private" / "clip.wav"
            sha = write_wav(audio)
            manifest = manifest_for(audio, sha)
            plan = public_plan_for(manifest)
            protocol = dataset_protocol_for(manifest)
            output = model_output_for(manifest, public_plan=plan)
            self.assertEqual(
                validate_model_output(output, plan, manifest, protocol)["model_alias"],
                "M001",
            )

            leaked = model_output_for(manifest, public_plan=plan)
            leaked["reference_accessed"] = True
            with self.assertRaisesRegex(ContractError, "reference"):
                validate_model_output(leaked, plan, manifest, protocol)

            drift = model_output_for(manifest, public_plan=plan)
            drift["predictions"][0]["pcm_sha256"] = "0" * 64
            with self.assertRaisesRegex(ContractError, "PCM"):
                validate_model_output(drift, plan, manifest, protocol)

            preprocessing = model_output_for(manifest, public_plan=plan)
            preprocessing["input_transform_id"] = "model-specific-denoise"
            with self.assertRaisesRegex(ContractError, "input transform"):
                validate_model_output(preprocessing, plan, manifest, protocol)

    def test_model_output_must_follow_exact_randomized_clip_order(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            audio = root / "private" / "clip.wav"
            sha = write_wav(audio)
            manifest = manifest_for(audio, sha)
            plan = public_plan_for(manifest)
            protocol = dataset_protocol_for(manifest)
            output = model_output_for(manifest, public_plan=plan)
            output["predictions"].reverse()
            with self.assertRaisesRegex(ContractError, "order"):
                validate_model_output(output, plan, manifest, protocol)

    def test_model_output_rejects_corrupted_public_plan_pcm_commitment(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            audio = root / "private" / "clip.wav"
            sha = write_wav(audio)
            manifest = manifest_for(audio, sha)
            plan = public_plan_for(manifest)
            protocol = dataset_protocol_for(manifest)
            output = model_output_for(manifest, public_plan=plan)
            plan["clip_pcm_sha256"][manifest["clips"][0]["clip_id"]] = "0" * 64
            with self.assertRaisesRegex(ContractError, "plan.*PCM|PCM.*plan"):
                validate_model_output(output, plan, manifest, protocol)

    def test_json_loader_rejects_duplicate_keys_and_large_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            duplicate = root / "duplicate.json"
            duplicate.write_text('{"schema_version":"1.0","schema_version":"2.0"}', encoding="utf-8")
            with self.assertRaisesRegex(ContractError, "duplicate JSON key"):
                load_json(duplicate)

            large = root / "large.json"
            large.write_text('{"padding":"' + ("x" * 128) + '"}', encoding="utf-8")
            with self.assertRaisesRegex(ContractError, "too large"):
                load_json(large, max_bytes=64)

    def test_committed_schema_files_are_draft_2020_12_json(self) -> None:
        root = Path(__file__).resolve().parents[1]
        expected = {
            "artifact-receipt.schema.json",
            "blind-run-plan.schema.json",
            "dataset-protocol.schema.json",
            "decoder-plan.schema.json",
            "development-emulator-model-output.schema.json",
            "development-emulator-score.schema.json",
            "recording-manifest.schema.json",
            "model-registry.schema.json",
            "reference-set.schema.json",
            "model-output.schema.json",
            "score-report.schema.json",
            "selection-failure.schema.json",
            "engineering-proof.schema.json",
            "engineering-report.schema.json",
        }
        actual = {path.name for path in (root / "contracts").glob("*.schema.json")}
        self.assertTrue(expected.issubset(actual))
        for name in expected:
            document = json.loads((root / "contracts" / name).read_text(encoding="utf-8"))
            self.assertEqual(document["$schema"], "https://json-schema.org/draft/2020-12/schema")
            self.assertFalse(document.get("additionalProperties", True))
        engineering_proof = json.loads(
            (root / "contracts" / "engineering-proof.schema.json").read_text(
                encoding="utf-8"
            )
        )
        engineering_report = json.loads(
            (root / "contracts" / "engineering-report.schema.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertFalse(
            engineering_proof["properties"]["runtime"]["additionalProperties"]
        )
        self.assertFalse(
            engineering_proof["properties"]["runner_proof"]["additionalProperties"]
        )
        self.assertFalse(
            engineering_report["properties"]["hard_gates"]["additionalProperties"]
        )


if __name__ == "__main__":
    unittest.main()
