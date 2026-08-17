package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.CanonicalJson
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import com.wordtaker.keyboard.asrbenchmark.core.MonotonicClock
import com.wordtaker.keyboard.asrbenchmark.core.StabilityProof
import com.wordtaker.keyboard.asrbenchmark.core.StabilityProofValidationException
import com.wordtaker.keyboard.asrbenchmark.core.StabilityProofValidator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

interface ContinuousStabilityTelemetry : AutoCloseable {
    fun start()
    fun markDecodeStarted(clipId: String)
    fun markDecodeCompleted(clipId: String, status: DecoderStatus)
    fun markLoopCompleted(
        loopIndex: Int,
        monotonicStartNanos: Long,
        monotonicEndNanos: Long,
        successful: Boolean,
    )

    fun finish(): StabilityProof
}

data class ContinuousStabilityResult(
    val document: Map<String, Any?>,
)

internal class ContinuousStabilityResourceProbeException : RuntimeException()

internal class ContinuousStabilityRunner(
    private val clock: MonotonicClock,
) {
    fun run(
        duration: ContinuousStabilityDuration,
        cancellationPhase: ParaformerCancellationPhase,
        artifactsByRole: Map<String, MeasuredFile>,
        canonicalPcm: MeasuredFile,
        adapter: CancellablePreparedDecoderAdapter,
        telemetry: ContinuousStabilityTelemetry,
    ): ContinuousStabilityResult {
        requireInputs(artifactsByRole, canonicalPcm)
        val startedAt = clock.nowNanos()
        val latencies = LatencySummary()
        val outputs = AggregateOutputDigest()
        var successCount = 0L
        var failureCount = 0L
        var telemetryFinished = false
        var activePhase = ContinuousStabilityFailurePhase.UNKNOWN

        fun result(
            status: ContinuousStabilityStatus,
            proof: StabilityProof? = null,
            failurePhase: ContinuousStabilityFailurePhase =
                ContinuousStabilityFailurePhase.NONE,
            failureCode: ContinuousStabilityFailureCode =
                ContinuousStabilityFailureCode.NONE,
        ): ContinuousStabilityResult = ContinuousStabilityReport.create(
            status = status,
            failurePhase = failurePhase,
            failureCode = failureCode,
            successCount = successCount,
            failureCount = failureCount,
            durationNanos = elapsedSince(startedAt),
            latencies = latencies,
            proof = proof,
            outputSha256 = outputs.finish(),
        )

        return try {
            if (cancellationPhase == ParaformerCancellationPhase.BEFORE) {
                adapter.cancel()
            }
            activePhase = ContinuousStabilityFailurePhase.PREPARE
            adapter.prepare(artifactsByRole)
            if (cancellationPhase == ParaformerCancellationPhase.BEFORE) {
                return result(
                    ContinuousStabilityStatus.CANCELLED,
                    failureCode = ContinuousStabilityFailureCode.CANCELLED,
                )
            }

            activePhase = ContinuousStabilityFailurePhase.UNKNOWN
            telemetry.start()
            val policy = duration.policy
            val targetDurationNanos = Math.addExact(
                policy.minimumDurationNanos,
                Math.addExact(
                    policy.sampleIntervalNanos,
                    policy.absoluteJitterNanos,
                ),
            )
            var loopIndex = 0
            var minimumLoopsCompletedAt: Long? = null
            do {
                loopIndex += 1
                val loopStartedAt = clock.nowNanos()
                telemetry.markDecodeStarted(CLIP_ID)
                val stopNanos = clock.nowNanos()
                activePhase = ContinuousStabilityFailurePhase.DECODE
                val decoded = if (
                    cancellationPhase == ParaformerCancellationPhase.DURING
                ) {
                    decodeWithConcurrentCancellation(adapter, canonicalPcm.bytes)
                } else {
                    adapter.decode(canonicalPcm.bytes)
                }
                val finalNanos = clock.nowNanos()
                if (finalNanos < stopNanos) {
                    throw BenchmarkContractException(
                        "continuous stability monotonic clock moved backwards",
                    )
                }
                latencies.record(finalNanos - stopNanos)

                if (cancellationPhase == ParaformerCancellationPhase.DURING) {
                    return result(
                        ContinuousStabilityStatus.CANCELLED,
                        failureCode = ContinuousStabilityFailureCode.CANCELLED,
                    )
                }
                activePhase = ContinuousStabilityFailurePhase.OUTPUT
                val outputFailure = outputFailure(decoded)
                if (outputFailure != null) {
                    failureCount = Math.addExact(failureCount, 1L)
                    return result(
                        status = ContinuousStabilityStatus.ERROR,
                        failurePhase = ContinuousStabilityFailurePhase.OUTPUT,
                        failureCode = outputFailure,
                    )
                }

                val accepted = requireNotNull(decoded)
                outputs.record(accepted.transcript)
                successCount = Math.addExact(successCount, 1L)
                telemetry.markDecodeCompleted(CLIP_ID, DecoderStatus.OK)
                telemetry.markLoopCompleted(
                    loopIndex = loopIndex,
                    monotonicStartNanos = loopStartedAt,
                    monotonicEndNanos = finalNanos,
                    successful = true,
                )
                if (loopIndex == policy.minimumCompleteLoops) {
                    minimumLoopsCompletedAt = finalNanos
                }
                if (cancellationPhase == ParaformerCancellationPhase.AFTER) {
                    adapter.cancel()
                    return result(
                        ContinuousStabilityStatus.CANCELLED,
                        failureCode = ContinuousStabilityFailureCode.CANCELLED,
                    )
                }
            } while (
                loopIndex < policy.minimumCompleteLoops ||
                elapsedSince(startedAt) < targetDurationNanos ||
                clock.nowNanos() - requireNotNull(minimumLoopsCompletedAt) <
                policy.sampleIntervalNanos + policy.absoluteJitterNanos
            )

            activePhase = ContinuousStabilityFailurePhase.TELEMETRY_FINISH
            val proof = telemetry.finish()
            activePhase = ContinuousStabilityFailurePhase.PROOF_VALIDATION
            StabilityProofValidator.validate(proof, listOf(CLIP_ID), policy)
            telemetryFinished = true
            result(ContinuousStabilityStatus.OK, proof)
        } catch (_: OutOfMemoryError) {
            failureCount = Math.addExact(failureCount, 1L)
            result(
                status = ContinuousStabilityStatus.ERROR,
                failurePhase = activePhase.failClosed(),
                failureCode = ContinuousStabilityFailureCode.OOM,
            )
        } catch (error: Exception) {
            failureCount = Math.addExact(failureCount, 1L)
            val classified = classifyFailure(error, activePhase)
            result(
                status = ContinuousStabilityStatus.ERROR,
                failurePhase = classified.first,
                failureCode = classified.second,
            )
        } finally {
            if (!telemetryFinished) {
                telemetry.close()
            }
        }
    }

    private fun requireInputs(
        artifactsByRole: Map<String, MeasuredFile>,
        canonicalPcm: MeasuredFile,
    ) {
        if (
            artifactsByRole.isEmpty() ||
            artifactsByRole.keys.any { it.isBlank() } ||
            canonicalPcm.pcm == null ||
            canonicalPcm.retainedBytesOrNull() == null
        ) {
            throw BenchmarkContractException(
                "continuous stability inputs are incomplete",
            )
        }
    }

    private fun elapsedSince(startedAt: Long): Long {
        val now = clock.nowNanos()
        if (now < startedAt) {
            throw BenchmarkContractException(
                "continuous stability monotonic clock moved backwards",
            )
        }
        return now - startedAt
    }

    private fun decodeWithConcurrentCancellation(
        adapter: CancellablePreparedDecoderAdapter,
        pcm: ByteArray,
    ): DecoderResult? {
        val enteredDecode = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "asr-stability-cancel").apply { isDaemon = true }
        }
        return try {
            val pending = executor.submit<DecoderResult> {
                enteredDecode.countDown()
                adapter.decode(pcm)
            }
            if (!enteredDecode.await(5L, TimeUnit.SECONDS)) {
                throw TimeoutException("continuous decode thread did not start")
            }
            adapter.cancel()
            try {
                pending.get(60L, TimeUnit.SECONDS)
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is OutOfMemoryError) {
                    throw cause
                }
                null
            }
        } finally {
            executor.shutdownNow()
            if (!executor.awaitTermination(60L, TimeUnit.SECONDS)) {
                throw TimeoutException("continuous decode thread did not stop")
            }
        }
    }

    private fun outputFailure(
        result: DecoderResult?,
    ): ContinuousStabilityFailureCode? = when {
        result == null -> ContinuousStabilityFailureCode.INVALID_OUTPUT
        result.status != DecoderStatus.OK || result.errorCode != null ->
            ContinuousStabilityFailureCode.DECODE_ERROR
        result.transcript.isBlank() -> ContinuousStabilityFailureCode.EMPTY_OUTPUT
        result.transcript.length > MAX_OUTPUT_CHARS ||
            result.transcript.any { it.code < 0x20 || it.code == 0x7f } ->
            ContinuousStabilityFailureCode.INVALID_OUTPUT
        else -> null
    }

    private fun classifyFailure(
        error: Exception,
        activePhase: ContinuousStabilityFailurePhase,
    ): Pair<ContinuousStabilityFailurePhase, ContinuousStabilityFailureCode> =
        when (error) {
            is ContinuousStabilityResourceProbeException ->
                ContinuousStabilityFailurePhase.RESOURCE_PROBE to
                    ContinuousStabilityFailureCode.RESOURCE_PROBE_ERROR
            is StabilityProofValidationException ->
                ContinuousStabilityFailurePhase.PROOF_VALIDATION to
                    ContinuousStabilityFailureCode.PROOF_ERROR
            else -> {
                val phase = activePhase.failClosed()
                phase to when (phase) {
                    ContinuousStabilityFailurePhase.TELEMETRY_FINISH ->
                        ContinuousStabilityFailureCode.TELEMETRY_ERROR
                    ContinuousStabilityFailurePhase.PROOF_VALIDATION ->
                        ContinuousStabilityFailureCode.PROOF_ERROR
                    ContinuousStabilityFailurePhase.OUTPUT ->
                        ContinuousStabilityFailureCode.INVALID_OUTPUT
                    ContinuousStabilityFailurePhase.UNKNOWN ->
                        ContinuousStabilityFailureCode.UNKNOWN
                    else -> ContinuousStabilityFailureCode.RUNTIME_ERROR
                }
            }
        }

    companion object {
        const val CLIP_ID = "continuous_pcm"
        private const val MAX_OUTPUT_CHARS = 100_000
    }
}

private enum class ContinuousStabilityStatus(val wireValue: String) {
    OK("ok"),
    ERROR("error"),
    CANCELLED("cancelled"),
}

private enum class ContinuousStabilityFailurePhase(val wireValue: String) {
    NONE("none"),
    PREPARE("prepare"),
    DECODE("decode"),
    OUTPUT("output"),
    TELEMETRY_FINISH("telemetry_finish"),
    PROOF_VALIDATION("proof_validation"),
    RESOURCE_PROBE("resource_probe"),
    UNKNOWN("unknown"),
    ;

    fun failClosed(): ContinuousStabilityFailurePhase =
        if (this == NONE) UNKNOWN else this
}

private enum class ContinuousStabilityFailureCode(val wireValue: String) {
    NONE("none"),
    CANCELLED("cancelled"),
    EMPTY_OUTPUT("empty_output"),
    INVALID_OUTPUT("invalid_output"),
    DECODE_ERROR("decode_error"),
    RUNTIME_ERROR("runtime_error"),
    OOM("oom"),
    TELEMETRY_ERROR("telemetry_error"),
    PROOF_ERROR("proof_error"),
    RESOURCE_PROBE_ERROR("resource_probe_error"),
    UNKNOWN("unknown"),
}

private class LatencySummary {
    private var count = 0L
    private var totalNanos = 0L
    private var minimumNanos: Long? = null
    private var maximumNanos: Long? = null

    fun record(nanos: Long) {
        if (nanos < 0L) {
            throw BenchmarkContractException(
                "continuous stability latency is invalid",
            )
        }
        count = Math.addExact(count, 1L)
        totalNanos = Math.addExact(totalNanos, nanos)
        minimumNanos = minimumNanos?.let { minOf(it, nanos) } ?: nanos
        maximumNanos = maximumNanos?.let { maxOf(it, nanos) } ?: nanos
    }

    fun document(): Map<String, Any?> = linkedMapOf(
        "count" to count,
        "minimum_ns" to minimumNanos,
        "maximum_ns" to maximumNanos,
        "mean_ns" to if (count == 0L) null else totalNanos / count,
    )
}

private class AggregateOutputDigest {
    private val digest = MessageDigest.getInstance("SHA-256").apply {
        update("kittyecho-continuous-output-v1\u0000".encodeToByteArray())
    }

    fun record(value: String) {
        val bytes = value.encodeToByteArray()
        digest.update(
            ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(bytes.size)
                .array(),
        )
        digest.update(bytes)
    }

    fun finish(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

private object ContinuousStabilityReport {
    fun create(
        status: ContinuousStabilityStatus,
        failurePhase: ContinuousStabilityFailurePhase,
        failureCode: ContinuousStabilityFailureCode,
        successCount: Long,
        failureCount: Long,
        durationNanos: Long,
        latencies: LatencySummary,
        proof: StabilityProof?,
        outputSha256: String,
    ): ContinuousStabilityResult {
        if (
            successCount < 0L ||
            failureCount < 0L ||
            durationNanos < 0L ||
            !SHA256.matches(outputSha256) ||
            (status == ContinuousStabilityStatus.OK && proof == null) ||
            (status != ContinuousStabilityStatus.OK && proof != null) ||
            (status == ContinuousStabilityStatus.OK && failureCount != 0L) ||
            (status == ContinuousStabilityStatus.ERROR && failureCount == 0L) ||
            (
                status == ContinuousStabilityStatus.OK &&
                    (
                        failurePhase != ContinuousStabilityFailurePhase.NONE ||
                            failureCode != ContinuousStabilityFailureCode.NONE
                        )
                ) ||
            (
                status == ContinuousStabilityStatus.CANCELLED &&
                    (
                        failurePhase != ContinuousStabilityFailurePhase.NONE ||
                            failureCode != ContinuousStabilityFailureCode.CANCELLED
                        )
                ) ||
            (
                status == ContinuousStabilityStatus.ERROR &&
                    (
                        failurePhase == ContinuousStabilityFailurePhase.NONE ||
                            failureCode in setOf(
                                ContinuousStabilityFailureCode.NONE,
                                ContinuousStabilityFailureCode.CANCELLED,
                            )
                        )
                )
        ) {
            throw BenchmarkContractException(
                "continuous stability result evidence is invalid",
            )
        }
        return ContinuousStabilityResult(
            linkedMapOf(
                "schema_version" to 1,
                "status" to status.wireValue,
                "failure_phase" to failurePhase.wireValue,
                "failure_code" to failureCode.wireValue,
                "success_count" to successCount,
                "failure_count" to failureCount,
                "duration_ns" to durationNanos,
                "stop_to_final" to latencies.document(),
                "resources" to resourceDocument(proof),
                "heartbeat_proof" to heartbeatDocument(proof),
                "loop_proof" to loopDocument(proof),
                "output_sha256" to outputSha256,
                "formal_eligible" to false,
                "product_decision_eligible" to false,
            ),
        )
    }

    private fun resourceDocument(proof: StabilityProof?): Map<String, Any?> {
        val samples = proof?.samples.orEmpty()
        if (samples.isEmpty()) {
            return emptyMap()
        }
        return linkedMapOf<String, Any?>(
            "rss_bytes" to memorySummary(samples.map { it.rssBytes }),
            "pss_bytes" to memorySummary(samples.map { it.pssBytes }),
        ).apply {
            val swap = samples.mapNotNull { it.swapBytes }
            if (swap.isNotEmpty()) {
                put("swap_bytes", memorySummary(swap))
            }
        }
    }

    private fun memorySummary(values: List<Long>): Map<String, Any> = linkedMapOf(
        "available_sample_count" to values.size.toLong(),
        "minimum_bytes" to values.min(),
        "maximum_bytes" to values.max(),
        "last_bytes" to values.last(),
    )

    private fun heartbeatDocument(proof: StabilityProof?): Map<String, Any?> {
        if (proof == null || proof.samples.isEmpty()) {
            return linkedMapOf(
                "count" to 0L,
                "interval_ns" to null,
                "duration_ns" to 0L,
                "proof_sha256" to null,
            )
        }
        val projection = proof.samples.map { sample ->
            linkedMapOf(
                "monotonic_ns" to sample.monotonicNanos,
                "rss_bytes" to sample.rssBytes,
                "pss_bytes" to sample.pssBytes,
                "swap_bytes" to sample.swapBytes,
                "thermal_status" to sample.thermalStatus,
                "heartbeat_index" to sample.heartbeatIndex,
                "runner_alive" to sample.runnerAlive,
                "decoder_active" to sample.decoderActive,
                "active_decode_progress" to sample.activeDecodeProgress,
                "completed_loops" to sample.completedLoops,
                "successful_loops" to sample.successfulLoops,
            )
        }
        return linkedMapOf(
            "count" to proof.samples.size.toLong(),
            "interval_ns" to proof.sampleIntervalNanos,
            "duration_ns" to (
                proof.samples.last().monotonicNanos -
                    proof.samples.first().monotonicNanos
                ),
            "proof_sha256" to CanonicalJson.sha256(projection),
        )
    }

    private fun loopDocument(proof: StabilityProof?): Map<String, Any?> {
        if (proof == null) {
            return linkedMapOf(
                "count" to 0L,
                "successful_count" to 0L,
                "total_decode_count" to 0L,
                "proof_sha256" to null,
            )
        }
        val projection = proof.loopProofs.map { loop ->
            linkedMapOf(
                "loop_index" to loop.loopIndex,
                "monotonic_start_ns" to loop.monotonicStartNanos,
                "monotonic_end_ns" to loop.monotonicEndNanos,
                "ordered_clip_ids_sha256" to loop.orderedClipIdsSha256,
                "clip_count" to loop.clipCount,
                "cumulative_decode_count" to loop.cumulativeDecodedClipCount,
                "successful" to loop.successful,
            )
        }
        return linkedMapOf(
            "count" to proof.loopCount.toLong(),
            "successful_count" to proof.successfulLoopCount.toLong(),
            "total_decode_count" to proof.totalDecodedClipCount.toLong(),
            "proof_sha256" to CanonicalJson.sha256(projection),
        )
    }

    private val SHA256 = Regex("^[0-9a-f]{64}$")
}
