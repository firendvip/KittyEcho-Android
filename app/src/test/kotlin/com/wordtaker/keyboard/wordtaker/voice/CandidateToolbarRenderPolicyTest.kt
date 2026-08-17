package com.wordtaker.keyboard.wordtaker.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class CandidateToolbarRenderPolicyTest : FunSpec({

    test("raw composing survives empty local and asynchronous cloud candidate snapshots") {
        val snapshots = listOf(
            false, // local provider has not published yet
            true,  // local candidates
            true,  // cloud merge/reorder
            false, // transient empty refresh
            true,  // refreshed candidates
        ).map { hasCandidates ->
            candidateToolbarRenderState(
                composingText = "nihao",
                hasVisibleCandidates = hasCandidates,
                isRecording = false,
            )
        }

        snapshots.map { it.preeditText } shouldContainExactly
            listOf("nihao", "nihao", "nihao", "nihao", "nihao")
        snapshots.map { it.presentation }.distinct() shouldContainExactly
            listOf(VoiceToolbarPresentation.Candidates)
    }

    test("preedit follows only the exact composing lifecycle") {
        listOf("n", "ni", "ni'h", "ni'hao").map { composing ->
            candidateToolbarRenderState(
                composingText = composing,
                hasVisibleCandidates = true,
                isRecording = false,
            ).preeditText
        } shouldContainExactly listOf("n", "ni", "ni'h", "ni'hao")

        candidateToolbarRenderState(
            composingText = "",
            hasVisibleCandidates = true,
            isRecording = false,
        ).preeditText shouldBe null
        candidateToolbarRenderState(
            composingText = "",
            hasVisibleCandidates = false,
            isRecording = false,
        ) shouldBe CandidateToolbarRenderState(
            presentation = VoiceToolbarPresentation.Normal,
            preeditText = null,
        )
        candidateToolbarRenderState(
            composingText = "nihao",
            hasVisibleCandidates = true,
            isRecording = true,
        ) shouldBe CandidateToolbarRenderState(
            presentation = VoiceToolbarPresentation.Normal,
            preeditText = null,
        )
    }

    test("production preedit policy does not read candidate labels or provider identity") {
        val source = productionSource("wordtaker/voice/CatKeyboardLayout.kt")
        val stateBlock = source
            .substringAfter("val toolbarState = candidateToolbarRenderState(")
            .substringBefore("// VISUAL")

        stateBlock shouldNotContain "secondaryText"
        stateBlock shouldNotContain "sourceProvider"
        stateBlock shouldNotContain "first()"
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
