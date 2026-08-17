package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DevelopmentBatchInstrumentationContractTest {
    @Test
    fun onlyParaformerAndZipformerHaveDevelopmentBatchCommands() {
        val paraformer = DevelopmentBatchInstrumentationCommand.parse(
            paraformerEntries(),
        )
        val zipformer = DevelopmentBatchInstrumentationCommand.parse(
            zipformerEntries(),
        )

        assertEquals(DevelopmentBatchCandidate.PARAFORMER, paraformer.candidate)
        assertEquals(DevelopmentBatchCandidate.ZIPFORMER, zipformer.candidate)
        assertEquals("run_000000000001", paraformer.runId)
        assertEquals("M001", paraformer.modelAlias)
        assertEquals("a".repeat(64), paraformer.expectedSnapshotFingerprintSha256)
        assertEquals("b".repeat(64), paraformer.expectedDecoderPlanSha256)
        assertEquals(
            setOf("weights", "tokenizer"),
            paraformer.modelPathsByRole.keys,
        )
        assertEquals(
            setOf("encoder", "decoder", "joiner", "tokenizer"),
            zipformer.modelPathsByRole.keys,
        )
    }

    @Test
    fun fireRedAndFunAsrCannotEnterTheDevelopmentBatchSurface() {
        listOf("firered-dev-batch", "funasr-nano-dev-batch").forEach { mode ->
            assertThrows(BenchmarkContractException::class.java) {
                DevelopmentBatchInstrumentationCommand.parse(
                    paraformerEntries().map {
                        if (it.first == "mode") "mode" to mode else it
                    },
                )
            }
        }
    }

    @Test
    fun commandArgumentsAreExactUniqueAndAbsolute() {
        val valid = paraformerEntries()
        listOf(
            valid.filterNot { it.first == "decoder_plan_path" },
            valid + ("decoder_plan_path" to "/private/duplicate.json"),
            valid + ("duration_seconds" to "600"),
            valid.map {
                if (it.first == "pcm_root_path") {
                    "pcm_root_path" to "relative/pcm"
                } else {
                    it
                }
            },
            valid.map {
                if (it.first == "model_path") {
                    "model_path" to "/private/bad\u0000model"
                } else {
                    it
                }
            },
        ).forEach { invalid ->
            assertThrows(BenchmarkContractException::class.java) {
                DevelopmentBatchInstrumentationCommand.parse(invalid)
            }
        }
    }

    @Test
    fun commandRejectsInvalidIdentityAndCrossCandidateFields() {
        listOf(
            "run_id" to "dev96",
            "model_alias" to "paraformer",
            "expected_snapshot_fingerprint_sha256" to "0",
            "expected_decoder_plan_sha256" to "A".repeat(64),
        ).forEach { (key, value) ->
            assertThrows(BenchmarkContractException::class.java) {
                DevelopmentBatchInstrumentationCommand.parse(
                    paraformerEntries().map {
                        if (it.first == key) key to value else it
                    },
                )
            }
        }
        assertThrows(BenchmarkContractException::class.java) {
            DevelopmentBatchInstrumentationCommand.parse(
                zipformerEntries().filterNot { it.first == "joiner_path" } +
                    ("model_path" to "/private/model.int8.onnx"),
            )
        }
    }

    private fun paraformerEntries(): List<Pair<String, String>> = listOf(
        "mode" to "paraformer-dev-batch",
        "run_id" to "run_000000000001",
        "model_alias" to "M001",
        "model_path" to "/private/model.int8.onnx",
        "tokens_path" to "/private/tokens.txt",
        "decoder_plan_path" to "/private/decoder-plan.dev.json",
        "pcm_root_path" to "/private/pcm",
        "expected_snapshot_fingerprint_sha256" to "a".repeat(64),
        "expected_decoder_plan_sha256" to "b".repeat(64),
    )

    private fun zipformerEntries(): List<Pair<String, String>> = listOf(
        "mode" to "zipformer-dev-batch",
        "run_id" to "run_000000000001",
        "model_alias" to "M001",
        "encoder_path" to "/private/encoder.int8.onnx",
        "decoder_path" to "/private/decoder.int8.onnx",
        "joiner_path" to "/private/joiner.int8.onnx",
        "tokens_path" to "/private/tokens.txt",
        "decoder_plan_path" to "/private/decoder-plan.dev.json",
        "pcm_root_path" to "/private/pcm",
        "expected_snapshot_fingerprint_sha256" to "a".repeat(64),
        "expected_decoder_plan_sha256" to "b".repeat(64),
    )
}
