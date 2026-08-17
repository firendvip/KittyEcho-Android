package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import java.io.File
import java.nio.file.Path

class ZipformerInstrumentationCommand private constructor(
    val runId: String,
    val datasetId: String,
    val modelAlias: String,
    val encoderPath: String,
    val decoderPath: String,
    val joinerPath: String,
    val tokensPath: String,
    val pcmPath: String,
    val cancellationPhase: ParaformerCancellationPhase,
) {
    companion object {
        private val requiredKeys = setOf(
            "mode",
            "run_id",
            "dataset_id",
            "model_alias",
            "encoder_path",
            "decoder_path",
            "joiner_path",
            "tokens_path",
            "pcm_path",
            "cancel_phase",
        )
        private val runId = Regex("^run_[0-9a-f]{12}$")
        private val datasetId = Regex("^dataset_[0-9a-f]{12}$")
        private val modelAlias = Regex("^M[0-9]{3}$")

        fun parse(entries: List<Pair<String, String>>): ZipformerInstrumentationCommand {
            if (
                entries.size != requiredKeys.size ||
                entries.map { it.first }.toSet() != requiredKeys
            ) {
                throw BenchmarkContractException(
                    "Zipformer instrumentation arguments must be exact and unique",
                )
            }
            if (entries.any { it.second.isBlank() || it.second.length > 4_096 }) {
                throw BenchmarkContractException(
                    "Zipformer instrumentation argument value is invalid",
                )
            }
            val values = entries.toMap()
            if (values.getValue("mode") != "zipformer-smoke") {
                throw BenchmarkContractException(
                    "Zipformer instrumentation mode is invalid",
                )
            }
            val runIdentity = values.getValue("run_id")
            val datasetIdentity = values.getValue("dataset_id")
            val alias = values.getValue("model_alias")
            if (
                !runId.matches(runIdentity) ||
                !datasetId.matches(datasetIdentity) ||
                !modelAlias.matches(alias)
            ) {
                throw BenchmarkContractException(
                    "Zipformer instrumentation identity is invalid",
                )
            }
            val pathKeys = listOf(
                "encoder_path",
                "decoder_path",
                "joiner_path",
                "tokens_path",
                "pcm_path",
            )
            if (pathKeys.any { values.getValue(it).indexOf('\u0000') >= 0 }) {
                throw BenchmarkContractException(
                    "Zipformer instrumentation path is invalid",
                )
            }
            return ZipformerInstrumentationCommand(
                runId = runIdentity,
                datasetId = datasetIdentity,
                modelAlias = alias,
                encoderPath = values.getValue("encoder_path"),
                decoderPath = values.getValue("decoder_path"),
                joinerPath = values.getValue("joiner_path"),
                tokensPath = values.getValue("tokens_path"),
                pcmPath = values.getValue("pcm_path"),
                cancellationPhase = ParaformerCancellationPhase.parse(
                    values.getValue("cancel_phase"),
                ),
            )
        }
    }
}

data class ValidatedZipformerPrivateInputs(
    val encoder: Path,
    val decoder: Path,
    val joiner: Path,
    val tokens: Path,
    val pcm: Path,
)

object ZipformerPrivateInputContract {
    fun validate(
        trustedRoot: Path,
        command: ZipformerInstrumentationCommand,
    ): ValidatedZipformerPrivateInputs {
        val encoder = direct(
            trustedRoot,
            command.encoderPath,
            "encoder.int8.onnx",
        )
        val decoder = direct(
            trustedRoot,
            command.decoderPath,
            "decoder.int8.onnx",
        )
        val joiner = direct(
            trustedRoot,
            command.joinerPath,
            "joiner.int8.onnx",
        )
        val tokens = direct(trustedRoot, command.tokensPath, "tokens.txt")
        val pcm = direct(trustedRoot, command.pcmPath, "synthetic-smoke.wav")
        if (setOf(encoder, decoder, joiner, tokens, pcm).size != 5) {
            throw BenchmarkContractException(
                "Zipformer private inputs must bind distinct files",
            )
        }
        return ValidatedZipformerPrivateInputs(
            encoder = encoder,
            decoder = decoder,
            joiner = joiner,
            tokens = tokens,
            pcm = pcm,
        )
    }

    private fun direct(
        trustedRoot: Path,
        value: String,
        filename: String,
    ): Path = ParaformerPrivateInputContract.requireDirectRegularFile(
        trustedRoot = trustedRoot,
        candidate = File(value).toPath(),
        expectedFileName = filename,
    )
}
