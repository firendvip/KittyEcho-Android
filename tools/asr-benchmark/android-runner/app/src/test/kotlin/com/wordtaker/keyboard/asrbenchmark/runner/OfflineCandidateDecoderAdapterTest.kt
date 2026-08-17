package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.FileIdentity
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import com.wordtaker.keyboard.asrbenchmark.core.SyntheticWav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCandidateDecoderAdapterTest {
    @Test
    fun fireRedValidatesEveryArtifactAndReturnsOnlyRawDecoderOutput() {
        val handle = FakeHandle(result = "  小猫Fire42  ")
        var createCount = 0
        val config = fireConfig()
        val adapter = FireRedDecoderAdapter(config) {
            createCount += 1
            handle
        }

        adapter.requireArtifactPaths(config.pathsByRole())
        adapter.prepare(measuredArtifacts(config.expectations()))
        val result = adapter.decode(SyntheticWav.pcm16Mono(16_000, 160))

        assertEquals(DecoderStatus.OK, result.status)
        assertEquals("  小猫Fire42  ", result.transcript)
        assertEquals(null, result.errorCode)
        assertEquals(1, createCount)
        assertEquals(16_000, handle.sampleRateHz)
        assertEquals(160, handle.sampleCount)
        assertEquals(1, handle.streamCloseCount)
    }

    @Test
    fun funAsrValidatesAllSixResourcesAndReusesOneRecognizer() {
        val handle = FakeHandle(result = "Nano原始结果。")
        var createCount = 0
        val config = nanoConfig()
        val adapter = FunAsrNanoDecoderAdapter(config) {
            createCount += 1
            handle
        }
        val artifacts = measuredArtifacts(config.expectations())

        adapter.requireArtifactPaths(config.pathsByRole())
        adapter.prepare(artifacts)
        adapter.prepare(artifacts)
        val wav = SyntheticWav.pcm16Mono(16_000, 32)
        assertEquals(DecoderStatus.OK, adapter.decode(wav).status)
        assertEquals(DecoderStatus.OK, adapter.decode(wav).status)

        assertEquals(1, createCount)
        assertEquals(2, handle.decodeCount)
        assertEquals(2, handle.streamCloseCount)
    }

    @Test
    fun adaptersFailClosedBeforeNativeInitializationOnAnyBindingMismatch() {
        val config = nanoConfig()
        var createCount = 0
        val adapter = FunAsrNanoDecoderAdapter(config) {
            createCount += 1
            FakeHandle()
        }
        val wrongHash = measuredArtifacts(config.expectations()).toMutableMap().apply {
            val expected = config.expectations().getValue("merges")
            this["merges"] = measured("merges", expected.sizeBytes, "0".repeat(64))
        }

        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(wrongHash)
        }
        assertThrows(BenchmarkContractException::class.java) {
            adapter.requireArtifactPaths(config.pathsByRole() - "vocab")
        }
        assertEquals(0, createCount)
    }

    @Test
    fun cancellationMalformedPcmInvalidOutputAndCloseNeverLeakText() {
        lateinit var during: FireRedDecoderAdapter
        val handle = FakeHandle(
            result = "must-not-escape",
            onDecode = { during.cancel() },
        )
        during = FireRedDecoderAdapter(fireConfig()) { handle }
        during.prepare(measuredArtifacts(fireConfig().expectations()))
        val cancelled = during.decode(SyntheticWav.pcm16Mono(16_000, 8))
        assertEquals(DecoderStatus.ERROR, cancelled.status)
        assertEquals("", cancelled.transcript)

        listOf<String?>(null, "", "bad\u0000text", "x".repeat(100_001)).forEach {
            val invalidHandle = FakeHandle(result = it)
            val adapter = FunAsrNanoDecoderAdapter(nanoConfig()) { invalidHandle }
            adapter.prepare(measuredArtifacts(nanoConfig().expectations()))
            val result = adapter.decode(SyntheticWav.pcm16Mono(16_000, 8))
            assertEquals(DecoderStatus.ERROR, result.status)
            assertEquals("", result.transcript)
            adapter.close()
        }

        val closed = FunAsrNanoDecoderAdapter(nanoConfig()) { FakeHandle() }
        closed.prepare(measuredArtifacts(nanoConfig().expectations()))
        closed.close()
        closed.close()
        assertEquals(
            DecoderStatus.ERROR,
            closed.decode(SyntheticWav.pcm16Mono(16_000, 8)).status,
        )
        assertTrue(handle.streamCloseCount == 1)
    }

    @Test
    fun lifecycleAndArtifactIdentityFailuresStayClosedBeforeDecode() {
        val config = fireConfig()
        val handle = FakeHandle()
        val adapter = FireRedDecoderAdapter(config) { handle }
        val artifacts = measuredArtifacts(config.expectations())
        adapter.prepare(artifacts)
        val expectedEncoder = config.expectations().getValue("encoder")
        val rebound = artifacts.toMutableMap().apply {
            this["encoder"] = measured(
                "rebound-encoder",
                expectedEncoder.sizeBytes,
                expectedEncoder.sha256,
            )
        }

        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(rebound)
        }
        assertEquals(1, handle.closeCount)
        adapter.close()
        assertThrows(BenchmarkContractException::class.java) {
            adapter.prepare(artifacts)
        }

        val missing = FireRedDecoderAdapter(config) { FakeHandle() }
        assertThrows(BenchmarkContractException::class.java) {
            missing.prepare(artifacts - "tokenizer")
        }

        var createCount = 0
        val cancelled = FunAsrNanoDecoderAdapter(nanoConfig()) {
            createCount += 1
            FakeHandle()
        }
        cancelled.cancel()
        cancelled.prepare(measuredArtifacts(nanoConfig().expectations()))
        assertEquals(0, createCount)
        assertEquals(
            DecoderStatus.ERROR,
            cancelled.decode(SyntheticWav.pcm16Mono(16_000, 8)).status,
        )
        cancelled.close()
    }

    @Test
    fun nativeBoundaryExceptionsMalformedPcmAndMidStreamCancelFailClosed() {
        val initializationFailure = FireRedDecoderAdapter(fireConfig()) {
            throw IllegalStateException("native initialization detail")
        }
        assertThrows(BenchmarkContractException::class.java) {
            initializationFailure.prepare(
                measuredArtifacts(fireConfig().expectations()),
            )
        }

        val expectedOom = OutOfMemoryError("expected test OOM")
        val oomAdapter = FireRedDecoderAdapter(fireConfig()) { throw expectedOom }
        val actualOom = assertThrows(OutOfMemoryError::class.java) {
            oomAdapter.prepare(measuredArtifacts(fireConfig().expectations()))
        }
        assertSame(expectedOom, actualOom)

        listOf(byteArrayOf(1, 2, 3), emptyPcmWav()).forEach { invalidPcm ->
            val adapter = FireRedDecoderAdapter(fireConfig()) { FakeHandle() }
            adapter.prepare(measuredArtifacts(fireConfig().expectations()))
            assertEquals(DecoderStatus.ERROR, adapter.decode(invalidPcm).status)
            adapter.close()
        }

        lateinit var cancelDuringAccept: FireRedDecoderAdapter
        cancelDuringAccept = FireRedDecoderAdapter(fireConfig()) {
            FakeHandle(onAccept = { cancelDuringAccept.cancel() })
        }
        cancelDuringAccept.prepare(measuredArtifacts(fireConfig().expectations()))
        assertEquals(
            DecoderStatus.ERROR,
            cancelDuringAccept.decode(SyntheticWav.pcm16Mono(16_000, 8)).status,
        )

        listOf(
            FakeHandle(failOnCreate = true),
            FakeHandle(failOnDecode = true),
            FakeHandle(failOnStreamClose = true),
        ).forEach { handle ->
            val adapter = FireRedDecoderAdapter(fireConfig()) { handle }
            adapter.prepare(measuredArtifacts(fireConfig().expectations()))
            assertEquals(
                DecoderStatus.ERROR,
                adapter.decode(SyntheticWav.pcm16Mono(16_000, 8)).status,
            )
            adapter.close()
        }

        val releaseFailure = FakeHandle(failOnClose = true)
        val releaseAdapter = FireRedDecoderAdapter(fireConfig()) { releaseFailure }
        releaseAdapter.prepare(measuredArtifacts(fireConfig().expectations()))
        releaseAdapter.close()
        assertEquals(1, releaseFailure.closeCount)
    }

    private fun fireConfig(): OfflineFireRedModelConfig =
        OfflineFireRedModelConfig.canonical(
            encoderPath = "/private/encoder.int8.onnx",
            decoderPath = "/private/decoder.int8.onnx",
            tokensPath = "/private/tokens.txt",
            runtimeAarPath = "/private/sherpa-onnx-1.13.3.aar",
        )

    private fun nanoConfig(): OfflineFunAsrNanoModelConfig =
        OfflineFunAsrNanoModelConfig.canonical(
            embeddingPath = "/private/embedding.int8.onnx",
            encoderAdaptorPath = "/private/encoder_adaptor.int8.onnx",
            llmPath = "/private/llm.int8.onnx",
            tokenizerDirectoryPath = "/private/Qwen3-0.6B",
            runtimeAarPath = "/private/sherpa-onnx-1.13.3.aar",
        )

    private fun measuredArtifacts(
        expectations: Map<String, ArtifactExpectation>,
    ): Map<String, MeasuredFile> = expectations.entries.associate { (role, expected) ->
        role to measured(role, expected.sizeBytes, expected.sha256)
    }

    private fun measured(
        role: String,
        sizeBytes: Long,
        sha256: String,
    ): MeasuredFile = MeasuredFile.fromDigest(
        pathToken = "$role-token",
        identity = FileIdentity(
            device = 1L,
            inode = role.hashCode().toLong().let { if (it < 0) -it else it } + 1L,
            sizeBytes = sizeBytes,
            mode = 0x8000,
            linkCount = 1,
        ),
        sha256 = sha256,
    )

    private fun emptyPcmWav(): ByteArray =
        SyntheticWav.pcm16Mono(16_000, 1).copyOf(44).apply {
            this[4] = 36
            this[5] = 0
            this[6] = 0
            this[7] = 0
            this[40] = 0
            this[41] = 0
            this[42] = 0
            this[43] = 0
        }

    private class FakeHandle(
        var result: String? = "小猫",
        private val onDecode: () -> Unit = {},
        private val onAccept: () -> Unit = {},
        private val failOnCreate: Boolean = false,
        private val failOnDecode: Boolean = false,
        private val failOnStreamClose: Boolean = false,
        private val failOnClose: Boolean = false,
    ) : OfflineCandidateRecognizerHandle {
        var decodeCount = 0
        var streamCloseCount = 0
        var sampleRateHz = 0
        var sampleCount = 0
        var closeCount = 0

        override fun createStream(): OfflineCandidateStreamHandle {
            if (failOnCreate) {
                throw IllegalStateException("create stream failed")
            }
            return object : OfflineCandidateStreamHandle {
                override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
                    this@FakeHandle.sampleRateHz = sampleRateHz
                    sampleCount = samples.size
                    onAccept()
                }

                override fun close() {
                    streamCloseCount += 1
                    if (failOnStreamClose) {
                        throw IllegalStateException("stream close failed")
                    }
                }
            }
        }

        override fun decode(stream: OfflineCandidateStreamHandle) {
            decodeCount += 1
            if (failOnDecode) {
                throw IllegalStateException("decode failed")
            }
            onDecode()
        }

        override fun resultText(stream: OfflineCandidateStreamHandle): String? = result

        override fun close() {
            closeCount += 1
            if (failOnClose) {
                throw IllegalStateException("recognizer close failed")
            }
        }
    }
}
