package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class ParaformerHttpContractTest : FunSpec({
    lateinit var server: MockWebServer

    beforeTest {
        server = MockWebServer()
        server.start()
    }

    afterTest {
        server.shutdown()
    }

    test("public artifact GET has no body account token device PCM or text headers") {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"one\"")
                .setHeader("Content-Length", "4")
                .setBody("data"),
        )
        val output = ByteArrayOutputStream()
        ParaformerHttpTransfer(OkHttpClient()).transfer(
            request = ParaformerDownloadRequestFactory.create(
                url = server.url("/model.int8.onnx"),
                offset = 0L,
                etag = null,
                allowHttpForTest = true,
            ),
            expectedTotal = 4L,
            offset = 0L,
            expectedEtag = null,
            output = output,
        )

        val recorded = server.takeRequest()
        recorded.method shouldBe "GET"
        recorded.bodySize shouldBe 0L
        recorded.getHeader("Authorization") shouldBe null
        recorded.getHeader("Cookie") shouldBe null
        recorded.getHeader("X-Device-Id") shouldBe null
        recorded.getHeader("X-Transcript") shouldBe null
        output.toString(Charsets.UTF_8.name()) shouldBe "data"
    }

    test("initial artifact request rejects cleartext before opening a response body") {
        shouldThrow<IllegalArgumentException> {
            ParaformerDownloadRequestFactory.create(
                url = "http://huggingface.co/model.int8.onnx".toHttpUrl(),
                offset = 0L,
                etag = null,
            )
        }
    }

    test("resume sends Range and If-Range and accepts only exact response") {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"same\"")
                .setHeader("Content-Range", "bytes 2-3/4")
                .setHeader("Content-Length", "2")
                .setBody("ta"),
        )
        val output = ByteArrayOutputStream()
        ParaformerHttpTransfer(OkHttpClient()).transfer(
            request = ParaformerDownloadRequestFactory.create(
                url = server.url("/model.int8.onnx"),
                offset = 2L,
                etag = "\"same\"",
                allowHttpForTest = true,
            ),
            expectedTotal = 4L,
            offset = 2L,
            expectedEtag = "\"same\"",
            output = output,
        )
        val recorded = server.takeRequest()
        recorded.getHeader("Range") shouldBe "bytes=2-"
        recorded.getHeader("If-Range") shouldBe "\"same\""
        output.toString(Charsets.UTF_8.name()) shouldBe "ta"
    }

    test("truncated body and range mismatch fail without an integrity fallback") {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"same\"")
                .setHeader("Content-Range", "bytes 1-2/4")
                .setHeader("Content-Length", "2")
                .setBody("xx"),
        )
        shouldThrow<ParaformerAttemptException> {
            ParaformerHttpTransfer(OkHttpClient()).transfer(
                request = ParaformerDownloadRequestFactory.create(
                    url = server.url("/model.int8.onnx"),
                    offset = 2L,
                    etag = "\"same\"",
                    allowHttpForTest = true,
                ),
                expectedTotal = 4L,
                offset = 2L,
                expectedEtag = "\"same\"",
                output = ByteArrayOutputStream(),
            )
        }.failure shouldBe ParaformerAttemptFailure.Protocol

        server.takeRequest().path shouldContain "/model.int8.onnx"
    }

    test("fresh status and etag failures expose distinct safe diagnostics") {
        val client = loopbackClient()
        val request = ParaformerDownloadRequestFactory.create(
            url = server.url("/tokens.txt")
                .newBuilder()
                .host(OFFICIAL_HOST)
                .addQueryParameter("X-Amz-Signature", "do-not-log-query")
                .build(),
            offset = 0L,
            etag = null,
            allowHttpForTest = true,
        )

        fun capture(response: MockResponse, expectedTotal: Long): ParaformerAttemptException {
            server.enqueue(response)
            return shouldThrow<ParaformerAttemptException> {
                ParaformerHttpTransfer(client).transfer(
                    request = request,
                    expectedTotal = expectedTotal,
                    offset = 0L,
                    expectedEtag = null,
                    output = ByteArrayOutputStream(),
                )
            }
        }

        val statusFailure = capture(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"do-not-log-etag\"")
                .setBody("data"),
            expectedTotal = 4L,
        )
        val etagFailure = capture(
            MockResponse()
                .setResponseCode(200)
                .setBody("data"),
            expectedTotal = 4L,
        )

        statusFailure.failure shouldBe ParaformerAttemptFailure.Protocol
        etagFailure.failure shouldBe ParaformerAttemptFailure.Protocol

        val statusDiagnostic = requireNotNull(statusFailure.message)
        val etagDiagnostic = requireNotNull(etagFailure.message)
        listOf(statusDiagnostic, etagDiagnostic).forEach { diagnostic ->
            diagnostic shouldContain "source=Official"
            diagnostic shouldContain "failureStage=ResponseContract"
            diagnostic shouldContain "finalHostClass=Official"
            diagnostic shouldNotContain "/tokens.txt"
            diagnostic shouldNotContain "?"
            diagnostic shouldNotContain "X-Amz-Signature"
            diagnostic shouldNotContain "do-not-log-query"
            diagnostic shouldNotContain "do-not-log-etag"
        }
        statusDiagnostic shouldContain "reason=FreshStatusMismatch"
        statusDiagnostic shouldContain "status=206"
        etagDiagnostic shouldContain "reason=FreshEtagMissing"
        etagDiagnostic shouldContain "etagPresent=false"
    }

    test("fresh unknown content length streams an exact body successfully") {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"chunked\"")
                .setChunkedBody("data", 2),
        )
        val output = ByteArrayOutputStream()
        var frozenEtag: String? = null

        val result = ParaformerHttpTransfer(loopbackClient()).transfer(
            request = ParaformerDownloadRequestFactory.create(
                url = server.url("/tokens.txt").newBuilder().host(OFFICIAL_HOST).build(),
                offset = 0L,
                etag = null,
                allowHttpForTest = true,
            ),
            expectedTotal = 4L,
            offset = 0L,
            expectedEtag = null,
            output = output,
            onResponseMetadata = { frozenEtag = it },
        )

        result.transferredBytes shouldBe 4L
        frozenEtag shouldBe "\"chunked\""
        output.toString(Charsets.UTF_8.name()) shouldBe "data"
    }

    test("fresh unknown length short and long bodies fail integrity without overflow writes") {
        fun transfer(body: String): Pair<ParaformerAttemptException, ByteArrayOutputStream> {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"chunked\"")
                    .setChunkedBody(body, 1),
            )
            val output = ByteArrayOutputStream()
            val failure = shouldThrow<ParaformerAttemptException> {
                ParaformerHttpTransfer(loopbackClient()).transfer(
                    request = ParaformerDownloadRequestFactory.create(
                        url = server.url("/tokens.txt").newBuilder().host(OFFICIAL_HOST).build(),
                        offset = 0L,
                        etag = null,
                        allowHttpForTest = true,
                    ),
                    expectedTotal = 4L,
                    offset = 0L,
                    expectedEtag = null,
                    output = output,
                )
            }
            return failure to output
        }

        val (shortFailure, shortOutput) = transfer("dat")
        val (longFailure, longOutput) = transfer("datax")

        shortFailure.failure shouldBe ParaformerAttemptFailure.Integrity
        shortOutput.toString(Charsets.UTF_8.name()) shouldBe "dat"
        longFailure.failure shouldBe ParaformerAttemptFailure.Integrity
        longOutput.toString(Charsets.UTF_8.name()) shouldBe "data"
    }

    test("fresh interrupted stream retains written bytes and frozen etag as network failure") {
        val body = "x".repeat(128 * 1024)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"resume-me\"")
                .setChunkedBody(body, 1024)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val output = ByteArrayOutputStream()
        var frozenEtag: String? = null

        val failure = shouldThrow<ParaformerAttemptException> {
            ParaformerHttpTransfer(loopbackClient()).transfer(
                request = ParaformerDownloadRequestFactory.create(
                    url = server.url("/model.int8.onnx").newBuilder().host(OFFICIAL_HOST).build(),
                    offset = 0L,
                    etag = null,
                    allowHttpForTest = true,
                ),
                expectedTotal = body.length.toLong(),
                offset = 0L,
                expectedEtag = null,
                output = output,
                onResponseMetadata = { frozenEtag = it },
            )
        }

        (failure.failure in setOf(
            ParaformerAttemptFailure.Network,
            ParaformerAttemptFailure.Timeout,
        )) shouldBe true
        frozenEtag shouldBe "\"resume-me\""
        (output.size() in 1 until body.length) shouldBe true
    }

    test("official CDN and mirror to official CDN redirect chains are allowed") {
        assertAllowedRedirectChain(
            listOf(
                "https://huggingface.co/start",
                "https://cdn-lfs.huggingface.co/final",
            ),
        )
        assertAllowedRedirectChain(
            listOf(
                "https://hf-mirror.com/start",
                "https://www.hf-mirror.com/mirror-hop",
                "https://huggingface.co/official-hop",
                "https://cas-bridge.xethub.hf.co/final",
            ),
        )
        assertAllowedRedirectChain(
            listOf(
                "https://hf-mirror.com/start",
                "https://us.aws.cdn.hf.co/final",
            ),
        )
    }

    test("unknown HTTP and official to mirror hops are rejected before body bytes") {
        val rejectedChains = listOf(
            listOf("https://unknown.example/initial"),
            listOf("https://huggingface.co/start", "https://unknown.example/final"),
            listOf(
                "https://huggingface.co/start",
                "https://unknown.example/prior",
                "https://cdn-lfs.huggingface.co/final",
            ),
            listOf(
                "https://huggingface.co/start",
                "http://cdn-lfs.huggingface.co/insecure",
                "https://cdn-lfs.huggingface.co/final",
            ),
            listOf("https://huggingface.co/start", "https://hf-mirror.com/final"),
            listOf("https://hf-mirror.com/start", "http://us.aws.cdn.hf.co/final"),
            listOf("https://hf-mirror.com/start", "https://x.us.aws.cdn.hf.co/final"),
            listOf(
                "https://hf-mirror.com/start",
                "https://us.aws.cdn.hf.co.evil.com/final",
            ),
        )
        val observations = rejectedChains.map(::observeRedirectChain)

        observations.forEach { (failure, output) ->
            failure?.failure shouldBe ParaformerAttemptFailure.Protocol
            output.size() shouldBe 0
            requireNotNull(failure?.message).also { diagnostic ->
                diagnostic shouldContain "failureStage=Redirect"
                diagnostic shouldContain "finalHostClass=OtherRejected"
                diagnostic shouldNotContain "http://"
                diagnostic shouldNotContain "https://"
                diagnostic shouldNotContain "?"
                diagnostic shouldNotContain "unknown.example"
                diagnostic shouldNotContain "huggingface.co"
                diagnostic shouldNotContain "hf-mirror.com"
                diagnostic shouldNotContain "xethub.hf.co"
            }
        }
    }

    test("direct mirror response diagnostics use the Mirror host class") {
        listOf("hf-mirror.com", "www.hf-mirror.com").forEach { host ->
            val output = ByteArrayOutputStream()
            val failure = shouldThrow<ParaformerAttemptException> {
                ParaformerHttpTransfer(syntheticRedirectClient(
                    chain = listOf("https://$host/tokens.txt"),
                    etag = null,
                )).transfer(
                    request = ParaformerDownloadRequestFactory.create(
                        url = "https://$host/tokens.txt".toHttpUrl(),
                        offset = 0L,
                        etag = null,
                    ),
                    expectedTotal = 4L,
                    offset = 0L,
                    expectedEtag = null,
                    output = output,
                )
            }

            failure.failure shouldBe ParaformerAttemptFailure.Protocol
            requireNotNull(failure.message) shouldContain "finalHostClass=Mirror"
            output.size() shouldBe 0
        }
    }

    test("official socket timeout remains transport timeout rather than integrity") {
        val client = OkHttpClient.Builder()
            .addInterceptor {
                throw SocketTimeoutException(
                    "do-not-log https://huggingface.co/private?token=do-not-log-token",
                )
            }
            .build()
        val request = ParaformerDownloadRequestFactory.create(
            url = server.url("/tokens.txt")
                .newBuilder()
                .host(OFFICIAL_HOST)
                .build(),
            offset = 0L,
            etag = null,
            allowHttpForTest = true,
        )

        val failure = shouldThrow<ParaformerAttemptException> {
            ParaformerHttpTransfer(client).transfer(
                request = request,
                expectedTotal = 4L,
                offset = 0L,
                expectedEtag = null,
                output = ByteArrayOutputStream(),
            )
        }

        failure.failure shouldBe ParaformerAttemptFailure.Timeout
        requireNotNull(failure.message).also { diagnostic ->
            diagnostic shouldContain "source=Official"
            diagnostic shouldContain "failureStage=Transport"
            diagnostic shouldContain "finalHostClass=Official"
            diagnostic shouldContain "reason=Timeout"
            diagnostic shouldNotContain "/private"
            diagnostic shouldNotContain "?"
            diagnostic shouldNotContain "do-not-log-token"
        }
    }
})

private fun loopbackClient(): OkHttpClient {
    val loopback = InetAddress.getByName("127.0.0.1")
    return OkHttpClient.Builder()
        .dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(loopback)
            },
        )
        .proxy(Proxy.NO_PROXY)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

private fun assertAllowedRedirectChain(chain: List<String>) {
    val output = ByteArrayOutputStream()

    ParaformerHttpTransfer(syntheticRedirectClient(chain)).transfer(
        request = ParaformerDownloadRequestFactory.create(
            url = chain.first().toHttpUrl(),
            offset = 0L,
            etag = null,
        ),
        expectedTotal = 4L,
        offset = 0L,
        expectedEtag = null,
        output = output,
    )

    output.toString(Charsets.UTF_8.name()) shouldBe "data"
}

private fun observeRedirectChain(
    chain: List<String>,
): Pair<ParaformerAttemptException?, ByteArrayOutputStream> {
    val output = ByteArrayOutputStream()
    val throwable = runCatching {
        ParaformerHttpTransfer(syntheticRedirectClient(chain)).transfer(
            request = ParaformerDownloadRequestFactory.create(
                url = chain.first().toHttpUrl(),
                offset = 0L,
                etag = null,
            ),
            expectedTotal = 4L,
            offset = 0L,
            expectedEtag = null,
            output = output,
        )
    }.exceptionOrNull()
    if (throwable != null && throwable !is ParaformerAttemptException) throw throwable
    return throwable as? ParaformerAttemptException to output
}

private fun syntheticRedirectClient(
    chain: List<String>,
    etag: String? = "\"chain\"",
): OkHttpClient {
    require(chain.isNotEmpty())
    return OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .addInterceptor { syntheticRedirectResponse(chain, etag) }
        .build()
}

private fun syntheticRedirectResponse(
    chain: List<String>,
    etag: String?,
): Response {
    val requests = chain.map { url ->
        Request.Builder()
            .url(url)
            .get()
            .build()
    }
    var prior: Response? = null
    requests.dropLast(1).forEach { request ->
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .apply { prior?.let { previous -> priorResponse(previous) } }
            .build()
        prior = response
    }
    return Response.Builder()
        .request(requests.last())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("data".toResponseBody())
        .apply {
            etag?.let { header("ETag", it) }
            prior?.let { previous -> priorResponse(previous) }
        }
        .build()
}

private const val OFFICIAL_HOST = "huggingface.co"
