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
 * Chinese Pinyin suggestion provider backed by the bundled AOSP Google PinyinIME
 * native decoder (libpinyinime.so).
 *
 * Pinyin letters typed on a QWERTY layout accumulate into the composing region;
 * for each composing string we run a fresh native search and surface the Hanzi
 * candidates. Tapping a candidate commits its text via the standard editor flow.
 *
 * The native decoder is shared across all pinyin-family providers via
 * [PinyinNativeBridge]; this provider never touches the decoder directly.
 */
class PinyinLanguageProvider(val context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.pinyin"

        // Valid pinyin input characters on the QWERTY layout.
        private val PINYIN_CHARS = "abcdefghijklmnopqrstuvwxyz'".toSet()
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
        val composing = content.composingText.lowercase().filter { it in PINYIN_CHARS }
        if (composing.isEmpty()) {
            return emptyList()
        }
        val results = PinyinNativeBridge.search(composing, maxCandidateCount)
        val suggestions = results.mapIndexed { index, (word, segmentedPy) ->
            WordSuggestionCandidate(
                text = word,
                secondaryText = if (index == 0 && segmentedPy.isNotEmpty()) segmentedPy else null,
                confidence = (results.size - index).toDouble() / results.size,
                isEligibleForAutoCommit = false,
                isEligibleForUserRemoval = false,
                sourceProvider = this,
            )
        }
        flogDebug { "Pinyin '$composing' -> ${suggestions.size} candidates" }
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
        // Treat the trailing run of pinyin letters as the composing region.
        return breakIterators.character(subtype.primaryLocale) {
            it.setText(textBeforeSelection.toString())
            val end = it.last()
            var start = end
            var next = it.previous()
            while (next != BreakIterator.DONE && start > localLastCommitPosition) {
                val sub = textBeforeSelection.substring(next, start)
                if (!sub.all { char -> char.lowercaseChar() in PINYIN_CHARS }) break
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
