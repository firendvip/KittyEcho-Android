package com.wordtaker.keyboard.wordtaker.speech

import java.io.IOException

internal enum class ParaformerArtifactSource {
    Official,
    Mirror,
}

internal enum class ParaformerFailureStage {
    Transport,
    Redirect,
    ResponseContract,
}

internal enum class ParaformerFinalHostClass {
    Official,
    Mirror,
    OtherRejected,
}

internal enum class ParaformerHttpFailureReason {
    Timeout,
    Network,
    HttpStatus,
    MissingBody,
    FreshStatusMismatch,
    FreshLengthMismatch,
    FreshEtagMissing,
    UnexpectedRedirectHost,
}

internal data class ParaformerHttpDiagnostic(
    val source: ParaformerArtifactSource,
    val failureStage: ParaformerFailureStage,
    val finalHostClass: ParaformerFinalHostClass,
    val reason: ParaformerHttpFailureReason,
    val status: Int? = null,
    val length: Long? = null,
    val etagPresent: Boolean? = null,
) {
    fun safeSummary(): String = buildList {
        add("source=$source")
        add("failureStage=$failureStage")
        add("finalHostClass=$finalHostClass")
        add("reason=$reason")
        status?.let { add("status=$it") }
        length?.let { add("length=$it") }
        etagPresent?.let { add("etagPresent=$it") }
    }.joinToString(" ")
}

internal sealed interface ParaformerAttemptFailure {
    data object Network : ParaformerAttemptFailure
    data object Timeout : ParaformerAttemptFailure
    data class Http(val statusCode: Int) : ParaformerAttemptFailure
    data object Protocol : ParaformerAttemptFailure
    data object Integrity : ParaformerAttemptFailure
    data object Storage : ParaformerAttemptFailure
    data object Cancelled : ParaformerAttemptFailure
}

internal class ParaformerAttemptException(
    val failure: ParaformerAttemptFailure,
    cause: Throwable? = null,
    val diagnostic: ParaformerHttpDiagnostic? = null,
) : IOException(
    diagnostic?.safeSummary() ?: "Paraformer artifact attempt failed: $failure",
    cause,
) {
    fun safeDiagnosticSummary(): String? = diagnostic?.safeSummary()
}

internal enum class ParaformerNetworkDecision {
    Allow,
    PauseOffline,
    PauseMetered,
}

internal object ParaformerDownloadPolicy {
    const val FREE_SPACE_RESERVE_BYTES = 128L * 1024L * 1024L
    const val MAX_SAME_HOST_FRESH_RETRIES = 1

    fun requiredFreeBytes(expectedBytes: Long, resumableBytes: Long): Long {
        val safeExpected = expectedBytes.coerceAtLeast(0L)
        val reusable = resumableBytes.coerceIn(0L, safeExpected)
        val remaining = safeExpected - reusable
        return if (remaining > Long.MAX_VALUE - FREE_SPACE_RESERVE_BYTES) {
            Long.MAX_VALUE
        } else {
            remaining + FREE_SPACE_RESERVE_BYTES
        }
    }

    fun mayFallbackToMirror(failure: ParaformerAttemptFailure): Boolean = when (failure) {
        ParaformerAttemptFailure.Network,
        ParaformerAttemptFailure.Timeout,
        -> true
        is ParaformerAttemptFailure.Http ->
            failure.statusCode == 408 ||
                failure.statusCode == 429 ||
                failure.statusCode in 500..599
        ParaformerAttemptFailure.Protocol,
        ParaformerAttemptFailure.Integrity,
        ParaformerAttemptFailure.Storage,
        ParaformerAttemptFailure.Cancelled,
        -> false
    }

    fun <T> runWithMirrorFallback(
        initialOffset: Long,
        attempt: (ParaformerArtifactSource, Long) -> T,
    ): T {
        return try {
            attempt(ParaformerArtifactSource.Official, initialOffset.coerceAtLeast(0L))
        } catch (error: ParaformerAttemptException) {
            if (!mayFallbackToMirror(error.failure)) throw error
            // A partial response is scoped to one origin and one ETag. Crossing hosts always
            // starts from byte zero even when both origins are pinned to the same digest.
            try {
                attempt(ParaformerArtifactSource.Mirror, 0L)
            } catch (mirrorError: ParaformerAttemptException) {
                if (mirrorError.failure == ParaformerAttemptFailure.Protocol) {
                    error.addSuppressed(mirrorError)
                    throw error
                }
                throw mirrorError
            }
        }
    }

    fun <T> runResumeWithSingleFreshRetry(
        initialOffset: Long,
        resetPartial: () -> Unit,
        attempt: (Long) -> T,
    ): T {
        val resumeOffset = initialOffset.coerceAtLeast(0L)
        if (resumeOffset == 0L) return attempt(0L)
        return try {
            attempt(resumeOffset)
        } catch (error: ParaformerAttemptException) {
            if (error.failure != ParaformerAttemptFailure.Protocol) throw error
            resetPartial()
            attempt(0L)
        }
    }

    fun validateResumeResponse(
        offset: Long,
        expectedTotal: Long,
        statusCode: Int,
        contentLength: Long,
        contentRange: String?,
        expectedEtag: String,
        responseEtag: String?,
    ) {
        val match = contentRange?.let(CONTENT_RANGE::matchEntire)
        val start = match?.groupValues?.getOrNull(1)?.toLongOrNull()
        val end = match?.groupValues?.getOrNull(2)?.toLongOrNull()
        val total = match?.groupValues?.getOrNull(3)?.toLongOrNull()
        val expectedRemaining = expectedTotal - offset
        if (
            offset <= 0L ||
            expectedTotal <= offset ||
            statusCode != 206 ||
            contentLength != expectedRemaining ||
            start != offset ||
            end != expectedTotal - 1L ||
            total != expectedTotal ||
            expectedEtag.isBlank() ||
            responseEtag != expectedEtag
        ) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Protocol)
        }
    }

    fun networkDecision(
        connected: Boolean,
        metered: Boolean,
        mobileConfirmed: Boolean,
    ): ParaformerNetworkDecision = when {
        !connected -> ParaformerNetworkDecision.PauseOffline
        metered && !mobileConfirmed -> ParaformerNetworkDecision.PauseMetered
        else -> ParaformerNetworkDecision.Allow
    }

    private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
}
