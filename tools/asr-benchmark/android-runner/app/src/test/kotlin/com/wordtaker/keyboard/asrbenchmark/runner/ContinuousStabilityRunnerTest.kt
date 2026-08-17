package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.CanonicalJson
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.FileIdentity
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import com.wordtaker.keyboard.asrbenchmark.core.MonotonicClock
import com.wordtaker.keyboard.asrbenchmark.core.StabilityLoopProof
import com.wordtaker.keyboard.asrbenchmark.core.StabilityProof
import com.wordtaker.keyboard.asrbenchmark.core.StabilitySample
import com.wordtaker.keyboard.asrbenchmark.core.SyntheticWav
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousStabilityRunnerTest {
    @Test
    fun preparesOnceAndPublishesOnlyAggregateContinuousEvidence() {
        val clock = AdvancingClock()
        val telemetry = RecordingTelemetry(validProof(includeSwap = true))
        val adapter = RecordingAdapter(clock = clock, decodeAdvanceNanos = 6_000_000_000L)

        val result = ContinuousStabilityRunner(clock).run(
            duration = ContinuousStabilityDuration.TEN_SECONDS,
            cancellationPhase = ParaformerCancellationPhase.NONE,
            artifactsByRole = artifacts(),
            canonicalPcm = pcm(),
            adapter = adapter,
            telemetry = telemetry,
        )
        val document = result.document

        assertEquals(1, adapter.prepareCount)
        assertEquals(3, adapter.decodeCount)
        assertEquals(3L, document["success_count"])
        assertEquals(0L, document["failure_count"])
        assertEquals("ok", document["status"])
        assertEquals("none", document["failure_phase"])
        assertEquals("none", document["failure_code"])
        assertEquals(18_000_000_000L, document["duration_ns"])
        assertEquals(false, document["formal_eligible"])
        assertEquals(false, document["product_decision_eligible"])
        assertEquals(1, telemetry.startCount)
        assertEquals(1, telemetry.finishCount)
        assertEquals(0, telemetry.closeCount)
        assertEquals(3, telemetry.completedLoops)
        assertTrue(adapter.decodedPcm.all { it.contentEquals(adapter.decodedPcm.first()) })
        assertFalse(deepStrings(document).contains(RecordingAdapter.PRIVATE_TRANSCRIPT))
        assertFalse(deepStrings(document).any { it.contains("/private/") })
        assertEquals(64, (document["output_sha256"] as String).length)
        assertNotEquals("0".repeat(64), document["output_sha256"])

        @Suppress("UNCHECKED_CAST")
        val stop = document["stop_to_final"] as Map<String, Any>
        assertEquals(3L, stop["count"])
        assertEquals(6_000_000_000L, stop["minimum_ns"])
        assertEquals(6_000_000_000L, stop["maximum_ns"])
        assertEquals(6_000_000_000L, stop["mean_ns"])
        @Suppress("UNCHECKED_CAST")
        val resources = document["resources"] as Map<String, Any>
        assertTrue(resources.containsKey("swap_bytes"))
        assertEquals(
            setOf(
                "schema_version",
                "status",
                "failure_phase",
                "failure_code",
                "success_count",
                "failure_count",
                "duration_ns",
                "stop_to_final",
                "resources",
                "heartbeat_proof",
                "loop_proof",
                "output_sha256",
                "formal_eligible",
                "product_decision_eligible",
            ),
            document.keys,
        )
    }

    @Test
    fun unavailableSwapIsOmittedInsteadOfInvented() {
        val clock = AdvancingClock()
        val result = ContinuousStabilityRunner(clock).run(
            duration = ContinuousStabilityDuration.TEN_SECONDS,
            cancellationPhase = ParaformerCancellationPhase.NONE,
            artifactsByRole = artifacts(),
            canonicalPcm = pcm(),
            adapter = RecordingAdapter(clock, 6_000_000_000L),
            telemetry = RecordingTelemetry(validProof(includeSwap = false)),
        )

        @Suppress("UNCHECKED_CAST")
        val resources = result.document["resources"] as Map<String, Any>
        assertFalse(resources.containsKey("swap_bytes"))
    }

    @Test
    fun slowSecondLoopRunsUntilAHeartbeatCanObserveBothCompleteLoops() {
        val clock = AdvancingClock()
        val telemetry = RecordingTelemetry(validProof(includeSwap = false))

        val result = ContinuousStabilityRunner(clock).run(
            duration = ContinuousStabilityDuration.TEN_SECONDS,
            cancellationPhase = ParaformerCancellationPhase.NONE,
            artifactsByRole = artifacts(),
            canonicalPcm = pcm(),
            adapter = RecordingAdapter(clock, 11_000_000_000L),
            telemetry = telemetry,
        )

        assertEquals("ok", result.document["status"])
        assertEquals(3L, result.document["success_count"])
        assertEquals(3, telemetry.completedLoops)
        assertEquals(33_000_000_000L, result.document["duration_ns"])
    }

    @Test
    fun emptyErrorExceptionAndOomResultsFailClosedWithoutOutputDisclosure() {
        val outcomes = listOf(
            Triple(
                RecordingAdapter(
                    result = DecoderResult("", DecoderStatus.OK, null),
                ),
                "output",
                "empty_output",
            ),
            Triple(
                RecordingAdapter(
                    result = DecoderResult("", DecoderStatus.ERROR, "decode_error"),
                ),
                "output",
                "decode_error",
            ),
            Triple(
                RecordingAdapter(
                    decodeFailure = IllegalStateException("/private/model"),
                ),
                "decode",
                "runtime_error",
            ),
            Triple(
                RecordingAdapter(decodeOom = true),
                "decode",
                "oom",
            ),
        )

        outcomes.forEach { (adapter, expectedPhase, expectedCode) ->
            val telemetry = RecordingTelemetry(validProof(includeSwap = false))
            val result = ContinuousStabilityRunner(AdvancingClock()).run(
                duration = ContinuousStabilityDuration.TEN_SECONDS,
                cancellationPhase = ParaformerCancellationPhase.NONE,
                artifactsByRole = artifacts(),
                canonicalPcm = pcm(),
                adapter = adapter,
                telemetry = telemetry,
            )

            assertEquals("error", result.document["status"])
            assertEquals(expectedPhase, result.document["failure_phase"])
            assertEquals(expectedCode, result.document["failure_code"])
            assertEquals(0L, result.document["success_count"])
            assertEquals(1L, result.document["failure_count"])
            assertEquals(1, telemetry.closeCount)
            assertEquals(0, telemetry.finishCount)
            assertFalse(deepStrings(result.document).any { it.contains("/private/") })
        }
    }

    @Test
    fun prepareFailureIsReportedWithoutStartingTelemetry() {
        val telemetry = RecordingTelemetry(validProof(includeSwap = false))
        val adapter = RecordingAdapter(
            prepareFailure = IllegalStateException("secret model path"),
        )

        val result = ContinuousStabilityRunner(AdvancingClock()).run(
            duration = ContinuousStabilityDuration.TEN_SECONDS,
            cancellationPhase = ParaformerCancellationPhase.NONE,
            artifactsByRole = artifacts(),
            canonicalPcm = pcm(),
            adapter = adapter,
            telemetry = telemetry,
        )

        assertEquals("error", result.document["status"])
        assertEquals("prepare", result.document["failure_phase"])
        assertEquals("runtime_error", result.document["failure_code"])
        assertEquals(1, adapter.prepareCount)
        assertEquals(0, adapter.decodeCount)
        assertEquals(0, telemetry.startCount)
        assertEquals(1, telemetry.closeCount)
        assertFalse(deepStrings(result.document).contains("secret model path"))
    }

    @Test
    fun telemetryFinishProofValidationAndResourceProbeFailuresAreDistinct() {
        val failures = listOf(
            Triple(
                RecordingTelemetry(
                    validProof(includeSwap = false),
                    finishFailure = IllegalStateException("private telemetry detail"),
                ),
                "telemetry_finish",
                "telemetry_error",
            ),
            Triple(
                RecordingTelemetry(
                    validProof(includeSwap = false).copy(samples = emptyList()),
                ),
                "proof_validation",
                "proof_error",
            ),
            Triple(
                RecordingTelemetry(
                    validProof(includeSwap = false),
                    finishFailure = ContinuousStabilityResourceProbeException(),
                ),
                "resource_probe",
                "resource_probe_error",
            ),
        )

        failures.forEach { (telemetry, expectedPhase, expectedCode) ->
            val clock = AdvancingClock()
            val result = ContinuousStabilityRunner(clock).run(
                duration = ContinuousStabilityDuration.TEN_SECONDS,
                cancellationPhase = ParaformerCancellationPhase.NONE,
                artifactsByRole = artifacts(),
                canonicalPcm = pcm(),
                adapter = RecordingAdapter(
                    clock = clock,
                    decodeAdvanceNanos = 6_000_000_000L,
                ),
                telemetry = telemetry,
            )

            assertEquals("error", result.document["status"])
            assertEquals(expectedPhase, result.document["failure_phase"])
            assertEquals(expectedCode, result.document["failure_code"])
            assertFalse(
                deepStrings(result.document).any {
                    it.contains("private telemetry detail") || it.contains("/private/")
                },
            )
        }
    }

    @Test
    fun everyCancellationPhaseStopsWithoutClaimingStableSuccess() {
        ParaformerCancellationPhase.entries
            .filterNot { it == ParaformerCancellationPhase.NONE }
            .forEach { phase ->
                val clock = AdvancingClock()
                val adapter = RecordingAdapter(
                    clock = clock,
                    decodeAdvanceNanos = 1L,
                    waitForCancellation = phase == ParaformerCancellationPhase.DURING,
                )
                val telemetry = RecordingTelemetry(validProof(includeSwap = false))

                val result = ContinuousStabilityRunner(clock).run(
                    duration = ContinuousStabilityDuration.TEN_SECONDS,
                    cancellationPhase = phase,
                    artifactsByRole = artifacts(),
                    canonicalPcm = pcm(),
                    adapter = adapter,
                    telemetry = telemetry,
                )

                assertEquals("cancelled", result.document["status"])
                assertEquals("none", result.document["failure_phase"])
                assertEquals("cancelled", result.document["failure_code"])
                assertEquals(1, adapter.prepareCount)
                assertEquals(1, adapter.cancelCount)
                assertEquals(0, telemetry.finishCount)
                assertFalse(result.document["formal_eligible"] as Boolean)
            }
    }

    private fun artifacts(): Map<String, MeasuredFile> = mapOf(
        "weights" to measured("weights", byteArrayOf(1, 2, 3)),
    )

    private fun pcm(): MeasuredFile = measured(
        "pcm",
        SyntheticWav.pcm16Mono(16_000, 160),
        requireCanonicalPcm = true,
    )

    private fun measured(
        role: String,
        bytes: ByteArray,
        requireCanonicalPcm: Boolean = false,
    ): MeasuredFile = MeasuredFile.fromBytes(
        pathToken = role,
        bytes = bytes,
        identity = FileIdentity(
            device = 1,
            inode = role.hashCode().toLong().let { if (it < 0) -it else it } + 1L,
            sizeBytes = bytes.size.toLong(),
            mode = 0x8000,
            linkCount = 1,
        ),
        requireCanonicalPcm = requireCanonicalPcm,
    )

    private fun validProof(includeSwap: Boolean): StabilityProof {
        val clipIds = listOf(ContinuousStabilityRunner.CLIP_ID)
        val orderSha = CanonicalJson.sha256(clipIds)
        val loops = listOf(
            StabilityLoopProof(1, 0L, 6_000_000_000L, orderSha, 1, 1, true),
            StabilityLoopProof(
                2,
                6_000_000_000L,
                12_000_000_000L,
                orderSha,
                1,
                2,
                true,
            ),
        )
        val samples = listOf(
            sample(0, 0L, 0, 0, false, includeSwap),
            sample(1, 5_000_000_000L, 0, 0, true, includeSwap),
            sample(2, 10_000_000_000L, 1, 1, true, includeSwap),
            sample(3, 15_000_000_000L, 2, 2, true, includeSwap),
        )
        return StabilityProof(
            sampleIntervalNanos = 5_000_000_000L,
            samples = samples,
            loopProofs = loops,
            loopCount = 2,
            successfulLoopCount = 2,
            totalDecodedClipCount = 2,
        )
    }

    private fun sample(
        index: Int,
        nanos: Long,
        completedLoops: Int,
        progress: Long,
        active: Boolean,
        includeSwap: Boolean,
    ) = StabilitySample(
        monotonicNanos = nanos,
        rssBytes = 100L + index,
        pssBytes = 90L + index,
        thermalStatus = 0,
        heartbeatIndex = index,
        runnerAlive = true,
        decoderActive = active,
        activeClipId = if (active) ContinuousStabilityRunner.CLIP_ID else null,
        activeDecodeProgress = progress,
        completedClipCount = progress.toInt(),
        completedLoops = completedLoops,
        completedClipsInCurrentLoop = 0,
        successfulLoops = completedLoops,
        lastVerifiedClipId = if (progress == 0L) null else ContinuousStabilityRunner.CLIP_ID,
        swapBytes = if (includeSwap) 10L + index else null,
    )

    private fun deepStrings(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is Map<*, *> -> value.entries.flatMap {
            deepStrings(it.key) + deepStrings(it.value)
        }
        is Iterable<*> -> value.flatMap(::deepStrings)
        else -> emptyList()
    }
}

private class AdvancingClock : MonotonicClock {
    private var value = 0L

    @Synchronized
    override fun nowNanos(): Long = value

    @Synchronized
    fun advance(nanos: Long) {
        value = Math.addExact(value, nanos)
    }
}

private class RecordingTelemetry(
    private val proof: StabilityProof,
    private val finishFailure: Throwable? = null,
) : ContinuousStabilityTelemetry {
    var startCount = 0
    var finishCount = 0
    var closeCount = 0
    var completedLoops = 0

    override fun start() {
        startCount += 1
    }

    override fun markDecodeStarted(clipId: String) = Unit

    override fun markDecodeCompleted(clipId: String, status: DecoderStatus) = Unit

    override fun markLoopCompleted(
        loopIndex: Int,
        monotonicStartNanos: Long,
        monotonicEndNanos: Long,
        successful: Boolean,
    ) {
        completedLoops += 1
    }

    override fun finish(): StabilityProof {
        finishCount += 1
        finishFailure?.let { throw it }
        return proof
    }

    override fun close() {
        closeCount += 1
    }
}

private class RecordingAdapter(
    private val clock: AdvancingClock? = null,
    private val decodeAdvanceNanos: Long = 0L,
    private val result: DecoderResult = DecoderResult(
        PRIVATE_TRANSCRIPT,
        DecoderStatus.OK,
        null,
    ),
    private val prepareFailure: Exception? = null,
    private val decodeFailure: Exception? = null,
    private val decodeOom: Boolean = false,
    private val waitForCancellation: Boolean = false,
) : CancellablePreparedDecoderAdapter {
    var prepareCount = 0
    var decodeCount = 0
    var cancelCount = 0
    val decodedPcm = mutableListOf<ByteArray>()
    private val cancelled = CountDownLatch(1)

    override fun prepare(artifactsByRole: Map<String, MeasuredFile>) {
        prepareCount += 1
        prepareFailure?.let { throw it }
    }

    override fun decode(canonicalPcmWav: ByteArray): DecoderResult {
        decodeCount += 1
        decodedPcm += canonicalPcmWav.copyOf()
        if (waitForCancellation) {
            check(cancelled.await(5, TimeUnit.SECONDS))
        }
        clock?.advance(decodeAdvanceNanos)
        if (decodeOom) throw OutOfMemoryError("synthetic")
        decodeFailure?.let { throw it }
        return result
    }

    override fun cancel() {
        cancelCount += 1
        cancelled.countDown()
    }

    override fun close() = Unit

    companion object {
        const val PRIVATE_TRANSCRIPT = "private-transcript-must-not-escape"
    }
}
