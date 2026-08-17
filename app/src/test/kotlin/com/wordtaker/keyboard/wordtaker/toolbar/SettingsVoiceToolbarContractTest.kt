package com.wordtaker.keyboard.wordtaker.toolbar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class SettingsVoiceToolbarContractTest : FunSpec({

    test("idle text and settings reuse one cat plus talk pill primary component") {
        val source = productionSource("wordtaker/voice/CatKeyboardLayout.kt")
        val normal = source
            .substringAfter("private fun VoiceToolbarRow(")
            .substringBefore("private fun CandidateToolbarContent(")
        val settings = source
            .substringAfter("internal fun ImeSettingsVoiceToolbarRow(")
            .substringBefore("private fun CandidateToolbarContent(")
        val primary = source
            .substringAfter("private fun VoiceToolbarPrimaryActions(")
            .substringBefore("internal fun ImeSettingsVoiceToolbarRow(")

        normal shouldContain "VoiceToolbarPrimaryActions("
        settings shouldContain "VoiceToolbarPrimaryActions("
        primary shouldContain "ToolbarCatButton("
        primary shouldContain "TalkPill("
        primary shouldContain "sizing.itemGapDp"
    }

    test("settings talk closes settings before requesting the same voice trigger") {
        val source = productionSource("ime/window/ImeWindow.kt")
        val slot = source
            .substringAfter("private fun ImeSettingsToolbarSlot()")
            .substringBefore("private fun BoxScope.FloatingDockToFixedIndicator()")
        val close = "keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT"
        val start = "VoiceTrigger.requestStart()"

        slot shouldContain "ImeSettingsVoiceToolbarRow("
        slot shouldContain close
        slot shouldContain start
        (slot.indexOf(close) < slot.indexOf(start)) shouldBe true
    }

    test("settings removes the independent voice node and keeps collapse usable") {
        val source = productionSource("wordtaker/voice/CatKeyboardLayout.kt")
        val settings = source
            .substringAfter("internal fun ImeSettingsVoiceToolbarRow(")
            .substringBefore("private fun CandidateToolbarContent(")

        settings shouldNotContain "ic_wt_voice"
        settings shouldNotContain "\"语音输入\""
        settings shouldNotContain "ToolbarIconButton("
        settings shouldContain "R.drawable.ic_wt_collapse"
        settings shouldContain "contentDescription = \"收起键盘\""
        settings shouldContain "FlorisImeService.hideUi()"
    }

    test("settings strip no longer mounts the legacy toolbar voice action") {
        val source = productionSource("ime/window/ImeWindow.kt")
        val slot = source
            .substringAfter("private fun ImeSettingsToolbarSlot()")
            .substringBefore("private fun BoxScope.FloatingDockToFixedIndicator()")

        slot shouldNotContain "ImeToolbar()"
        slot shouldContain ".height(catStripHeight())"
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
