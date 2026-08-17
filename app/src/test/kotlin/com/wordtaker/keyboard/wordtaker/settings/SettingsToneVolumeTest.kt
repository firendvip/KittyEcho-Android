package com.wordtaker.keyboard.wordtaker.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class SettingsToneVolumeTest : FunSpec({

    test("new users without a stored preference default to thirty percent") {
        resolveToneVolume(stored = null) shouldBe 30
        SettingsState().toneVolume shouldBe 30
    }

    test("existing stored preferences are preserved without migration") {
        listOf(0, 1, 30, 73, 100).forEach { stored ->
            resolveToneVolume(stored) shouldBe stored
        }
    }

    test("corrupt out of range values remain bounded to the slider contract") {
        resolveToneVolume(-1) shouldBe 0
        resolveToneVolume(101) shouldBe 100
    }

    test("default end gain remains thirty percent with the existing meow attenuation") {
        val defaultGain = SettingsState.DEFAULT_TONE_VOLUME / 100f
        defaultGain shouldBe (0.30f plusOrMinus 0.0001f)
        defaultGain * 0.85f shouldBe (0.255f plusOrMinus 0.0001f)

        productionSource("wordtaker/audio/ToneController.kt") shouldContain
            "const val MEOW_END_VOLUME = 0.85f"
        productionSource("wordtaker/voice/VoiceViewModel.kt") shouldContain
            "settings.value.toneVolume / 100f"
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
