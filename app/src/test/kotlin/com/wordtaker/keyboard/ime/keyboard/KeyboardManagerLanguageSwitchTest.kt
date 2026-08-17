package com.wordtaker.keyboard.ime.keyboard

import com.wordtaker.keyboard.ime.ImeUiMode
import com.wordtaker.keyboard.ime.nlp.WordSuggestionCandidate
import com.wordtaker.keyboard.ime.text.key.KeyVariation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class KeyboardManagerLanguageSwitchTest : FunSpec({

    test("keyboard state register regions and flags use unique bits") {
        val occupiedMasks = listOf(
            KeyboardState.M_KEYBOARD_MODE shl KeyboardState.O_KEYBOARD_MODE,
            KeyboardState.M_KEY_VARIATION shl KeyboardState.O_KEY_VARIATION,
            KeyboardState.M_INPUT_SHIFT_STATE shl KeyboardState.O_INPUT_SHIFT_STATE,
            KeyboardState.M_IME_UI_MODE shl KeyboardState.O_IME_UI_MODE,
            KeyboardState.F_IS_SELECTION_MODE,
            KeyboardState.F_IS_MANUAL_SELECTION_MODE,
            KeyboardState.F_IS_MANUAL_SELECTION_MODE_START,
            KeyboardState.F_IS_MANUAL_SELECTION_MODE_END,
            KeyboardState.F_IS_INCOGNITO_MODE,
            KeyboardState.F_IS_ACTIONS_OVERFLOW_VISIBLE,
            KeyboardState.F_IS_ACTIONS_EDITOR_VISIBLE,
            KeyboardState.F_IS_COMPOSING_ENABLED,
            KeyboardState.F_IS_CHAR_HALF_WIDTH,
            KeyboardState.F_IS_KANA_KATA,
            KeyboardState.F_IS_KANA_SMALL,
            KeyboardState.F_IS_ENGLISH_MODE,
            KeyboardState.F_IS_RTL_LAYOUT_DIRECTION,
            KeyboardState.F_IS_SUBTYPE_SELECTION_VISIBLE,
            KeyboardState.F_DEBUG_SHOW_DRAG_AND_DROP_HELPERS,
        )

        occupiedMasks.indices.forEach { left ->
            (left + 1 until occupiedMasks.size).forEach { right ->
                (occupiedMasks[left] and occupiedMasks[right]) shouldBe 0uL
            }
        }
    }

    test("English mode flag never aliases the IME UI mode register") {
        val state = KeyboardState.new().apply {
            imeUiMode = ImeUiMode.TEXT
            isEnglishMode = true
        }

        state.isEnglishMode shouldBe true
        state.imeUiMode shouldBe ImeUiMode.TEXT

        state.imeUiMode = ImeUiMode.MEDIA
        state.isEnglishMode shouldBe true
        state.imeUiMode shouldBe ImeUiMode.MEDIA
    }

    test("short press always returns to text and toggles Chinese to English") {
        listOf(ImeUiMode.TEXT, ImeUiMode.CAT_VOICE, ImeUiMode.HISTORY).forEach { currentMode ->
            val plan = planLanguageSwitch(
                currentImeUiMode = currentMode,
                isEnglishMode = false,
                keyVariation = KeyVariation.NORMAL,
                suggestionsEnabled = true,
                providerForcesSuggestions = true,
                hasComposing = false,
                hasPreferredCandidate = false,
            )

            plan.targetImeUiMode shouldBe ImeUiMode.TEXT
            plan.isEnglishMode shouldBe true
            plan.isComposingEnabled shouldBe false
            plan.compositionAction shouldBe LanguageSwitchCompositionAction.None
        }
    }

    test("second short press returns to Chinese and restores composing when allowed") {
        val plan = planLanguageSwitch(
            currentImeUiMode = ImeUiMode.TEXT,
            isEnglishMode = true,
            keyVariation = KeyVariation.NORMAL,
            suggestionsEnabled = false,
            providerForcesSuggestions = true,
            hasComposing = false,
            hasPreferredCandidate = false,
        )

        plan.targetImeUiMode shouldBe ImeUiMode.TEXT
        plan.isEnglishMode shouldBe false
        plan.isComposingEnabled shouldBe true
    }

    test("password fields never regain composing when returning to Chinese") {
        planLanguageSwitch(
            currentImeUiMode = ImeUiMode.TEXT,
            isEnglishMode = true,
            keyVariation = KeyVariation.PASSWORD,
            suggestionsEnabled = true,
            providerForcesSuggestions = true,
            hasComposing = false,
            hasPreferredCandidate = false,
        ).isComposingEnabled shouldBe false
    }

    test("pending pinyin commits an available candidate before clearing suggestions") {
        planLanguageSwitch(
            currentImeUiMode = ImeUiMode.TEXT,
            isEnglishMode = false,
            keyVariation = KeyVariation.NORMAL,
            suggestionsEnabled = true,
            providerForcesSuggestions = true,
            hasComposing = true,
            hasPreferredCandidate = true,
        ).compositionAction shouldBe LanguageSwitchCompositionAction.Candidate
    }

    test("pending pinyin prefers the first visible candidate even when it is not auto commit") {
        val firstVisible = WordSuggestionCandidate(
            text = "你",
            isEligibleForAutoCommit = false,
        )
        val laterAutoCommit = WordSuggestionCandidate(
            text = "呢",
            isEligibleForAutoCommit = true,
        )

        preferredLanguageSwitchCandidate(listOf(firstVisible, laterAutoCommit)) shouldBe firstVisible
        preferredLanguageSwitchCandidate(emptyList()) shouldBe null
    }

    test("pending pinyin falls back to raw composing text when no candidate is ready") {
        planLanguageSwitch(
            currentImeUiMode = ImeUiMode.TEXT,
            isEnglishMode = false,
            keyVariation = KeyVariation.NORMAL,
            suggestionsEnabled = true,
            providerForcesSuggestions = true,
            hasComposing = true,
            hasPreferredCandidate = false,
        ).compositionAction shouldBe LanguageSwitchCompositionAction.Raw
    }

    test("long press remains routed to the system input method picker") {
        val source = productionSource("ime/text/keyboard/TextKeyboardLayout.kt")
        val languageLongPress = source
            .substringAfter("KeyCode.LANGUAGE_SWITCH -> {")
            .substringBefore("else -> {")

        languageLongPress shouldContain
            "inputEventDispatcher.sendDownUp(TextKeyData.SYSTEM_INPUT_METHOD_PICKER)"
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
