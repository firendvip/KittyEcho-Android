package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.CanonicalJson
import com.wordtaker.keyboard.asrbenchmark.core.MonotonicClock
import com.wordtaker.keyboard.asrbenchmark.core.ResourceProbe
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.StabilityLoopProof
import com.wordtaker.keyboard.asrbenchmark.core.StabilityProgressLedger
import com.wordtaker.keyboard.asrbenchmark.core.StabilityProof
import com.wordtaker.keyboard.asrbenchmark.core.StabilityPolicy
import com.wordtaker.keyboard.asrbenchmark.core.StabilityProofValidator
import com.wordtaker.keyboard.asrbenchmark.core.StabilitySample
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AndroidStabilityTelemetryCollector(
    private val orderedClipIds: List<String>,
    private val clock: MonotonicClock,
    private val resourceProbe: ResourceProbe,
    private val policy: StabilityPolicy = StabilityPolicy.formal(),
) : ContinuousStabilityTelemetry {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
    private val stateLock = Any()
    private val samples = mutableListOf<StabilitySample>()
    private val loops = mutableListOf<StabilityLoopProof>()
    private val progressLedger = StabilityProgressLedger(orderedClipIds)
    private val started = AtomicBoolean(false)
    private val asynchronousFailure = AtomicReference<Throwable?>(null)

    override fun start() {
        if (!started.compareAndSet(false, true)) {
            throw BenchmarkContractException("stability collector already started")
        }
        sampleHeartbeat()
        executor.scheduleAtFixedRate(
            {
                try {
                    sampleHeartbeat()
                } catch (error: Throwable) {
                    asynchronousFailure.compareAndSet(null, error)
                    throw error
                }
            },
            policy.sampleIntervalNanos,
            policy.sampleIntervalNanos,
            TimeUnit.NANOSECONDS,
        )
    }

    override fun markDecodeStarted(clipId: String) {
        ensureStarted()
        synchronized(stateLock) {
            progressLedger.markDecodeStarted(clipId)
        }
    }

    override fun markDecodeCompleted(
        clipId: String,
        status: DecoderStatus,
    ) {
        ensureStarted()
        synchronized(stateLock) {
            progressLedger.markDecodeCompleted(clipId, status)
        }
    }

    override fun markLoopCompleted(
        loopIndex: Int,
        monotonicStartNanos: Long,
        monotonicEndNanos: Long,
        successful: Boolean,
    ) {
        ensureStarted()
        synchronized(stateLock) {
            val expected = loops.size + 1
            if (
                loopIndex != expected ||
                monotonicEndNanos < monotonicStartNanos ||
                !successful
            ) {
                throw BenchmarkContractException(
                    "stability loop completion is invalid",
                )
            }
            progressLedger.markLoopCompleted(loopIndex)
            val committedEndNanos = maxOf(
                monotonicEndNanos,
                clock.nowNanos(),
            )
            loops += StabilityLoopProof(
                loopIndex = loopIndex,
                monotonicStartNanos = monotonicStartNanos,
                monotonicEndNanos = committedEndNanos,
                orderedClipIdsSha256 = CanonicalJson.sha256(orderedClipIds),
                clipCount = orderedClipIds.size,
                cumulativeDecodedClipCount = loopIndex * orderedClipIds.size,
                successful = true,
            )
        }
    }

    override fun finish(): StabilityProof {
        ensureStarted()
        executor.shutdown()
        if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
            throw BenchmarkContractException("stability telemetry sampler did not stop")
        }
        asynchronousFailure.get()?.let { throw it }
        val proof = synchronized(stateLock) {
            progressLedger.finish()
            val visibleLoopCount = samples.lastOrNull()?.completedLoops ?: 0
            val visibleLoops = loops.take(visibleLoopCount)
            StabilityProof(
                sampleIntervalNanos = policy.sampleIntervalNanos,
                samples = samples.toList(),
                loopProofs = visibleLoops,
                loopCount = visibleLoopCount,
                successfulLoopCount = visibleLoopCount,
                totalDecodedClipCount =
                    visibleLoopCount * orderedClipIds.size,
            )
        }
        StabilityProofValidator.validate(proof, orderedClipIds, policy)
        return proof
    }

    override fun close() {
        synchronized(stateLock) {
            progressLedger.finish()
        }
        executor.shutdownNow()
    }

    private fun sampleHeartbeat() {
        val resources = try {
            resourceProbe.sample()
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (_: Exception) {
            throw ContinuousStabilityResourceProbeException()
        }
        synchronized(stateLock) {
            val index = samples.size
            val progress = progressLedger.snapshot()
            samples += StabilitySample(
                monotonicNanos = clock.nowNanos(),
                rssBytes = resources.rssBytes,
                pssBytes = resources.pssBytes,
                thermalStatus = resources.thermalStatus,
                heartbeatIndex = index,
                runnerAlive = true,
                decoderActive = progress.decoderActive,
                activeClipId = progress.activeClipId,
                activeDecodeProgress = progress.activeDecodeProgress,
                completedClipCount = progress.completedClipCount,
                completedLoops = progress.completedLoops,
                completedClipsInCurrentLoop =
                    progress.completedClipsInCurrentLoop,
                successfulLoops = progress.successfulLoops,
                lastVerifiedClipId = progress.lastVerifiedClipId,
                swapBytes = resources.swapBytes,
            )
        }
    }

    private fun ensureStarted() {
        if (!started.get()) {
            throw BenchmarkContractException("stability collector is not started")
        }
    }
}
