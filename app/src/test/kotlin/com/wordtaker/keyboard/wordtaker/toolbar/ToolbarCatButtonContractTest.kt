package com.wordtaker.keyboard.wordtaker.toolbar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class ToolbarCatButtonContractTest : FunSpec({

    test("shared cat avatar keeps the required touch background and glyph sizes") {
        ToolbarCatAvatarSpec.touchSizeDp shouldBe 44
        ToolbarCatAvatarSpec.backgroundSizeDp shouldBe 34
        ToolbarCatAvatarSpec.glyphSizeDp shouldBe 30
    }

    test("keyboard and in keyboard settings toolbar reuse the exact same avatar component") {
        val keyboard = productionSource("wordtaker/voice/CatKeyboardLayout.kt")
        val toolbar = productionSource("wordtaker/toolbar/ImeToolbar.kt")

        keyboard shouldContain "ToolbarCatButton("
        keyboard shouldNotContain "fun StripCatButton("
        toolbar shouldContain "fun ToolbarCatButton("
        toolbar shouldContain "LocalFlorisImeThemeIsNight.current"
        toolbar shouldContain "DoubaoImeSkin.palette(dark)"
    }

    test("keyboard and settings resolve the same absolute cat center on portrait and rotation") {
        listOf(
            360f to 54.72f,
            800f to 121.6f,
        ).forEach { (widthDp, slotHeightDp) ->
            val keyboardAnchor = toolbarCatAnchor(widthDp, slotHeightDp)
            val settingsAnchor = toolbarCatAnchor(widthDp, slotHeightDp)

            settingsAnchor shouldBe keyboardAnchor
            keyboardAnchor.centerXDp shouldBe
                toolbarCatHorizontalInsetDp(widthDp) + ToolbarCatAvatarSpec.touchSizeDp / 2f
            keyboardAnchor.centerYDp shouldBe slotHeightDp / 2f
        }

        toolbarCatHorizontalInsetDp(360f) shouldBe 2
        toolbarCatHorizontalInsetDp(379.9f) shouldBe 2
        toolbarCatHorizontalInsetDp(380f) shouldBe 6
        toolbarCatHorizontalInsetDp(800f) shouldBe 6
    }

    test("settings uses the same full strip primary actions as idle text") {
        val keyboard = productionSource("wordtaker/voice/CatKeyboardLayout.kt")
        val window = productionSource("ime/window/ImeWindow.kt")

        keyboard shouldContain "ImeSettingsVoiceToolbarRow("
        keyboard shouldContain "VoiceToolbarPrimaryActions("
        window shouldContain "ImeSettingsToolbarSlot("
        window shouldContain "ImeSettingsVoiceToolbarRow("
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
