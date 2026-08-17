package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ZipformerModelConfig private constructor(
    val encoderPath: String,
    val decoderPath: String,
    val joinerPath: String,
    val tokensPath: String,
) {
    val sampleRateHz: Int = 16_000
    val featureDim: Int = 80
    val dither: Float = 0.0f
    val decodingMethod: String = "greedy_search"
    val numThreads: Int = 4
    val provider: String = "cpu"
    val modelType: String = ""
    val runtimeReceiptCommitSha256: String =
        OfflineParaformerModelConfig.RUNTIME_RECEIPT_COMMIT_SHA256
    val registrySnapshotSha256: String =
        OfflineParaformerModelConfig.REGISTRY_SNAPSHOT_SHA256

    init {
        requireFrozenPath(encoderPath, "encoder.int8.onnx")
        requireFrozenPath(decoderPath, "decoder.int8.onnx")
        requireFrozenPath(joinerPath, "joiner.int8.onnx")
        requireFrozenPath(tokensPath, "tokens.txt")
    }

    internal fun expectations(): Map<String, ArtifactExpectation> = mapOf(
        "encoder" to ArtifactExpectation(ENCODER_BYTES, ENCODER_SHA256),
        "decoder" to ArtifactExpectation(DECODER_BYTES, DECODER_SHA256),
        "joiner" to ArtifactExpectation(JOINER_BYTES, JOINER_SHA256),
        "tokenizer" to ArtifactExpectation(TOKENS_BYTES, TOKENS_SHA256),
    )

    internal fun pathsByRole(): Map<String, String> = mapOf(
        "encoder" to encoderPath,
        "decoder" to decoderPath,
        "joiner" to joinerPath,
        "tokenizer" to tokensPath,
    )

    companion object {
        const val ENCODER_BYTES = 70_109_350L
        const val ENCODER_SHA256 =
            "1870fad66d2d7b0d9ea8045859ddd32621d505d363b3242a9de13183809751cb"
        const val DECODER_BYTES = 1_308_688L
        const val DECODER_SHA256 =
            "1ec2099d6adfa149c9a2d7ce84bd14d471e277d1a88ef898dc550ff8bec86342"
        const val JOINER_BYTES = 1_033_416L
        const val JOINER_SHA256 =
            "7c00afc5450290d0e0df2bda461a20c6891110b9f111291396ed57559db8b4e9"
        const val TOKENS_BYTES = 18_626L
        const val TOKENS_SHA256 =
            "6722bd1585f46f84456b29c3550a343a3cc375b971645773c02ed8e0b4e2405c"

        fun devicePrivate(
            encoderPath: String,
            decoderPath: String,
            joinerPath: String,
            tokensPath: String,
        ): ZipformerModelConfig = ZipformerModelConfig(
            encoderPath = encoderPath,
            decoderPath = decoderPath,
            joinerPath = joinerPath,
            tokensPath = tokensPath,
        )

        private fun requireFrozenPath(path: String, filename: String) {
            val file = File(path)
            if (
                path.isBlank() ||
                path.indexOf('\u0000') >= 0 ||
                !file.isAbsolute ||
                file.name != filename
            ) {
                throw BenchmarkContractException(
                    "Zipformer $filename path is not one absolute frozen path",
                )
            }
        }
    }
}

internal fun interface ZipformerRecognizerFactory {
    fun create(config: ZipformerModelConfig): ZipformerRecognizerHandle
}

internal interface ZipformerRecognizerHandle : AutoCloseable {
    fun createStream(): ZipformerStreamHandle
    fun isReady(stream: ZipformerStreamHandle): Boolean
    fun decode(stream: ZipformerStreamHandle)
    fun resultText(stream: ZipformerStreamHandle): String?
}

internal interface ZipformerStreamHandle : AutoCloseable {
    fun acceptWaveform(samples: FloatArray, sampleRateHz: Int)
    fun inputFinished()
}

class ZipformerDecoderAdapter internal constructor(
    private val config: ZipformerModelConfig,
    private val recognizerFactory: ZipformerRecognizerFactory =
        SherpaOnlineZipformerRecognizerFactory,
) : CancellablePreparedDecoderAdapter, ArtifactPathBoundDecoderAdapter {
    private val cancelled = AtomicBoolean(false)
    private var recognizer: ZipformerRecognizerHandle? = null
    private var preparedArtifacts: Map<String, PreparedArtifactBinding>? = null
    private var closed = false

    override fun requireArtifactPaths(pathsByRole: Map<String, String>) {
        if (pathsByRole != config.pathsByRole()) {
            throw BenchmarkContractException(
                "Zipformer artifact paths differ from the adapter configuration",
            )
        }
    }

    @Synchronized
    override fun prepare(artifactsByRole: Map<String, MeasuredFile>) {
        if (closed) {
            throw BenchmarkContractException("Zipformer adapter is closed")
        }
        if (cancelled.get()) {
            return
        }
        val binding = validateArtifacts(artifactsByRole)
        val previous = preparedArtifacts
        if (previous != null) {
            if (previous != binding) {
                releaseRecognizer()
                preparedArtifacts = null
                throw BenchmarkContractException(
                    "Zipformer artifact identity changed after preparation",
                )
            }
            return
        }
        val built = try {
            recognizerFactory.create(config)
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (_: Exception) {
            throw BenchmarkContractException(
                "Zipformer recognizer initialization failed",
            )
        }
        recognizer = built
        preparedArtifacts = binding
    }

    @Synchronized
    override fun decode(canonicalPcmWav: ByteArray): DecoderResult {
        if (closed || cancelled.get()) {
            return decoderError()
        }
        val activeRecognizer = recognizer ?: return decoderError()
        val samples = try {
            Pcm16Waveform.samples(canonicalPcmWav)
        } catch (_: Exception) {
            return decoderError()
        }
        if (samples.isEmpty()) {
            return decoderError()
        }

        var stream: ZipformerStreamHandle? = null
        var transcript: String? = null
        var failed = false
        try {
            stream = activeRecognizer.createStream()
            stream.acceptWaveform(samples, config.sampleRateHz)
            stream.inputFinished()
            var decodeSteps = 0
            while (!cancelled.get() && activeRecognizer.isReady(stream)) {
                if (decodeSteps >= MAX_DECODE_STEPS) {
                    failed = true
                    break
                }
                activeRecognizer.decode(stream)
                decodeSteps += 1
            }
            if (cancelled.get()) {
                failed = true
            } else if (!failed) {
                transcript = activeRecognizer.resultText(stream)
            }
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            try {
                stream?.close()
            } catch (_: Exception) {
                failed = true
            }
        }
        if (
            failed ||
            cancelled.get() ||
            transcript == null ||
            !validRawTranscript(transcript)
        ) {
            return decoderError()
        }
        return DecoderResult(
            transcript = transcript,
            status = DecoderStatus.OK,
            errorCode = null,
        )
    }

    override fun cancel() {
        cancelled.set(true)
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        releaseRecognizer()
        preparedArtifacts = null
    }

    private fun validateArtifacts(
        artifactsByRole: Map<String, MeasuredFile>,
    ): Map<String, PreparedArtifactBinding> {
        val expectations = config.expectations()
        if (artifactsByRole.keys != expectations.keys) {
            throw BenchmarkContractException(
                "Zipformer runner artifacts are incomplete or contain extra roles",
            )
        }
        return expectations.mapValues { (role, expected) ->
            val measured = requireNotNull(artifactsByRole[role])
            if (
                measured.identity.sizeBytes != expected.sizeBytes ||
                measured.sha256 != expected.sha256
            ) {
                throw BenchmarkContractException(
                    "Zipformer $role artifact differs from its frozen registry entry",
                )
            }
            PreparedArtifactBinding(
                pathToken = measured.pathToken,
                measured = measured,
            )
        }
    }

    private fun releaseRecognizer() {
        val current = recognizer
        recognizer = null
        try {
            current?.close()
        } catch (_: Exception) {
            // The adapter remains unusable after release failure.
        }
    }

    private fun validRawTranscript(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= 100_000 &&
            value.none { it.code < 0x20 || it.code == 0x7f }

    private fun decoderError(): DecoderResult = DecoderResult(
        transcript = "",
        status = DecoderStatus.ERROR,
        errorCode = "decode_error",
    )

    companion object {
        private const val MAX_DECODE_STEPS = 100_000
    }
}
