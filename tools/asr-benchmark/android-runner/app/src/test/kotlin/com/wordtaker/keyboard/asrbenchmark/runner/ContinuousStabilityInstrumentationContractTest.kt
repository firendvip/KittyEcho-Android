package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.StabilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContinuousStabilityInstrumentationContractTest {
    @Test
    fun allSupportedCandidatesReuseSmokeContractsAtBothFrozenDurations() {
        listOf("10", "600").forEach { seconds ->
            val paraformer = ParaformerStabilityInstrumentationCommand.parse(
                paraformerEntries(seconds),
            )
            val zipformer = ZipformerStabilityInstrumentationCommand.parse(
                zipformerEntries(seconds),
            )
            val nano = FunAsrNanoStabilityInstrumentationCommand.parse(
                nanoEntries(seconds),
            )

            assertEquals(seconds, paraformer.duration.wireValue)
            assertEquals(seconds, zipformer.duration.wireValue)
            assertEquals(seconds, nano.duration.wireValue)
            assertEquals("run_000000000001", paraformer.smokeCommand.runId)
            assertEquals("run_000000000001", zipformer.smokeCommand.runId)
            assertEquals("run_000000000001", nano.smokeCommand.runId)
            assertEquals(
                ParaformerCancellationPhase.NONE,
                paraformer.smokeCommand.cancellationPhase,
            )
        }
    }

    @Test
    fun durationIsAnExactTenOrSixHundredSecondWhitelist() {
        assertEquals(
            StabilityPolicy.developmentEmulator600(),
            ContinuousStabilityDuration.TEN_MINUTES.policy,
        )
        assertEquals(
            StabilityPolicy.development(10_000_000_000L),
            ContinuousStabilityDuration.TEN_SECONDS.policy,
        )
        org.junit.Assert.assertNotEquals(
            StabilityPolicy.formal(),
            ContinuousStabilityDuration.TEN_MINUTES.policy,
        )
        listOf("", "0", "9", "010", "10.0", "11", "599", "601", "3600")
            .forEach { invalid ->
                assertThrows(BenchmarkContractException::class.java) {
                    ParaformerStabilityInstrumentationCommand.parse(
                        paraformerEntries(invalid),
                    )
                }
            }
    }

    @Test
    fun missingDuplicateExtraAndCrossCandidateArgumentsFailClosed() {
        val valid = zipformerEntries("10")
        listOf(
            valid.filterNot { it.first == "duration_seconds" },
            valid + ("duration_seconds" to "10"),
            valid + ("host_timeout" to "10"),
            valid.filterNot { it.first == "joiner_path" },
            valid.map {
                if (it.first == "mode") it.first to "paraformer-stability" else it
            },
        ).forEach { invalid ->
            assertThrows(BenchmarkContractException::class.java) {
                ZipformerStabilityInstrumentationCommand.parse(invalid)
            }
        }
    }

    @Test
    fun existingIdentityPathAndCancellationValidationCannotBeBypassed() {
        listOf(
            "run_id" to "bad",
            "model_path" to "relative/model.int8.onnx",
            "cancel_phase" to "later",
            "tokens_path" to "bad\u0000path",
        ).forEach { (key, value) ->
            assertThrows(BenchmarkContractException::class.java) {
                ParaformerStabilityInstrumentationCommand.parse(
                    paraformerEntries("10").map {
                        if (it.first == key) key to value else it
                    },
                )
            }
        }
    }

    @Test
    fun noFireRedContinuousModeIsAccepted() {
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerStabilityInstrumentationCommand.parse(
                paraformerEntries("10").map {
                    if (it.first == "mode") it.first to "firered-stability" else it
                },
            )
        }
    }

    private fun paraformerEntries(duration: String) = listOf(
        "mode" to "paraformer-stability",
        "run_id" to "run_000000000001",
        "dataset_id" to "dataset_000000000001",
        "model_alias" to "M001",
        "model_path" to "/private/model.int8.onnx",
        "tokens_path" to "/private/tokens.txt",
        "pcm_path" to "/private/synthetic-smoke.wav",
        "cancel_phase" to "none",
        "duration_seconds" to duration,
    )

    private fun zipformerEntries(duration: String) = listOf(
        "mode" to "zipformer-stability",
        "run_id" to "run_000000000001",
        "dataset_id" to "dataset_000000000001",
        "model_alias" to "M001",
        "encoder_path" to "/private/encoder.int8.onnx",
        "decoder_path" to "/private/decoder.int8.onnx",
        "joiner_path" to "/private/joiner.int8.onnx",
        "tokens_path" to "/private/tokens.txt",
        "pcm_path" to "/private/synthetic-smoke.wav",
        "cancel_phase" to "none",
        "duration_seconds" to duration,
    )

    private fun nanoEntries(duration: String) = listOf(
        "mode" to "funasr-nano-stability",
        "run_id" to "run_000000000001",
        "dataset_id" to "dataset_000000000001",
        "model_alias" to "M001",
        "embedding_path" to "/private/embedding.int8.onnx",
        "encoder_adaptor_path" to "/private/encoder_adaptor.int8.onnx",
        "llm_path" to "/private/llm.int8.onnx",
        "tokenizer_directory_path" to "/private/Qwen3-0.6B",
        "pcm_path" to "/private/synthetic-smoke.wav",
        "cancel_phase" to "none",
        "duration_seconds" to duration,
    )
}
