from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from kittyecho_asr_bench.contracts import ContractError
from kittyecho_asr_bench.development_emulator import (
    score_development_emulator_output,
    validate_development_emulator_output,
    validate_development_emulator_score,
)
from kittyecho_asr_bench.metrics import score_model
from kittyecho_asr_bench.normalization import NormalizationConfig
from kittyecho_asr_bench.reveal import reveal_winner
from kittyecho_asr_bench.selection import SelectionConfig, select_winner

from support import (
    dataset_protocol_for,
    manifest_for,
    model_output_for,
    public_plan_for,
    write_wav,
)


class DevelopmentEmulatorOutputTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        audio = root / "private" / "clip.wav"
        sha256 = write_wav(audio)
        self.manifest = manifest_for(audio, sha256, clip_count=96)
        self.public_plan = public_plan_for(self.manifest)
        self.formal_output = model_output_for(
            self.manifest,
            hypotheses=[f" raw-{index} " for index in range(96)],
            latencies_ms=[100.0 + index for index in range(96)],
            public_plan=self.public_plan,
        )
        self.snapshot_sha256 = "a" * 64
        self.development_plan_sha256 = "b" * 64
        self.expected_clip_ids = [
            prediction["clip_id"]
            for prediction in self.formal_output["predictions"]
        ]
        self.references = {
            "schema_version": "1.0",
            "dataset_id": self.manifest["dataset_id"],
            "references": [
                {
                    "clip_id": clip_id,
                    "text": f"开发参考句子{index}",
                    "annotations": [],
                }
                for index, clip_id in enumerate(self.expected_clip_ids)
            ],
        }
        self.development_output = {
            "schema_version": "development-emulator-model-output-v1",
            "run_id": self.formal_output["run_id"],
            "dataset_id": self.manifest["dataset_id"],
            "model_alias": self.formal_output["model_alias"],
            "decoder_contract_id": "first-layer-raw-v1",
            "pcm_contract_id": "pcm16k-mono-s16le-v1",
            "input_transform_id": "canonical-pcm-direct-v1",
            "snapshot_fingerprint_sha256": self.snapshot_sha256,
            "development_decoder_plan_sha256": self.development_plan_sha256,
            "reference_accessed": False,
            "predictions": [
                {
                    **prediction,
                    "stop_to_final_ns": 100_000_000 + index,
                }
                for index, prediction in enumerate(
                    self.formal_output["predictions"]
                )
            ],
            "eligibility": {
                "development_only": True,
                "emulator_only": True,
                "formal_eligible": False,
                "product_decision_eligible": False,
                "production_eligible": False,
            },
        }

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_dev_scorer_retains_dev_authority_and_omits_private_text(self) -> None:
        report = score_development_emulator_output(
            self.development_output,
            self.references,
            NormalizationConfig(),
            expected_dataset_id=self.manifest["dataset_id"],
            expected_snapshot_fingerprint_sha256=self.snapshot_sha256,
            expected_development_decoder_plan_sha256=self.development_plan_sha256,
            expected_clip_ids=self.expected_clip_ids,
        )

        self.assertEqual(
            set(report),
            {
                "schema_version",
                "run_id",
                "dataset_id",
                "model_alias",
                "snapshot_fingerprint_sha256",
                "development_decoder_plan_sha256",
                "normalization",
                "metrics",
                "clip_scores",
                "eligibility",
            },
        )
        self.assertEqual(report["schema_version"], "development-emulator-score-v1")
        self.assertEqual(report["dataset_id"], self.manifest["dataset_id"])
        self.assertEqual(
            report["snapshot_fingerprint_sha256"], self.snapshot_sha256
        )
        self.assertEqual(
            report["development_decoder_plan_sha256"],
            self.development_plan_sha256,
        )
        self.assertEqual(report["metrics"]["sentence_count"], 96)
        self.assertEqual(len(report["clip_scores"]), 96)
        self.assertEqual(
            report["eligibility"],
            {
                "development_only": True,
                "emulator_only": True,
                "formal_eligible": False,
                "product_decision_eligible": False,
                "production_eligible": False,
            },
        )
        serialized = json.dumps(report, ensure_ascii=False, sort_keys=True)
        self.assertNotIn(self.references["references"][0]["text"], serialized)
        self.assertNotIn(
            self.development_output["predictions"][0]["hypothesis"], serialized
        )
        validate_development_emulator_score(
            report,
            expected_dataset_id=self.manifest["dataset_id"],
            expected_snapshot_fingerprint_sha256=self.snapshot_sha256,
            expected_development_decoder_plan_sha256=self.development_plan_sha256,
            expected_clip_ids=self.expected_clip_ids,
        )

    def test_formal_score_select_and_reveal_reject_dev_authority(self) -> None:
        report = self.score(self.development_output)

        with self.assertRaises(ContractError):
            score_model(
                self.manifest,
                self.references,
                self.development_output,
                NormalizationConfig(),
                self.public_plan,
                dataset_protocol_for(self.manifest),
                SelectionConfig().to_dict(),
            )
        with self.assertRaises(ContractError):
            select_winner(
                [report],
                SelectionConfig(),
                public_plan=self.public_plan,
            )
        with self.assertRaisesRegex(ContractError, "Phase A reveal is disabled"):
            reveal_winner(report, report, {}, self.public_plan, {})

    def test_dev_score_eligibility_shape_and_metrics_fail_closed(self) -> None:
        report = self.score(self.development_output)
        attacks = []
        promoted = copy.deepcopy(report)
        promoted["eligibility"]["formal_eligible"] = True
        attacks.append(promoted)
        extra_answer = copy.deepcopy(report)
        extra_answer["clip_scores"][0]["reference"] = "private answer"
        attacks.append(extra_answer)
        wrong_cer = copy.deepcopy(report)
        wrong_cer["metrics"]["cer"] += 0.01
        attacks.append(wrong_cer)
        wrong_exact = copy.deepcopy(report)
        wrong_exact["clip_scores"][0]["exact"] = not wrong_exact[
            "clip_scores"
        ][0]["exact"]
        attacks.append(wrong_exact)
        wrong_order = copy.deepcopy(report)
        wrong_order["clip_scores"].reverse()
        attacks.append(wrong_order)

        for value in attacks:
            with self.subTest(value=value):
                with self.assertRaises(ContractError):
                    validate_development_emulator_score(
                        value,
                        expected_dataset_id=self.manifest["dataset_id"],
                        expected_snapshot_fingerprint_sha256=self.snapshot_sha256,
                        expected_development_decoder_plan_sha256=(
                            self.development_plan_sha256
                        ),
                        expected_clip_ids=self.expected_clip_ids,
                    )

    def test_dev_only_eligibility_answers_and_clip_contract_fail_closed(self) -> None:
        attacks = []
        for field in (
            "formal_eligible",
            "product_decision_eligible",
            "production_eligible",
        ):
            value = copy.deepcopy(self.development_output)
            value["eligibility"][field] = True
            attacks.append(value)
        leaked = copy.deepcopy(self.development_output)
        leaked["predictions"][0]["reference"] = "private answer"
        attacks.append(leaked)
        path_leak = copy.deepcopy(self.development_output)
        path_leak["predictions"][0]["audio_path"] = "/private/clip.wav"
        attacks.append(path_leak)
        duplicate = copy.deepcopy(self.development_output)
        duplicate["predictions"][1]["clip_id"] = duplicate["predictions"][0]["clip_id"]
        attacks.append(duplicate)
        missing = copy.deepcopy(self.development_output)
        missing["predictions"].pop()
        attacks.append(missing)
        negative_latency = copy.deepcopy(self.development_output)
        negative_latency["predictions"][0]["stop_to_final_ns"] = -1
        attacks.append(negative_latency)
        accessed = copy.deepcopy(self.development_output)
        accessed["reference_accessed"] = True
        attacks.append(accessed)

        for value in attacks:
            with self.subTest(value=value):
                with self.assertRaises(ContractError):
                    self.validate(value)

    def test_host_snapshot_plan_and_order_commitments_cannot_be_substituted(self) -> None:
        mutations = (
            {"expected_snapshot_fingerprint_sha256": "0" * 64},
            {"expected_development_decoder_plan_sha256": "0" * 64},
            {"expected_clip_ids": list(reversed(self.expected_clip_ids))},
        )
        for mutation in mutations:
            arguments = {
                "expected_dataset_id": self.manifest["dataset_id"],
                "expected_snapshot_fingerprint_sha256": self.snapshot_sha256,
                "expected_development_decoder_plan_sha256": self.development_plan_sha256,
                "expected_clip_ids": self.expected_clip_ids,
            }
            arguments.update(mutation)
            with self.subTest(mutation=mutation):
                with self.assertRaises(ContractError):
                    validate_development_emulator_output(
                        self.development_output,
                        **arguments,
                    )

    def validate(self, value: object) -> dict[str, object]:
        return validate_development_emulator_output(
            value,
            expected_dataset_id=self.manifest["dataset_id"],
            expected_snapshot_fingerprint_sha256=self.snapshot_sha256,
            expected_development_decoder_plan_sha256=self.development_plan_sha256,
            expected_clip_ids=self.expected_clip_ids,
        )

    def score(self, value: object) -> dict[str, object]:
        return score_development_emulator_output(
            value,
            self.references,
            NormalizationConfig(),
            expected_dataset_id=self.manifest["dataset_id"],
            expected_snapshot_fingerprint_sha256=self.snapshot_sha256,
            expected_development_decoder_plan_sha256=self.development_plan_sha256,
            expected_clip_ids=self.expected_clip_ids,
        )


if __name__ == "__main__":
    unittest.main()
