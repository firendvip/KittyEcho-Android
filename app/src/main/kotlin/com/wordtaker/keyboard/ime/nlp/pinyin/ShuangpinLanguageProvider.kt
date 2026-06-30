/*
 * Copyright (C) 2025 The WordTaker Contributors
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

package com.wordtaker.keyboard.ime.nlp.pinyin

import android.content.Context
import android.icu.text.BreakIterator
import com.wordtaker.keyboard.ime.core.Subtype
import com.wordtaker.keyboard.ime.editor.EditorContent
import com.wordtaker.keyboard.ime.editor.EditorRange
import com.wordtaker.keyboard.ime.nlp.BreakIteratorGroup
import com.wordtaker.keyboard.ime.nlp.SuggestionCandidate
import com.wordtaker.keyboard.ime.nlp.SuggestionProvider
import com.wordtaker.keyboard.ime.nlp.WordSuggestionCandidate
import com.wordtaker.keyboard.lib.devtools.flogDebug

/**
 * 小鹤双拼 (Xiaohe Shuangpin) suggestion provider.
 *
 * Each syllable is two keystrokes; [ShuangpinConverter] expands the typed keys into
 * full Hanyu Pinyin which is then fed to the shared AOSP pinyin decoder via
 * [PinyinNativeBridge]. The decoder is shared with the QWERTY-pinyin and T9 providers
 * and is never opened/closed by this provider directly.
 */
class ShuangpinLanguageProvider(val context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.shuangpin"

        // Shuangpin keys are plain letters; no apostrophe segmentation needed.
        private val SHUANGPIN_CHARS = "abcdefghijklmnopqrstuvwxyz".toSet()
    }

    override val providerId = ProviderId

    override suspend fun create() {
        // No language-independent setup required; the decoder is opened in preload().
    }

    override suspend fun preload(subtype: Subtype) {
        PinyinNativeBridge.preload(context)
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val composing = content.composingText.lowercase().filter { it in SHUANGPIN_CHARS }
        if (composing.isEmpty()) {
            return emptyList()
        }
        val fullPinyin = ShuangpinConverter.toPinyin(composing)
        if (fullPinyin.isEmpty()) {
            return emptyList()
        }
        val results = PinyinNativeBridge.search(fullPinyin, maxCandidateCount)
        val suggestions = results.mapIndexed { index, (word, segmentedPy) ->
            WordSuggestionCandidate(
                text = word,
                secondaryText = if (index == 0) {
                    if (segmentedPy.isNotEmpty()) segmentedPy else fullPinyin
                } else {
                    null
                },
                confidence = (results.size - index).toDouble() / results.size,
                isEligibleForAutoCommit = false,
                isEligibleForUserRemoval = false,
                sourceProvider = this,
            )
        }
        flogDebug { "Shuangpin '$composing' -> '$fullPinyin' -> ${suggestions.size} candidates" }
        return suggestions
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { "accepted: ${candidate.text}" }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { "reverted: ${candidate.text}" }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return emptyList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return 0.0
    }

    override suspend fun determineLocalComposing(
        subtype: Subtype,
        textBeforeSelection: CharSequence,
        breakIterators: BreakIteratorGroup,
        localLastCommitPosition: Int,
    ): EditorRange {
        // Treat the trailing run of shuangpin letters as the composing region.
        return breakIterators.character(subtype.primaryLocale) {
            it.setText(textBeforeSelection.toString())
            val end = it.last()
            var start = end
            var next = it.previous()
            while (next != BreakIterator.DONE && start > localLastCommitPosition) {
                val sub = textBeforeSelection.substring(next, start)
                if (!sub.all { char -> char.lowercaseChar() in SHUANGPIN_CHARS }) break
                start = next
                next = it.previous()
            }
            if (start != end) {
                EditorRange(start, end)
            } else {
                EditorRange.Unspecified
            }
        }
    }

    override suspend fun destroy() {
        PinyinNativeBridge.destroy()
    }

    override val forcesSuggestionOn
        get() = true
}
