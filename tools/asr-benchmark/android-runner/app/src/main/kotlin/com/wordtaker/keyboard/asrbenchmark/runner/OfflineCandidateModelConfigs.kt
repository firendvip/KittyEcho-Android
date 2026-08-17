package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import java.io.File

class OfflineFireRedModelConfig private constructor(
    val encoderPath: String,
    val decoderPath: String,
    val tokensPath: String,
    val runtimeAarPath: String?,
) {
    val sampleRateHz: Int = 16_000
    val featureDim: Int = 80
    val dither: Float = 0.0f
    val decodingMethod: String = "greedy_search"
    val numThreads: Int = 1
    val provider: String = "cpu"
    val modelType: String = ""
    val registrySnapshotSha256: String = REGISTRY_SNAPSHOT_SHA256
    val receiptCommitSha256ByRole: Map<String, String> = buildMap {
        put("encoder", ENCODER_RECEIPT_COMMIT_SHA256)
        put("decoder", DECODER_RECEIPT_COMMIT_SHA256)
        put("tokenizer", TOKENS_RECEIPT_COMMIT_SHA256)
        put("runtime", RUNTIME_RECEIPT_COMMIT_SHA256)
    }

    init {
        requireFrozenFilePath(encoderPath, "encoder.int8.onnx", "FireRed")
        requireFrozenFilePath(decoderPath, "decoder.int8.onnx", "FireRed")
        requireFrozenFilePath(tokensPath, "tokens.txt", "FireRed")
        runtimeAarPath?.let {
            requireFrozenFilePath(it, "sherpa-onnx-1.13.3.aar", "FireRed")
        }
        requireDistinctPaths(
            listOfNotNull(encoderPath, decoderPath, tokensPath, runtimeAarPath),
            "FireRed",
        )
    }

    internal fun expectations(): Map<String, ArtifactExpectation> = buildMap {
        put("encoder", ArtifactExpectation(ENCODER_BYTES, ENCODER_SHA256))
        put("decoder", ArtifactExpectation(DECODER_BYTES, DECODER_SHA256))
        put("tokenizer", ArtifactExpectation(TOKENS_BYTES, TOKENS_SHA256))
        if (runtimeAarPath != null) {
            put("runtime", ArtifactExpectation(RUNTIME_BYTES, RUNTIME_SHA256))
        }
    }

    internal fun pathsByRole(): Map<String, String> = buildMap {
        put("encoder", encoderPath)
        put("decoder", decoderPath)
        put("tokenizer", tokensPath)
        runtimeAarPath?.let { put("runtime", it) }
    }

    companion object {
        const val ENCODER_BYTES = 817_286_833L
        const val ENCODER_SHA256 =
            "54048d66b6e8f3c80ea7ce95efe794587b0fd81d7271651d0decd3803852ae82"
        const val DECODER_BYTES = 417_291_928L
        const val DECODER_SHA256 =
            "b840ce7196ae4a14d05ae84bbf56082b6b61ccec5610fda907dddbcea37354ff"
        const val TOKENS_BYTES = 79_172L
        const val TOKENS_SHA256 =
            "1bc613de2112d257e61a349c3e72d1b1a9cf19c33d3ca954197ad2171e5ea07b"
        const val RUNTIME_BYTES = 57_044_841L
        const val RUNTIME_SHA256 =
            "243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6"
        const val ENCODER_RECEIPT_COMMIT_SHA256 =
            "b0fb4f7d00f9548503c4bd2e03f4e5772b93aba9c9210d71fd2a5e468ebbf560"
        const val DECODER_RECEIPT_COMMIT_SHA256 =
            "6eb573e14309b4a0f45abc17cc6b47b8e005f5a8224ed9714aed63659385d187"
        const val TOKENS_RECEIPT_COMMIT_SHA256 =
            "d14beb110cdc3437334ef8145a6da0194a6a7a39d19b80674e59609b80d208a7"
        const val RUNTIME_RECEIPT_COMMIT_SHA256 =
            "6e8f54fc82a8c38be80d859236cfb5f0f8601d86d3aff8b7f133af5eed61982b"
        const val REGISTRY_SNAPSHOT_SHA256 =
            "10725b3c912d6668cb9b310049ad87bf09df275fa8a0e8ced7a94c50a7ddce75"

        fun canonical(
            encoderPath: String,
            decoderPath: String,
            tokensPath: String,
            runtimeAarPath: String,
        ): OfflineFireRedModelConfig = OfflineFireRedModelConfig(
            encoderPath,
            decoderPath,
            tokensPath,
            runtimeAarPath,
        )

        fun devicePrivate(
            encoderPath: String,
            decoderPath: String,
            tokensPath: String,
        ): OfflineFireRedModelConfig = OfflineFireRedModelConfig(
            encoderPath,
            decoderPath,
            tokensPath,
            runtimeAarPath = null,
        )
    }
}

class OfflineFunAsrNanoModelConfig private constructor(
    val embeddingPath: String,
    val encoderAdaptorPath: String,
    val llmPath: String,
    val tokenizerDirectoryPath: String,
    val runtimeAarPath: String?,
) {
    val sampleRateHz: Int = 16_000
    val featureDim: Int = 80
    val dither: Float = 0.0f
    val decodingMethod: String = "greedy_search"
    val numThreads: Int = 2
    val provider: String = "cpu"
    val modelType: String = ""
    val systemPrompt: String = "You are a helpful assistant."
    val userPrompt: String = "语音转写："
    val maxNewTokens: Int = 512
    val temperature: Float = 0.000001f
    val topP: Float = 0.8f
    val seed: Int = 42
    val language: String = ""
    val itn: Boolean = true
    val hotwords: String = ""
    val registrySnapshotSha256: String = REGISTRY_SNAPSHOT_SHA256
    val receiptCommitSha256ByRole: Map<String, String> = buildMap {
        put("embedding", EMBEDDING_RECEIPT_COMMIT_SHA256)
        put("encoder_adaptor", ENCODER_ADAPTOR_RECEIPT_COMMIT_SHA256)
        put("llm", LLM_RECEIPT_COMMIT_SHA256)
        put("tokenizer_json", TOKENIZER_RECEIPT_COMMIT_SHA256)
        put("vocab", VOCAB_RECEIPT_COMMIT_SHA256)
        put("merges", MERGES_RECEIPT_COMMIT_SHA256)
        put("runtime", RUNTIME_RECEIPT_COMMIT_SHA256)
    }

    init {
        requireFrozenFilePath(embeddingPath, "embedding.int8.onnx", "FunASR Nano")
        requireFrozenFilePath(
            encoderAdaptorPath,
            "encoder_adaptor.int8.onnx",
            "FunASR Nano",
        )
        requireFrozenFilePath(llmPath, "llm.int8.onnx", "FunASR Nano")
        requireFrozenDirectoryPath(
            tokenizerDirectoryPath,
            "Qwen3-0.6B",
            "FunASR Nano",
        )
        runtimeAarPath?.let {
            requireFrozenFilePath(it, "sherpa-onnx-1.13.3.aar", "FunASR Nano")
        }
        requireDistinctPaths(
            listOfNotNull(
                embeddingPath,
                encoderAdaptorPath,
                llmPath,
                tokenizerDirectoryPath,
                runtimeAarPath,
            ),
            "FunASR Nano",
        )
    }

    internal fun expectations(): Map<String, ArtifactExpectation> = buildMap {
        put("embedding", ArtifactExpectation(EMBEDDING_BYTES, EMBEDDING_SHA256))
        put(
            "encoder_adaptor",
            ArtifactExpectation(ENCODER_ADAPTOR_BYTES, ENCODER_ADAPTOR_SHA256),
        )
        put("llm", ArtifactExpectation(LLM_BYTES, LLM_SHA256))
        put(
            "tokenizer_json",
            ArtifactExpectation(TOKENIZER_BYTES, TOKENIZER_SHA256),
        )
        put("vocab", ArtifactExpectation(VOCAB_BYTES, VOCAB_SHA256))
        put("merges", ArtifactExpectation(MERGES_BYTES, MERGES_SHA256))
        if (runtimeAarPath != null) {
            put("runtime", ArtifactExpectation(RUNTIME_BYTES, RUNTIME_SHA256))
        }
    }

    internal fun pathsByRole(): Map<String, String> = buildMap {
        put("embedding", embeddingPath)
        put("encoder_adaptor", encoderAdaptorPath)
        put("llm", llmPath)
        put("tokenizer_json", tokenizerFile("tokenizer.json"))
        put("vocab", tokenizerFile("vocab.json"))
        put("merges", tokenizerFile("merges.txt"))
        runtimeAarPath?.let { put("runtime", it) }
    }

    private fun tokenizerFile(filename: String): String =
        File(tokenizerDirectoryPath, filename).absolutePath

    companion object {
        const val EMBEDDING_BYTES = 155_584_380L
        const val EMBEDDING_SHA256 =
            "95e61cd0c9c3b9543339a4cf973c95c116815e745ccc1e0285cbd81f76d18644"
        const val ENCODER_ADAPTOR_BYTES = 237_792_748L
        const val ENCODER_ADAPTOR_SHA256 =
            "f36dea2e30fbc33b5db1d7a7265cc976c5e5586c77b042d5adb1ad27c72db422"
        const val LLM_BYTES = 600_356_593L
        const val LLM_SHA256 =
            "dfbf9aa3be41bccc257587f151e15c63fbe1b549f2b517f5ccd5bdce3bf4322a"
        const val TOKENIZER_BYTES = 11_422_654L
        const val TOKENIZER_SHA256 =
            "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4"
        const val VOCAB_BYTES = 2_776_833L
        const val VOCAB_SHA256 =
            "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"
        const val MERGES_BYTES = 1_671_853L
        const val MERGES_SHA256 =
            "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"
        const val RUNTIME_BYTES = 57_044_841L
        const val RUNTIME_SHA256 =
            "243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6"
        const val EMBEDDING_RECEIPT_COMMIT_SHA256 =
            "cc8b63d3931e6b5f999688f227e17c893eca97af5aa92a0ba744fbba44fb0fb6"
        const val ENCODER_ADAPTOR_RECEIPT_COMMIT_SHA256 =
            "5ccbde4bac3cc871b318e583762b6622d2578fd004530bd083c49b82619953ca"
        const val LLM_RECEIPT_COMMIT_SHA256 =
            "294233e950a57f65a1bac745fb7406875cec6842d412e3276c6461a52c252261"
        const val TOKENIZER_RECEIPT_COMMIT_SHA256 =
            "ffb0ede9078650bfe2aff30b6326481d3ae6362911a41f3db6d67c6b1753e2d8"
        const val VOCAB_RECEIPT_COMMIT_SHA256 =
            "f8b3cd974d0318aee9fc0948471f5aa010ad01acbbedab5dbe50cc01ad1632d8"
        const val MERGES_RECEIPT_COMMIT_SHA256 =
            "ec9b8ffb857c49ea3dbd376cf668d2518161eff925135b4564624a8817b59ea6"
        const val RUNTIME_RECEIPT_COMMIT_SHA256 =
            "b7286380506c87ee15214684dc067a366a23987ca79854e270f44013c5d0bba9"
        const val REGISTRY_SNAPSHOT_SHA256 =
            "10725b3c912d6668cb9b310049ad87bf09df275fa8a0e8ced7a94c50a7ddce75"

        fun canonical(
            embeddingPath: String,
            encoderAdaptorPath: String,
            llmPath: String,
            tokenizerDirectoryPath: String,
            runtimeAarPath: String,
        ): OfflineFunAsrNanoModelConfig = OfflineFunAsrNanoModelConfig(
            embeddingPath,
            encoderAdaptorPath,
            llmPath,
            tokenizerDirectoryPath,
            runtimeAarPath,
        )

        fun devicePrivate(
            embeddingPath: String,
            encoderAdaptorPath: String,
            llmPath: String,
            tokenizerDirectoryPath: String,
        ): OfflineFunAsrNanoModelConfig = OfflineFunAsrNanoModelConfig(
            embeddingPath,
            encoderAdaptorPath,
            llmPath,
            tokenizerDirectoryPath,
            runtimeAarPath = null,
        )
    }
}

private fun requireFrozenFilePath(
    path: String,
    expectedFilename: String,
    modelName: String,
) {
    val file = File(path)
    if (
        path.isBlank() ||
        path.indexOf('\u0000') >= 0 ||
        !file.isAbsolute ||
        file.name != expectedFilename
    ) {
        throw BenchmarkContractException(
            "$modelName $expectedFilename path is not an absolute frozen artifact path",
        )
    }
}

private fun requireFrozenDirectoryPath(
    path: String,
    expectedDirectoryName: String,
    modelName: String,
) {
    val directory = File(path)
    if (
        path.isBlank() ||
        path.indexOf('\u0000') >= 0 ||
        !directory.isAbsolute ||
        directory.name != expectedDirectoryName
    ) {
        throw BenchmarkContractException(
            "$modelName $expectedDirectoryName path is not an absolute frozen artifact directory",
        )
    }
}

private fun requireDistinctPaths(paths: List<String>, modelName: String) {
    if (paths.toSet().size != paths.size) {
        throw BenchmarkContractException("$modelName artifact paths must be distinct")
    }
}
