package com.wordtaker.keyboard.ime.nlp.pinyin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class PinyinSegmentedSelectionIntegrationContractTest : FunSpec({

    test("candidate taps route segmented pinyin before the generic final commit") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val keyboardManager = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/keyboard/KeyboardManager.kt",
        ).readText()
        val commitCandidate = keyboardManager
            .substringAfter("fun commitCandidate(candidate: SuggestionCandidate)")
            .substringBefore("fun commitGesture(")

        commitCandidate shouldContain "PinyinSegmentedSuggestionCandidate"
        commitCandidate shouldContain "replaceComposingText"
    }

    test("local and cloud candidates carry the same segmented selection contract") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val pinyinProvider = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/nlp/pinyin/PinyinLanguageProvider.kt",
        ).readText()
        val cloudAugmenter = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/nlp/pinyin/CloudDictionaryAugmenter.kt",
        ).readText()

        pinyinProvider shouldContain "PinyinSegmentedSuggestionCandidate("
        cloudAugmenter shouldContain "segmentedSelectionForCloudCandidate("
    }

    test("partial replacement never finishes composing while explicit enter clears the session") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val abstractEditor = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/editor/AbstractEditorInstance.kt",
        ).readText()
        val editorInstance = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/editor/EditorInstance.kt",
        ).readText()
        val replacement = abstractEditor
            .substringAfter("open fun replaceComposingText(text: String)")
            .substringBefore("protected suspend fun deleteAroundCursor(")
        val enter = editorInstance
            .substringAfter("fun tryPerformEnterCommitRaw()")
            .substringBefore("fun performEnterAction(")

        replacement shouldContain "ic.setComposingText(text, 1)"
        replacement shouldNotContain "finishComposingText"
        enter shouldContain "resetPinyinCompositionSession()"
        enter shouldContain "originalRawFor(activeContent.composingText)"
        enter shouldContain "finalizeComposingText(raw)"
    }

    test("remaining segment owns cloud requests and explicit lifecycle boundaries clear stale state") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val nlpManager = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/nlp/NlpManager.kt",
        ).readText()
        val editorInstance = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/editor/EditorInstance.kt",
        ).readText()
        val keyboardManager = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/keyboard/KeyboardManager.kt",
        ).readText()

        nlpManager shouldContain "remainingRawFor(content.composingText)"
        nlpManager shouldContain "fun resetPinyinCompositionSession()"
        editorInstance shouldContain "override fun reset()"
        editorInstance shouldContain "nlpManager.resetPinyinCompositionSession()"
        editorInstance shouldContain "expectsSelectionUpdate(newSelection, composing)"
        keyboardManager shouldContain "allowPartialPinyinSelection = false"
    }

    test("language switch never strands composing text when its preferred candidate is stale") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val keyboardManager = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/keyboard/KeyboardManager.kt",
        ).readText()
        val rejectBranch = keyboardManager
            .substringAfter("PinyinCandidateSelectionPlan.Reject ->")
            .substringBefore("is PinyinCandidateSelectionPlan.Continue")

        rejectBranch shouldContain "if (!allowPartialSelection)"
        rejectBranch shouldContain "activePinyinCompositionSession.clear()"
        rejectBranch shouldContain "editorInstance.finalizeComposingText("
    }

    test("character backspace undoes one selected segment without committing or deleting raw") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val keyboardManager = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/keyboard/KeyboardManager.kt",
        ).readText()
        val backwardDelete = keyboardManager
            .substringAfter("private fun handleBackwardDelete(unit: OperationUnit)")
            .substringBefore("private fun handleForwardDelete(unit: OperationUnit)")

        backwardDelete shouldContain "planPinyinBackspace("
        backwardDelete shouldContain "activePinyinCompositionSession.transition("
        backwardDelete shouldContain "editorInstance.replaceComposingText("
        backwardDelete shouldContain "return"
        backwardDelete shouldNotContain "commitCompletion"
        backwardDelete shouldNotContain "finalizeComposingText"
    }
})
