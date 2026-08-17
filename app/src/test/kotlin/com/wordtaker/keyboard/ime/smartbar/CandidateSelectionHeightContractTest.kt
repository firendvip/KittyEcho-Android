package com.wordtaker.keyboard.ime.smartbar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class CandidateSelectionHeightContractTest : FunSpec({

    test("single character and phrase selections share one fixed visual height") {
        CandidateSelectionVisualSpec.outerHeightDp.shouldBeExactly(30f)

        val source = productionSource("ime/smartbar/CandidatesRow.kt")
        val item = source.substringAfter("private fun CandidateItem(")

        item shouldContain "Box("
        item shouldContain "modifier = modifier"
        item shouldContain ".pointerInput(Unit)"
        item shouldContain "contentAlignment = Alignment.Center"
        item shouldContain "modifier = Modifier.height(CandidateSelectionVisualSpec.outerHeightDp.dp)"
        item shouldNotContain "modifier = Modifier.wrapContentHeight()"
        item shouldNotContain "SnyggRow(\n        elementName = elementName,\n        attributes = attributes,\n        selector = selector,\n        modifier = modifier"
    }

    test("candidate strip and touch height remain fixed independently of candidate text width") {
        val source = productionSource("ime/smartbar/CandidatesRow.kt")
        val candidateModifier = source
            .substringAfter("val candidateModifier")
            .substringBefore("val list =")

        candidateModifier shouldContain ".fillMaxHeight()"
        candidateModifier shouldContain "wrapContentWidth().widthIn(max = 160.dp)"
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
