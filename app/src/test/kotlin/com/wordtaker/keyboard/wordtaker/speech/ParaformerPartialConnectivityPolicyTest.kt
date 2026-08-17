package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class ParaformerPartialConnectivityPolicyTest : FunSpec({

    test("INTERNET capability may attempt HTTPS while metered access still requires confirmation") {
        val cases = listOf(
            NetworkCase(
                hasInternet = false,
                metered = false,
                mobileConfirmed = false,
                expected = ParaformerNetworkDecision.PauseOffline,
            ),
            NetworkCase(
                hasInternet = true,
                metered = true,
                mobileConfirmed = false,
                expected = ParaformerNetworkDecision.PauseMetered,
            ),
            NetworkCase(
                hasInternet = true,
                metered = true,
                mobileConfirmed = true,
                expected = ParaformerNetworkDecision.Allow,
            ),
            NetworkCase(
                hasInternet = true,
                metered = false,
                mobileConfirmed = false,
                expected = ParaformerNetworkDecision.Allow,
            ),
        )

        cases.forEach { case ->
            ParaformerDownloadPolicy.networkDecision(
                connected = case.hasInternet,
                metered = case.metered,
                mobileConfirmed = case.mobileConfirmed,
            ) shouldBe case.expected
        }
    }

    test("Android worker treats PARTIAL INTERNET as attemptable without calling it unmetered") {
        val worker = File(
            locateProjectRoot(),
            "app/src/main/kotlin/com/wordtaker/keyboard/wordtaker/speech/" +
                "ParaformerModelDownloadWorker.kt",
        ).readText()

        worker shouldContain "NET_CAPABILITY_INTERNET"
        worker shouldContain "NET_CAPABILITY_NOT_METERED"
        worker shouldNotContain "NET_CAPABILITY_VALIDATED"
    }
})

private data class NetworkCase(
    val hasInternet: Boolean,
    val metered: Boolean,
    val mobileConfirmed: Boolean,
    val expected: ParaformerNetworkDecision,
)

private fun locateProjectRoot(): File {
    var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    repeat(8) {
        if (File(current, "app/src/main").isDirectory) return current
        current = current.parentFile ?: error("Could not locate project root")
    }
    error("Could not locate project root")
}
