package com.wordtaker.keyboard.wordtaker.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TranscriptTextTest : FunSpec({

    test("counts requested Unicode extended grapheme clusters as user-visible characters") {
        TranscriptText.prepare("e\u0301👍🏽🇨🇳👨‍👩‍👧‍👦中A") shouldBe PreparedTranscript(
            text = "e\u0301👍🏽🇨🇳👨‍👩‍👧‍👦中A",
            visibleGraphemeCount = 6,
        )
    }

    test("trims Unicode White Space only and preserves internal whitespace and normalization") {
        TranscriptText.prepare("\u00A0\u3000a b\nc e\u0301\u202F") shouldBe PreparedTranscript(
            text = "a b\nc e\u0301",
            visibleGraphemeCount = 4,
        )
    }

    test("ignores whitespace control and format-only clusters at the threshold") {
        TranscriptText.prepare("\u200B\u0000\u2060 \n") shouldBe PreparedTranscript(
            text = "\u200B\u0000\u2060",
            visibleGraphemeCount = 0,
        )
    }

    test("does not normalize or rewrite the transcript body") {
        val decomposed = "e\u0301"
        val prepared = TranscriptText.prepare(decomposed)

        prepared.text shouldBe decomposed
        prepared.visibleGraphemeCount shouldBe 1
    }
})
