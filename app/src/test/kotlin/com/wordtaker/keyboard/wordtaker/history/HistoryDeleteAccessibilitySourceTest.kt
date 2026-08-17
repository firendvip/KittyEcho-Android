package com.wordtaker.keyboard.wordtaker.history

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class HistoryDeleteAccessibilitySourceTest : FunSpec({

    test("shared single-delete confirmation exposes modal and button semantics") {
        val source = productionSource("wordtaker/history/HistoryDeleteConfirmation.kt")

        source.contains("paneTitle = HistoryDeleteCopy.PANE_TITLE") shouldBe true
        source.contains("Role.Button") shouldBe true
        source.contains("HistoryDeleteCopy.deleteActionDescription") shouldBe true
        source.contains("HistoryDeleteCopy.TITLE") shouldBe true
        source.contains("HistoryDeleteCopy.MESSAGE") shouldBe true
        source.contains("HistoryDeleteCopy.CANCEL") shouldBe true
        source.contains("HistoryDeleteCopy.CONFIRM") shouldBe true
        source.contains("detectTapGestures(onTap = { onDismiss() })") shouldBe true
        source.contains("onClick = onDismiss") shouldBe true
    }

    test("both history surfaces route item deletion through the shared confirmation") {
        val ime = productionSource("wordtaker/history/ImeHistoryLayout.kt")
        val standalone = productionSource("wordtaker/ui/WordTakerHistoryActivity.kt")

        ime.contains("HistoryDeleteConfirmation(") shouldBe true
        standalone.contains("HistoryDeleteConfirmation(") shouldBe true
        standalone.contains("BackHandler(") shouldBe true
        ime.contains("repository.remove(entry.id)") shouldBe false
        standalone.contains("repository.remove(entry.id)") shouldBe false
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
