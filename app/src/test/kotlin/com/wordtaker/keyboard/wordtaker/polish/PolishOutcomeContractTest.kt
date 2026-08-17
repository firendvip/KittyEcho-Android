package com.wordtaker.keyboard.wordtaker.polish

import com.wordtaker.keyboard.wordtaker.backend.BackendException
import com.wordtaker.keyboard.wordtaker.backend.PolishOutcome
import com.wordtaker.keyboard.wordtaker.network.InternetConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class PolishOutcomeContractTest : FunSpec({

    test("validated anonymous long text enters one top-level backend attempt") {
        var backendCalls = 0
        val diagnostics = OutcomeDiagnostics()
        val polisher = OnlineOnlyPolisher(
            internetConnection = InternetConnection { true },
            onlineDelegate = RealPolisher(
                backend = PolishBackend { _, _, _ ->
                    backendCalls += 1
                    outcome("匿名已润色")
                },
                diagnostics = diagnostics,
            ),
            diagnostics = diagnostics,
        )

        runTest {
            polisher.polishResult("一二三四五六七", "normal") shouldBe
                PolishResult("匿名已润色", PolishOutcomeKind.Polished)
        }
        backendCalls shouldBe 1
        diagnostics.events.count { it == PolishEvent.TOP_LEVEL_ATTEMPT } shouldBe 1
    }

    test("a non-blank backend response equal to raw is still polished") {
        var backendCalls = 0
        val raw = "保持原样也是成功"
        val polisher = RealPolisher(
            backend = PolishBackend { _, _, _ ->
                backendCalls += 1
                outcome(raw)
            },
            diagnostics = OutcomeDiagnostics(),
        )

        runTest {
            polisher.polishResult(raw, "normal") shouldBe
                PolishResult(raw, PolishOutcomeKind.Polished)
        }
        backendCalls shouldBe 1
    }

    test("quota auth timeout network and server failures retain their typed raw fallback") {
        val cases = listOf(
            BackendException(
                BackendException.Kind.HTTP,
                "quota detail",
                code = BackendException.CODE_INSUFFICIENT_QUOTA,
                status = 402,
            ) to PolishOutcomeKind.FallbackQuota,
            BackendException(
                BackendException.Kind.HTTP,
                "auth detail",
                code = BackendException.CODE_NOT_LOGGED_IN,
                status = 401,
            ) to PolishOutcomeKind.FallbackAuthExpired,
            BackendException(BackendException.Kind.TIMEOUT, "timeout detail") to
                PolishOutcomeKind.FallbackTimeout,
            BackendException(BackendException.Kind.NETWORK, "network detail") to
                PolishOutcomeKind.FallbackNetwork,
            BackendException(BackendException.Kind.HTTP, "server detail", status = 503) to
                PolishOutcomeKind.FallbackServer,
        )

        cases.forEach { (failure, expectedOutcome) ->
            var invalidations = 0
            val raw = "隐私文本一二三四五六七"
            val polisher = RealPolisher(
                backend = PolishBackend { _, _, _ -> throw failure },
                onAuthExpired = { invalidations += 1 },
                diagnostics = OutcomeDiagnostics(),
            )

            runTest {
                polisher.polishResult(raw, "normal") shouldBe
                    PolishResult(raw, expectedOutcome)
            }
            invalidations shouldBe if (expectedOutcome == PolishOutcomeKind.FallbackAuthExpired) 1 else 0
        }
    }

    test("blank backend response retains raw text as a server fallback") {
        val polisher = RealPolisher(
            backend = PolishBackend { _, _, _ -> outcome("") },
            diagnostics = OutcomeDiagnostics(),
        )

        runTest {
            polisher.polishResult("一二三四五六七", "normal") shouldBe
                PolishResult("一二三四五六七", PolishOutcomeKind.FallbackServer)
        }
    }

    test("offline is a typed design-direct result without invoking the delegate") {
        var delegateCalls = 0
        val polisher = OnlineOnlyPolisher(
            internetConnection = InternetConnection { false },
            onlineDelegate = object : Polisher {
                override suspend fun polish(raw: String, role: String): String {
                    delegateCalls += 1
                    return "unused"
                }
            },
            diagnostics = OutcomeDiagnostics(),
        )

        runTest {
            polisher.polishResult("一二三四五六七", "normal") shouldBe
                PolishResult("一二三四五六七", PolishOutcomeKind.OfflineDirect)
        }
        delegateCalls shouldBe 0
    }

    test("unexpected backend exceptions remain visible to the voice fallback classifier") {
        val polisher = RealPolisher(
            backend = PolishBackend { _, _, _ -> error("unexpected local failure") },
            diagnostics = OutcomeDiagnostics(),
        )

        runTest {
            shouldThrow<IllegalStateException> {
                polisher.polishResult("一二三四五六七", "normal")
            }
        }
    }
})

private class OutcomeDiagnostics : PolishDiagnostics {
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
