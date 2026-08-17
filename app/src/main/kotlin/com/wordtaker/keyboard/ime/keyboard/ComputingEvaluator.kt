/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wordtaker.keyboard.ime.keyboard

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.ui.graphics.vector.ImageVector
import com.wordtaker.keyboard.FlorisImeService
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.ime.core.DisplayLanguageNamesIn
import com.wordtaker.keyboard.ime.core.Subtype
import com.wordtaker.keyboard.ime.editor.FlorisEditorInfo
import com.wordtaker.keyboard.ime.editor.ImeOptions
import com.wordtaker.keyboard.ime.input.InputShiftState
import com.wordtaker.keyboard.ime.text.key.KeyCode
import com.wordtaker.keyboard.ime.text.key.KeyType
import com.wordtaker.keyboard.ime.window.ImeWindowMode
import com.wordtaker.keyboard.lib.FlorisLocale
import com.wordtaker.keyboard.lib.compose.vectorResource
import com.wordtaker.lib.compose.icons.ForwardDelete

interface ComputingEvaluator {
    val version: Int

    val keyboard: Keyboard

    val editorInfo: FlorisEditorInfo

    val state: KeyboardState

    val subtype: Subtype

    fun context(): Context?

    fun displayLanguageNamesIn(): DisplayLanguageNamesIn

    fun evaluateEnabled(data: KeyData): Boolean

    fun evaluateVisible(data: KeyData): Boolean

    fun isSlot(data: KeyData): Boolean

    fun slotData(data: KeyData): KeyData?
}

object DefaultComputingEvaluator : ComputingEvaluator {
    override val version = -1

    override val keyboard = PlaceholderLoadingKeyboard

    override val editorInfo = FlorisEditorInfo.Unspecified

    override val state = KeyboardState.new()

    override val subtype = Subtype.DEFAULT

    override fun context(): Context? = null

    override fun displayLanguageNamesIn() = DisplayLanguageNamesIn.NATIVE_LOCALE

    override fun evaluateEnabled(data: KeyData): Boolean = true

    override fun evaluateVisible(data: KeyData): Boolean = true

    override fun isSlot(data: KeyData): Boolean = false

    override fun slotData(data: KeyData): KeyData? = null
}

private var cachedDisplayNameState = Triple(FlorisLocale.ROOT, DisplayLanguageNamesIn.SYSTEM_LOCALE, "")

/**
 * Compute language name with a cache to prevent repetitive calling of `locale.displayName()`, which invokes the
 * underlying `LocaleNative.getLanguageName()` method and in turn uses the rather slow ICU data table to look up the
 * language name. This only caches the last display name, but that's more than enough, as a one-time re-computation when
 * the subtype changes does not hurt, the repetitive computation for the same language hurts.
 */
private fun computeLanguageDisplayName(locale: FlorisLocale, displayLanguageNamesIn: DisplayLanguageNamesIn): String {
    val (cachedLocale, cachedDisplayLanguageNamesIn, cachedDisplayName) = cachedDisplayNameState
    if (cachedLocale == locale && cachedDisplayLanguageNamesIn == displayLanguageNamesIn) {
        return cachedDisplayName
    }
    val displayName = when (displayLanguageNamesIn) {
        DisplayLanguageNamesIn.SYSTEM_LOCALE -> locale.displayName()
        DisplayLanguageNamesIn.NATIVE_LOCALE -> locale.displayName(locale)
    }
    cachedDisplayNameState = Triple(locale, displayLanguageNamesIn, displayName)
    return displayName
}

fun ComputingEvaluator.computeLabel(data: KeyData): String? {
    val evaluator = this
    return if (data.type == KeyType.CHARACTER && data.code != KeyCode.SPACE && data.code != KeyCode.CJK_SPACE
        && data.code != KeyCode.HALF_SPACE && data.code != KeyCode.KESHIDA || data.type == KeyType.NUMERIC
    ) {
        data.asString(isForDisplay = true)
    } else {
        when (data.code) {
            KeyCode.PHONE_PAUSE -> evaluator.context()?.getString(R.string.key__phone_pause)
            KeyCode.PHONE_WAIT -> evaluator.context()?.getString(R.string.key__phone_wait)
            // WordTaker 中/英 toggle: the key now VISUALLY renders BOTH "中" and "英" with the
            // current mode highlighted (see TextKeyButton special-case in TextKeyboardLayout).
            // This label is kept as a text fallback / for non-visual consumers.
            KeyCode.LANGUAGE_SWITCH -> "中/英"
            KeyCode.SPACE, KeyCode.CJK_SPACE -> {
                // WeChat-style spacebar: no subtype/language label ("拼音罗马字"). On the
                // main keyboard a mic icon is shown instead (see computeImageVector). On the
                // numeric calculator pad the key reads "空格" (matching the target design);
                // on the symbols keyboards it is a plain, icon-less space bar.
                when (evaluator.keyboard.mode) {
                    KeyboardMode.NUMERIC,
                    KeyboardMode.NUMERIC_ADVANCED -> "空格"
                    else -> null
                }
            }
            KeyCode.IME_UI_MODE_TEXT,
            KeyCode.VIEW_CHARACTERS -> {
                // WordTaker: on the symbols/numeric keyboards, the "back to letters" key shows
                // a "←" arrow (matching the target design) instead of the "ABC" label.
                when (evaluator.keyboard.mode) {
                    KeyboardMode.SYMBOLS,
                    KeyboardMode.SYMBOLS2,
                    KeyboardMode.NUMERIC,
                    KeyboardMode.NUMERIC_ADVANCED -> "←"
                    else -> evaluator.context()?.getString(R.string.key__view_characters)
                }
            }
            // WordTaker: the ENTER key shows a "换行" text label on the symbols/numeric keyboards
            // (matching the target design). The icon is suppressed for these modes in
            // computeImageVector below.
            KeyCode.ENTER -> when (evaluator.keyboard.mode) {
                KeyboardMode.SYMBOLS,
                KeyboardMode.SYMBOLS2,
                KeyboardMode.NUMERIC,
                KeyboardMode.NUMERIC_ADVANCED -> "换行"
                else -> null
            }
            KeyCode.VIEW_NUMERIC,
            KeyCode.VIEW_NUMERIC_ADVANCED -> {
                evaluator.context()?.getString(R.string.key__view_numeric)
            }
            KeyCode.VIEW_PHONE -> {
                evaluator.context()?.getString(R.string.key__view_phone)
            }
            KeyCode.VIEW_PHONE2 -> {
                evaluator.context()?.getString(R.string.key__view_phone2)
            }
            KeyCode.VIEW_SYMBOLS -> {
                // WordTaker: on the numeric calculator pad this key reads "符号" (jump to the
                // symbols keyboard) instead of the default "123" label.
                when (evaluator.keyboard.mode) {
                    KeyboardMode.NUMERIC,
                    KeyboardMode.NUMERIC_ADVANCED -> "符号"
                    else -> evaluator.context()?.getString(R.string.key__view_symbols)
                }
            }
            KeyCode.VIEW_SYMBOLS2 -> {
                evaluator.context()?.getString(R.string.key__view_symbols2)
            }
            // WordTaker 符号页 category tabs (bottom row of the SYMBOLS2 keyboard). The
            // RECENT tab renders a clock icon instead (see computeImageVector below).
            KeyCode.SYM2_CAT_CJK -> "中"
            KeyCode.SYM2_CAT_EN -> "EN"
            KeyCode.SYM2_CAT_BRACKET -> "[]"
            KeyCode.SYM2_CAT_CURRENCY -> "¥"
            KeyCode.SYM2_CAT_MATH -> "数"
            KeyCode.SYM2_CAT_DASH -> "(-)"
            KeyCode.SYM2_CAT_CIRCLED -> "①"
            KeyCode.HALF_SPACE -> {
                evaluator.context()?.getString(R.string.key__view_half_space)
            }
            KeyCode.KESHIDA -> {
                evaluator.context()?.getString(R.string.key__view_keshida)
            }
            else -> null
        }
    }
}

fun ComputingEvaluator.computeImageVector(data: KeyData): ImageVector? {
    val evaluator = this
    return when (data.code) {
        KeyCode.ARROW_LEFT -> {
            Icons.AutoMirrored.Filled.KeyboardArrowLeft
        }
        KeyCode.ARROW_RIGHT -> {
            Icons.AutoMirrored.Filled.KeyboardArrowRight
        }
        KeyCode.ARROW_UP -> {
            Icons.Default.KeyboardArrowUp
        }
        KeyCode.ARROW_DOWN -> {
            Icons.Default.KeyboardArrowDown
        }
        KeyCode.CLIPBOARD_COPY -> {
            Icons.Default.ContentCopy
        }
        KeyCode.CLIPBOARD_CUT -> {
            Icons.Default.ContentCut
        }
        KeyCode.CLIPBOARD_PASTE -> {
            Icons.Default.ContentPasteGo
        }
        KeyCode.CLIPBOARD_SELECT_ALL -> {
            Icons.Default.SelectAll
        }
        KeyCode.CLIPBOARD_CLEAR_PRIMARY_CLIP -> {
            Icons.Default.DeleteSweep
        }
        KeyCode.COMPACT_LAYOUT_TO_LEFT,
        KeyCode.COMPACT_LAYOUT_TO_RIGHT,
        KeyCode.TOGGLE_COMPACT_LAYOUT -> {
            context()?.vectorResource(id = R.drawable.ic_accessibility_one_handed)
        }
        KeyCode.TOGGLE_FLOATING_WINDOW -> {
            val enabledIcon = context()?.vectorResource(id = R.drawable.ic_floating_keyboard)
            val disabledIcon = context()?.vectorResource(id = R.drawable.ic_floating_keyboard_disable)
            val windowController = FlorisImeService.windowControllerOrNull() ?: return enabledIcon
            when (windowController.activeWindowConfig.value.mode) {
                ImeWindowMode.FIXED -> enabledIcon
                ImeWindowMode.FLOATING -> disabledIcon
            }
        }
        KeyCode.TOGGLE_RESIZE_MODE -> {
            context()?.vectorResource(id = R.drawable.ic_resize)
        }
        KeyCode.VOICE_INPUT -> {
            Icons.Default.KeyboardVoice
        }
        KeyCode.IME_HIDE_UI -> {
            Icons.Default.KeyboardHide
        }
        KeyCode.DELETE -> {
            Icons.AutoMirrored.Outlined.Backspace
        }
        KeyCode.ENTER -> {
            // WordTaker: on the symbols/numeric keyboards the ENTER key renders the "换行" text
            // label (see computeLabel) instead of an icon, so suppress the icon there.
            when (evaluator.keyboard.mode) {
                KeyboardMode.SYMBOLS,
                KeyboardMode.SYMBOLS2,
                KeyboardMode.NUMERIC,
                KeyboardMode.NUMERIC_ADVANCED -> return null
                else -> {}
            }
            val imeOptions = evaluator.editorInfo.imeOptions
            val inputAttributes = evaluator.editorInfo.inputAttributes
            if (imeOptions.flagNoEnterAction || inputAttributes.flagTextMultiLine) {
                Icons.AutoMirrored.Filled.KeyboardReturn
            } else {
                when (imeOptions.action) {
                    ImeOptions.Action.DONE -> Icons.Default.Done
                    ImeOptions.Action.GO -> Icons.AutoMirrored.Filled.ArrowRightAlt
                    ImeOptions.Action.NEXT -> Icons.AutoMirrored.Filled.ArrowRightAlt
                    ImeOptions.Action.NONE -> Icons.AutoMirrored.Filled.KeyboardReturn
                    ImeOptions.Action.PREVIOUS -> Icons.AutoMirrored.Filled.ArrowRightAlt
                    ImeOptions.Action.SEARCH -> Icons.Default.Search
                    ImeOptions.Action.SEND -> Icons.AutoMirrored.Filled.Send
                    ImeOptions.Action.UNSPECIFIED -> Icons.AutoMirrored.Filled.KeyboardReturn
                }
            }
        }
        KeyCode.FORWARD_DELETE -> {
            Icons.AutoMirrored.Default.ForwardDelete
        }
        KeyCode.IME_UI_MODE_MEDIA -> {
            Icons.Default.SentimentSatisfiedAlt
        }
        KeyCode.IME_UI_MODE_CAT_VOICE -> {
            Icons.Default.KeyboardVoice
        }
        KeyCode.IME_UI_MODE_CLIPBOARD -> {
            Icons.AutoMirrored.Outlined.Assignment
        }
        KeyCode.LANGUAGE_SWITCH -> {
            // WordTaker shows a 中/英 text label instead of a globe icon.
            null
        }
        KeyCode.SETTINGS -> {
            Icons.Default.Settings
        }
        KeyCode.SYM2_CAT_RECENT -> {
            // WordTaker 符号页「最近使用」tab — clock glyph.
            Icons.Default.Schedule
        }
        KeyCode.SHIFT -> {
            when (evaluator.state.inputShiftState != InputShiftState.UNSHIFTED) {
                true -> Icons.Default.KeyboardCapslock
                else -> Icons.Default.KeyboardArrowUp
            }
        }
        KeyCode.SPACE, KeyCode.CJK_SPACE -> {
            when (evaluator.keyboard.mode) {
                // Numeric calculator pad shows a "空格" text label (see computeLabel), so
                // suppress the icon. Symbols keyboards get a plain, icon-less space bar to
                // match the target design (no mic).
                KeyboardMode.NUMERIC,
                KeyboardMode.NUMERIC_ADVANCED,
                KeyboardMode.SYMBOLS,
                KeyboardMode.SYMBOLS2 -> null
                KeyboardMode.PHONE,
                KeyboardMode.PHONE2 -> {
                    Icons.Default.SpaceBar
                }
                // Main keyboard: reuse KittyEcho's existing "语音说话" microphone vector.
                // This is visual only; short press, long press and swipe behavior stay on
                // the existing space-key event paths.
                else -> this.context()?.vectorResource(R.drawable.ic_wt_voice)
            }
        }
        KeyCode.UNDO -> {
            Icons.AutoMirrored.Filled.Undo
        }
        KeyCode.REDO -> {
            Icons.AutoMirrored.Filled.Redo
        }
        KeyCode.TOGGLE_ACTIONS_OVERFLOW -> {
            Icons.Default.MoreHoriz
        }
        KeyCode.TOGGLE_INCOGNITO_MODE -> {
            if (evaluator.state.isIncognitoMode) {
                this.context()?.vectorResource(id = R.drawable.ic_incognito)
            } else {
                this.context()?.vectorResource(id = R.drawable.ic_incognito_off)
            }
        }
        KeyCode.TOGGLE_AUTOCORRECT -> {
            Icons.Default.FontDownload
        }
        KeyCode.KANA_SWITCHER -> {
            if (evaluator.state.isKanaKata) {
                this.context()?.vectorResource(R.drawable.ic_keyboard_kana_switcher_kata)
            } else {
                this.context()?.vectorResource(R.drawable.ic_keyboard_kana_switcher_hira)
            }
        }
        KeyCode.CHAR_WIDTH_SWITCHER -> {
            if (evaluator.state.isCharHalfWidth) {
                this.context()?.vectorResource(R.drawable.ic_keyboard_char_width_switcher_full)
            } else {
                this.context()?.vectorResource(R.drawable.ic_keyboard_char_width_switcher_half)
            }
        }
        KeyCode.CHAR_WIDTH_FULL -> {
            this.context()?.vectorResource(R.drawable.ic_keyboard_char_width_switcher_full)
        }
        KeyCode.CHAR_WIDTH_HALF -> {
            this.context()?.vectorResource(R.drawable.ic_keyboard_char_width_switcher_half)
        }
        KeyCode.DRAG_MARKER -> {
            if (evaluator.state.debugShowDragAndDropHelpers) Icons.Default.Close else null
        }
        KeyCode.NOOP -> {
            Icons.Default.Close
        }
        else -> null
    }
}
