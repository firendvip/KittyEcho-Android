package com.wordtaker.keyboard.wordtaker.speech

import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal object ParaformerDownloadRequestFactory {
    fun create(
        url: HttpUrl,
        offset: Long,
        etag: String?,
        allowHttpForTest: Boolean = false,
    ): Request {
        require(offset >= 0L)
        require(allowHttpForTest || url.isHttps) { "Paraformer artifacts require HTTPS" }
        val builder = Request.Builder()
            .url(url)
            .get()
            // Prevent transparent content coding from changing the frozen byte contract.
            .header("Accept-Encoding", "identity")
        if (allowHttpForTest) {
            builder.tag(
                ParaformerHttpTestOverride::class.java,
                ParaformerHttpTestOverride,
            )
        }
        if (offset > 0L) {
            require(!etag.isNullOrBlank()) { "A resumable request requires its frozen ETag" }
            builder.header("Range", "bytes=$offset-")
            builder.header("If-Range", etag)
        }
        return builder.build()
    }
}

internal data class ParaformerTransferResult(
    val etag: String,
    val transferredBytes: Long,
)

private data object ParaformerHttpTestOverride

/** Stateless public GET transport. It never receives account, device, PCM, or transcript data. */
internal class ParaformerHttpTransfer(
    private val client: OkHttpClient,
) {
    fun transfer(
        request: Request,
        expectedTotal: Long,
        offset: Long,
        expectedEtag: String?,
        output: OutputStream,
        onProgress: (Long) -> Unit = {},
        onResponseMetadata: (String) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): ParaformerTransferResult {
        check(request.method == "GET" && request.body == null)
        check(request.header("Authorization") == null)
        check(request.header("Cookie") == null)
        check(request.header("X-Device-Id") == null)
        check(request.header("X-Transcript") == null)
        val source = sourceFor(request)
        val allowHttpForTest = request.tag(ParaformerHttpTestOverride::class.java) != null
        try {
            executeFollowingAllowedRedirects(
                request = request,
                source = source,
                allowHttpForTest = allowHttpForTest,
            ).use { response ->
                val finalHostClass = if (allowHttpForTest) {
                    finalHostClass(source)
                } else {
                    validateResponseChain(source, request, response)
                }
                val code = response.code
                val body = response.body
                val contentLength = body?.contentLength() ?: -1L
                val responseEtag = response.header("ETag")
                if (offset > 0L) {
                    ParaformerDownloadPolicy.validateResumeResponse(
                        offset = offset,
                        expectedTotal = expectedTotal,
                        statusCode = code,
                        contentLength = contentLength,
                        contentRange = response.header("Content-Range"),
                        expectedEtag = requireNotNull(expectedEtag),
                        responseEtag = responseEtag,
                    )
                } else if (code !in 200..299) {
                    throw ParaformerAttemptException(
                        failure = ParaformerAttemptFailure.Http(code),
                        diagnostic = responseDiagnostic(
                            source = source,
                            finalHostClass = finalHostClass,
                            reason = ParaformerHttpFailureReason.HttpStatus,
                            status = code,
                        ),
                    )
                }
                if (body == null) {
                    throw ParaformerAttemptException(
                        failure = ParaformerAttemptFailure.Protocol,
                        diagnostic = responseDiagnostic(
                            source = source,
                            finalHostClass = finalHostClass,
                            reason = ParaformerHttpFailureReason.MissingBody,
                            status = code,
                            etagPresent = !response.header("ETag").isNullOrBlank(),
                        ),
                    )
                }
                if (offset == 0L) {
                    val reason = when {
                        code != 200 -> ParaformerHttpFailureReason.FreshStatusMismatch
                        responseEtag.isNullOrBlank() -> ParaformerHttpFailureReason.FreshEtagMissing
                        else -> null
                    }
                    if (reason != null) {
                        throw ParaformerAttemptException(
                            failure = ParaformerAttemptFailure.Protocol,
                            diagnostic = responseDiagnostic(
                                source = source,
                                finalHostClass = finalHostClass,
                                reason = reason,
                                status = code,
                                length = contentLength,
                                etagPresent = !responseEtag.isNullOrBlank(),
                            ),
                        )
                    }
                }
                val frozenEtag = requireNotNull(responseEtag)
                onResponseMetadata(frozenEtag)

                val expectedTransfer = expectedTotal - offset
                var transferred = 0L
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (shouldCancel()) {
                            throw ParaformerAttemptException(ParaformerAttemptFailure.Cancelled)
                        }
                        val remaining = expectedTransfer - transferred
                        val readLimit = if (remaining < buffer.size.toLong()) {
                            (remaining + 1L).toInt()
                        } else {
                            buffer.size
                        }
                        val count = input.read(buffer, 0, readLimit)
                        if (count < 0) break
                        if (count == 0) continue
                        val writable = count.toLong().coerceAtMost(remaining).toInt()
                        if (writable > 0) {
                            try {
                                output.write(buffer, 0, writable)
                            } catch (error: IOException) {
                                throw ParaformerAttemptException(
                                    ParaformerAttemptFailure.Storage,
                                    error,
                                )
                            }
                            transferred += writable
                        }
                        if (writable != count) {
                            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
                        }
                        onProgress(offset + transferred)
                    }
                }
                if (transferred != expectedTransfer) {
                    throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
                }
                return ParaformerTransferResult(
                    etag = frozenEtag,
                    transferredBytes = transferred,
                )
            }
        } catch (error: ParaformerAttemptException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw ParaformerAttemptException(
                failure = ParaformerAttemptFailure.Timeout,
                cause = error,
                diagnostic = transportDiagnostic(
                    source = source,
                    reason = ParaformerHttpFailureReason.Timeout,
                ),
            )
        } catch (error: IOException) {
            throw ParaformerAttemptException(
                failure = ParaformerAttemptFailure.Network,
                cause = error,
                diagnostic = transportDiagnostic(
                    source = source,
                    reason = ParaformerHttpFailureReason.Network,
                ),
            )
        }
    }

    private fun sourceFor(request: Request): ParaformerArtifactSource =
        if (request.url.host.lowercase() in MIRROR_HOSTS) {
            ParaformerArtifactSource.Mirror
        } else {
            ParaformerArtifactSource.Official
        }

    private fun executeFollowingAllowedRedirects(
        request: Request,
        source: ParaformerArtifactSource,
        allowHttpForTest: Boolean,
    ): Response {
        val redirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var currentRequest = request
        var redirectCount = 0
        while (true) {
            if (!allowHttpForTest) requireAllowedHop(source, currentRequest.url)
            val response = redirectClient.newCall(currentRequest).execute()
            val location = if (response.code in REDIRECT_STATUS_CODES) {
                response.header("Location")
            } else {
                null
            }
            if (location == null) return response
            val nextUrl = response.request.url.resolve(location)
            if (
                nextUrl == null ||
                redirectCount >= MAX_REDIRECTS ||
                (!allowHttpForTest && !isAllowedHop(source, nextUrl))
            ) {
                response.close()
                throw rejectedRedirect(source)
            }
            response.close()
            currentRequest = currentRequest.newBuilder().url(nextUrl).build()
            redirectCount += 1
        }
    }

    private fun validateResponseChain(
        source: ParaformerArtifactSource,
        initialRequest: Request,
        response: Response,
    ): ParaformerFinalHostClass {
        requireAllowedHop(source, initialRequest.url)
        var current: Response? = response
        while (current != null) {
            requireAllowedHop(source, current.request.url)
            current = current.priorResponse
        }
        return classifyHost(response.request.url)
    }

    private fun requireAllowedHop(
        source: ParaformerArtifactSource,
        url: HttpUrl,
    ) {
        if (!isAllowedHop(source, url)) throw rejectedRedirect(source)
    }

    private fun isAllowedHop(
        source: ParaformerArtifactSource,
        url: HttpUrl,
    ): Boolean {
        val hostClass = classifyHost(url)
        if (hostClass == ParaformerFinalHostClass.OtherRejected) return false
        return source != ParaformerArtifactSource.Official ||
            hostClass != ParaformerFinalHostClass.Mirror
    }

    private fun classifyHost(url: HttpUrl): ParaformerFinalHostClass {
        if (!url.isHttps) return ParaformerFinalHostClass.OtherRejected
        return when (url.host.lowercase()) {
            in MIRROR_HOSTS -> ParaformerFinalHostClass.Mirror
            OFFICIAL_HOST,
            in OFFICIAL_CDN_HOSTS,
            -> ParaformerFinalHostClass.Official
            else -> ParaformerFinalHostClass.OtherRejected
        }
    }

    private fun rejectedRedirect(source: ParaformerArtifactSource) =
        ParaformerAttemptException(
            failure = ParaformerAttemptFailure.Protocol,
            diagnostic = ParaformerHttpDiagnostic(
                source = source,
                failureStage = ParaformerFailureStage.Redirect,
                finalHostClass = ParaformerFinalHostClass.OtherRejected,
                reason = ParaformerHttpFailureReason.UnexpectedRedirectHost,
            ),
        )

    private fun responseDiagnostic(
        source: ParaformerArtifactSource,
        finalHostClass: ParaformerFinalHostClass,
        reason: ParaformerHttpFailureReason,
        status: Int? = null,
        length: Long? = null,
        etagPresent: Boolean? = null,
    ) = ParaformerHttpDiagnostic(
        source = source,
        failureStage = ParaformerFailureStage.ResponseContract,
        finalHostClass = finalHostClass,
        reason = reason,
        status = status,
        length = length,
        etagPresent = etagPresent,
    )

    private fun transportDiagnostic(
        source: ParaformerArtifactSource,
        reason: ParaformerHttpFailureReason,
    ) = ParaformerHttpDiagnostic(
        source = source,
        failureStage = ParaformerFailureStage.Transport,
        finalHostClass = finalHostClass(source),
        reason = reason,
    )

    private fun finalHostClass(source: ParaformerArtifactSource) = when (source) {
        ParaformerArtifactSource.Official -> ParaformerFinalHostClass.Official
        ParaformerArtifactSource.Mirror -> ParaformerFinalHostClass.Mirror
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_REDIRECTS = 20
        const val OFFICIAL_HOST = "huggingface.co"
        val MIRROR_HOSTS = setOf("hf-mirror.com", "www.hf-mirror.com")
        val OFFICIAL_CDN_HOSTS = setOf(
            "cdn-lfs.huggingface.co",
            "cas-bridge.xethub.hf.co",
            "us.aws.cdn.hf.co",
        )
        val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}
