package com.wordtaker.keyboard.wordtaker.polish

import com.wordtaker.keyboard.wordtaker.backend.BackendException
import com.wordtaker.keyboard.wordtaker.backend.BackendClient
import com.wordtaker.keyboard.wordtaker.backend.AuthRequestSession
import com.wordtaker.keyboard.wordtaker.backend.PolishOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class RealPolisherTest : FunSpec({

    test("backend failures expose payload-free HTTP business quota timeout auth and network categories") {
        val cases = listOf(
            BackendException(BackendException.Kind.HTTP, "server", status = 503) to
                PolishEvent.BACKEND_HTTP_FAILURE,
            BackendException(
                BackendException.Kind.HTTP,
                "business",
                code = "POLICY_REJECTED",
                status = 400,
            ) to PolishEvent.BACKEND_BUSINESS_FAILURE,
            BackendException(
                BackendException.Kind.HTTP,
                "quota",
                code = BackendException.CODE_INSUFFICIENT_QUOTA,
                status = 402,
            ) to PolishEvent.BACKEND_QUOTA_FAILURE,
            BackendException(BackendException.Kind.TIMEOUT, "timeout") to
                PolishEvent.BACKEND_TIMEOUT,
            BackendException(BackendException.Kind.HTTP, "expired", status = 401) to
                PolishEvent.BACKEND_AUTH_FAILURE,
            BackendException(BackendException.Kind.NETWORK, "network") to
                PolishEvent.BACKEND_NETWORK_FAILURE,
        )

        cases.forEach { (failure, expectedEvent) ->
            val diagnostics = TestDiagnostics()
            val polisher = RealPolisher(
                backend = PolishBackend { _, _, _ -> throw failure },
                diagnostics = diagnostics,
            )

            runTest {
                polisher.polish("private transcript 1234567", "normal")
            }

            diagnostics.events shouldContain expectedEvent
            diagnostics.events.joinToString().contains("private transcript") shouldBe false
            diagnostics.events.joinToString().contains("POLICY_REJECTED") shouldBe false
        }
    }

    test("backend failure returns the raw transcript with its typed outcome") {
        val polisher = RealPolisher(
            backend = PolishBackend { _, _, _ ->
                throw BackendException(BackendException.Kind.NETWORK, "network")
            },
            diagnostics = TestDiagnostics(),
        )

        runTest {
            polisher.polishResult("synthetic transcript 1234567", "normal") shouldBe
                PolishResult(
                    text = "synthetic transcript 1234567",
                    outcome = PolishOutcomeKind.FallbackNetwork,
                )
        }
    }

    test("backend success is observed without exposing text or credentials") {
        val diagnostics = TestDiagnostics()
        val polisher = RealPolisher(
            backend = PolishBackend { _, _, _ -> outcome("polished secret") },
            diagnostics = diagnostics,
        )

        runTest {
            polisher.polish("raw secret 1234567", "normal") shouldBe "polished secret"
        }

        diagnostics.events shouldBe listOf(PolishEvent.BACKEND_SUCCESS)
        diagnostics.events.joinToString().contains("secret") shouldBe false
        diagnostics.events.joinToString().contains("token") shouldBe false
    }

    test("an authentication failure invokes invalidation exactly once") {
        var invalidations = 0
        val diagnostics = TestDiagnostics()
        val polisher = RealPolisher(
            backend = PolishBackend { _, _, _ ->
                throw BackendException(
                    BackendException.Kind.HTTP,
                    "expired",
                    code = BackendException.CODE_NOT_LOGGED_IN,
                    status = 401,
                )
            },
            onAuthExpired = { invalidations += 1 },
            diagnostics = diagnostics,
        )

        runTest {
            polisher.polish("一二三四五六七", "normal") shouldBe "一二三四五六七"
        }

        invalidations shouldBe 1
        diagnostics.events shouldContain PolishEvent.BACKEND_AUTH_FAILURE
    }

    test("blank backend output returns raw and empty input bypasses the backend") {
        var backendCalls = 0
        val diagnostics = TestDiagnostics()
        val polisher = RealPolisher(
            backend = PolishBackend { _, _, _ ->
                backendCalls += 1
                outcome("")
            },
            diagnostics = diagnostics,
        )

        runTest {
            polisher.polish("   ", "normal") shouldBe "   "
            polisher.polish("1234567", "normal") shouldBe "1234567"
        }

        backendCalls shouldBe 1
        diagnostics.events shouldBe listOf(PolishEvent.BACKEND_BLANK_RESPONSE)
    }

    test("normal gaoeq and unknown roles map to contract modes and input is trimmed and capped") {
        val requests = mutableListOf<Pair<String, String>>()
        val polisher = RealPolisher(
            backend = PolishBackend { text, mode, _ ->
                requests += text to mode
                outcome("ok")
            },
            diagnostics = TestDiagnostics(),
        )

        runTest {
            polisher.polish("  normal text  ", "normal")
            polisher.polish("gaoeq text", "gaoeq")
            polisher.polish("x".repeat(4_100), "vibecoding")
        }

        requests[0] shouldBe ("normal text" to "normal")
        requests[1] shouldBe ("gaoeq text" to "gaoeq")
        requests[2].first.length shouldBe 4_000
        requests[2].second shouldBe "copywriting"
    }

    test("production constructor delegates to BackendClient without a real service") {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"data":{"output":"mock polished"}}""",
                ),
            )
            val backend = BackendClient(
                deviceId = "0123456789abcdef0123456789abcdef",
                authSessionProvider = { AuthRequestSession(0L, null) },
                baseUrl = server.url("/aiapi").toString(),
            )
            val diagnostics = TestDiagnostics()
            val polisher = RealPolisher(
                backend = backend,
                diagnostics = diagnostics,
            )

            runTest {
                polisher.polish("1234567", "normal") shouldBe "mock polished"
            }

            server.takeRequest().path shouldBe "/aiapi/polish"
            diagnostics.events shouldBe listOf(PolishEvent.BACKEND_SUCCESS)
        } finally {
            server.shutdown()
        }
    }
})

private class TestDiagnostics : PolishDiagnostics {
    val events = mutableListOf<PolishEvent>()

    override fun record(event: PolishEvent) {
        events += event
    }
}

private fun outcome(text: String): PolishOutcome = PolishOutcome(
    text = text,
    visibleChars = null,
    cloudRemaining = null,
    dailyUsed = null,
    dailyCap = null,
)
