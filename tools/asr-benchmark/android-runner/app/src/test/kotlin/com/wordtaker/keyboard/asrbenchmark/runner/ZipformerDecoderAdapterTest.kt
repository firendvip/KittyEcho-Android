package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.FileIdentity
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import com.wordtaker.keyboard.asrbenchmark.core.SyntheticWav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipformerDecoderAdapterTest {
    @Test
    fun deviceConfigFreezesFilesHashesAndDecoderSettings() {
        val config = config()

        assertEquals(16_000, config.sampleRateHz)
        assertEquals(80, config.featureDim)
        assertEquals(0.0f, config.dither)
        assertEquals("greedy_search", config.decodingMethod)
        assertEquals(4, config.numThreads)
        assertEquals("cpu", config.provider)
        assertEquals("", config.modelType)
        assertEquals(
            setOf("encoder", "decoder", "joiner", "tokenizer"),
            config.expectations().keys,
        )
        assertEquals(
            OfflineParaformerModelConfig.RUNTIME_RECEIPT_COMMIT_SHA256,
            config.runtimeReceiptCommitSha256,
        )
        assertEquals(
            OfflineParaformerModelConfig.REGISTRY_SNAPSHOT_SHA256,
            config.registrySnapshotSha256,
        )
        assertEquals(
            mapOf(
                "encoder" to ENCODER_PATH,
                "decoder" to DECODER_PATH,
                "joiner" to JOINER_PATH,
                "tokenizer" to TOKENS_PATH,
            ),
            config.pathsByRole(),
        )
    }

    @Test
    fun configRejectsRelativeSwappedAndDuplicatePaths() {
        assertThrows(BenchmarkContractException::class.java) {
            ZipformerModelConfig.devicePrivate(
                encoderPath = "encoder.int8.onnx",
                decoderPath = DECODER_PATH,
                joinerPath = JOINER_PATH,
                tokensPath = TOKENS_PATH,
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            ZipformerModelConfig.devicePrivate(
                encoderPath = DECODER_PATH,
                decoderPath = ENCODER_PATH,
                joinerPath = JOINER_PATH,
                tokensPath = TOKENS_PATH,
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            ZipformerModelConfig.devicePrivate(
                encoderPath = ENCODER_PATH,
                decoderPath = DECODER_PATH,
                joinerPath = JOINER_PATH,
                tokensPath = JOINER_PATH,
            )
        }
    }

    @Test
    fun sherpaConfigMatchesProductionBaselineWithoutLoadingJni() {
        val sherpa = SherpaOnlineZipformerRecognizerFactory
            .buildRecognizerConfig(config())

        assertEquals(16_000, sherpa.featConfig.sampleRate)
        assertEquals(80, sherpa.featConfig.featureDim)
        assertEquals(ENCODER_PATH, sherpa.modelConfig.transducer.encoder)
        assertEquals(DECODER_PATH, sherpa.modelConfig.transducer.decoder)
        assertEquals(JOINER_PATH, sherpa.modelConfig.transducer.joiner)
        assertEquals(TOKENS_PATH, sherpa.modelConfig.tokens)
        assertEquals(4, sherpa.modelConfig.numThreads)
        assertEquals("cpu", sherpa.modelConfig.provider)
        assertEquals("", sherpa.modelConfig.modelType)
        assertEquals(false, sherpa.enableEndpoint)
        assertEquals("greedy_search", sherpa.decodingMethod)
    }

    @Test
    fun prepareRejectsMissingOrMismatchedArtifactsBeforeInitialization() {
        val factory = FakeZipformerRecognizerFactory()
        val adapter = ZipformerDecoderAdapter(config(), factory)

        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(artifacts() - "joiner")
        }
        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(
                artifacts().toMutableMap().apply {
                    this["encoder"] = measured(
                        "encoder",
                        ZipformerModelConfig.ENCODER_BYTES,
                        "0".repeat(64),
                    )
                },
            )
        }
        assertEquals(0, factory.createCount)
    }

    @Test
    fun pathBindingClosedPrepareReplacementAndInitFailureFailClosed() {
        val adapter = ZipformerDecoderAdapter(config(), FakeZipformerRecognizerFactory())
        assertThrows(BenchmarkContractException::class.java) {
            adapter.requireArtifactPaths(
                config().pathsByRole().toMutableMap().apply {
                    this["encoder"] = "$ENCODER_PATH.replaced"
                },
            )
        }
        adapter.requireArtifactPaths(config().pathsByRole())
        adapter.prepare(artifacts())
        val replaced = artifacts().toMutableMap().apply {
            this["encoder"] = measured(
                "encoder",
                ZipformerModelConfig.ENCODER_BYTES,
                ZipformerModelConfig.ENCODER_SHA256,
                inode = 999,
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(replaced)
        }

        val initializationFailure = ZipformerDecoderAdapter(
            config(),
            FakeZipformerRecognizerFactory(
                createFailure = IllegalStateException("synthetic"),
            ),
        )
        assertThrows(BenchmarkContractException::class.java) {
            initializationFailure.prepare(artifacts())
        }

        val closed = ZipformerDecoderAdapter(config(), FakeZipformerRecognizerFactory())
        closed.close()
        assertThrows(BenchmarkContractException::class.java) {
            closed.prepare(artifacts())
        }
    }

    @Test
    fun wholePcmIsFinalizedDecodedAndReleasedAsOneSession() {
        val handle = FakeZipformerRecognizerHandle(
            readySequence = ArrayDeque(listOf(true, true, false)),
            resultText = "基线结果",
        )
        val adapter = preparedAdapter(handle)

        val result = adapter.decode(
            SyntheticWav.pcm16Mono(sampleRate = 16_000, frameCount = 320),
        )

        assertEquals(DecoderStatus.OK, result.status)
        assertEquals("基线结果", result.transcript)
        assertEquals(320, handle.lastSamples?.size)
        assertEquals(16_000, handle.lastSampleRate)
        assertEquals(1, handle.inputFinishedCount)
        assertEquals(2, handle.decodeCount)
        assertEquals(1, handle.streamCloseCount)
    }

    @Test
    fun cancellationBeforeAndDuringDecodeDiscardsOutput() {
        val beforeFactory = FakeZipformerRecognizerFactory()
        val before = ZipformerDecoderAdapter(config(), beforeFactory)
        before.cancel()
        before.prepare(artifacts())
        assertEquals(
            DecoderStatus.ERROR,
            before.decode(SyntheticWav.pcm16Mono(16_000, 16)).status,
        )
        assertEquals(0, beforeFactory.createCount)

        lateinit var during: ZipformerDecoderAdapter
        val handle = FakeZipformerRecognizerHandle(
            readySequence = ArrayDeque(listOf(true, false)),
            resultText = "must-not-escape",
            onDecode = { during.cancel() },
        )
        during = preparedAdapter(handle)
        val result = during.decode(SyntheticWav.pcm16Mono(16_000, 16))
        assertEquals(DecoderStatus.ERROR, result.status)
        assertEquals("", result.transcript)
        assertEquals(1, handle.streamCloseCount)
    }

    @Test
    fun malformedPcmNativeFailureAndInvalidOutputFailClosed() {
        val malformedHandle = FakeZipformerRecognizerHandle(
            readySequence = ArrayDeque(listOf(false)),
            resultText = "unused",
        )
        assertEquals(
            DecoderStatus.ERROR,
            preparedAdapter(malformedHandle).decode(byteArrayOf(1, 2)).status,
        )
        assertEquals(0, malformedHandle.streamCreateCount)

        val emptyPayload = SyntheticWav.pcm16Mono(16_000, 1).copyOf(44).apply {
            this[4] = 36
            this[40] = 0
        }
        assertEquals(
            DecoderStatus.ERROR,
            preparedAdapter(malformedHandle).decode(emptyPayload).status,
        )

        val nativeFailure = FakeZipformerRecognizerHandle(
            readySequence = ArrayDeque(listOf(true)),
            resultText = "unused",
            decodeFailure = IllegalStateException("synthetic"),
        )
        assertEquals(
            DecoderStatus.ERROR,
            preparedAdapter(nativeFailure).decode(
                SyntheticWav.pcm16Mono(16_000, 16),
            ).status,
        )
        assertEquals(1, nativeFailure.streamCloseCount)

        listOf("", "bad\u0000output").forEach { invalid ->
            val invalidHandle = FakeZipformerRecognizerHandle(
                readySequence = ArrayDeque(listOf(false)),
                resultText = invalid,
            )
            assertEquals(
                DecoderStatus.ERROR,
                preparedAdapter(invalidHandle).decode(
                    SyntheticWav.pcm16Mono(16_000, 16),
                ).status,
            )
        }
    }

    @Test
    fun closeIsIdempotentAndRepeatedDecodeUsesDistinctStreams() {
        val handle = FakeZipformerRecognizerHandle(
            readySequence = ArrayDeque(listOf(false, false)),
            resultText = "小猫",
        )
        val adapter = preparedAdapter(handle)
        val wav = SyntheticWav.pcm16Mono(16_000, 16)

        assertEquals(DecoderStatus.OK, adapter.decode(wav).status)
        assertEquals(DecoderStatus.OK, adapter.decode(wav).status)
        adapter.close()
        adapter.close()
        assertEquals(DecoderStatus.ERROR, adapter.decode(wav).status)
        assertEquals(2, handle.streamCreateCount)
        assertEquals(2, handle.streamCloseCount)
        assertEquals(1, handle.closeCount)
        assertTrue(handle.closed)
    }

    private fun config(): ZipformerModelConfig =
        ZipformerModelConfig.devicePrivate(
            encoderPath = ENCODER_PATH,
            decoderPath = DECODER_PATH,
            joinerPath = JOINER_PATH,
            tokensPath = TOKENS_PATH,
        )

    private fun preparedAdapter(
        handle: FakeZipformerRecognizerHandle,
    ): ZipformerDecoderAdapter = ZipformerDecoderAdapter(
        config(),
        FakeZipformerRecognizerFactory(handle),
    ).also { it.prepare(artifacts()) }

    private fun artifacts(): Map<String, MeasuredFile> = mapOf(
        "encoder" to measured(
            "encoder",
            ZipformerModelConfig.ENCODER_BYTES,
            ZipformerModelConfig.ENCODER_SHA256,
        ),
        "decoder" to measured(
            "decoder",
            ZipformerModelConfig.DECODER_BYTES,
            ZipformerModelConfig.DECODER_SHA256,
        ),
        "joiner" to measured(
            "joiner",
            ZipformerModelConfig.JOINER_BYTES,
            ZipformerModelConfig.JOINER_SHA256,
        ),
        "tokenizer" to measured(
            "tokenizer",
            ZipformerModelConfig.TOKENS_BYTES,
            ZipformerModelConfig.TOKENS_SHA256,
        ),
    )

    private fun measured(
        role: String,
        bytes: Long,
        sha256: String,
        inode: Long = role.hashCode().toLong().let { if (it < 0) -it else it },
    ): MeasuredFile = MeasuredFile.fromDigest(
        pathToken = "$role-token",
        identity = FileIdentity(
            device = 1,
            inode = inode,
            sizeBytes = bytes,
            mode = 0x8000,
            linkCount = 1,
        ),
        sha256 = sha256,
    )

    private class FakeZipformerRecognizerFactory(
        private val handle: FakeZipformerRecognizerHandle =
            FakeZipformerRecognizerHandle(),
        private val createFailure: RuntimeException? = null,
    ) : ZipformerRecognizerFactory {
        var createCount = 0

        override fun create(
            config: ZipformerModelConfig,
        ): ZipformerRecognizerHandle {
            createCount += 1
            createFailure?.let { throw it }
            return handle
        }
    }

    private class FakeZipformerRecognizerHandle(
        private val readySequence: ArrayDeque<Boolean> = ArrayDeque(),
        var resultText: String = "小猫",
        private val decodeFailure: RuntimeException? = null,
        private val onDecode: () -> Unit = {},
    ) : ZipformerRecognizerHandle {
        var streamCreateCount = 0
        var streamCloseCount = 0
        var inputFinishedCount = 0
        var decodeCount = 0
        var closeCount = 0
        var lastSamples: FloatArray? = null
        var lastSampleRate = 0
        var closed = false

        override fun createStream(): ZipformerStreamHandle {
            streamCreateCount += 1
            return object : ZipformerStreamHandle {
                override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
                    lastSamples = samples.copyOf()
                    lastSampleRate = sampleRateHz
                }

                override fun inputFinished() {
                    inputFinishedCount += 1
                }

                override fun close() {
                    streamCloseCount += 1
                }
            }
        }

        override fun isReady(stream: ZipformerStreamHandle): Boolean =
            if (readySequence.isEmpty()) false else readySequence.removeFirst()

        override fun decode(stream: ZipformerStreamHandle) {
            decodeCount += 1
            onDecode()
            decodeFailure?.let { throw it }
        }

        override fun resultText(stream: ZipformerStreamHandle): String = resultText

        override fun close() {
            if (!closed) {
                closed = true
                closeCount += 1
            }
        }
    }

    companion object {
        private const val ROOT =
            "/data/user/0/com.wordtaker.keyboard.asrbenchmark.runner/files/"
        private const val ENCODER_PATH = "${ROOT}encoder.int8.onnx"
        private const val DECODER_PATH = "${ROOT}decoder.int8.onnx"
        private const val JOINER_PATH = "${ROOT}joiner.int8.onnx"
        private const val TOKENS_PATH = "${ROOT}tokens.txt"
    }
}
