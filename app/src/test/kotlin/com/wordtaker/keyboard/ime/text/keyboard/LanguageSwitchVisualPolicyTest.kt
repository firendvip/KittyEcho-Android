package com.wordtaker.keyboard.ime.text.keyboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.math.abs
import kotlin.math.min

class LanguageSwitchVisualPolicyTest : FunSpec({

    test("language key visual width stays at 8.52 percent across widths and densities") {
        listOf(240f, 320f, 360f, 600f, 840f).forEach { widthDp ->
            listOf(0.75f, 1f, 2f, 3.5f).forEach { density ->
                val logicalWidthPx = widthDp * density
                val visualWidthPx = languageSwitchVisualWidthPx(logicalWidthPx)

                abs(visualWidthPx / logicalWidthPx - 0.0852f) shouldBeLessThanOrEqual 0.000001f
                visualWidthPx shouldBeGreaterThan 0f
            }
        }
        languageSwitchVisualWidthPx(0f) shouldBe 0f
        languageSwitchVisualWidthPx(-20f) shouldBe 0f
    }

    test("font policy prevents diagonal glyph overflow at density and font scale extremes") {
        val cases = listOf(
            LanguageSwitchTestCase(240f, 38f, 0.75f, 0.85f),
            LanguageSwitchTestCase(320f, 42f, 1f, 1f),
            LanguageSwitchTestCase(360f, 48f, 3f, 1.3f),
            LanguageSwitchTestCase(600f, 56f, 2f, 2f),
            LanguageSwitchTestCase(840f, 64f, 3.5f, 2.5f),
        )

        cases.forEach { case ->
            val widthPx = languageSwitchVisualWidthPx(case.widthDp * case.density)
            val heightPx = case.heightDp * case.density
            val baseSizeSp = languageSwitchBaseSizeSp(
                requestedSizeSp = 22f,
                availableWidthPx = widthPx,
                availableHeightPx = heightPx,
                density = case.density,
                fontScale = case.fontScale,
            )
            val footprintPx = languageSwitchContentFootprintPx(
                baseSizeSp = baseSizeSp,
                density = case.density,
                fontScale = case.fontScale,
            )
            val paddedLimitPx = min(widthPx, heightPx) -
                2f * LANGUAGE_SWITCH_CONTENT_PADDING_DP * case.density

            baseSizeSp shouldBeGreaterThan 0f
            footprintPx shouldBeLessThanOrEqual (paddedLimitPx + 0.001f)
            baseSizeSp shouldBeLessThanOrEqual 22f
        }
    }

    test("invalid measurement inputs fail closed without NaN or oversized text") {
        languageSwitchBaseSizeSp(22f, 0f, 48f, 3f, 1f) shouldBe 0f
        languageSwitchBaseSizeSp(22f, 30f, 48f, 0f, 1f) shouldBe 0f
        languageSwitchBaseSizeSp(22f, 30f, 48f, 3f, 0f) shouldBe 0f
        languageSwitchBaseSizeSp(Float.NaN, 30f, 48f, 3f, 1f) shouldBe 0f
        languageSwitchContentFootprintPx(0f, 3f, 1f).toDouble() shouldBeExactly 0.0
    }

    test("renderer changes only language key visual width and uses constrained diagonal content") {
        val source = productionSource("ime/text/keyboard/TextKeyboardLayout.kt")
        val render = source
            .substringAfter("private fun TextKeyButton(")
            .substringBefore("private fun SnyggContentScope.TextKeyIcon")

        render shouldContain "languageSwitchVisualWidthPx"
        render shouldContain "size.copy(width ="
        render shouldContain "BoxWithConstraints("
        render shouldContain "languageSwitchBaseSizeSp("
        render shouldContain ".clipToBounds()"
        render shouldContain "keyShape"
        render shouldContain "animatedBgState"
    }
})

private data class LanguageSwitchTestCase(
    val widthDp: Float,
    val heightDp: Float,
    val density: Float,
    val fontScale: Float,
)

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
