package com.wordtaker.keyboard.asrbenchmark.runner

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig as SherpaFireRedModelConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig as SherpaFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException

internal interface OfflineCandidateRecognizerHandle : AutoCloseable {
    fun createStream(): OfflineCandidateStreamHandle
    fun decode(stream: OfflineCandidateStreamHandle)
    fun resultText(stream: OfflineCandidateStreamHandle): String?
}

internal interface OfflineCandidateStreamHandle : AutoCloseable {
    fun acceptWaveform(samples: FloatArray, sampleRateHz: Int)
}

internal object SherpaOfflineFireRedRecognizerFactory {
    internal fun buildRecognizerConfig(
        config: OfflineFireRedModelConfig,
    ): OfflineRecognizerConfig = recognizerConfig(
        sampleRateHz = config.sampleRateHz,
        featureDim = config.featureDim,
        dither = config.dither,
        decodingMethod = config.decodingMethod,
        modelConfig = OfflineModelConfig().apply {
            fireRedAsr = SherpaFireRedModelConfig(
                encoder = config.encoderPath,
                decoder = config.decoderPath,
            )
            tokens = config.tokensPath
            numThreads = config.numThreads
            debug = false
            provider = config.provider
            modelType = config.modelType
            modelingUnit = ""
            bpeVocab = ""
        },
    )

    internal fun create(
        config: OfflineFireRedModelConfig,
    ): OfflineCandidateRecognizerHandle = SherpaOfflineCandidateHandle(
        OfflineRecognizer(config = buildRecognizerConfig(config)),
    )
}

internal object SherpaOfflineFunAsrNanoRecognizerFactory {
    internal fun buildRecognizerConfig(
        config: OfflineFunAsrNanoModelConfig,
    ): OfflineRecognizerConfig = recognizerConfig(
        sampleRateHz = config.sampleRateHz,
        featureDim = config.featureDim,
        dither = config.dither,
        decodingMethod = config.decodingMethod,
        modelConfig = OfflineModelConfig().apply {
            funasrNano = SherpaFunAsrNanoModelConfig(
                encoderAdaptor = config.encoderAdaptorPath,
                llm = config.llmPath,
                embedding = config.embeddingPath,
                tokenizer = config.tokenizerDirectoryPath,
                systemPrompt = config.systemPrompt,
                userPrompt = config.userPrompt,
                maxNewTokens = config.maxNewTokens,
                temperature = config.temperature,
                topP = config.topP,
                seed = config.seed,
                language = config.language,
                itn = config.itn,
                hotwords = config.hotwords,
            )
            tokens = ""
            numThreads = config.numThreads
            debug = false
            provider = config.provider
            modelType = config.modelType
            modelingUnit = ""
            bpeVocab = ""
        },
    )

    internal fun create(
        config: OfflineFunAsrNanoModelConfig,
    ): OfflineCandidateRecognizerHandle = SherpaOfflineCandidateHandle(
        OfflineRecognizer(config = buildRecognizerConfig(config)),
    )
}

private fun recognizerConfig(
    sampleRateHz: Int,
    featureDim: Int,
    dither: Float,
    decodingMethod: String,
    modelConfig: OfflineModelConfig,
): OfflineRecognizerConfig = OfflineRecognizerConfig().apply {
    featConfig = FeatureConfig(
        sampleRate = sampleRateHz,
        featureDim = featureDim,
        dither = dither,
    )
    this.modelConfig = modelConfig
    this.decodingMethod = decodingMethod
    maxActivePaths = 4
    hotwordsFile = ""
    hotwordsScore = 1.5f
    ruleFsts = ""
    ruleFars = ""
    blankPenalty = 0.0f
}

private class SherpaOfflineCandidateHandle(
    private val recognizer: OfflineRecognizer,
) : OfflineCandidateRecognizerHandle {
    private var closed = false

    override fun createStream(): OfflineCandidateStreamHandle {
        check(!closed) { "recognizer is closed" }
        return SherpaOfflineCandidateStream(recognizer.createStream())
    }

    override fun decode(stream: OfflineCandidateStreamHandle) {
        recognizer.decode(stream.requireSherpaStream())
    }

    override fun resultText(stream: OfflineCandidateStreamHandle): String =
        recognizer.getResult(stream.requireSherpaStream()).text

    override fun close() {
        if (!closed) {
            closed = true
            recognizer.release()
        }
    }
}

private class SherpaOfflineCandidateStream(
    internal val stream: OfflineStream,
) : OfflineCandidateStreamHandle {
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

private fun OfflineCandidateStreamHandle.requireSherpaStream(): OfflineStream =
    (this as? SherpaOfflineCandidateStream)?.stream
        ?: throw BenchmarkContractException(
            "offline candidate native stream implementation is invalid",
        )
