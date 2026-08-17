package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.DecoderAdapter
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.FileIdentity
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import com.wordtaker.keyboard.asrbenchmark.core.SyntheticWav
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParaformerDecoderAdapterTest {
    @Test
    fun canonicalConfigFreezesReceiptsModelAndDecoderSettings() {
        val config = canonicalConfig()

        assertEquals(16_000, config.sampleRateHz)
        assertEquals(80, config.featureDim)
        assertEquals(0.0f, config.dither)
        assertEquals("zh-CN", config.languageTag)
        assertEquals("greedy_search", config.decodingMethod)
        assertEquals(4, config.numThreads)
        assertEquals("cpu", config.provider)
        assertEquals("", config.modelType)
        assertEquals(
            "bc03d13ddb2184ab63ec3bf2a32ee8a856073ae54852774aaa68437dcc15e964",
            config.receipts.modelReceiptCommitSha256,
        )
        assertEquals(
            "c13885a083472c35d99fa2b97dbf98de5e46ef88bdf89daf0ea3c9ceaf79326e",
            config.receipts.tokensReceiptCommitSha256,
        )
        assertEquals(
            "18b8172f77a8bc4654804664b7a0fddfe5a42d44f36f21c12e9485e272257ba5",
            config.receipts.runtimeReceiptCommitSha256,
        )
        assertEquals(
            "ac813fa0649bbd1a554603ae4c5a5301c48b0d5839611ea59ffe32b6373db860",
            config.receipts.registrySnapshotSha256,
        )
    }

    @Test
    fun configRejectsMissingRelativeOrSwappedFiles() {
        assertThrows(BenchmarkContractException::class.java) {
            OfflineParaformerModelConfig.canonical(
                modelPath = "model.int8.onnx",
                tokensPath = TOKENS_PATH,
                runtimeAarPath = RUNTIME_PATH,
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            OfflineParaformerModelConfig.canonical(
                modelPath = TOKENS_PATH,
                tokensPath = MODEL_PATH,
                runtimeAarPath = RUNTIME_PATH,
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            OfflineParaformerModelConfig.canonical(
                modelPath = MODEL_PATH,
                tokensPath = "",
                runtimeAarPath = RUNTIME_PATH,
            )
        }
    }

    @Test
    fun configRejectsAnyNonCanonicalReceiptCommitment() {
        assertThrows(BenchmarkContractException::class.java) {
            ParaformerReceiptCommitments(
                modelReceiptCommitSha256 = "0".repeat(64),
                tokensReceiptCommitSha256 =
                    OfflineParaformerModelConfig.TOKENS_RECEIPT_COMMIT_SHA256,
                runtimeReceiptCommitSha256 =
                    OfflineParaformerModelConfig.RUNTIME_RECEIPT_COMMIT_SHA256,
                registrySnapshotSha256 =
                    OfflineParaformerModelConfig.REGISTRY_SNAPSHOT_SHA256,
            )
        }
    }

    @Test
    fun adapterRequiresExactArtifactPathsAndCompleteRoles() {
        val adapter = adapter()

        assertThrows(BenchmarkContractException::class.java) {
            adapter.requireArtifactPaths(
                mapOf(
                    "weights" to MODEL_PATH,
                    "runtime" to RUNTIME_PATH,
                ),
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            adapter.requireArtifactPaths(
                mapOf(
                    "weights" to "$MODEL_PATH.replaced",
                    "tokenizer" to TOKENS_PATH,
                    "runtime" to RUNTIME_PATH,
                ),
            )
        }
        adapter.requireArtifactPaths(canonicalPaths())
    }

    @Test
    fun orchestratorBoundaryBindsArtifactsAndClosesAdapterOnEveryExit() {
        val success = LifecycleAdapter()
        assertEquals(
            "done",
            runWithBoundAdapter(success, canonicalPaths()) { "done" },
        )
        assertEquals(canonicalPaths(), success.boundPaths)
        assertEquals(1, success.closeCount)

        val failure = LifecycleAdapter()
        assertThrows(IllegalStateException::class.java) {
            runWithBoundAdapter(failure, canonicalPaths()) {
                throw IllegalStateException("synthetic operation failure")
            }
        }
        assertEquals(canonicalPaths(), failure.boundPaths)
        assertEquals(1, failure.closeCount)

        val rejected = LifecycleAdapter(rejectPaths = true)
        assertThrows(BenchmarkContractException::class.java) {
            runWithBoundAdapter(rejected, canonicalPaths()) {
                "must not run"
            }
        }
        assertEquals(1, rejected.closeCount)
    }

    @Test
    fun prepareRejectsMissingWrongHashAndWrongSizeBeforeApiInitialization() {
        val factory = FakeRecognizerFactory()
        val adapter = adapter(factory)

        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(correctArtifacts() - "tokenizer")
        }
        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(
                correctArtifacts().toMutableMap().apply {
                    this["weights"] = measured(
                        role = "weights",
                        sizeBytes = OfflineParaformerModelConfig.MODEL_BYTES,
                        sha256 = "1".repeat(64),
                    )
                },
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(
                correctArtifacts().toMutableMap().apply {
                    this["runtime"] = measured(
                        role = "runtime",
                        sizeBytes = OfflineParaformerModelConfig.RUNTIME_BYTES - 1,
                        sha256 = OfflineParaformerModelConfig.RUNTIME_SHA256,
                    )
                },
            )
        }
        assertEquals(0, factory.createCount)
    }

    @Test
    fun prepareRejectsSameContentPathReplacementAndReleasesRecognizer() {
        val handle = FakeRecognizerHandle()
        val adapter = adapter(FakeRecognizerFactory(handle = handle))
        adapter.prepare(correctArtifacts())
        val replaced = correctArtifacts().toMutableMap().apply {
            this["weights"] = measured(
                role = "weights",
                sizeBytes = OfflineParaformerModelConfig.MODEL_BYTES,
                sha256 = OfflineParaformerModelConfig.MODEL_SHA256,
                inode = 99,
            )
        }

        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(replaced)
        }
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun apiInitializationFailureIsFailClosedAndRetryable() {
        val factory = FakeRecognizerFactory(
            createFailure = IllegalStateException("native init failed"),
        )
        val adapter = adapter(factory)

        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(correctArtifacts())
        }
        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(correctArtifacts())
        }
        assertEquals(2, factory.createCount)
    }

    @Test
    fun decodePassesCanonicalSamplesAndReturnsRawTranscript() {
        val handle = FakeRecognizerHandle(resultText = "  小猫Mix42  ")
        val adapter = preparedAdapter(handle)
        val wav = SyntheticWav.pcm16Mono(sampleRate = 16_000, frameCount = 160)

        val result = adapter.decode(wav)

        assertEquals(DecoderStatus.OK, result.status)
        assertEquals("  小猫Mix42  ", result.transcript)
        assertEquals(null, result.errorCode)
        assertEquals(16_000, handle.lastSampleRate)
        assertEquals(160, handle.lastSamples?.size)
        assertTrue(handle.lastSamples!!.any { it != 0.0f })
    }

    @Test
    fun pcmConversionPreservesSignedLittleEndianExtremes() {
        val handle = FakeRecognizerHandle(resultText = "原样")
        val adapter = preparedAdapter(handle)
        val wav = SyntheticWav.pcm16Mono(sampleRate = 16_000, frameCount = 2)
        wav[44] = 0
        wav[45] = 0x80.toByte()
        wav[46] = 0xff.toByte()
        wav[47] = 0x7f

        assertEquals(DecoderStatus.OK, adapter.decode(wav).status)
        assertArrayEquals(
            floatArrayOf(-1.0f, 32767.0f / 32768.0f),
            handle.lastSamples,
            0.0f,
        )
    }

    @Test
    fun emptyNullControlAndOversizedOutputsFailClosedWithoutPolishing() {
        listOf<String?>(
            null,
            "",
            "   ",
            "bad\u0000text",
            "x".repeat(100_001),
        ).forEach { output ->
            val adapter = preparedAdapter(
                FakeRecognizerHandle(resultText = output),
            )
            val result = adapter.decode(
                SyntheticWav.pcm16Mono(16_000, 8),
            )
            assertEquals(DecoderStatus.ERROR, result.status)
            assertEquals("", result.transcript)
            assertEquals("decode_error", result.errorCode)
        }
    }

    @Test
    fun malformedOrEmptyPcmFailsClosedBeforeNativeDecode() {
        val handle = FakeRecognizerHandle(resultText = "不应调用")
        val adapter = preparedAdapter(handle)
        val emptyPayload = SyntheticWav.pcm16Mono(16_000, 1).copyOf(44).apply {
            this[4] = 36
            this[40] = 0
        }

        listOf(byteArrayOf(1, 2, 3), emptyPayload).forEach { invalid ->
            val result = adapter.decode(invalid)
            assertEquals(DecoderStatus.ERROR, result.status)
            assertEquals("decode_error", result.errorCode)
        }
        assertEquals(0, handle.decodeCount)
    }

    @Test
    fun decodeExceptionReleasesStreamAndReturnsDecoderError() {
        val handle = FakeRecognizerHandle(
            decodeFailure = IllegalStateException("decode failed"),
        )
        val adapter = preparedAdapter(handle)

        val result = adapter.decode(SyntheticWav.pcm16Mono(16_000, 8))

        assertEquals(DecoderStatus.ERROR, result.status)
        assertEquals("decode_error", result.errorCode)
        assertEquals(1, handle.streamCloseCount)
    }

    @Test
    fun closeIsIdempotentAndDecodeAfterCloseFailsClosed() {
        val handle = FakeRecognizerHandle(resultText = "小猫")
        val adapter = preparedAdapter(handle)
        adapter.close()
        adapter.close()

        val result = adapter.decode(SyntheticWav.pcm16Mono(16_000, 8))

        assertEquals(DecoderStatus.ERROR, result.status)
        assertEquals("decode_error", result.errorCode)
        assertEquals(1, handle.closeCount)
        assertEquals(0, handle.decodeCount)
    }

    @Test
    fun cancellationBeforeAndDuringDecodeDiscardsOutputAndReleasesResources() {
        val beforeFactory = FakeRecognizerFactory()
        val before = adapter(beforeFactory)
        before.cancel()
        before.prepare(correctArtifacts())
        val beforeResult = before.decode(SyntheticWav.pcm16Mono(16_000, 8))
        assertEquals(DecoderStatus.ERROR, beforeResult.status)
        assertEquals(0, beforeFactory.createCount)

        lateinit var during: ParaformerDecoderAdapter
        val handle = FakeRecognizerHandle(
            resultText = "must not escape",
            onDecode = { during.cancel() },
        )
        during = preparedAdapter(handle)
        val duringResult = during.decode(SyntheticWav.pcm16Mono(16_000, 8))
        assertEquals(DecoderStatus.ERROR, duringResult.status)
        assertEquals("", duringResult.transcript)
        assertEquals(1, handle.streamCloseCount)
    }

    @Test
    fun repeatedCallsReuseRecognizerButUseAndReleaseDistinctStreams() {
        val handle = FakeRecognizerHandle(resultText = "小猫")
        val factory = FakeRecognizerFactory(handle = handle)
        val adapter = adapter(factory)
        adapter.prepare(correctArtifacts())
        val wav = SyntheticWav.pcm16Mono(16_000, 8)

        assertEquals(DecoderStatus.OK, adapter.decode(wav).status)
        assertEquals(DecoderStatus.OK, adapter.decode(wav).status)

        assertEquals(1, factory.createCount)
        assertEquals(2, handle.decodeCount)
        assertEquals(2, handle.streamCreateCount)
        assertEquals(2, handle.streamCloseCount)
        assertFalse(handle.closed)
    }

    @Test
    fun decodeBeforeRunnerPreparationFailsClosed() {
        val factory = FakeRecognizerFactory()
        val adapter = adapter(factory)

        val result = adapter.decode(SyntheticWav.pcm16Mono(16_000, 8))

        assertEquals(DecoderStatus.ERROR, result.status)
        assertEquals("decode_error", result.errorCode)
        assertEquals(0, factory.createCount)
    }

    @Test
    fun sherpaConfigUsesAar1133FileApiWithoutLoadingJni() {
        val config = canonicalConfig()

        val sherpa = SherpaOfflineParaformerRecognizerFactory
            .buildRecognizerConfig(config)

        assertEquals(16_000, sherpa.featConfig.sampleRate)
        assertEquals(80, sherpa.featConfig.featureDim)
        assertEquals(0.0f, sherpa.featConfig.dither)
        assertEquals(MODEL_PATH, sherpa.modelConfig.paraformer.model)
        assertEquals(TOKENS_PATH, sherpa.modelConfig.tokens)
        assertEquals(4, sherpa.modelConfig.numThreads)
        assertEquals(false, sherpa.modelConfig.debug)
        assertEquals("cpu", sherpa.modelConfig.provider)
        assertEquals("", sherpa.modelConfig.modelType)
        assertEquals("greedy_search", sherpa.decodingMethod)
        assertEquals(4, sherpa.maxActivePaths)
    }

    private fun canonicalConfig(): OfflineParaformerModelConfig =
        OfflineParaformerModelConfig.canonical(
            modelPath = MODEL_PATH,
            tokensPath = TOKENS_PATH,
            runtimeAarPath = RUNTIME_PATH,
        )

    private fun adapter(
        factory: FakeRecognizerFactory = FakeRecognizerFactory(),
    ): ParaformerDecoderAdapter = ParaformerDecoderAdapter(
        config = canonicalConfig(),
        recognizerFactory = factory,
    )

    private fun preparedAdapter(
        handle: FakeRecognizerHandle,
    ): ParaformerDecoderAdapter = adapter(
        FakeRecognizerFactory(handle = handle),
    ).also {
        it.prepare(correctArtifacts())
    }

    private fun canonicalPaths(): Map<String, String> = mapOf(
        "weights" to MODEL_PATH,
        "tokenizer" to TOKENS_PATH,
        "runtime" to RUNTIME_PATH,
    )

    private fun correctArtifacts(): Map<String, MeasuredFile> = mapOf(
        "weights" to measured(
            role = "weights",
            sizeBytes = OfflineParaformerModelConfig.MODEL_BYTES,
            sha256 = OfflineParaformerModelConfig.MODEL_SHA256,
            inode = 10,
        ),
        "tokenizer" to measured(
            role = "tokenizer",
            sizeBytes = OfflineParaformerModelConfig.TOKENS_BYTES,
            sha256 = OfflineParaformerModelConfig.TOKENS_SHA256,
            inode = 11,
        ),
        "runtime" to measured(
            role = "runtime",
            sizeBytes = OfflineParaformerModelConfig.RUNTIME_BYTES,
            sha256 = OfflineParaformerModelConfig.RUNTIME_SHA256,
            inode = 12,
        ),
    )

    private fun measured(
        role: String,
        sizeBytes: Long,
        sha256: String,
        inode: Long = 1,
    ): MeasuredFile = MeasuredFile.fromDigest(
        pathToken = "$role-token",
        identity = FileIdentity(
            device = 1,
            inode = inode,
            sizeBytes = sizeBytes,
            mode = 0x8000,
            linkCount = 1,
        ),
        sha256 = sha256,
    )

    private class FakeRecognizerFactory(
        private val handle: FakeRecognizerHandle = FakeRecognizerHandle(),
        private val createFailure: RuntimeException? = null,
    ) : ParaformerRecognizerFactory {
        var createCount = 0

        override fun create(
            config: OfflineParaformerModelConfig,
        ): ParaformerRecognizerHandle {
            createCount += 1
            createFailure?.let { throw it }
            return handle
        }
    }

    private class LifecycleAdapter(
        private val rejectPaths: Boolean = false,
    ) : DecoderAdapter, ArtifactPathBoundDecoderAdapter, AutoCloseable {
        var boundPaths: Map<String, String>? = null
        var closeCount = 0

        override fun requireArtifactPaths(pathsByRole: Map<String, String>) {
            boundPaths = pathsByRole
            if (rejectPaths) {
                throw BenchmarkContractException("synthetic path rejection")
            }
        }

        override fun decode(canonicalPcmWav: ByteArray): DecoderResult =
            throw UnsupportedOperationException("not used")

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeRecognizerHandle(
        var resultText: String? = "小猫",
        private val decodeFailure: RuntimeException? = null,
        private val onDecode: () -> Unit = {},
    ) : ParaformerRecognizerHandle {
        var createStreamFailure: RuntimeException? = null
        var decodeCount = 0
        var streamCreateCount = 0
        var streamCloseCount = 0
        var closeCount = 0
        var lastSampleRate = 0
        var lastSamples: FloatArray? = null
        var closed = false

        override fun createStream(): ParaformerStreamHandle {
            createStreamFailure?.let { throw it }
            streamCreateCount += 1
            return object : ParaformerStreamHandle {
                override fun acceptWaveform(
                    samples: FloatArray,
                    sampleRateHz: Int,
                ) {
                    lastSamples = samples.copyOf()
                    lastSampleRate = sampleRateHz
                }

                override fun close() {
                    streamCloseCount += 1
                }
            }
        }

        override fun decode(stream: ParaformerStreamHandle) {
            decodeCount += 1
            onDecode()
            decodeFailure?.let { throw it }
        }

        override fun resultText(stream: ParaformerStreamHandle): String? =
            resultText

        override fun close() {
            if (!closed) {
                closed = true
                closeCount += 1
            }
        }
    }

    companion object {
        private const val MODEL_PATH =
            "/data/user/0/com.wordtaker.keyboard.asrbenchmark.runner/files/" +
                "model.int8.onnx"
        private const val TOKENS_PATH =
            "/data/user/0/com.wordtaker.keyboard.asrbenchmark.runner/files/" +
                "tokens.txt"
        private const val RUNTIME_PATH =
            "/data/user/0/com.wordtaker.keyboard.asrbenchmark.runner/files/" +
                "sherpa-onnx-1.13.3.aar"
    }
}
