package com.wordtaker.keyboard.ime.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class NlpManagerCloudRequestRaceTest : FunSpec({

    fun moduleRoot(): File = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile

    fun String.bodyBetween(start: String, end: String): String =
        substringAfter(start).substringBefore(end)

    test("an older local computation cannot invalidate or publish after the newest request") {
        val source = File(
            moduleRoot(),
            "src/main/kotlin/com/wordtaker/keyboard/ime/nlp/NlpManager.kt",
        ).readText()
        val suggestBody = source.bodyBetween(
            start = "fun suggest(subtype: Subtype, content: EditorContent)",
            end = "private fun requestCloudDictAugmentIfApplicable",
        )
        val cloudRequestBody = source.bodyBetween(
            start = "private fun requestCloudDictAugmentIfApplicable",
            end = "private fun isCloudDictRequestAllowed",
        )

        suggestBody shouldContain
            "val requestId = suggestionRequestGate.beginRequest()"
        suggestBody shouldContain
            "suggestionRequestGate.runIfLatest(requestId)"
        cloudRequestBody shouldNotContain
            "cloudDictAugmenter.invalidate()"
    }

    test("direct suggestions and clearing begin a new request that immediately cancels cloud work") {
        val source = File(
            moduleRoot(),
            "src/main/kotlin/com/wordtaker/keyboard/ime/nlp/NlpManager.kt",
        ).readText()
        val directBody = source.bodyBetween(
            start = "fun suggestDirectly(suggestions: List<SuggestionCandidate>)",
            end = "fun clearSuggestions()",
        )
        val clearBody = source.bodyBetween(
            start = "fun clearSuggestions()",
            end = "fun getAutoCommitCandidate()",
        )

        directBody shouldContain
            "val requestId = suggestionRequestGate.beginRequest()"
        clearBody shouldContain
            "val requestId = suggestionRequestGate.beginRequest()"
    }
})
