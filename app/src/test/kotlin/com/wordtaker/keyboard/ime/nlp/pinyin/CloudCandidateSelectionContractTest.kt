package com.wordtaker.keyboard.ime.nlp.pinyin

import com.wordtaker.keyboard.ime.nlp.SuggestionCandidate
import com.wordtaker.keyboard.ime.nlp.WordSuggestionCandidate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.File

class CloudCandidateSelectionContractTest : FunSpec({

    test("space selects the visible candidate but enter never routes through candidates") {
        val cloudFirst = CloudSuggestionCandidate(text = "云第一", score = 0.9)
        val candidates: List<SuggestionCandidate> = listOf(
            cloudFirst,
            WordSuggestionCandidate(text = "本地第一"),
        )

        cloudFirst.isEligibleForAutoCommit.shouldBeFalse()
        candidates.firstOrNull() shouldBeSameInstanceAs cloudFirst

        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val keyboardManager = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/keyboard/KeyboardManager.kt",
        ).readText()
        val editorInstance = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/editor/EditorInstance.kt",
        ).readText()
        val selectionExpression = "nlpManager.activeCandidates.firstOrNull()"

        val handleSpace = keyboardManager
            .substringAfter("private fun handleSpace(")
            .substringBefore("private suspend fun handleToggleIncognitoMode(")
        val enterCommit = editorInstance
            .substringAfter("fun tryPerformEnterCommitRaw()")
            .substringBefore("fun performEnterAction(")

        handleSpace shouldContain selectionExpression
        enterCommit shouldNotContain selectionExpression
        enterCommit shouldNotContain "commitCompletion("
    }
})
