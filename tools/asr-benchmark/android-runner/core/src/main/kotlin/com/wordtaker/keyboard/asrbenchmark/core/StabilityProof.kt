package com.wordtaker.keyboard.asrbenchmark.core

import kotlin.math.abs

data class StabilityPolicy(
    val sampleIntervalNanos: Long,
    val minimumDurationNanos: Long,
    val minimumCompleteLoops: Int,
    val absoluteJitterNanos: Long,
    val relativeJitterPercent: Int,
) {
    init {
        if (
            sampleIntervalNanos <= 0 ||
            minimumDurationNanos <= 0 ||
            minimumCompleteLoops < 2 ||
            absoluteJitterNanos < 0 ||
            relativeJitterPercent !in 0..100
        ) {
            throw BenchmarkContractException("stability policy is invalid")
        }
    }

    companion object {
        fun formal(): StabilityPolicy = StabilityPolicy(
            sampleIntervalNanos = 5_000_000_000L,
            minimumDurationNanos = 600_000_000_000L,
            minimumCompleteLoops = 2,
            absoluteJitterNanos = 250_000_000L,
            relativeJitterPercent = 5,
        )

        fun development(minimumDurationNanos: Long): StabilityPolicy = StabilityPolicy(
            sampleIntervalNanos = 5_000_000_000L,
            minimumDurationNanos = minimumDurationNanos,
            minimumCompleteLoops = 2,
            absoluteJitterNanos = 250_000_000L,
            relativeJitterPercent = 5,
        )

        /**
         * Fixed development-emulator scheduling tolerance. It is isolated
         * from formal stability, which retains the stricter 250ms/5% bound.
         */
        fun developmentEmulator600(): StabilityPolicy = StabilityPolicy(
            sampleIntervalNanos = 5_000_000_000L,
            minimumDurationNanos = 600_000_000_000L,
            minimumCompleteLoops = 2,
            absoluteJitterNanos = 1_250_000_000L,
            relativeJitterPercent = 25,
        )
    }
}

data class StabilitySample(
    val monotonicNanos: Long,
    val rssBytes: Long,
    val pssBytes: Long,
    val thermalStatus: Int,
    val heartbeatIndex: Int,
    val runnerAlive: Boolean,
    val decoderActive: Boolean,
    val activeClipId: String?,
    val activeDecodeProgress: Long,
    val completedClipCount: Int,
    val completedLoops: Int,
    val completedClipsInCurrentLoop: Int,
    val successfulLoops: Int,
    val lastVerifiedClipId: String?,
    val swapBytes: Long? = null,
)

data class StabilityLoopProof(
    val loopIndex: Int,
    val monotonicStartNanos: Long,
    val monotonicEndNanos: Long,
    val orderedClipIdsSha256: String,
    val clipCount: Int,
    val cumulativeDecodedClipCount: Int,
    val successful: Boolean,
)

data class StabilityProof(
    val sampleIntervalNanos: Long,
    val samples: List<StabilitySample>,
    val loopProofs: List<StabilityLoopProof>,
    val loopCount: Int,
    val successfulLoopCount: Int,
    val totalDecodedClipCount: Int,
)

data class StabilityProgressSnapshot(
    val decoderActive: Boolean,
    val activeClipId: String?,
    val activeDecodeProgress: Long,
    val completedClipCount: Int,
    val completedLoops: Int,
    val completedClipsInCurrentLoop: Int,
    val successfulLoops: Int,
    val lastVerifiedClipId: String?,
)

/**
 * Runner-owned activity ledger. Sampling this object is read-only: only a
 * decode that returned and passed the runner's input/artifact revalidation can
 * advance activeDecodeProgress.
 */
class StabilityProgressLedger(
    private val orderedClipIds: List<String>,
) {
    private var activeClipId: String? = null
    private var activeDecodeProgress = 0L
    private var completedLoops = 0
    private var completedClipsInCurrentLoop = 0
    private var successfulLoops = 0
    private var lastVerifiedClipId: String? = null

    init {
        if (
            orderedClipIds.isEmpty() ||
            orderedClipIds.distinct().size != orderedClipIds.size
        ) {
            throw BenchmarkContractException(
                "stability progress requires a unique frozen clip order",
            )
        }
    }

    fun markDecodeStarted(clipId: String) {
        if (
            activeClipId != null ||
            completedClipsInCurrentLoop >= orderedClipIds.size
        ) {
            throw BenchmarkContractException(
                "stability decode start overlaps an active or uncommitted loop",
            )
        }
        val expectedClip = orderedClipIds[completedClipsInCurrentLoop]
        if (clipId != expectedClip) {
            throw BenchmarkContractException(
                "stability decode start differs from frozen clip order",
            )
        }
        activeClipId = clipId
    }

    fun markDecodeCompleted(
        clipId: String,
        status: DecoderStatus,
    ) {
        if (activeClipId != clipId || status != DecoderStatus.OK) {
            throw BenchmarkContractException(
                "only an active successful runner decode may advance progress",
            )
        }
        activeClipId = null
        activeDecodeProgress = Math.addExact(activeDecodeProgress, 1L)
        completedClipsInCurrentLoop += 1
        lastVerifiedClipId = clipId
    }

    fun markLoopCompleted(loopIndex: Int) {
        val expectedLoop = completedLoops + 1
        val expectedProgress = loopIndex.toLong() * orderedClipIds.size
        if (
            loopIndex != expectedLoop ||
            activeDecodeProgress != expectedProgress ||
            completedClipsInCurrentLoop != orderedClipIds.size ||
            activeClipId != null
        ) {
            throw BenchmarkContractException(
                "stability loop lacks its exact verified decode iterations",
            )
        }
        completedLoops = loopIndex
        completedClipsInCurrentLoop = 0
        successfulLoops = loopIndex
    }

    fun snapshot(): StabilityProgressSnapshot = StabilityProgressSnapshot(
        decoderActive = activeClipId != null,
        activeClipId = activeClipId,
        activeDecodeProgress = activeDecodeProgress,
        completedClipCount = Math.toIntExact(activeDecodeProgress),
        completedLoops = completedLoops,
        completedClipsInCurrentLoop = completedClipsInCurrentLoop,
        successfulLoops = successfulLoops,
        lastVerifiedClipId = lastVerifiedClipId,
    )

    fun finish() {
        activeClipId = null
    }
}

class StabilityProofValidationException(message: String) :
    BenchmarkContractException(message)

object StabilityProofValidator {
    fun validate(
        proof: StabilityProof,
        orderedClipIds: List<String>,
        policy: StabilityPolicy,
    ): Boolean {
        if (orderedClipIds.isEmpty() || orderedClipIds.distinct().size != orderedClipIds.size) {
            invalid("stability clip order is empty or duplicated")
        }
        if (proof.sampleIntervalNanos != policy.sampleIntervalNanos) {
            invalid("stability sampling interval differs from policy")
        }
        if (proof.samples.size < 2) {
            invalid("stability proof needs at least two heartbeats")
        }
        if (
            proof.loopProofs.size < policy.minimumCompleteLoops ||
            proof.loopCount != proof.loopProofs.size ||
            proof.successfulLoopCount != proof.loopProofs.size
        ) {
            invalid("stability proof lacks complete successful loops")
        }
        val orderSha256 = CanonicalJson.sha256(orderedClipIds)
        var previousLoopEnd = -1L
        proof.loopProofs.forEachIndexed { index, loop ->
            val expectedIndex = index + 1
            if (
                loop.loopIndex != expectedIndex ||
                loop.monotonicStartNanos < previousLoopEnd ||
                loop.monotonicEndNanos < loop.monotonicStartNanos ||
                loop.orderedClipIdsSha256 != orderSha256 ||
                loop.clipCount != orderedClipIds.size ||
                loop.cumulativeDecodedClipCount != expectedIndex * orderedClipIds.size ||
                !loop.successful
            ) {
                invalid("stability loop does not cover frozen clip order")
            }
            previousLoopEnd = loop.monotonicEndNanos
        }
        val allowedJitter = minOf(
            policy.absoluteJitterNanos,
            policy.sampleIntervalNanos * policy.relativeJitterPercent / 100,
        )
        var previousTimestamp: Long? = null
        var previousProgress = -1L
        var previousCompletedClips = -1
        var previousCompletedLoops = -1
        proof.samples.forEachIndexed { index, sample ->
            val priorTimestamp = previousTimestamp
            if (priorTimestamp != null) {
                val delta = sample.monotonicNanos - priorTimestamp
                if (
                    delta <= 0 ||
                    abs(delta - policy.sampleIntervalNanos) > allowedJitter
                ) {
                    invalid(
                        "stability heartbeat exceeds policy-bounded jitter",
                    )
                }
            }
            if (
                sample.monotonicNanos < 0 ||
                sample.rssBytes < 0 ||
                sample.pssBytes < 0 ||
                sample.swapBytes?.let { it < 0L } == true ||
                sample.thermalStatus !in 0..6 ||
                sample.heartbeatIndex != index ||
                !sample.runnerAlive ||
                sample.activeDecodeProgress < previousProgress ||
                sample.completedClipCount < previousCompletedClips ||
                sample.completedLoops < previousCompletedLoops ||
                sample.successfulLoops != sample.completedLoops
            ) {
                invalid(
                    "stability heartbeat is idle, missing, or non-monotonic",
                )
            }
            if (
                sample.completedClipsInCurrentLoop !in
                0..orderedClipIds.size
            ) {
                invalid(
                    "stability current-loop clip count is outside the frozen order",
                )
            }
            val derivedProgress = Math.addExact(
                Math.multiplyExact(
                    sample.completedLoops.toLong(),
                    orderedClipIds.size.toLong(),
                ),
                sample.completedClipsInCurrentLoop.toLong(),
            )
            if (
                sample.activeDecodeProgress != derivedProgress ||
                sample.completedClipCount.toLong() != derivedProgress
            ) {
                invalid(
                    "stability progress is not uniquely derived from loop and clip state",
                )
            }
            val expectedLastClip = if (derivedProgress == 0L) {
                null
            } else {
                orderedClipIds[
                    ((derivedProgress - 1L) % orderedClipIds.size).toInt()
                ]
            }
            if (sample.lastVerifiedClipId != expectedLastClip) {
                invalid(
                    "stability last verified clip differs from frozen order",
                )
            }
            val expectedActiveClip = if (
                sample.decoderActive &&
                sample.completedClipsInCurrentLoop < orderedClipIds.size
            ) {
                orderedClipIds[sample.completedClipsInCurrentLoop]
            } else {
                null
            }
            if (
                sample.activeClipId != expectedActiveClip ||
                (
                    sample.decoderActive &&
                    sample.completedClipsInCurrentLoop == orderedClipIds.size
                    )
            ) {
                invalid(
                    "stability active clip differs from the next frozen-order clip",
                )
            }
            if (
                index == 0 &&
                (
                    sample.decoderActive ||
                    sample.activeClipId != null ||
                    sample.activeDecodeProgress != 0L ||
                    sample.completedClipCount != 0 ||
                    sample.completedLoops != 0 ||
                    sample.completedClipsInCurrentLoop != 0 ||
                    sample.successfulLoops != 0 ||
                    sample.lastVerifiedClipId != null
                    )
            ) {
                invalid(
                    "first stability heartbeat must precede every decode",
                )
            }
            if (
                index > 0 &&
                !sample.decoderActive &&
                sample.activeDecodeProgress == previousProgress
            ) {
                invalid(
                    "stability heartbeat is idle without verified decode progress",
                )
            }
            val completeAtSample = proof.loopProofs.count {
                it.monotonicEndNanos <= sample.monotonicNanos
            }
            if (
                sample.completedLoops != completeAtSample ||
                sample.activeDecodeProgress <
                completeAtSample.toLong() * orderedClipIds.size
            ) {
                invalid(
                    "stability heartbeat counters differ from loop proofs",
                )
            }
            previousTimestamp = sample.monotonicNanos
            previousProgress = sample.activeDecodeProgress
            previousCompletedClips = sample.completedClipCount
            previousCompletedLoops = sample.completedLoops
        }
        val duration = proof.samples.last().monotonicNanos -
            proof.samples.first().monotonicNanos
        if (duration < policy.minimumDurationNanos) {
            invalid("stability duration is below the frozen minimum")
        }
        val expectedDecoded = proof.loopProofs.size * orderedClipIds.size
        if (
            proof.totalDecodedClipCount != expectedDecoded ||
            previousCompletedLoops != proof.loopProofs.size ||
            previousProgress < expectedDecoded ||
            previousProgress > expectedDecoded + orderedClipIds.size ||
            previousCompletedClips.toLong() != previousProgress
        ) {
            invalid("stability final counters are inconsistent")
        }
        return true
    }

    private fun invalid(message: String): Nothing =
        throw StabilityProofValidationException(message)
}
