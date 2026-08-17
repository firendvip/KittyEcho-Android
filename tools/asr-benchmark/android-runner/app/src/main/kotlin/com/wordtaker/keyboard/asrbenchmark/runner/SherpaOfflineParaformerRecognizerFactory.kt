package com.wordtaker.keyboard.asrbenchmark.runner

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig as SherpaOfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException

/**
 * Thin JNI boundary for sherpa-onnx 1.13.3.
 *
 * Host unit tests cover the pure configuration mapping without loading JNI.
 * Native construction, decode, and release remain device-runtime gates.
 */
internal object SherpaOfflineParaformerRecognizerFactory :
    ParaformerRecognizerFactory {
    internal fun buildRecognizerConfig(
        config: OfflineParaformerModelConfig,
    ): OfflineRecognizerConfig {
        val modelConfig = OfflineModelConfig().apply {
            paraformer = SherpaOfflineParaformerModelConfig(
                model = config.modelPath,
            )
            tokens = config.tokensPath
            numThreads = config.numThreads
            debug = false
            provider = config.provider
            modelType = config.modelType
            modelingUnit = ""
            bpeVocab = ""
        }
        return OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(
                sampleRate = config.sampleRateHz,
                featureDim = config.featureDim,
                dither = config.dither,
            )
            this.modelConfig = modelConfig
            decodingMethod = config.decodingMethod
            maxActivePaths = 4
            hotwordsFile = ""
            hotwordsScore = 1.5f
            ruleFsts = ""
            ruleFars = ""
            blankPenalty = 0.0f
        }
    }

    override fun create(
        config: OfflineParaformerModelConfig,
    ): ParaformerRecognizerHandle = SherpaRecognizerHandle(
        OfflineRecognizer(config = buildRecognizerConfig(config)),
    )
}

private class SherpaRecognizerHandle(
    private val recognizer: OfflineRecognizer,
) : ParaformerRecognizerHandle {
    private var closed = false

    override fun createStream(): ParaformerStreamHandle {
        check(!closed) { "recognizer is closed" }
        return SherpaStreamHandle(recognizer.createStream())
    }

    override fun decode(stream: ParaformerStreamHandle) {
        recognizer.decode(stream.requireSherpaStream())
    }

    override fun resultText(stream: ParaformerStreamHandle): String =
        recognizer.getResult(stream.requireSherpaStream()).text

    override fun close() {
        if (!closed) {
            closed = true
            recognizer.release()
        }
    }
}

private class SherpaStreamHandle(
    internal val stream: OfflineStream,
) : ParaformerStreamHandle {
    private var closed = false

    override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
        check(!closed) { "stream is closed" }
        stream.acceptWaveform(samples, sampleRateHz)
    }

    override fun close() {
        if (!closed) {
            closed = true
            stream.release()
        }
    }
}

private fun ParaformerStreamHandle.requireSherpaStream(): OfflineStream =
    (this as? SherpaStreamHandle)?.stream
        ?: throw BenchmarkContractException(
            "Paraformer native stream implementation is invalid",
        )
