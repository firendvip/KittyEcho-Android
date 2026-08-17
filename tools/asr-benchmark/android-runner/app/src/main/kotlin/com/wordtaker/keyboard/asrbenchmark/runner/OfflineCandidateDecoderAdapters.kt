package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import java.util.concurrent.atomic.AtomicBoolean

class FireRedDecoderAdapter internal constructor(
    config: OfflineFireRedModelConfig,
    recognizerFactory: () -> OfflineCandidateRecognizerHandle = {
        SherpaOfflineFireRedRecognizerFactory.create(config)
    },
) : CancellablePreparedDecoderAdapter, ArtifactPathBoundDecoderAdapter {
    private val delegate = OfflineCandidateDecoderEngine(
        modelName = "FireRed",
        sampleRateHz = config.sampleRateHz,
        pathsByRole = config.pathsByRole(),
        expectations = config.expectations(),
        recognizerFactory = recognizerFactory,
    )

    override fun requireArtifactPaths(pathsByRole: Map<String, String>) =
        delegate.requireArtifactPaths(pathsByRole)

    override fun prepare(artifactsByRole: Map<String, MeasuredFile>) =
        delegate.prepare(artifactsByRole)

    override fun decode(canonicalPcmWav: ByteArray): DecoderResult =
        delegate.decode(canonicalPcmWav)

    override fun cancel() = delegate.cancel()

    override fun close() = delegate.close()
}

class FunAsrNanoDecoderAdapter internal constructor(
    config: OfflineFunAsrNanoModelConfig,
    recognizerFactory: () -> OfflineCandidateRecognizerHandle = {
        SherpaOfflineFunAsrNanoRecognizerFactory.create(config)
    },
) : CancellablePreparedDecoderAdapter, ArtifactPathBoundDecoderAdapter {
    private val delegate = OfflineCandidateDecoderEngine(
        modelName = "FunASR Nano",
        sampleRateHz = config.sampleRateHz,
        pathsByRole = config.pathsByRole(),
        expectations = config.expectations(),
        recognizerFactory = recognizerFactory,
    )

    override fun requireArtifactPaths(pathsByRole: Map<String, String>) =
        delegate.requireArtifactPaths(pathsByRole)

    override fun prepare(artifactsByRole: Map<String, MeasuredFile>) =
        delegate.prepare(artifactsByRole)

    override fun decode(canonicalPcmWav: ByteArray): DecoderResult =
        delegate.decode(canonicalPcmWav)

    override fun cancel() = delegate.cancel()

    override fun close() = delegate.close()
}

private class OfflineCandidateDecoderEngine(
    private val modelName: String,
    private val sampleRateHz: Int,
    private val pathsByRole: Map<String, String>,
    private val expectations: Map<String, ArtifactExpectation>,
    private val recognizerFactory: () -> OfflineCandidateRecognizerHandle,
) : CancellablePreparedDecoderAdapter, ArtifactPathBoundDecoderAdapter {
    private val cancelled = AtomicBoolean(false)
    private var recognizer: OfflineCandidateRecognizerHandle? = null
    private var preparedArtifacts: Map<String, PreparedArtifactBinding>? = null
    private var closed = false

    override fun requireArtifactPaths(pathsByRole: Map<String, String>) {
        if (pathsByRole != this.pathsByRole) {
            throw BenchmarkContractException(
                "$modelName artifact paths differ from the adapter configuration",
            )
        }
    }

    @Synchronized
    override fun prepare(artifactsByRole: Map<String, MeasuredFile>) {
        if (closed) {
            throw BenchmarkContractException("$modelName adapter is closed")
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
                    "$modelName artifact identity changed after preparation",
                )
            }
            return
        }
        val built = try {
            recognizerFactory()
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (_: Exception) {
            throw BenchmarkContractException(
                "$modelName recognizer initialization failed",
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

        var stream: OfflineCandidateStreamHandle? = null
        var transcript: String? = null
        var failed = false
        try {
            stream = activeRecognizer.createStream()
            stream.acceptWaveform(samples, sampleRateHz)
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
        if (artifactsByRole.keys != expectations.keys) {
            throw BenchmarkContractException(
                "$modelName runner artifacts are incomplete or contain extra roles",
            )
        }
        return expectations.mapValues { (role, expected) ->
            val measured = requireNotNull(artifactsByRole[role])
            if (
                measured.identity.sizeBytes != expected.sizeBytes ||
                measured.sha256 != expected.sha256
            ) {
                throw BenchmarkContractException(
                    "$modelName $role artifact differs from its frozen receipt",
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
