package com.wordtaker.keyboard.ime.editor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class EnterRawCompositionContractTest : FunSpec({

    test("Chinese enter plan preserves the exact raw composing text") {
        rawCompositionForEnter("zh", "nihao") shouldBe "nihao"
        rawCompositionForEnter("zh-CN", "lüe") shouldBe "lüe"
        rawCompositionForEnter("zh-Hans", "  ni  ") shouldBe "  ni  "
    }

    test("no composing or non Chinese input leaves standard enter untouched") {
        rawCompositionForEnter("zh", "") shouldBe null
        rawCompositionForEnter("en", "hello") shouldBe null
        rawCompositionForEnter("", "raw") shouldBe null
    }

    test("visible newline finishes after raw composing without inserting a newline") {
        listOf(
            enterKeyFollowUp(
                flagNoEnterAction = true,
                isMultiline = false,
                isShiftPressed = false,
                action = ImeOptions.Action.SEND,
                hadRawComposition = true,
            ),
            enterKeyFollowUp(
                flagNoEnterAction = false,
                isMultiline = true,
                isShiftPressed = true,
                action = ImeOptions.Action.SEND,
                hadRawComposition = true,
            ),
            enterKeyFollowUp(
                flagNoEnterAction = false,
                isMultiline = false,
                isShiftPressed = false,
                action = ImeOptions.Action.NONE,
                hadRawComposition = true,
            ),
            enterKeyFollowUp(
                flagNoEnterAction = false,
                isMultiline = false,
                isShiftPressed = false,
                action = ImeOptions.Action.UNSPECIFIED,
                hadRawComposition = true,
            ),
        ) shouldContainExactly List(4) { EnterKeyFollowUp.FINISH_AFTER_RAW }
    }

    test("newline without composing and non-newline editor actions preserve standard behavior") {
        enterKeyFollowUp(
            flagNoEnterAction = false,
            isMultiline = false,
            isShiftPressed = false,
            action = ImeOptions.Action.NONE,
            hadRawComposition = false,
        ) shouldBe EnterKeyFollowUp.INSERT_NEWLINE

        listOf(
            ImeOptions.Action.DONE,
            ImeOptions.Action.GO,
            ImeOptions.Action.NEXT,
            ImeOptions.Action.PREVIOUS,
            ImeOptions.Action.SEARCH,
            ImeOptions.Action.SEND,
        ).forEach { action ->
            enterKeyFollowUp(
                flagNoEnterAction = false,
                isMultiline = false,
                isShiftPressed = false,
                action = action,
                hadRawComposition = false,
            ) shouldBe EnterKeyFollowUp.PERFORM_EDITOR_ACTION
            enterKeyFollowUp(
                flagNoEnterAction = false,
                isMultiline = false,
                isShiftPressed = false,
                action = action,
                hadRawComposition = true,
            ) shouldBe EnterKeyFollowUp.PERFORM_EDITOR_ACTION
        }
    }

    test("editor commits raw exactly once and is independent of candidate order") {
        val source = productionSource("ime/editor/EditorInstance.kt")
        val enterCommit = source
            .substringAfter("fun tryPerformEnterCommitRaw()")
            .substringBefore("fun performEnterAction(")

        enterCommit shouldContain "rawCompositionForEnter("
        enterCommit shouldContain "finalizeComposingText(raw)"
        enterCommit shouldNotContain "activeCandidates"
        enterCommit shouldNotContain "commitCompletion("
        enterCommit shouldContain "return true"
        enterCommit shouldNotContain "return finalizeComposingText(raw)"
        enterCommit.countOccurrences("finalizeComposingText(raw)") shouldBe 1
    }

    test("keyboard finishes a visible newline after raw but preserves non-newline editor actions") {
        val source = productionSource("ime/keyboard/KeyboardManager.kt")
        val handleEnter = source
            .substringAfter("private fun handleEnter()")
            .substringBefore("private fun handleLanguageSwitch()")
        val rawCall = "editorInstance.tryPerformEnterCommitRaw()"

        handleEnter shouldContain rawCall
        handleEnter shouldContain "enterKeyFollowUp("
        handleEnter shouldContain "EnterKeyFollowUp.FINISH_AFTER_RAW -> Unit"
        handleEnter shouldContain "EnterKeyFollowUp.INSERT_NEWLINE -> editorInstance.performEnter()"
        handleEnter shouldContain "EnterKeyFollowUp.PERFORM_EDITOR_ACTION ->"
        handleEnter shouldContain "editorInstance.performEnterAction(action)"
        handleEnter.substringAfter("EnterKeyFollowUp.FINISH_AFTER_RAW ->")
            .substringBefore("EnterKeyFollowUp.INSERT_NEWLINE ->") shouldNotContain "performEnter"
        handleEnter.countOccurrences(rawCall) shouldBe 1
    }
})

private fun String.countOccurrences(needle: String): Int =
    windowed(size = needle.length, step = 1).count { it == needle }

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
