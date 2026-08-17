package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class FireRedInstrumentationCommand private constructor(
    val runId: String,
    val datasetId: String,
    val modelAlias: String,
    val encoderPath: String,
    val decoderPath: String,
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
            "tokens_path",
            "pcm_path",
            "cancel_phase",
        )

        fun parse(entries: List<Pair<String, String>>): FireRedInstrumentationCommand {
            val values = OfflineCandidateCommandParser.parse(
                entries = entries,
                requiredKeys = requiredKeys,
                expectedMode = "firered-smoke",
                modelName = "FireRed",
            )
            return FireRedInstrumentationCommand(
                runId = values.getValue("run_id"),
                datasetId = values.getValue("dataset_id"),
                modelAlias = values.getValue("model_alias"),
                encoderPath = values.getValue("encoder_path"),
                decoderPath = values.getValue("decoder_path"),
                tokensPath = values.getValue("tokens_path"),
                pcmPath = values.getValue("pcm_path"),
                cancellationPhase = ParaformerCancellationPhase.parse(
                    values.getValue("cancel_phase"),
                ),
            )
        }
    }
}

class FunAsrNanoInstrumentationCommand private constructor(
    val runId: String,
    val datasetId: String,
    val modelAlias: String,
    val embeddingPath: String,
    val encoderAdaptorPath: String,
    val llmPath: String,
    val tokenizerDirectoryPath: String,
    val pcmPath: String,
    val cancellationPhase: ParaformerCancellationPhase,
) {
    companion object {
        private val requiredKeys = setOf(
            "mode",
            "run_id",
            "dataset_id",
            "model_alias",
            "embedding_path",
            "encoder_adaptor_path",
            "llm_path",
            "tokenizer_directory_path",
            "pcm_path",
            "cancel_phase",
        )

        fun parse(
            entries: List<Pair<String, String>>,
        ): FunAsrNanoInstrumentationCommand {
            val values = OfflineCandidateCommandParser.parse(
                entries = entries,
                requiredKeys = requiredKeys,
                expectedMode = "funasr-nano-smoke",
                modelName = "FunASR Nano",
            )
            return FunAsrNanoInstrumentationCommand(
                runId = values.getValue("run_id"),
                datasetId = values.getValue("dataset_id"),
                modelAlias = values.getValue("model_alias"),
                embeddingPath = values.getValue("embedding_path"),
                encoderAdaptorPath = values.getValue("encoder_adaptor_path"),
                llmPath = values.getValue("llm_path"),
                tokenizerDirectoryPath = values.getValue(
                    "tokenizer_directory_path",
                ),
                pcmPath = values.getValue("pcm_path"),
                cancellationPhase = ParaformerCancellationPhase.parse(
                    values.getValue("cancel_phase"),
                ),
            )
        }
    }
}

private object OfflineCandidateCommandParser {
    private val runId = Regex("^run_[0-9a-f]{12}$")
    private val datasetId = Regex("^dataset_[0-9a-f]{12}$")
    private val modelAlias = Regex("^M[0-9]{3}$")

    fun parse(
        entries: List<Pair<String, String>>,
        requiredKeys: Set<String>,
        expectedMode: String,
        modelName: String,
    ): Map<String, String> {
        if (
            entries.size != requiredKeys.size ||
            entries.map { it.first }.toSet() != requiredKeys
        ) {
            throw BenchmarkContractException(
                "$modelName instrumentation arguments must be exact and unique",
            )
        }
        if (
            entries.any {
                it.second.isBlank() ||
                    it.second.length > MAX_ARGUMENT_LENGTH ||
                    it.second.indexOf('\u0000') >= 0
            }
        ) {
            throw BenchmarkContractException(
                "$modelName instrumentation argument value is invalid",
            )
        }
        val values = entries.toMap()
        if (values.getValue("mode") != expectedMode) {
            throw BenchmarkContractException(
                "$modelName instrumentation mode is invalid",
            )
        }
        if (
            !runId.matches(values.getValue("run_id")) ||
            !datasetId.matches(values.getValue("dataset_id")) ||
            !modelAlias.matches(values.getValue("model_alias"))
        ) {
            throw BenchmarkContractException(
                "$modelName instrumentation identity is invalid",
            )
        }
        return values
    }

    private const val MAX_ARGUMENT_LENGTH = 4_096
}

data class ValidatedFireRedPrivateInputs(
    val encoder: Path,
    val decoder: Path,
    val tokens: Path,
    val pcm: Path,
)

object FireRedPrivateInputContract {
    fun validate(
        trustedRoot: Path,
        command: FireRedInstrumentationCommand,
    ): ValidatedFireRedPrivateInputs {
        val root = PrivateInputLayout.requireTrustedDirectory(
            trustedRoot,
            "FireRed",
        )
        val encoder = PrivateInputLayout.requireDirectRegularFile(
            root,
            command.encoderPath,
            "encoder.int8.onnx",
            "FireRed",
        )
        val decoder = PrivateInputLayout.requireDirectRegularFile(
            root,
            command.decoderPath,
            "decoder.int8.onnx",
            "FireRed",
        )
        val tokens = PrivateInputLayout.requireDirectRegularFile(
            root,
            command.tokensPath,
            "tokens.txt",
            "FireRed",
        )
        val pcm = PrivateInputLayout.requireDirectRegularFile(
            root,
            command.pcmPath,
            "synthetic-smoke.wav",
            "FireRed",
        )
        PrivateInputLayout.requireDistinctRegularFiles(
            listOf(encoder, decoder, tokens, pcm),
            "FireRed",
        )
        return ValidatedFireRedPrivateInputs(encoder, decoder, tokens, pcm)
    }
}

data class ValidatedFunAsrNanoPrivateInputs(
    val embedding: Path,
    val encoderAdaptor: Path,
    val llm: Path,
    val tokenizerDirectory: Path,
    val tokenizer: Path,
    val vocab: Path,
    val merges: Path,
    val pcm: Path,
)

object FunAsrNanoPrivateInputContract {
    fun validate(
        trustedRoot: Path,
        command: FunAsrNanoInstrumentationCommand,
    ): ValidatedFunAsrNanoPrivateInputs {
        val root = PrivateInputLayout.requireTrustedDirectory(
            trustedRoot,
            "FunASR Nano",
        )
        val embedding = PrivateInputLayout.requireDirectRegularFile(
            root,
            command.embeddingPath,
            "embedding.int8.onnx",
            "FunASR Nano",
        )
        val encoderAdaptor = PrivateInputLayout.requireDirectRegularFile(
            root,
            command.encoderAdaptorPath,
            "encoder_adaptor.int8.onnx",
            "FunASR Nano",
        )
        val llm = PrivateInputLayout.requireDirectRegularFile(
            root,
            command.llmPath,
            "llm.int8.onnx",
            "FunASR Nano",
        )
        val pcm = PrivateInputLayout.requireDirectRegularFile(
            root,
            command.pcmPath,
            "synthetic-smoke.wav",
            "FunASR Nano",
        )
        val tokenizerDirectory = PrivateInputLayout.requireDirectDirectory(
            root,
            command.tokenizerDirectoryPath,
            TOKENIZER_DIRECTORY,
            "FunASR Nano",
        )
        PrivateInputLayout.requireExactChildren(
            tokenizerDirectory,
            TOKENIZER_FILES,
            "FunASR Nano",
        )
        val tokenizer = PrivateInputLayout.requireDirectRegularFile(
            tokenizerDirectory,
            tokenizerDirectory.resolve("tokenizer.json").toString(),
            "tokenizer.json",
            "FunASR Nano",
        )
        val vocab = PrivateInputLayout.requireDirectRegularFile(
            tokenizerDirectory,
            tokenizerDirectory.resolve("vocab.json").toString(),
            "vocab.json",
            "FunASR Nano",
        )
        val merges = PrivateInputLayout.requireDirectRegularFile(
            tokenizerDirectory,
            tokenizerDirectory.resolve("merges.txt").toString(),
            "merges.txt",
            "FunASR Nano",
        )
        PrivateInputLayout.requireDistinctRegularFiles(
            listOf(
                embedding,
                encoderAdaptor,
                llm,
                tokenizer,
                vocab,
                merges,
                pcm,
            ),
            "FunASR Nano",
        )
        return ValidatedFunAsrNanoPrivateInputs(
            embedding = embedding,
            encoderAdaptor = encoderAdaptor,
            llm = llm,
            tokenizerDirectory = tokenizerDirectory,
            tokenizer = tokenizer,
            vocab = vocab,
            merges = merges,
            pcm = pcm,
        )
    }

    private const val TOKENIZER_DIRECTORY = "Qwen3-0.6B"
    private val TOKENIZER_FILES = setOf("tokenizer.json", "vocab.json", "merges.txt")
}

private object PrivateInputLayout {
    fun requireTrustedDirectory(candidate: Path, modelName: String): Path {
        val normalized = candidate.normalize()
        if (
            !candidate.isAbsolute ||
            candidate != normalized ||
            Files.isSymbolicLink(normalized) ||
            !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw invalidLayout(modelName)
        }
        return normalized
    }

    fun requireDirectDirectory(
        trustedRoot: Path,
        value: String,
        expectedName: String,
        modelName: String,
    ): Path {
        val candidate = pathOf(value, modelName)
        val normalized = candidate.normalize()
        if (
            !candidate.isAbsolute ||
            candidate != normalized ||
            normalized.parent != trustedRoot ||
            normalized.fileName.toString() != expectedName ||
            Files.isSymbolicLink(normalized) ||
            !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw invalidLayout(modelName)
        }
        return normalized
    }

    fun requireDirectRegularFile(
        trustedRoot: Path,
        value: String,
        expectedName: String,
        modelName: String,
    ): Path {
        val candidate = pathOf(value, modelName)
        val normalized = candidate.normalize()
        if (
            !candidate.isAbsolute ||
            candidate != normalized ||
            normalized.parent != trustedRoot ||
            normalized.fileName.toString() != expectedName ||
            Files.isSymbolicLink(normalized) ||
            !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw invalidLayout(modelName)
        }
        return normalized
    }

    fun requireExactChildren(
        directory: Path,
        expectedNames: Set<String>,
        modelName: String,
    ) {
        val names = try {
            Files.newDirectoryStream(directory).use { children ->
                children.map { it.fileName.toString() }.toSet()
            }
        } catch (_: Exception) {
            throw invalidLayout(modelName)
        }
        if (names != expectedNames) {
            throw invalidLayout(modelName)
        }
    }

    fun requireDistinctRegularFiles(files: List<Path>, modelName: String) {
        try {
            files.indices.forEach { left ->
                ((left + 1) until files.size).forEach { right ->
                    if (Files.isSameFile(files[left], files[right])) {
                        throw invalidLayout(modelName)
                    }
                }
            }
        } catch (error: BenchmarkContractException) {
            throw error
        } catch (_: Exception) {
            throw invalidLayout(modelName)
        }
    }

    private fun pathOf(value: String, modelName: String): Path = try {
        File(value).toPath()
    } catch (_: Exception) {
        throw invalidLayout(modelName)
    }

    private fun invalidLayout(modelName: String) = BenchmarkContractException(
        "$modelName private input layout is invalid",
    )
}
