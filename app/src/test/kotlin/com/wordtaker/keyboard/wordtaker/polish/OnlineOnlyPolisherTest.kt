package com.wordtaker.keyboard.wordtaker.polish

import com.wordtaker.keyboard.wordtaker.network.InternetConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class OnlineOnlyPolisherTest : FunSpec({

    test("offline returns raw without invoking the backend and relay stack") {
        val delegate = RecordingPolisher { _, _ -> "should not run" }
        val diagnostics = RecordingPolishDiagnostics()
        val polisher = OnlineOnlyPolisher(
            InternetConnection { false },
            delegate,
            diagnostics,
        )

        runTest {
            polisher.polish("原始转录", "normal") shouldBe "原始转录"
        }
        delegate.calls shouldBe 0
        diagnostics.events shouldBe listOf(PolishEvent.OFFLINE_BYPASS)
    }

    test("validated internet invokes online delegate") {
        val delegate = RecordingPolisher { raw, role -> "$role:$raw" }
        val diagnostics = RecordingPolishDiagnostics()
        val polisher = OnlineOnlyPolisher(
            InternetConnection { true },
            delegate,
            diagnostics,
        )

        runTest {
            polisher.polish("1234567", "gaoeq") shouldBe "gaoeq:1234567"
        }
        delegate.calls shouldBe 1
        diagnostics.events shouldBe listOf(PolishEvent.TOP_LEVEL_ATTEMPT)
    }

    test("online failure stays visible to the VoiceViewModel fallback") {
        val delegate = RecordingPolisher { _, _ -> error("backend unavailable") }
        val polisher = OnlineOnlyPolisher(InternetConnection { true }, delegate)

        runTest {
            shouldThrow<IllegalStateException> {
                polisher.polish("keep me", "normal")
            }
        }
        delegate.calls shouldBe 1
    }

    test("scope cancellation is not converted into a successful raw result") {
        val delegate = RecordingPolisher { _, _ -> throw CancellationException("cancelled") }
        val polisher = OnlineOnlyPolisher(InternetConnection { true }, delegate)

        runTest {
            val result = runCatching { polisher.polish("raw", "normal") }
            (result.exceptionOrNull() is CancellationException) shouldBe true
        }
    }
})

private class RecordingPolishDiagnostics : PolishDiagnostics {
    val events = mutableListOf<PolishEvent>()

    override fun record(event: PolishEvent) {
        events += event
    }
}

private class RecordingPolisher(
    private val result: suspend (String, String) -> String,
) : Polisher {
    var calls: Int = 0
        private set

    override suspend fun polish(raw: String, role: String): String {
        calls += 1
        return result(raw, role)
    }
}
