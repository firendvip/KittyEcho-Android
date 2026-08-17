from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from kittyecho_asr_bench.contracts import validate_model_output
from kittyecho_asr_bench.metrics import edit_stats, score_model
from kittyecho_asr_bench.normalization import NormalizationConfig, normalize_text

from support import (
    REQUIRED_SELECTION_CONDITIONS,
    dataset_protocol_for,
    manifest_for,
    model_output_for,
    public_plan_for,
    references_for,
    selection_config_dict,
    write_wav,
)


class NormalizationTest(unittest.TestCase):
    def test_default_v1_normalizes_width_whitespace_punctuation_case_and_decimal_digits(self) -> None:
        config = NormalizationConfig()
        normalized = normalize_text(" ＡＩ，小猫 １２٣！ ", config)
        self.assertEqual(normalized, "ai小猫123")
        self.assertEqual(config.profile_id, "zh-normalization-v1")
        self.assertEqual(len(config.fingerprint()), 64)

    def test_each_strategy_is_configurable_and_serialized(self) -> None:
        config = NormalizationConfig(
            unicode_form="NONE",
            whitespace="preserve",
            punctuation="preserve",
            latin_case="preserve",
            numbers="preserve",
        )
        self.assertEqual(normalize_text("Ａ A，١", config), "Ａ A，١")
        self.assertEqual(config.to_dict()["numbers"], "preserve")

    def test_collapse_whitespace_is_deterministic(self) -> None:
        config = NormalizationConfig(whitespace="collapse", punctuation="preserve")
        self.assertEqual(normalize_text("小猫 \n\t AI", config), "小猫 ai")

    def test_invalid_normalization_strategy_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "punctuation"):
            NormalizationConfig(punctuation="rewrite")


class MetricTest(unittest.TestCase):
    def test_cer_handles_chinese_english_numbers_and_alignment_counts(self) -> None:
        chinese = edit_stats("小猫喜欢鱼", "小狗喜欢")
        self.assertEqual((chinese.substitutions, chinese.deletions, chinese.insertions), (1, 1, 0))
        self.assertAlmostEqual(chinese.cer, 2 / 5)

        english = edit_stats("abc", "adc")
        self.assertEqual(english.substitutions, 1)

        number = edit_stats("2026", "2025")
        self.assertEqual(number.substitutions, 1)

    def test_cer_handles_empty_reference_and_hypothesis(self) -> None:
        self.assertEqual(edit_stats("", "").cer, 0.0)
        self.assertEqual(edit_stats("", "猫").cer, 1.0)
        self.assertEqual(edit_stats("猫", "").deletions, 1)

    def test_head_and_tail_deletions_are_counted(self) -> None:
        result = edit_stats("开始中间结束", "中间")
        self.assertEqual(result.head_deleted_chars, 2)
        self.assertEqual(result.tail_deleted_chars, 2)

    def test_model_report_has_anonymous_errors_groups_and_annotation_recall(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            audio = root / "private" / "clip.wav"
            sha = write_wav(audio)
            manifest = manifest_for(audio, sha)
            references = references_for()
            plan = public_plan_for(manifest)
            output = model_output_for(
                manifest,
                hypotheses=["小猫ai二零二六", "会议三点"],
                latencies_ms=[300.0, 700.0],
                public_plan=plan,
            )
            validate_model_output(output, plan, manifest, dataset_protocol_for(manifest))
            report = score_model(
                manifest,
                references,
                output,
                NormalizationConfig(),
                plan,
                dataset_protocol_for(manifest),
                selection_config_dict(),
            )

        self.assertEqual(report["metrics"]["sentence_count"], 2)
        self.assertAlmostEqual(report["metrics"]["sentence_exact_rate"], 0.5)
        self.assertAlmostEqual(report["metrics"]["proper_name_recall"], 1.0)
        self.assertAlmostEqual(report["metrics"]["number_recall"], 1.0)
        self.assertIn("code_switch", report["groups"])
        self.assertIn("noise", report["groups"])
        self.assertNotIn("latency", report["groups"]["normal_short"])
        self.assertTrue(report["evaluation_contract"]["blind_evaluation"])
        self.assertEqual(
            report["evaluation_contract"]["decoder_contract_id"],
            "first-layer-raw-v1",
        )
        self.assertEqual(len(report["evaluation_contract"]["public_plan_sha256"]), 64)
        self.assertEqual(len(report["evaluation_contract"]["pcm_set_sha256"]), 64)
        self.assertEqual(
            set(report["dataset_summary"]["condition_counts"]),
            set(REQUIRED_SELECTION_CONDITIONS),
        )
        self.assertEqual(
            report["dataset_summary"]["source_kind_counts"],
            {
                "local_target_device_recording": 2,
                "local_consented_recording": 0,
                "synthetic_fixture": 0,
            },
        )
        self.assertEqual(
            set(report["sentence_errors"][0]),
            {
                "clip_id",
                "cer",
                "substitutions",
                "deletions",
                "insertions",
                "reference_characters",
                "head_deleted_chars",
                "tail_deleted_chars",
                "chinese_edits",
                "chinese_reference_characters",
                "latin_edits",
                "latin_reference_characters",
                "latin_word_edits",
                "latin_reference_words",
                "number_total",
                "number_hits",
                "proper_name_total",
                "proper_name_hits",
                "missing_annotation_ids",
                "pcm_sha256",
                "conditions",
                "source_kind",
                "speaker_cluster_id",
                "session_cluster_id",
                "status",
            },
        )
        self.assertIn("chinese_cer", report["groups"]["code_switch"])
        self.assertIn("latin_cer", report["groups"]["code_switch"])
        self.assertIn("latin_wer", report["groups"]["code_switch"])
        serialized = str(report["sentence_errors"])
        self.assertNotIn("小猫ai二零二六", serialized)
        self.assertNotIn("会议在三点开始", serialized)


if __name__ == "__main__":
    unittest.main()
