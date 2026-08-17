package com.wordtaker.keyboard.ime.editor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PinyinComposingEditorPolicyTest : FunSpec({

    test("partial pinyin selection replaces the same composing region without a final commit") {
        val content = EditorContent(
            text = "已发nihao尾",
            offset = 0,
            localSelection = EditorRange.cursor(7),
            localComposing = EditorRange(2, 7),
            localCurrentWord = EditorRange(2, 7),
        )

        content.replacingComposingText("你hao") shouldBe EditorContent(
            text = "已发你hao尾",
            offset = 0,
            localSelection = EditorRange.cursor(6),
            localComposing = EditorRange(2, 6),
            localCurrentWord = EditorRange(2, 6),
        )
    }

    test("invalid or empty composing snapshots fail without changing editor state") {
        EditorContent.Unspecified.replacingComposingText("你hao").shouldBeNull()
        EditorContent(
            text = "nihao",
            offset = 0,
            localSelection = EditorRange.cursor(5),
            localComposing = EditorRange(0, 5),
            localCurrentWord = EditorRange(0, 5),
        ).replacingComposingText("").shouldBeNull()
        EditorContent(
            text = "nihao",
            offset = 0,
            localSelection = EditorRange.cursor(2),
            localComposing = EditorRange(0, 5),
            localCurrentWord = EditorRange(0, 5),
        ).replacingComposingText("你hao").shouldBeNull()
    }
})
