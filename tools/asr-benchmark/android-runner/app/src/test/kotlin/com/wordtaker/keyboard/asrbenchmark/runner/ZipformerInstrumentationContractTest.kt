package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipformerInstrumentationContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exactCommandAndFiveDirectFilesAreBound() {
        val inputs = createInputs()

        val command = ZipformerInstrumentationCommand.parse(validEntries(inputs))
        val validated = ZipformerPrivateInputContract.validate(inputs.root, command)

        assertEquals("run_abcdef123456", command.runId)
        assertEquals("dataset_abcdef123456", command.datasetId)
        assertEquals("M000", command.modelAlias)
        assertEquals(ParaformerCancellationPhase.NONE, command.cancellationPhase)
        assertEquals(inputs.encoder, validated.encoder)
        assertEquals(inputs.decoder, validated.decoder)
        assertEquals(inputs.joiner, validated.joiner)
        assertEquals(inputs.tokens, validated.tokens)
        assertEquals(inputs.pcm, validated.pcm)
    }

    @Test
    fun missingDuplicateUnexpectedAndWrongModeAreRejected() {
        val entries = validEntries(createInputs())
        listOf(
            entries.dropLast(1),
            entries + entries.first(),
            entries + ("host_hash" to "0".repeat(64)),
            entries.map {
                if (it.first == "mode") it.first to "paraformer-smoke" else it
            },
        ).forEach { invalid ->
            assertThrows(BenchmarkContractException::class.java) {
                ZipformerInstrumentationCommand.parse(invalid)
            }
        }
    }

    @Test
    fun identityCancellationAndPathValuesAreValidated() {
        val entries = validEntries(createInputs())
        listOf(
            "run_id" to "run_bad",
            "dataset_id" to "dataset_bad",
            "model_alias" to "baseline",
            "cancel_phase" to "sometimes",
            "encoder_path" to "bad\u0000path",
        ).forEach { (key, value) ->
            assertThrows(BenchmarkContractException::class.java) {
                ZipformerInstrumentationCommand.parse(
                    entries.map { if (it.first == key) key to value else it },
                )
            }
        }
    }

    @Test
    fun escapedWrongBasenameAndSymbolicLinkInputsAreRejected() {
        val inputs = createInputs()
        val linkedEncoder = inputs.root.resolve("linked-encoder.int8.onnx")
        Files.createSymbolicLink(linkedEncoder, inputs.encoder)
        listOf(
            "encoder.int8.onnx",
            inputs.root.resolve("../encoder.int8.onnx").toString(),
            inputs.root.resolve("encoder.onnx").toString(),
            linkedEncoder.toString(),
        ).forEach { invalid ->
            val command = ZipformerInstrumentationCommand.parse(
                validEntries(inputs).map {
                    if (it.first == "encoder_path") it.first to invalid else it
                },
            )
            assertThrows(BenchmarkContractException::class.java) {
                ZipformerPrivateInputContract.validate(inputs.root, command)
            }
        }
    }

    private fun createInputs(): Inputs {
        val root = temporaryFolder.newFolder("zip-${System.nanoTime()}").toPath()
        return Inputs(
            root = root,
            encoder = Files.write(root.resolve("encoder.int8.onnx"), byteArrayOf(1)),
            decoder = Files.write(root.resolve("decoder.int8.onnx"), byteArrayOf(2)),
            joiner = Files.write(root.resolve("joiner.int8.onnx"), byteArrayOf(3)),
            tokens = Files.write(root.resolve("tokens.txt"), byteArrayOf(4)),
            pcm = Files.write(root.resolve("synthetic-smoke.wav"), byteArrayOf(5)),
        )
    }

    private fun validEntries(inputs: Inputs): List<Pair<String, String>> = listOf(
        "mode" to "zipformer-smoke",
        "run_id" to "run_abcdef123456",
        "dataset_id" to "dataset_abcdef123456",
        "model_alias" to "M000",
        "encoder_path" to inputs.encoder.toString(),
        "decoder_path" to inputs.decoder.toString(),
        "joiner_path" to inputs.joiner.toString(),
        "tokens_path" to inputs.tokens.toString(),
        "pcm_path" to inputs.pcm.toString(),
        "cancel_phase" to "none",
    )

    private data class Inputs(
        val root: Path,
        val encoder: Path,
        val decoder: Path,
        val joiner: Path,
        val tokens: Path,
        val pcm: Path,
    )
}
