package com.wordtaker.keyboard.wordtaker.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class CatVoiceFeedbackSourceTest : FunSpec({

    test("voice feedback uses the existing CatSkin without the Doubao waveform") {
        val source = productionSource("wordtaker/voice/CatKeyboardLayout.kt")

        source.contains("CatSkin(") shouldBe true
        source.contains("VoiceWaveform(") shouldBe false
        source.contains("正在录音，小猫正在聆听") shouldBe true
        source.contains("正在转录，小猫正在思考") shouldBe true
        source.contains("正在在线润色，小猫正在思考") shouldBe true
        source.contains("语音输入完成，小猫送来星光") shouldBe true
    }

    test("cat canvas is hidden from accessibility and animation can follow the system scale") {
        val source = productionSource("wordtaker/cat/CatSkin.kt")

        source.contains("animationsEnabled: Boolean = ValueAnimator.areAnimatorsEnabled()") shouldBe true
        source.contains("hideFromAccessibility()") shouldBe true
        source.contains("if (!animationsEnabled)") shouldBe true
    }

    test("sleep transition has no settle shrink and clears residual notes immediately") {
        val source = productionSource("wordtaker/cat/CatSkin.kt")

        source.contains("\"settle\"") shouldBe false
        source.contains("RETURN_MS") shouldBe false
        source.contains("rt.notes.clear()") shouldBe true
        source.contains("sizeDemoPx = 8f") shouldBe true
        source.contains("sizeDemoPx = 10f") shouldBe true
        source.contains("sizeDemoPx = 12f") shouldBe true
        source.contains("outwardOffsetDemoPx = 4f") shouldBe true
        source.contains("outwardOffsetDemoPx = 8f") shouldBe true
        source.contains("outwardOffsetDemoPx = 12f") shouldBe true
        source.contains("fontSize = fontSizeSp.sp") shouldBe true
        source.contains("fontSize = z.sizeSp.sp") shouldBe false
    }

    test("start recording and display status expose separate non duplicated semantics") {
        val source = productionSource("wordtaker/voice/CatKeyboardLayout.kt")
        val status = source
            .substringAfter("private fun VoiceStatusIndicator(")
            .substringBefore("private fun TalkPill(")
        val talk = source
            .substringAfter("private fun TalkPill(")
            .substringBefore("internal fun VoiceUiState.toCatState()")

        status.contains(".clickable(") shouldBe false
        status.contains("voiceStatusDescription(state.phase, state.polishOutcome)") shouldBe true
        talk.contains("contentDescription = \"开始新录音\"") shouldBe true
        talk.contains("hideFromAccessibility()") shouldBe true
        source.contains("overflow = TextOverflow.Ellipsis") shouldBe true
    }

    test("candidate replacement excludes normal controls and polishing status viewport is not clipped") {
        val source = productionSource("wordtaker/voice/CatKeyboardLayout.kt")
        val candidateContent = source
            .substringAfter("private fun CandidateToolbarContent(")
            .substringBefore("private fun CatRecordingPanel(")
        val status = source
            .substringAfter("private fun VoiceStatusIndicator(")
            .substringBefore("private fun TalkPill(")

        source.contains("VoiceToolbarPresentation.Candidates -> CandidateToolbarContent(") shouldBe true
        candidateContent.contains("ToolbarCatButton(") shouldBe false
        candidateContent.contains("TalkPill(") shouldBe false
        candidateContent.contains("CandidatesRow(") shouldBe true
        status.contains("statusCatVisualSpec(state.phase, sizing)") shouldBe true
        status.contains("viewportWidthDp") shouldBe true
        status.contains("clipToBounds()") shouldBe false
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
