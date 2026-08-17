package com.wordtaker.keyboard.wordtaker.ui

import com.wordtaker.keyboard.ime.smartbar.shouldShowCandidateSecondaryText
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.json.JSONObject
import java.io.File

class DoubaoImeSkinTest : FunSpec({

    test("light palette matches the emulator reference capture") {
        DoubaoImeSkin.Light.panelArgb shouldBe 0xFFE0E2E6L
        DoubaoImeSkin.Light.keyArgb shouldBe 0xFFFFFFFFL
        DoubaoImeSkin.Light.functionalKeyArgb shouldBe 0xFFBDC2C8L
        DoubaoImeSkin.Light.primaryArgb shouldBe 0xFF4F84FFL
        DoubaoImeSkin.Light.foregroundArgb shouldBe 0xFF17181AL
    }

    test("layout metrics preserve the measured Doubao keyboard rhythm") {
        DoubaoImeSkin.toolbarHeightRatio.shouldBeExactly(0.152f)
        DoubaoImeSkin.keyCornerRadiusDp.shouldBeExactly(10f)
        DoubaoImeSkin.keyLabelSizeSp.shouldBeExactly(20f)
        DoubaoImeSkin.edgeActionKeyWidthFactor.shouldBeExactly(1.35f)
        DoubaoImeSkin.bottomActionKeyWidthFactor.shouldBeExactly(2f)
        DoubaoImeSkin.toolbarTouchTargetDp.shouldBeExactly(48f)
        DoubaoImeSkin.minimumToolbarHeightDp.shouldBeExactly(54f)
    }

    test("candidate stack partitions every supported viewport without overlap") {
        listOf(250f, 280f, 320f, 360f).forEach { viewportWidthDp ->
            val stripHeightDp = (viewportWidthDp * DoubaoImeSkin.toolbarHeightRatio)
                .coerceAtLeast(DoubaoImeSkin.minimumToolbarHeightDp)
            val candidateSlotHeightDp = stripHeightDp - DoubaoImeSkin.pinyinPreeditHeightDp
            val candidateVisualHeightDp = DoubaoImeSkin.candidateLineBoxHeightDp +
                DoubaoImeSkin.candidateVerticalPaddingDp * 2f +
                DoubaoImeSkin.candidateVerticalMarginDp * 2f

            (DoubaoImeSkin.pinyinPreeditHeightDp <= stripHeightDp) shouldBe true
            (DoubaoImeSkin.pinyinCandidateRowHeightDp <= candidateSlotHeightDp) shouldBe true
            (candidateVisualHeightDp <= DoubaoImeSkin.pinyinCandidateRowHeightDp) shouldBe true
        }
    }

    test("only pinyin-family secondary text is lifted into the preedit line") {
        DoubaoImeSkin.shouldLiftSecondaryText("org.florisboard.nlp.providers.pinyin") shouldBe true
        DoubaoImeSkin.shouldLiftSecondaryText("org.florisboard.nlp.providers.shuangpin") shouldBe true
        DoubaoImeSkin.shouldLiftSecondaryText("org.florisboard.nlp.providers.t9") shouldBe true
        DoubaoImeSkin.shouldLiftSecondaryText("org.florisboard.nlp.providers.han.shape") shouldBe false
        DoubaoImeSkin.shouldLiftSecondaryText(null) shouldBe false
    }

    test("mixed candidate providers only hide the lifted pinyin spelling") {
        val liftedProviders = DoubaoImeSkin.liftedSecondaryTextProviderIds

        shouldShowCandidateSecondaryText(
            enabled = true,
            providerId = "org.florisboard.nlp.providers.pinyin",
            hiddenProviderIds = liftedProviders,
        ) shouldBe false
        shouldShowCandidateSecondaryText(
            enabled = true,
            providerId = "org.florisboard.nlp.providers.emoji",
            hiddenProviderIds = liftedProviders,
        ) shouldBe true
        shouldShowCandidateSecondaryText(
            enabled = true,
            providerId = null,
            hiddenProviderIds = liftedProviders,
        ) shouldBe true
        shouldShowCandidateSecondaryText(
            enabled = false,
            providerId = "org.florisboard.nlp.providers.emoji",
            hiddenProviderIds = liftedProviders,
        ) shouldBe false
    }

    test("dedicated preedit is only used when every secondary label is pinyin-family") {
        DoubaoImeSkin.shouldUseDedicatedPreedit(
            listOf("org.florisboard.nlp.providers.pinyin", "org.florisboard.nlp.providers.t9"),
        ) shouldBe true
        DoubaoImeSkin.shouldUseDedicatedPreedit(
            listOf("org.florisboard.nlp.providers.pinyin", "org.florisboard.nlp.providers.emoji"),
        ) shouldBe false
        DoubaoImeSkin.shouldUseDedicatedPreedit(
            listOf("org.florisboard.nlp.providers.pinyin", null),
        ) shouldBe false
        DoubaoImeSkin.shouldUseDedicatedPreedit(emptyList()) shouldBe false
    }

    test("voice waveform is bounded, symmetric and reacts to the level") {
        val quiet = DoubaoImeSkin.waveformBarFractions(level = -1f)
        val loud = DoubaoImeSkin.waveformBarFractions(level = 2f)

        quiet shouldHaveSize 21
        loud shouldHaveSize 21
        quiet.zip(quiet.reversed()).forEach { (left, right) -> left shouldBe right }
        loud.zip(loud.reversed()).forEach { (left, right) -> left shouldBe right }
        quiet.all { it in 0.12f..1f } shouldBe true
        loud.all { it in 0.12f..1f } shouldBe true
        (loud[10] > quiet[10]) shouldBe true
        (loud[10] > loud.first()) shouldBe true
    }

    test("bundled day stylesheet targets the measured selectors exactly") {
        assertThemeContract(
            relativePath = "ime/theme/org.florisboard.themes/stylesheets/floris_day.json",
            primary = "#4F84FF",
            panel = "#E0E2E6",
            surface = "#FFFFFF",
            functional = "#BDC2C8",
        )
    }

    test("bundled night stylesheet preserves the same selector contract") {
        assertThemeContract(
            relativePath = "ime/theme/org.florisboard.themes/stylesheets/floris_night.json",
            primary = "#5C8BFF",
            panel = "#202226",
            surface = "#34373C",
            functional = "#4A4E55",
        )
    }

    test("every selectable bundled theme keeps the Doubao visual contract") {
        val dayThemes = listOf(
            "org.florisboard.themes/stylesheets/floris_day.json",
            "org.florisboard.themes/stylesheets/floris_day_borderless.json",
            "org.florisboard.themes.my/stylesheets/floris_day_my.json",
            "org.florisboard.themes.my/stylesheets/floris_day_my_borderless.json",
        )
        val nightThemes = listOf(
            "org.florisboard.themes/stylesheets/floris_night.json",
            "org.florisboard.themes/stylesheets/floris_night_borderless.json",
            "org.florisboard.themes/stylesheets/floris_pure_night.json",
            "org.florisboard.themes/stylesheets/floris_pure_night_borderless.json",
            "org.florisboard.themes.my/stylesheets/floris_night_my.json",
            "org.florisboard.themes.my/stylesheets/floris_night_my_borderless.json",
            "org.florisboard.themes.my/stylesheets/floris_pure_night_my.json",
            "org.florisboard.themes.my/stylesheets/floris_pure_night_my_borderless.json",
        )

        dayThemes.forEach { theme ->
            assertThemeContract(
                relativePath = "ime/theme/$theme",
                primary = "#4F84FF",
                panel = "#E0E2E6",
                surface = "#FFFFFF",
                functional = "#BDC2C8",
            )
        }
        nightThemes.forEach { theme ->
            assertThemeContract(
                relativePath = "ime/theme/$theme",
                primary = "#5C8BFF",
                panel = "#202226",
                surface = "#34373C",
                functional = "#4A4E55",
            )
        }
    }
})

private fun assertThemeContract(
    relativePath: String,
    primary: String,
    panel: String,
    surface: String,
    functional: String,
) {
    val stylesheet = JSONObject(sourceAsset(relativePath))
    val defines = stylesheet.getJSONObject("@defines")

    defines.getString("--primary") shouldBe primary
    defines.getString("--background") shouldBe panel
    defines.getString("--surface") shouldBe surface
    defines.getString("--functional-key") shouldBe functional
    defines.getString("--shape") shouldBe "rounded-corner(10dp, 10dp, 10dp, 10dp)"

    stylesheet.getJSONObject("key").apply {
        getString("background") shouldBe "var(--surface)"
        getString("font-size") shouldBe "20sp"
        getString("font-weight") shouldBe "normal"
        getString("shape") shouldBe "var(--shape)"
    }
    stylesheet.getJSONObject("key:pressed")
        .getString("background") shouldBe "var(--surface-variant)"
    stylesheet.getJSONObject("key[code=-7,-11,-202,-203,-233]")
        .getString("background") shouldBe "var(--functional-key)"
    stylesheet.getJSONObject("key[code=-227]").has("background") shouldBe false
    stylesheet.getJSONObject("key[code=10]")
        .getString("background") shouldBe "var(--accent)"
    stylesheet.getJSONObject("smartbar-candidate-word").apply {
        getString("margin") shouldBe "3dp 1dp"
        // Snygg's two-value syntax is horizontal then vertical.
        getString("padding") shouldBe "8dp 2dp"
    }
    stylesheet.getJSONObject("smartbar-candidate-word[first=1]").apply {
        getString("background") shouldBe "var(--surface)"
        getString("foreground") shouldBe "var(--candidate)"
    }
}

private fun sourceAsset(relativePath: String): String {
    val start = File(checkNotNull(System.getProperty("user.dir")))
    val candidates = generateSequence(start) { it.parentFile }
        .take(6)
        .flatMap { root ->
            sequenceOf(
                File(root, "src/main/assets/$relativePath"),
                File(root, "app/src/main/assets/$relativePath"),
            )
        }
    return candidates.firstOrNull(File::isFile)?.readText()
        ?: error("Unable to locate source asset: $relativePath")
}
