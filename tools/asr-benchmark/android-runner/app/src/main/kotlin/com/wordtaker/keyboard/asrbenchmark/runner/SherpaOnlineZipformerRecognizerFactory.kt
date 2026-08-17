package com.wordtaker.keyboard.asrbenchmark.runner

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException

internal object SherpaOnlineZipformerRecognizerFactory :
    ZipformerRecognizerFactory {
    internal fun buildRecognizerConfig(
        config: ZipformerModelConfig,
    ): OnlineRecognizerConfig {
        val modelConfig = OnlineModelConfig().apply {
            transducer = OnlineTransducerModelConfig(
                encoder = config.encoderPath,
                decoder = config.decoderPath,
                joiner = config.joinerPath,
            )
            tokens = config.tokensPath
            numThreads = config.numThreads
            debug = false
            provider = config.provider
            modelType = config.modelType
            modelingUnit = ""
            bpeVocab = ""
        }
        return OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig(
                sampleRate = config.sampleRateHz,
                featureDim = config.featureDim,
                dither = config.dither,
            )
            this.modelConfig = modelConfig
            enableEndpoint = false
            decodingMethod = config.decodingMethod
            maxActivePaths = 4
            hotwordsFile = ""
            hotwordsScore = 1.5f
            ruleFsts = ""
            ruleFars = ""
            blankPenalty = 0.0f
        }
    }

    override fun create(config: ZipformerModelConfig): ZipformerRecognizerHandle =
        SherpaZipformerRecognizerHandle(
            OnlineRecognizer(config = buildRecognizerConfig(config)),
        )
}

private class SherpaZipformerRecognizerHandle(
    private val recognizer: OnlineRecognizer,
) : ZipformerRecognizerHandle {
    private var closed = false

    override fun createStream(): ZipformerStreamHandle {
        check(!closed) { "recognizer is closed" }
        return SherpaZipformerStreamHandle(recognizer.createStream(""))
    }

    override fun isReady(stream: ZipformerStreamHandle): Boolean =
        recognizer.isReady(stream.requireSherpaStream())

    override fun decode(stream: ZipformerStreamHandle) {
        recognizer.decode(stream.requireSherpaStream())
    }

    override fun resultText(stream: ZipformerStreamHandle): String =
        recognizer.getResult(stream.requireSherpaStream()).text

    override fun close() {
        if (!closed) {
            closed = true
            recognizer.release()
        }
    }
}

private class SherpaZipformerStreamHandle(
    internal val stream: OnlineStream,
) : ZipformerStreamHandle {
    private var closed = false

    override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
        check(!closed) { "stream is closed" }
        stream.acceptWaveform(samples, sampleRateHz)
    }

    override fun inputFinished() {
        check(!closed) { "stream is closed" }
        stream.inputFinished()
    }

    override fun close() {
        if (!closed) {
            closed = true
            stream.release()
        }
    }
}

private fun ZipformerStreamHandle.requireSherpaStream(): OnlineStream =
    (this as? SherpaZipformerStreamHandle)?.stream
        ?: throw BenchmarkContractException(
            "Zipformer native stream implementation is invalid",
        )
