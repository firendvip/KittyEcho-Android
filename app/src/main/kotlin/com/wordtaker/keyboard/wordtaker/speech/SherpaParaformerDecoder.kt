package com.wordtaker.keyboard.wordtaker.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/** Whole-utterance sherpa-onnx Paraformer decoder with fail-closed private model loading. */
internal class SherpaParaformerDecoder(
    private val modelStore: ParaformerPrivateModelStore,
) : PreparableWholeUtteranceDecoder, AutoCloseable {
    @Volatile
    private var recognizer: OfflineRecognizer? = null
    @Volatile
    private var failure: AsrFailureException? = null

    override fun isReady(): Boolean = recognizer != null

    override fun readinessFailure(): AsrFailureException? = failure

    override suspend fun prepare() {
        if (recognizer != null) return
        var built: OfflineRecognizer? = null
        try {
            val before = modelStore.validateAndResolve()
            built = OfflineRecognizer(config = recognizerConfig(before))
            val after = modelStore.validateAndResolve()
            if (before != after) throw AsrModelCorruptException()
            recognizer = built
            built = null
            failure = null
        } catch (error: AsrFailureException) {
            failure = error
            throw error
        } catch (error: OutOfMemoryError) {
            val typed = AsrOutOfMemoryException(error)
            failure = typed
            throw typed
        } catch (error: Throwable) {
            val typed = AsrInitializationException(error)
            failure = typed
            throw typed
        } finally {
            runCatching { built?.release() }
        }
    }

    override suspend fun decode(samples: FloatArray): AsrResult {
        if (recognizer == null) prepare()
        val active = recognizer ?: throw failure ?: AsrInitializationException()
        if (samples.isEmpty()) return result("")
        var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
        try {
            stream = active.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            active.decode(stream)
            val text = active.getResult(stream).text
            if (text.length > MAX_TRANSCRIPT_CHARS || text.any { it.code < 0x20 && it !in "\n\t" }) {
                throw AsrDecodeException()
            }
            return result(text)
        } catch (error: AsrFailureException) {
            throw error
        } catch (error: OutOfMemoryError) {
            throw AsrOutOfMemoryException(error)
        } catch (error: Throwable) {
            throw AsrDecodeException(error)
        } finally {
            runCatching { stream?.release() }
        }
    }

    override fun close() {
        val current = recognizer
        recognizer = null
        runCatching { current?.release() }
    }

    private fun recognizerConfig(files: ValidatedParaformerFiles): OfflineRecognizerConfig {
        val model = OfflineModelConfig().apply {
            paraformer = OfflineParaformerModelConfig(model = files.modelPath)
            tokens = files.tokensPath
            numThreads = 4
            debug = false
            provider = "cpu"
            modelType = ""
            modelingUnit = ""
            bpeVocab = ""
        }
        return OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80, dither = 0f)
            modelConfig = model
            decodingMethod = "greedy_search"
            maxActivePaths = 4
            hotwordsFile = ""
            hotwordsScore = 1.5f
            ruleFsts = ""
            ruleFars = ""
            blankPenalty = 0f
        }
    }

    private fun result(text: String) = AsrResult(
        text = text,
        modelId = ParaformerModelContract.MODEL_ID,
        modelRevision = ParaformerModelContract.MODEL_REVISION,
    )

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MAX_TRANSCRIPT_CHARS = 100_000
    }
}
