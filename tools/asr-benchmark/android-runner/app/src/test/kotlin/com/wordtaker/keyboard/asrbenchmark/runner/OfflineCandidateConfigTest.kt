package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflineCandidateConfigTest {
    @Test
    fun fireRedFreezesArtifactsReceiptsAndSherpaMapping() {
        val config = OfflineFireRedModelConfig.canonical(
            encoderPath = FIRE_ENCODER,
            decoderPath = FIRE_DECODER,
            tokensPath = FIRE_TOKENS,
            runtimeAarPath = RUNTIME,
        )

        assertEquals(
            mapOf(
                "encoder" to ArtifactExpectation(
                    817_286_833L,
                    "54048d66b6e8f3c80ea7ce95efe794587b0fd81d7271651d0decd3803852ae82",
                ),
                "decoder" to ArtifactExpectation(
                    417_291_928L,
                    "b840ce7196ae4a14d05ae84bbf56082b6b61ccec5610fda907dddbcea37354ff",
                ),
                "tokenizer" to ArtifactExpectation(
                    79_172L,
                    "1bc613de2112d257e61a349c3e72d1b1a9cf19c33d3ca954197ad2171e5ea07b",
                ),
                "runtime" to ArtifactExpectation(
                    57_044_841L,
                    "243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6",
                ),
            ),
            config.expectations(),
        )
        assertEquals(
            mapOf(
                "encoder" to FIRE_ENCODER,
                "decoder" to FIRE_DECODER,
                "tokenizer" to FIRE_TOKENS,
                "runtime" to RUNTIME,
            ),
            config.pathsByRole(),
        )
        assertEquals(
            "10725b3c912d6668cb9b310049ad87bf09df275fa8a0e8ced7a94c50a7ddce75",
            config.registrySnapshotSha256,
        )
        assertEquals(
            mapOf(
                "encoder" to "b0fb4f7d00f9548503c4bd2e03f4e5772b93aba9c9210d71fd2a5e468ebbf560",
                "decoder" to "6eb573e14309b4a0f45abc17cc6b47b8e005f5a8224ed9714aed63659385d187",
                "tokenizer" to "d14beb110cdc3437334ef8145a6da0194a6a7a39d19b80674e59609b80d208a7",
                "runtime" to "6e8f54fc82a8c38be80d859236cfb5f0f8601d86d3aff8b7f133af5eed61982b",
            ),
            config.receiptCommitSha256ByRole,
        )

        val sherpa = SherpaOfflineFireRedRecognizerFactory.buildRecognizerConfig(config)
        assertEquals(FIRE_ENCODER, sherpa.modelConfig.fireRedAsr.encoder)
        assertEquals(FIRE_DECODER, sherpa.modelConfig.fireRedAsr.decoder)
        assertEquals(FIRE_TOKENS, sherpa.modelConfig.tokens)
        assertEquals(1, sherpa.modelConfig.numThreads)
        assertEquals("cpu", sherpa.modelConfig.provider)
        assertEquals("greedy_search", sherpa.decodingMethod)
    }

    @Test
    fun funAsrFreezesArtifactsReceiptsAndSherpaMapping() {
        val config = OfflineFunAsrNanoModelConfig.canonical(
            embeddingPath = NANO_EMBEDDING,
            encoderAdaptorPath = NANO_ENCODER_ADAPTOR,
            llmPath = NANO_LLM,
            tokenizerDirectoryPath = NANO_TOKENIZER_DIR,
            runtimeAarPath = RUNTIME,
        )

        assertEquals(
            setOf(
                "embedding",
                "encoder_adaptor",
                "llm",
                "tokenizer_json",
                "vocab",
                "merges",
                "runtime",
            ),
            config.expectations().keys,
        )
        assertEquals(
            ArtifactExpectation(
                1_671_853L,
                "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5",
            ),
            config.expectations().getValue("merges"),
        )
        assertEquals(
            "$NANO_TOKENIZER_DIR/tokenizer.json",
            config.pathsByRole().getValue("tokenizer_json"),
        )
        assertEquals(
            "$NANO_TOKENIZER_DIR/vocab.json",
            config.pathsByRole().getValue("vocab"),
        )
        assertEquals(
            "$NANO_TOKENIZER_DIR/merges.txt",
            config.pathsByRole().getValue("merges"),
        )
        assertEquals(
            "10725b3c912d6668cb9b310049ad87bf09df275fa8a0e8ced7a94c50a7ddce75",
            config.registrySnapshotSha256,
        )
        assertEquals(
            "cc8b63d3931e6b5f999688f227e17c893eca97af5aa92a0ba744fbba44fb0fb6",
            config.receiptCommitSha256ByRole.getValue("embedding"),
        )
        assertEquals(
            "ec9b8ffb857c49ea3dbd376cf668d2518161eff925135b4564624a8817b59ea6",
            config.receiptCommitSha256ByRole.getValue("merges"),
        )

        val sherpa = SherpaOfflineFunAsrNanoRecognizerFactory
            .buildRecognizerConfig(config)
        val nano = sherpa.modelConfig.funasrNano
        assertEquals(NANO_ENCODER_ADAPTOR, nano.encoderAdaptor)
        assertEquals(NANO_LLM, nano.llm)
        assertEquals(NANO_EMBEDDING, nano.embedding)
        assertEquals(NANO_TOKENIZER_DIR, nano.tokenizer)
        assertEquals("You are a helpful assistant.", nano.systemPrompt)
        assertEquals("语音转写：", nano.userPrompt)
        assertEquals(512, nano.maxNewTokens)
        assertEquals(0.000001f, nano.temperature)
        assertEquals(0.8f, nano.topP)
        assertEquals(42, nano.seed)
        assertEquals("", nano.language)
        assertEquals(true, nano.itn)
        assertEquals("", nano.hotwords)
        assertEquals(2, sherpa.modelConfig.numThreads)
    }

    @Test
    fun configsRejectRelativeSwappedDuplicateAndWrongTokenizerLayout() {
        assertThrows(BenchmarkContractException::class.java) {
            OfflineFireRedModelConfig.canonical(
                encoderPath = "encoder.int8.onnx",
                decoderPath = FIRE_DECODER,
                tokensPath = FIRE_TOKENS,
                runtimeAarPath = RUNTIME,
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            OfflineFireRedModelConfig.canonical(
                encoderPath = FIRE_DECODER,
                decoderPath = FIRE_ENCODER,
                tokensPath = FIRE_TOKENS,
                runtimeAarPath = RUNTIME,
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            OfflineFunAsrNanoModelConfig.canonical(
                embeddingPath = NANO_EMBEDDING,
                encoderAdaptorPath = NANO_ENCODER_ADAPTOR,
                llmPath = NANO_LLM,
                tokenizerDirectoryPath = "/private/tokenizer.json",
                runtimeAarPath = RUNTIME,
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            OfflineFunAsrNanoModelConfig.canonical(
                embeddingPath = NANO_LLM,
                encoderAdaptorPath = NANO_ENCODER_ADAPTOR,
                llmPath = NANO_LLM,
                tokenizerDirectoryPath = NANO_TOKENIZER_DIR,
                runtimeAarPath = RUNTIME,
            )
        }
    }

    @Test
    fun smokeEvidenceAcceptsEveryCandidateRoleButNothingUnknown() {
        listOf(
            "embedding",
            "encoder_adaptor",
            "llm",
            "tokenizer_json",
            "vocab",
            "merges",
        ).forEach { role ->
            AsrSmokeArtifact(role, 1L, "a".repeat(64))
        }
        assertThrows(BenchmarkContractException::class.java) {
            AsrSmokeArtifact("candidate_unknown", 1L, "a".repeat(64))
        }
    }

    private companion object {
        const val FIRE_ENCODER = "/private/encoder.int8.onnx"
        const val FIRE_DECODER = "/private/decoder.int8.onnx"
        const val FIRE_TOKENS = "/private/tokens.txt"
        const val NANO_EMBEDDING = "/private/embedding.int8.onnx"
        const val NANO_ENCODER_ADAPTOR = "/private/encoder_adaptor.int8.onnx"
        const val NANO_LLM = "/private/llm.int8.onnx"
        const val NANO_TOKENIZER_DIR = "/private/Qwen3-0.6B"
        const val RUNTIME = "/private/sherpa-onnx-1.13.3.aar"
    }
}
