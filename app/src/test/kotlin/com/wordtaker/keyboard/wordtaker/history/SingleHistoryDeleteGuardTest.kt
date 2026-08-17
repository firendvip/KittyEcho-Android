package com.wordtaker.keyboard.wordtaker.history

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SingleHistoryDeleteGuardTest : FunSpec({

    test("cancel back and outside dismissal leave the target untouched") {
        listOf("取消", "返回", "点外部").forEach { _ ->
            val guard = SingleHistoryDeleteGuard()

            guard.request(41) shouldBe true
            guard.dismiss()

            guard.confirm() shouldBe null
            guard.request(42) shouldBe true
        }
    }

    test("rapid repeated confirmation consumes one exact target only once") {
        val guard = SingleHistoryDeleteGuard()

        guard.request(41) shouldBe true

        guard.confirm() shouldBe 41
        guard.confirm() shouldBe null
        guard.request(42) shouldBe false
    }

    test("list refresh or another delete request cannot replace the pending target") {
        val guard = SingleHistoryDeleteGuard()

        guard.request(41) shouldBe true
        guard.request(99) shouldBe false

        guard.confirm() shouldBe 41
    }

    test("only completing the active deletion unlocks the next target") {
        val guard = SingleHistoryDeleteGuard()

        guard.request(41)
        guard.confirm() shouldBe 41

        guard.complete(99)
        guard.request(42) shouldBe false

        guard.complete(41)
        guard.request(42) shouldBe true
        guard.confirm() shouldBe 42
    }

    test("Chinese copy explicitly describes one record and distinct safe actions") {
        HistoryDeleteCopy.TITLE shouldBe "删除这条历史记录？"
        HistoryDeleteCopy.MESSAGE shouldBe "仅删除上面这一条，删除后无法恢复。"
        HistoryDeleteCopy.CANCEL shouldBe "取消"
        HistoryDeleteCopy.CONFIRM shouldBe "删除"
        HistoryDeleteCopy.PANE_TITLE shouldBe "单条历史记录删除确认"
        HistoryDeleteCopy.recordIdentifier(41) shouldBe "记录 ID 41"
        HistoryDeleteCopy.deleteActionDescription(41, "春天去公园") shouldBe
            "删除历史记录 ID 41：春天去公园"
    }

    test("target preview is single-line bounded and never splits an emoji surrogate pair") {
        val preview = "猫".repeat(31) + "🐈" + "\n尾巴"

        historyDeletePreview(preview) shouldBe "猫".repeat(31) + "🐈"
        historyDeletePreview("  春天\n  去公园  ") shouldBe "春天 去公园"
        historyDeletePreview(" \n ") shouldBe "空白记录"
    }
})
