package com.wordtaker.keyboard.ime.input

import com.wordtaker.keyboard.app.FlorisPreferenceModel
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class KeyboardHapticFeedbackContractTest : FunSpec({

    coroutineTestScope = true

    test("legacy haptic preference defaults on and round trips the complete boolean domain") {
        val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)

        prefs.inputFeedback.hapticEnabled.get() shouldBe true
        listOf(false, true).forEach { enabled ->
            prefs.inputFeedback.hapticEnabled.set(enabled)
            prefs.inputFeedback.hapticEnabled.get() shouldBe enabled
        }
    }

    test("the existing persisted key and true default remain stable for upgrades") {
        val source = productionSource("app/AppPrefs.kt")
        val hapticPreference = source
            .substringAfter("val hapticEnabled = boolean(")
            .substringBefore("val hapticActivationMode")

        hapticPreference shouldContain """key = "input_feedback__haptic_enabled""""
        hapticPreference shouldContain "default = true"
        hapticPreference shouldNotContain "int("
        hapticPreference shouldNotContain "string("
    }

    test("the persisted switch is the actual haptic gate and never gates key audio") {
        val source = productionSource("ime/input/InputFeedbackController.kt")
        val audioPath = source
            .substringAfter("private fun performAudioFeedback")
            .substringBefore("private fun performHapticFeedback")
        val hapticPath = source.substringAfter("private fun performHapticFeedback")

        hapticPath shouldContain "if (!prefs.inputFeedback.hapticEnabled.get()) return"
        audioPath shouldNotContain "hapticEnabled"
    }

    test("voice start haptic runs once only when enabled and hardware is available") {
        var pulses = 0

        dispatchVoiceStartHaptic(
            enabled = true,
            hardwareAvailable = true,
            perform = { pulses += 1 },
        )
        dispatchVoiceStartHaptic(
            enabled = false,
            hardwareAvailable = true,
            perform = { pulses += 1 },
        )
        dispatchVoiceStartHaptic(
            enabled = true,
            hardwareAvailable = false,
            perform = { pulses += 1 },
        )

        pulses shouldBe 1
    }

    test("voice start haptic failure is contained and never escapes into recording") {
        dispatchVoiceStartHaptic(
            enabled = true,
            hardwareAvailable = true,
            perform = { error("device haptic failure") },
        )
    }

    test("voice start feedback is haptic only and reuses the persisted haptic gate") {
        val source = productionSource("ime/input/InputFeedbackController.kt")
        val voicePath = source
            .substringAfter("fun voiceRecordingStart()")
            .substringBefore("private fun systemPref")

        voicePath shouldContain "prefs.inputFeedback.hapticEnabled.get()"
        voicePath shouldContain "dispatchVoiceStartHaptic("
        voicePath shouldContain "performHapticFeedback("
        voicePath shouldNotContain "performAudioFeedback("
        voicePath shouldNotContain "playSoundEffect"
    }

    test("launcher for configured users resolves to MinimalSettingsScreen with a live haptic binding") {
        val manifest = appSource("src/main/AndroidManifest.xml")
        val launcherAlias = Regex(
            """<activity-alias\b.*?</activity-alias>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(manifest).single { match ->
            match.value.contains("""android.intent.action.MAIN""") &&
                match.value.contains("""android.intent.category.LAUNCHER""")
        }.value
        launcherAlias shouldContain
            """android:targetActivity="com.wordtaker.keyboard.app.FlorisAppActivity""""

        val activityRoute = productionSource("app/FlorisAppActivity.kt")
            .substringAfter("private fun AppContent()")
            .substringBefore("LaunchedEffect(intentToBeHandled)")
        activityRoute shouldContain
            "startDestination = if (isImeSetUp) Routes.Settings.MinimalSettings::class"

        val navGraph = productionSource("app/Routes.kt")
            .substringAfter("fun AppNavHost(")
            .substringBefore("composableWithDeepLink(Settings.About::class)")
        navGraph shouldContain
            "composableWithDeepLink(Settings.MinimalSettings::class) { MinimalSettingsScreen() }"

        assertHapticBinding(minimalSettingsMainSource())
    }

    test("all three KittyEcho settings entries bind both directions to the same haptic preference") {
        listOf(
            minimalSettingsMainSource(),
            productionSource("wordtaker/settings/ImeSettingsLayout.kt"),
            productionSource("wordtaker/ui/WordTakerSettingsActivity.kt"),
        ).forEach(::assertHapticBinding)
    }
})

private fun assertHapticBinding(screenSource: String) {
    val observedState = Regex(
        """val\s+(\w+)\s+by\s+prefs\.inputFeedback\.hapticEnabled\.observeAsState\(\)""",
    ).find(screenSource)?.groupValues?.get(1)
        ?: error("screen does not observe the shared haptic preference")
    val titleIndex = screenSource.indexOf("""title = "键盘震动反馈"""")
    check(titleIndex >= 0) { "screen does not expose the haptic row" }
    val rowStart = screenSource.lastIndexOf("ToggleRow(", titleIndex)
    check(rowStart >= 0) { "haptic title is not rendered by ToggleRow" }
    val nextRowStart = screenSource.indexOf("ToggleRow(", titleIndex + 1)
        .takeIf { it >= 0 } ?: screenSource.length
    val hapticRow = screenSource.substring(rowStart, nextRowStart)

    hapticRow shouldContain "checked = $observedState"
    hapticRow shouldContain "prefs.inputFeedback.hapticEnabled.set(it)"
    hapticRow shouldNotContain "setTone("
    hapticRow shouldNotContain "cloudEnabled.set("
}

private fun minimalSettingsMainSource(): String =
    productionSource("app/settings/MinimalSettingsScreen.kt")
        .substringAfter("private fun MinimalSettingsMain(")
        .substringBefore("@Composable\nprivate fun BrandHeader()")

private fun productionSource(relativePath: String): String {
    return appSource("src/main/kotlin/com/wordtaker/keyboard/$relativePath")
}

private fun appSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, relativePath).readText()
}
