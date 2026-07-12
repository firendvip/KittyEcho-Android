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

package com.wordtaker.keyboard.ime.text

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.app.FlorisPreferenceStore
import com.wordtaker.keyboard.ime.smartbar.IncognitoDisplayMode
import com.wordtaker.keyboard.ime.smartbar.InlineSuggestionsStyleCache
import com.wordtaker.keyboard.ime.smartbar.quickaction.QuickActionsOverflowPanel
import com.wordtaker.keyboard.wordtaker.voice.DARK_PANEL_BG
import com.wordtaker.keyboard.wordtaker.voice.WT_PANEL_GRAY
import com.wordtaker.keyboard.wordtaker.handwriting.HandwritingInputLayout
import com.wordtaker.keyboard.ime.nlp.handwriting.HandwritingLanguageProvider
import com.wordtaker.keyboard.ime.keyboard.KeyboardMode
import com.wordtaker.keyboard.ime.text.keyboard.TextKeyboardLayout
import com.wordtaker.keyboard.ime.theme.FlorisImeUi
import com.wordtaker.keyboard.wordtaker.toolbar.QuickSymbolStrip
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import com.wordtaker.lib.snygg.ui.SnyggIcon

@Composable
fun TextInputLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val subtypeManager by context.subtypeManager()

    val prefs by FlorisPreferenceStore

    val state by keyboardManager.activeState.collectAsState()
    val evaluator by keyboardManager.activeEvaluator.collectAsState()
    val activeSubtype by subtypeManager.activeSubtypeFlow.collectAsState()

    // The handwriting subtype replaces the key grid entirely with the ink pad. Detect it via its
    // dedicated suggestion-provider id (set on HANDWRITING_DEFAULT) so the check survives any
    // future layout-map tweaks.
    val isHandwriting = activeSubtype.nlpProviders.suggestion == HandwritingLanguageProvider.ProviderId

    InlineSuggestionsStyleCache()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            // #7 键盘面板背景与猫语音界面统一为同一档浅灰（键盘那种灰，比微信稿略浅）。
            // 键按本身仍由 Snygg 主题绘制，这层灰只填充键之间/面板底色。深色模式用协调深灰。
            .background(
                if (isSystemInDarkTheme()) DARK_PANEL_BG else WT_PANEL_GRAY,
            ),
    ) {
        // WordTaker: the full FlorisBoard Smartbar (action toggles + overflow) is
        // replaced by the minimal WeChat-style ImeToolbar above the keyboard.
        // The pinyin candidate strip no longer renders here — candidates now fill
        // the TOP toolbar row (see CatKeyboardLayout). Keeping it suppressed avoids
        // double candidates AND keeps the keyboard body height stable (this bar was
        // already 0-height when empty; it is now always absent).
        // WtCandidatesBar() — intentionally removed; candidates render in the top strip.
        if (isHandwriting) {
            HandwritingInputLayout()
        } else if (state.isActionsOverflowVisible) {
            QuickActionsOverflowPanel()
        } else {
            Box {
                val incognitoDisplayMode by prefs.keyboard.incognitoDisplayMode.collectAsState()
                val showIncognitoIcon = evaluator.state.isIncognitoMode &&
                    incognitoDisplayMode == IncognitoDisplayMode.DISPLAY_BEHIND_KEYBOARD
                if (showIncognitoIcon) {
                    SnyggIcon(
                        FlorisImeUi.IncognitoModeIndicator.elementName,
                        modifier = Modifier
                            .matchParentSize()
                            .align(Alignment.Center),
                        painter = painterResource(R.drawable.ic_incognito),
                    )
                }
                // WordTaker: quick-symbol strip above the key rows on the symbols/numeric
                // keyboards (matches the target design). It is absent on the letter keyboard.
                val showQuickSymbols = state.keyboardMode == KeyboardMode.SYMBOLS ||
                    state.keyboardMode == KeyboardMode.SYMBOLS2 ||
                    state.keyboardMode == KeyboardMode.NUMERIC ||
                    state.keyboardMode == KeyboardMode.NUMERIC_ADVANCED
                if (showQuickSymbols) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        QuickSymbolStrip()
                        TextKeyboardLayout(evaluator = evaluator)
                    }
                } else {
                    TextKeyboardLayout(evaluator = evaluator)
                }
            }
        }
    }
}
