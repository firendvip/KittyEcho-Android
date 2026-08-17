package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.CanonicalPcmWav
import com.wordtaker.keyboard.asrbenchmark.core.DecoderAdapter
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import com.wordtaker.keyboard.asrbenchmark.core.RunnerPreparedDecoderAdapter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class ParaformerReceiptCommitments internal constructor(
    val modelReceiptCommitSha256: String,
    val tokensReceiptCommitSha256: String,
    val runtimeReceiptCommitSha256: String,
    val registrySnapshotSha256: String,
) {
    init {
        if (
            modelReceiptCommitSha256 !=
            OfflineParaformerModelConfig.MODEL_RECEIPT_COMMIT_SHA256 ||
            tokensReceiptCommitSha256 !=
            OfflineParaformerModelConfig.TOKENS_RECEIPT_COMMIT_SHA256 ||
            runtimeReceiptCommitSha256 !=
            OfflineParaformerModelConfig.RUNTIME_RECEIPT_COMMIT_SHA256 ||
            registrySnapshotSha256 !=
            OfflineParaformerModelConfig.REGISTRY_SNAPSHOT_SHA256
        ) {
            throw BenchmarkContractException(
                "Paraformer receipt commitments differ from the frozen build inputs",
            )
        }
    }
}

class OfflineParaformerModelConfig private constructor(
    val modelPath: String,
    val tokensPath: String,
    val runtimeAarPath: String?,
    val receipts: ParaformerReceiptCommitments,
) {
    val sampleRateHz: Int = 16_000
    val featureDim: Int = 80
    val dither: Float = 0.0f
    val languageTag: String = "zh-CN"
    val decodingMethod: String = "greedy_search"
    val numThreads: Int = 4
    val provider: String = "cpu"
    val modelType: String = ""

    init {
        requireFrozenPath(modelPath, "model.int8.onnx")
        requireFrozenPath(tokensPath, "tokens.txt")
        runtimeAarPath?.let {
            requireFrozenPath(it, "sherpa-onnx-1.13.3.aar")
        }
        val declaredPaths = listOfNotNull(
            modelPath,
            tokensPath,
            runtimeAarPath,
        )
        if (declaredPaths.toSet().size != declaredPaths.size) {
            throw BenchmarkContractException(
                "Paraformer artifact paths must be distinct",
            )
        }
    }

    internal fun expectations(): Map<String, ArtifactExpectation> = buildMap {
        put("weights", ArtifactExpectation(MODEL_BYTES, MODEL_SHA256))
        put("tokenizer", ArtifactExpectation(TOKENS_BYTES, TOKENS_SHA256))
        if (runtimeAarPath != null) {
            put("runtime", ArtifactExpectation(RUNTIME_BYTES, RUNTIME_SHA256))
        }
    }

    internal fun pathsByRole(): Map<String, String> = buildMap {
        put("weights", modelPath)
        put("tokenizer", tokensPath)
        runtimeAarPath?.let { put("runtime", it) }
    }

    companion object {
        const val MODEL_BYTES = 223_385_835L
        const val MODEL_SHA256 =
            "9ada9127ca5b82320385ac12340eb8b05dee64fd45cf8cf593ec693826ec2fd7"
        const val TOKENS_BYTES = 75_756L
        const val TOKENS_SHA256 =
            "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"
        const val RUNTIME_BYTES = 57_044_841L
        const val RUNTIME_SHA256 =
            "243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6"
        const val MODEL_RECEIPT_COMMIT_SHA256 =
            "bc03d13ddb2184ab63ec3bf2a32ee8a856073ae54852774aaa68437dcc15e964"
        const val TOKENS_RECEIPT_COMMIT_SHA256 =
            "c13885a083472c35d99fa2b97dbf98de5e46ef88bdf89daf0ea3c9ceaf79326e"
        const val RUNTIME_RECEIPT_COMMIT_SHA256 =
            "18b8172f77a8bc4654804664b7a0fddfe5a42d44f36f21c12e9485e272257ba5"
        const val REGISTRY_SNAPSHOT_SHA256 =
            "ac813fa0649bbd1a554603ae4c5a5301c48b0d5839611ea59ffe32b6373db860"

        fun canonical(
            modelPath: String,
            tokensPath: String,
            runtimeAarPath: String,
        ): OfflineParaformerModelConfig = OfflineParaformerModelConfig(
            modelPath = modelPath,
            tokensPath = tokensPath,
            runtimeAarPath = runtimeAarPath,
            receipts = ParaformerReceiptCommitments(
                modelReceiptCommitSha256 = MODEL_RECEIPT_COMMIT_SHA256,
                tokensReceiptCommitSha256 = TOKENS_RECEIPT_COMMIT_SHA256,
                runtimeReceiptCommitSha256 = RUNTIME_RECEIPT_COMMIT_SHA256,
                registrySnapshotSha256 = REGISTRY_SNAPSHOT_SHA256,
            ),
        )

        /**
         * Device smoke tests load sherpa from the benchmark APK. Only the
         * repository-external model and tokenizer are staged as private files;
         * the frozen runtime receipt remains bound through [receipts].
         */
        fun devicePrivate(
            modelPath: String,
            tokensPath: String,
        ): OfflineParaformerModelConfig = OfflineParaformerModelConfig(
            modelPath = modelPath,
            tokensPath = tokensPath,
            runtimeAarPath = null,
            receipts = ParaformerReceiptCommitments(
                modelReceiptCommitSha256 = MODEL_RECEIPT_COMMIT_SHA256,
                tokensReceiptCommitSha256 = TOKENS_RECEIPT_COMMIT_SHA256,
                runtimeReceiptCommitSha256 = RUNTIME_RECEIPT_COMMIT_SHA256,
                registrySnapshotSha256 = REGISTRY_SNAPSHOT_SHA256,
            ),
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
                    "Paraformer $filename path is not an absolute frozen artifact path",
                )
            }
        }
    }
}

internal data class ArtifactExpectation(
    val sizeBytes: Long,
    val sha256: String,
)

internal data class PreparedArtifactBinding(
    val pathToken: String,
    val measured: MeasuredFile,
) {
    override fun equals(other: Any?): Boolean =
        other is PreparedArtifactBinding &&
            pathToken == other.pathToken &&
            measured.identity == other.measured.identity &&
            measured.sha256 == other.measured.sha256

    override fun hashCode(): Int {
        var result = pathToken.hashCode()
        result = 31 * result + measured.identity.hashCode()
        result = 31 * result + measured.sha256.hashCode()
        return result
    }
}

internal interface ArtifactPathBoundDecoderAdapter {
    fun requireArtifactPaths(pathsByRole: Map<String, String>)
}

internal interface CancellablePreparedDecoderAdapter :
    RunnerPreparedDecoderAdapter,
    AutoCloseable {
    fun cancel()
}

internal inline fun <T> runWithBoundAdapter(
    adapter: DecoderAdapter,
    artifactPathsByRole: Map<String, String>,
    operation: () -> T,
): T = try {
    if (adapter is ArtifactPathBoundDecoderAdapter) {
        adapter.requireArtifactPaths(artifactPathsByRole)
    }
    operation()
} finally {
    if (adapter is AutoCloseable) {
        adapter.close()
    }
}

internal fun interface ParaformerRecognizerFactory {
    fun create(config: OfflineParaformerModelConfig): ParaformerRecognizerHandle
}

internal interface ParaformerRecognizerHandle : AutoCloseable {
    fun createStream(): ParaformerStreamHandle
    fun decode(stream: ParaformerStreamHandle)
    fun resultText(stream: ParaformerStreamHandle): String?
}

internal interface ParaformerStreamHandle : AutoCloseable {
    fun acceptWaveform(samples: FloatArray, sampleRateHz: Int)
}

class ParaformerDecoderAdapter internal constructor(
    private val config: OfflineParaformerModelConfig,
    private val recognizerFactory: ParaformerRecognizerFactory =
        SherpaOfflineParaformerRecognizerFactory,
) : CancellablePreparedDecoderAdapter, ArtifactPathBoundDecoderAdapter {
    private val cancelled = AtomicBoolean(false)
    private var recognizer: ParaformerRecognizerHandle? = null
    private var preparedArtifacts: Map<String, PreparedArtifactBinding>? = null
    private var closed = false

    override fun requireArtifactPaths(pathsByRole: Map<String, String>) {
        if (pathsByRole != config.pathsByRole()) {
            throw BenchmarkContractException(
                "Paraformer artifact paths differ from the adapter configuration",
            )
        }
    }

    @Synchronized
    override fun prepare(artifactsByRole: Map<String, MeasuredFile>) {
        if (closed) {
            throw BenchmarkContractException("Paraformer adapter is closed")
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
                    "Paraformer artifact identity changed after preparation",
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
                "Paraformer recognizer initialization failed",
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

        var stream: ParaformerStreamHandle? = null
        var transcript: String? = null
        var failed = false
        try {
            stream = activeRecognizer.createStream()
            stream.acceptWaveform(samples, config.sampleRateHz)
            if (cancelled.get()) {
                failed = true
            } else {
                activeRecognizer.decode(stream)
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
                "Paraformer runner artifacts are incomplete or contain extra roles",
            )
        }
        return expectations.mapValues { (role, expected) ->
            val measured = requireNotNull(artifactsByRole[role])
            if (
                measured.identity.sizeBytes != expected.sizeBytes ||
                measured.sha256 != expected.sha256
            ) {
                throw BenchmarkContractException(
                    "Paraformer $role artifact differs from its frozen receipt",
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
}

internal object Pcm16Waveform {
    fun samples(wav: ByteArray): FloatArray {
        val facts = CanonicalPcmWav.inspect(wav)
        if (facts.payloadBytes == 0L) {
            return FloatArray(0)
        }
        var offset = 12
        while (offset + 8 <= wav.size) {
            val chunkSize = littleEndianInt(wav, offset + 4)
            val contentStart = offset + 8
            if (
                wav.copyOfRange(offset, offset + 4)
                    .toString(Charsets.US_ASCII) == "data"
            ) {
                val payload = ByteBuffer
                    .wrap(wav, contentStart, chunkSize)
                    .order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(chunkSize / 2) {
                    payload.short.toFloat() / 32768.0f
                }
            }
            offset = contentStart + chunkSize + (chunkSize and 1)
        }
        throw BenchmarkContractException("canonical WAV data payload is unavailable")
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
