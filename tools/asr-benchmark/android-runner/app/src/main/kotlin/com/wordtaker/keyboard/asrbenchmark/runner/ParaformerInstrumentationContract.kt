package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.Hashing
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

enum class ParaformerCancellationPhase(val wireValue: String) {
    NONE("none"),
    BEFORE("before"),
    DURING("during"),
    AFTER("after"),
    ;

    companion object {
        fun parse(value: String): ParaformerCancellationPhase =
            entries.singleOrNull { it.wireValue == value }
                ?: throw BenchmarkContractException(
                    "Paraformer cancellation phase is invalid",
                )
    }
}

class ParaformerInstrumentationCommand private constructor(
    val runId: String,
    val datasetId: String,
    val modelAlias: String,
    val modelPath: String,
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
            "model_path",
            "tokens_path",
            "pcm_path",
            "cancel_phase",
        )
        private val runId = Regex("^run_[0-9a-f]{12}$")
        private val datasetId = Regex("^dataset_[0-9a-f]{12}$")
        private val modelAlias = Regex("^M[0-9]{3}$")

        fun parse(entries: List<Pair<String, String>>): ParaformerInstrumentationCommand {
            if (
                entries.size != requiredKeys.size ||
                entries.map { it.first }.toSet() != requiredKeys
            ) {
                throw BenchmarkContractException(
                    "Paraformer instrumentation arguments must be exact and unique",
                )
            }
            if (entries.any { it.second.isBlank() || it.second.length > 4_096 }) {
                throw BenchmarkContractException(
                    "Paraformer instrumentation argument value is invalid",
                )
            }
            val values = entries.toMap()
            if (values.getValue("mode") != "paraformer-smoke") {
                throw BenchmarkContractException(
                    "Paraformer instrumentation mode is invalid",
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
                    "Paraformer instrumentation identity is invalid",
                )
            }
            listOf("model_path", "tokens_path", "pcm_path").forEach { key ->
                if (values.getValue(key).indexOf('\u0000') >= 0) {
                    throw BenchmarkContractException(
                        "Paraformer instrumentation path is invalid",
                    )
                }
            }
            return ParaformerInstrumentationCommand(
                runId = runIdentity,
                datasetId = datasetIdentity,
                modelAlias = alias,
                modelPath = values.getValue("model_path"),
                tokensPath = values.getValue("tokens_path"),
                pcmPath = values.getValue("pcm_path"),
                cancellationPhase = ParaformerCancellationPhase.parse(
                    values.getValue("cancel_phase"),
                ),
            )
        }
    }
}

data class ValidatedParaformerPrivateInputs(
    val model: Path,
    val tokens: Path,
    val pcm: Path,
)

object ParaformerPrivateInputContract {
    fun validate(
        trustedRoot: Path,
        command: ParaformerInstrumentationCommand,
    ): ValidatedParaformerPrivateInputs {
        val model = requireDirectRegularFile(
            trustedRoot,
            File(command.modelPath).toPath(),
            "model.int8.onnx",
        )
        val tokens = requireDirectRegularFile(
            trustedRoot,
            File(command.tokensPath).toPath(),
            "tokens.txt",
        )
        val pcm = requireDirectRegularFile(
            trustedRoot,
            File(command.pcmPath).toPath(),
            "synthetic-smoke.wav",
        )
        if (setOf(model, tokens, pcm).size != 3) {
            throw BenchmarkContractException(
                "Paraformer private inputs must bind distinct files",
            )
        }
        return ValidatedParaformerPrivateInputs(model, tokens, pcm)
    }

    fun requireDirectRegularFile(
        trustedRoot: Path,
        candidate: Path,
        expectedFileName: String,
    ): Path {
        if (!trustedRoot.isAbsolute || !candidate.isAbsolute) {
            throw BenchmarkContractException(
                "Paraformer private input path must be absolute",
            )
        }
        val root = trustedRoot.normalize()
        val file = candidate.normalize()
        if (
            trustedRoot != root ||
            candidate != file ||
            Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
            file.parent != root ||
            file.fileName.toString() != expectedFileName ||
            Files.isSymbolicLink(file) ||
            !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw BenchmarkContractException(
                "Paraformer private input is not one direct regular file",
            )
        }
        return file
    }
}

data class AsrSmokeArtifact(
    val role: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        if (
            role !in setOf(
                "weights",
                "encoder",
                "decoder",
                "joiner",
                "tokenizer",
                "embedding",
                "encoder_adaptor",
                "llm",
                "tokenizer_json",
                "vocab",
                "merges",
                "pcm",
            ) ||
            sizeBytes < 0L ||
            !SHA256.matches(sha256)
        ) {
            throw BenchmarkContractException(
                "Paraformer smoke artifact evidence is invalid",
            )
        }
    }

    internal fun document(): Map<String, Any> = linkedMapOf(
        "role" to role,
        "size_bytes" to sizeBytes,
        "sha256" to sha256,
    )
}

class AsrSmokeResult private constructor(
    val document: Map<String, Any>,
) {
    companion object {
        private val runIdPattern = Regex("^[a-z][a-z0-9_]{2,63}$")
        private val exceptionCodePattern = Regex("^[a-z0-9_]{1,64}$")

        fun success(
            runId: String,
            outputSha256: String,
            stopToFinalNanos: Long,
            cancellationPhase: ParaformerCancellationPhase,
            artifacts: List<AsrSmokeArtifact>,
        ): AsrSmokeResult {
            requireRunId(runId)
            if (
                !SHA256.matches(outputSha256) ||
                stopToFinalNanos < 0L ||
                cancellationPhase != ParaformerCancellationPhase.NONE
            ) {
                throw BenchmarkContractException(
                    "Paraformer smoke success evidence is invalid",
                )
            }
            return AsrSmokeResult(
                baseDocument(runId, cancellationPhase, artifacts) + mapOf(
                    "status" to "ok",
                    "output_nonempty" to true,
                    "output_sha256" to outputSha256,
                    "stop_to_final_ns" to stopToFinalNanos,
                ),
            )
        }

        fun failure(
            runId: String,
            exceptionCode: String,
            cancellationPhase: ParaformerCancellationPhase,
            artifacts: List<AsrSmokeArtifact>,
        ): AsrSmokeResult {
            requireRunId(runId)
            if (!exceptionCodePattern.matches(exceptionCode)) {
                throw BenchmarkContractException(
                    "Paraformer smoke failure code is invalid",
                )
            }
            return AsrSmokeResult(
                baseDocument(runId, cancellationPhase, artifacts) + mapOf(
                    "status" to "error",
                    "output_nonempty" to false,
                    "exception_code" to exceptionCode,
                ),
            )
        }

        private fun baseDocument(
            runId: String,
            cancellationPhase: ParaformerCancellationPhase,
            artifacts: List<AsrSmokeArtifact>,
        ): Map<String, Any> {
            if (artifacts.map { it.role }.toSet().size != artifacts.size) {
                throw BenchmarkContractException(
                    "Paraformer smoke artifact roles must be unique",
                )
            }
            return linkedMapOf(
                "schema_version" to 1,
                "run_id" to runId,
                "cancellation_phase" to cancellationPhase.wireValue,
                "artifacts" to artifacts.map { it.document() },
                "formal_eligible" to false,
                "product_decision_eligible" to false,
                "adversarial_same_uid_resistant" to false,
                "external_process_observer" to "required",
            )
        }

        private fun requireRunId(runId: String) {
            if (!runIdPattern.matches(runId)) {
                throw BenchmarkContractException(
                    "Paraformer smoke run identity is invalid",
                )
            }
        }
    }
}

object AsrSmokeOutcomePolicy {
    fun fromDecoderResult(
        runId: String,
        decoderResult: DecoderResult,
        stopToFinalNanos: Long,
        cancellationPhase: ParaformerCancellationPhase,
        artifacts: List<AsrSmokeArtifact>,
    ): AsrSmokeResult {
        if (cancellationPhase != ParaformerCancellationPhase.NONE) {
            return AsrSmokeResult.failure(
                runId = runId,
                exceptionCode = "cancelled_${cancellationPhase.wireValue}",
                cancellationPhase = cancellationPhase,
                artifacts = artifacts,
            )
        }
        val transcript = decoderResult.transcript
        return if (
            decoderResult.status == DecoderStatus.OK &&
            decoderResult.errorCode == null &&
            transcript.isNotBlank() &&
            transcript.none { it.code < 0x20 || it.code == 0x7f }
        ) {
            AsrSmokeResult.success(
                runId = runId,
                outputSha256 = Hashing.sha256(transcript.encodeToByteArray()),
                stopToFinalNanos = stopToFinalNanos,
                cancellationPhase = cancellationPhase,
                artifacts = artifacts,
            )
        } else {
            AsrSmokeResult.failure(
                runId = runId,
                exceptionCode = decoderResult.errorCode ?: "invalid_output",
                cancellationPhase = cancellationPhase,
                artifacts = artifacts,
            )
        }
    }
}

typealias ParaformerSmokeArtifact = AsrSmokeArtifact
typealias ParaformerSmokeResult = AsrSmokeResult
typealias ParaformerSmokeOutcomePolicy = AsrSmokeOutcomePolicy

private val SHA256 = Regex("^[0-9a-f]{64}$")
