package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ParaformerDownloadPolicyTest : FunSpec({

    test("space preflight requires unfinished payload plus 128 MiB") {
        ParaformerDownloadPolicy.requiredFreeBytes(
            expectedBytes = 223_461_591L,
            resumableBytes = 0L,
        ) shouldBe 357_679_319L
        ParaformerDownloadPolicy.requiredFreeBytes(
            expectedBytes = 223_461_591L,
            resumableBytes = 100_000_000L,
        ) shouldBe 257_679_319L
        ParaformerDownloadPolicy.requiredFreeBytes(
            expectedBytes = 223_461_591L,
            resumableBytes = Long.MAX_VALUE,
        ) shouldBe 134_217_728L
    }

    test("official falls back only for network timeout 408 429 and 5xx") {
        listOf(
            ParaformerAttemptFailure.Network,
            ParaformerAttemptFailure.Timeout,
            ParaformerAttemptFailure.Http(408),
            ParaformerAttemptFailure.Http(429),
            ParaformerAttemptFailure.Http(500),
            ParaformerAttemptFailure.Http(599),
        ).forEach { ParaformerDownloadPolicy.mayFallbackToMirror(it) shouldBe true }

        listOf(
            ParaformerAttemptFailure.Http(400),
            ParaformerAttemptFailure.Http(401),
            ParaformerAttemptFailure.Http(404),
            ParaformerAttemptFailure.Protocol,
            ParaformerAttemptFailure.Integrity,
            ParaformerAttemptFailure.Storage,
            ParaformerAttemptFailure.Cancelled,
        ).forEach { ParaformerDownloadPolicy.mayFallbackToMirror(it) shouldBe false }
    }

    test("fallback starts mirror from zero and integrity never attempts mirror") {
        val attempts = mutableListOf<Pair<ParaformerArtifactSource, Long>>()
        val result = ParaformerDownloadPolicy.runWithMirrorFallback(initialOffset = 17L) { source, offset ->
            attempts += source to offset
            if (source == ParaformerArtifactSource.Official) {
                throw ParaformerAttemptException(ParaformerAttemptFailure.Timeout)
            }
            "ok"
        }
        result shouldBe "ok"
        attempts shouldContainExactly listOf(
            ParaformerArtifactSource.Official to 17L,
            ParaformerArtifactSource.Mirror to 0L,
        )

        attempts.clear()
        shouldThrow<ParaformerAttemptException> {
            ParaformerDownloadPolicy.runWithMirrorFallback(initialOffset = 17L) { source, offset ->
                attempts += source to offset
                throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
            }
        }
        attempts shouldContainExactly listOf(ParaformerArtifactSource.Official to 17L)
    }

    test("an unusable mirror cannot reclassify the original official timeout as integrity") {
        val failure = shouldThrow<ParaformerAttemptException> {
            ParaformerDownloadPolicy.runWithMirrorFallback(initialOffset = 0L) { source, _ ->
                when (source) {
                    ParaformerArtifactSource.Official ->
                        throw ParaformerAttemptException(ParaformerAttemptFailure.Timeout)
                    ParaformerArtifactSource.Mirror ->
                        throw ParaformerAttemptException(ParaformerAttemptFailure.Protocol)
                }
            }
        }

        failure.failure shouldBe ParaformerAttemptFailure.Timeout
    }

    test("same-host resume requires exact 206 range length total and unchanged etag") {
        ParaformerDownloadPolicy.validateResumeResponse(
            offset = 100L,
            expectedTotal = 1_000L,
            statusCode = 206,
            contentLength = 900L,
            contentRange = "bytes 100-999/1000",
            expectedEtag = "\"frozen\"",
            responseEtag = "\"frozen\"",
        )

        listOf(
            ResumeResponse(200, 1_000L, null, "\"frozen\""),
            ResumeResponse(206, 899L, "bytes 100-998/1000", "\"frozen\""),
            ResumeResponse(206, 900L, "bytes 99-998/1000", "\"frozen\""),
            ResumeResponse(206, 900L, "bytes 100-999/1001", "\"frozen\""),
            ResumeResponse(206, 900L, "bytes 100-999/1000", "\"changed\""),
        ).forEach { response ->
            shouldThrow<ParaformerAttemptException> {
                ParaformerDownloadPolicy.validateResumeResponse(
                    offset = 100L,
                    expectedTotal = 1_000L,
                    statusCode = response.code,
                    contentLength = response.length,
                    contentRange = response.range,
                    expectedEtag = "\"frozen\"",
                    responseEtag = response.etag,
                )
            }.failure shouldBe ParaformerAttemptFailure.Protocol
        }
    }

    test("network policy defaults to wifi and pauses on a newly metered network") {
        ParaformerDownloadPolicy.networkDecision(
            connected = true,
            metered = false,
            mobileConfirmed = false,
        ) shouldBe ParaformerNetworkDecision.Allow
        ParaformerDownloadPolicy.networkDecision(
            connected = true,
            metered = true,
            mobileConfirmed = false,
        ) shouldBe ParaformerNetworkDecision.PauseMetered
        ParaformerDownloadPolicy.networkDecision(
            connected = true,
            metered = true,
            mobileConfirmed = true,
        ) shouldBe ParaformerNetworkDecision.Allow
        ParaformerDownloadPolicy.networkDecision(
            connected = false,
            metered = false,
            mobileConfirmed = true,
        ) shouldBe ParaformerNetworkDecision.PauseOffline
    }
})

private data class ResumeResponse(
    val code: Int,
    val length: Long,
    val range: String?,
    val etag: String?,
)
