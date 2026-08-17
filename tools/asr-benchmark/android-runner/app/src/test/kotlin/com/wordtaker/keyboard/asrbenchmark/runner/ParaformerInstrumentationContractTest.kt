package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ParaformerInstrumentationContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exactCommandArgumentsAreParsedWithoutTrustingHostMeasurements() {
        val inputs = createInputs()

        val command = ParaformerInstrumentationCommand.parse(
            validEntries(inputs),
        )

        assertEquals("run_000000000001", command.runId)
        assertEquals("dataset_000000000001", command.datasetId)
        assertEquals("M001", command.modelAlias)
        assertEquals(ParaformerCancellationPhase.NONE, command.cancellationPhase)
        assertEquals(inputs.model.toString(), command.modelPath)
        assertEquals(inputs.tokens.toString(), command.tokensPath)
        assertEquals(inputs.pcm.toString(), command.pcmPath)
    }

    @Test
    fun missingArgumentIsRejected() {
        val entries = validEntries(createInputs()).filterNot {
            it.first == "pcm_path"
        }

        assertThrows(BenchmarkContractException::class.java) {
            ParaformerInstrumentationCommand.parse(entries)
        }
    }

    @Test
    fun duplicateArgumentIsRejectedBeforeBundleFlattening() {
        val entries = validEntries(createInputs()).toMutableList().apply {
            add("model_path" to last { it.first == "model_path" }.second)
        }

        assertThrows(BenchmarkContractException::class.java) {
            ParaformerInstrumentationCommand.parse(entries)
        }
    }

    @Test
    fun unexpectedArgumentAndInvalidCancellationPhaseAreRejected() {
        val inputs = createInputs()
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerInstrumentationCommand.parse(
                validEntries(inputs) + ("host_model_sha256" to "0".repeat(64)),
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerInstrumentationCommand.parse(
                validEntries(inputs).map {
                    if (it.first == "cancel_phase") it.first to "sometimes" else it
                },
            )
        }
    }

    @Test
    fun invalidModeIdentityAndPathValuesAreRejected() {
        val inputs = createInputs()
        listOf(
            "mode" to "run",
            "run_id" to "run_not_hex___",
            "dataset_id" to "dataset_bad",
            "model_alias" to "paraformer",
            "pcm_path" to "bad\u0000path",
            "tokens_path" to "x".repeat(4_097),
        ).forEach { (key, invalidValue) ->
            assertThrows(BenchmarkContractException::class.java) {
                ParaformerInstrumentationCommand.parse(
                    validEntries(inputs).map {
                        if (it.first == key) key to invalidValue else it
                    },
                )
            }
        }
    }

    @Test
    fun relativeEscapedAndWrongBasenameInputsAreRejected() {
        val inputs = createInputs()
        val root = inputs.root

        listOf(
            "model.int8.onnx",
            root.resolve("..").resolve("model.int8.onnx").toString(),
            root.resolve("model.onnx").toString(),
        ).forEach { invalidModel ->
            assertThrows(BenchmarkContractException::class.java) {
                ParaformerPrivateInputContract.validate(
                    trustedRoot = root,
                    command = ParaformerInstrumentationCommand.parse(
                        validEntries(inputs).map {
                            if (it.first == "model_path") {
                                it.first to invalidModel
                            } else {
                                it
                            }
                        },
                    ),
                )
            }
        }
    }

    @Test
    fun onlyThreeDirectRegularFilesBelowTrustedRootAreAccepted() {
        val inputs = createInputs()

        val validated = ParaformerPrivateInputContract.validate(
            trustedRoot = inputs.root,
            command = ParaformerInstrumentationCommand.parse(validEntries(inputs)),
        )

        assertEquals(inputs.model, validated.model)
        assertEquals(inputs.tokens, validated.tokens)
        assertEquals(inputs.pcm, validated.pcm)
    }

    @Test
    fun finalAndIntermediateSymbolicLinksAreRejected() {
        val inputs = createInputs()
        val alternate = temporaryFolder.newFolder("alternate").toPath()
        val linkedModel = inputs.root.resolve("linked-model.int8.onnx")
        Files.createSymbolicLink(linkedModel, inputs.model)
        val linkedRoot = temporaryFolder.root.toPath().resolve("linked-root")
        Files.createSymbolicLink(linkedRoot, inputs.root)

        assertThrows(BenchmarkContractException::class.java) {
            ParaformerPrivateInputContract.requireDirectRegularFile(
                inputs.root,
                linkedModel,
                "linked-model.int8.onnx",
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerPrivateInputContract.requireDirectRegularFile(
                linkedRoot,
                linkedRoot.resolve("model.int8.onnx"),
                "model.int8.onnx",
            )
        }
        assertTrue(Files.isDirectory(alternate))
    }

    @Test
    fun relativeRootMissingFileAndNormalizedTraversalAreRejected() {
        val inputs = createInputs()
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerPrivateInputContract.requireDirectRegularFile(
                Path.of("relative-root"),
                inputs.model,
                "model.int8.onnx",
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerPrivateInputContract.requireDirectRegularFile(
                inputs.root,
                inputs.root.resolve("missing.onnx"),
                "missing.onnx",
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerPrivateInputContract.requireDirectRegularFile(
                inputs.root,
                inputs.root.resolve("nested/../model.int8.onnx"),
                "model.int8.onnx",
            )
        }
    }

    @Test
    fun devicePrivateConfigMeasuresOnlyModelAndTokensWhileBindingRuntimeReceipt() {
        val inputs = createInputs()

        val config = OfflineParaformerModelConfig.devicePrivate(
            modelPath = inputs.model.toString(),
            tokensPath = inputs.tokens.toString(),
        )

        assertEquals(setOf("weights", "tokenizer"), config.expectations().keys)
        assertEquals(setOf("weights", "tokenizer"), config.pathsByRole().keys)
        assertEquals(
            OfflineParaformerModelConfig.RUNTIME_RECEIPT_COMMIT_SHA256,
            config.receipts.runtimeReceiptCommitSha256,
        )
        assertEquals(null, config.runtimeAarPath)
    }

    @Test
    fun smokeResultNeverContainsTranscriptOrEnablesEligibility() {
        val document = ParaformerSmokeResult.success(
            runId = "run_000000000001",
            outputSha256 = "1".repeat(64),
            stopToFinalNanos = 123L,
            cancellationPhase = ParaformerCancellationPhase.NONE,
            artifacts = emptyList(),
        ).document

        assertFalse(document.containsKey("transcript"))
        assertEquals(true, document["output_nonempty"])
        assertEquals(false, document["formal_eligible"])
        assertEquals(false, document["product_decision_eligible"])
        assertEquals(false, document["adversarial_same_uid_resistant"])
        assertEquals("required", document["external_process_observer"])
    }

    @Test
    fun failedSmokeResultRequiresBoundedEvidenceAndCannotClaimOutput() {
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerSmokeResult.failure(
                runId = "run_000000000001",
                exceptionCode = "",
                cancellationPhase = ParaformerCancellationPhase.DURING,
                artifacts = emptyList(),
            )
        }
        val failure = ParaformerSmokeResult.failure(
            runId = "run_000000000001",
            exceptionCode = "decode_error",
            cancellationPhase = ParaformerCancellationPhase.DURING,
            artifacts = emptyList(),
        ).document
        assertEquals(false, failure["output_nonempty"])
        assertEquals("decode_error", failure["exception_code"])
        assertEquals(false, failure["formal_eligible"])
    }

    @Test
    fun artifactEvidenceIsBoundedUniqueAndSerializedWithoutPaths() {
        val artifact = ParaformerSmokeArtifact(
            role = "weights",
            sizeBytes = 12L,
            sha256 = "a".repeat(64),
        )
        val document = ParaformerSmokeResult.failure(
            runId = "run_000000000001",
            exceptionCode = "decode_error",
            cancellationPhase = ParaformerCancellationPhase.NONE,
            artifacts = listOf(artifact),
        ).document
        @Suppress("UNCHECKED_CAST")
        val serialized = (document["artifacts"] as List<Map<String, Any>>).single()
        assertEquals("weights", serialized["role"])
        assertEquals(12L, serialized["size_bytes"])
        assertEquals("a".repeat(64), serialized["sha256"])
        assertFalse(serialized.containsKey("path"))

        listOf(
            { ParaformerSmokeArtifact("unknown", 1L, "a".repeat(64)) },
            { ParaformerSmokeArtifact("weights", -1L, "a".repeat(64)) },
            { ParaformerSmokeArtifact("weights", 1L, "bad") },
        ).forEach { invalid ->
            assertThrows(BenchmarkContractException::class.java) { invalid() }
        }
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerSmokeResult.failure(
                runId = "run_000000000001",
                exceptionCode = "decode_error",
                cancellationPhase = ParaformerCancellationPhase.NONE,
                artifacts = listOf(artifact, artifact),
            )
        }
    }

    @Test
    fun invalidSuccessAndRunEvidenceAreRejected() {
        listOf(
            Triple("bad", 1L, ParaformerCancellationPhase.NONE),
            Triple("a".repeat(64), -1L, ParaformerCancellationPhase.NONE),
            Triple("a".repeat(64), 1L, ParaformerCancellationPhase.AFTER),
        ).forEach { (sha256, nanos, phase) ->
            assertThrows(BenchmarkContractException::class.java) {
                ParaformerSmokeResult.success(
                    runId = "run_000000000001",
                    outputSha256 = sha256,
                    stopToFinalNanos = nanos,
                    cancellationPhase = phase,
                    artifacts = emptyList(),
                )
            }
        }
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerSmokeResult.failure(
                runId = "invalid-run",
                exceptionCode = "decode_error",
                cancellationPhase = ParaformerCancellationPhase.NONE,
                artifacts = emptyList(),
            )
        }
    }

    @Test
    fun outcomePolicyHashesSuccessfulRawOutputWithoutPublishingIt() {
        val result = ParaformerSmokeOutcomePolicy.fromDecoderResult(
            runId = "run_000000000001",
            decoderResult = DecoderResult(
                transcript = "仅用于哈希的合成结果",
                status = DecoderStatus.OK,
                errorCode = null,
            ),
            stopToFinalNanos = 456L,
            cancellationPhase = ParaformerCancellationPhase.NONE,
            artifacts = emptyList(),
        ).document

        assertEquals("ok", result["status"])
        assertEquals(true, result["output_nonempty"])
        assertFalse(result.values.contains("仅用于哈希的合成结果"))
        assertEquals(64, (result["output_sha256"] as String).length)
    }

    @Test
    fun outcomePolicyDiscardsOutputForEveryCancellationAndDecodeError() {
        ParaformerCancellationPhase.entries
            .filterNot { it == ParaformerCancellationPhase.NONE }
            .forEach { phase ->
                val result = ParaformerSmokeOutcomePolicy.fromDecoderResult(
                    runId = "run_000000000001",
                    decoderResult = DecoderResult(
                        transcript = "must-not-escape",
                        status = DecoderStatus.OK,
                        errorCode = null,
                    ),
                    stopToFinalNanos = 1L,
                    cancellationPhase = phase,
                    artifacts = emptyList(),
                ).document
                assertEquals(false, result["output_nonempty"])
                assertEquals("cancelled_${phase.wireValue}", result["exception_code"])
                assertFalse(result.containsKey("output_sha256"))
            }

        val decodeError = ParaformerSmokeOutcomePolicy.fromDecoderResult(
            runId = "run_000000000001",
            decoderResult = DecoderResult(
                transcript = "",
                status = DecoderStatus.ERROR,
                errorCode = "decode_error",
            ),
            stopToFinalNanos = 1L,
            cancellationPhase = ParaformerCancellationPhase.NONE,
            artifacts = emptyList(),
        ).document
        assertEquals("decode_error", decodeError["exception_code"])
        assertEquals(false, decodeError["output_nonempty"])
    }

    private fun createInputs(): Inputs {
        val root = temporaryFolder.newFolder("private-inputs-${System.nanoTime()}")
            .toPath()
        val model = Files.write(root.resolve("model.int8.onnx"), byteArrayOf(1))
        val tokens = Files.write(root.resolve("tokens.txt"), byteArrayOf(2))
        val pcm = Files.write(root.resolve("synthetic-smoke.wav"), byteArrayOf(3))
        return Inputs(root, model, tokens, pcm)
    }

    private fun validEntries(inputs: Inputs): List<Pair<String, String>> = listOf(
        "mode" to "paraformer-smoke",
        "run_id" to "run_000000000001",
        "dataset_id" to "dataset_000000000001",
        "model_alias" to "M001",
        "model_path" to inputs.model.toString(),
        "tokens_path" to inputs.tokens.toString(),
        "pcm_path" to inputs.pcm.toString(),
        "cancel_phase" to "none",
    )

    private data class Inputs(
        val root: Path,
        val model: Path,
        val tokens: Path,
        val pcm: Path,
    )
}
