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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 九宫格拼音 (T9) suggestion provider.
 *
 * Each digit 2-9 maps to a set of letters (classic phone keypad). The typed digit
 * string is expanded (bounded cartesian product) into candidate letter-strings, each
 * of which is searched against the shared AOSP pinyin decoder via [PinyinNativeBridge];
 * the resulting Hanzi candidates are merged and de-duplicated. The decoder is shared
 * with the QWERTY-pinyin and Shuangpin providers and is never opened/closed here.
 */
class T9LanguageProvider(val context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.t9"

        // Valid T9 input characters (digit keys). '1' carries no letters.
        private val T9_CHARS = "123456789".toSet()

        // Classic phone keypad letter groups. '1' is intentionally absent (no letters).
        private val DIGIT_LETTERS = mapOf(
            '2' to "abc",
            '3' to "def",
            '4' to "ghi",
            '5' to "jkl",
            '6' to "mno",
            '7' to "pqrs",
            '8' to "tuv",
            '9' to "wxyz",
        )

        // Hard cap on how many letter-string expansions we explore per keystroke run,
        // to keep the cartesian product bounded.
        private const val MAX_T9_EXPANSIONS = 256
    }

    override val providerId = ProviderId

    override suspend fun create() {
        // No language-independent setup required; the decoder is opened in preload().
    }

    override suspend fun preload(subtype: Subtype) {
        PinyinNativeBridge.preload(context)
    }

    /**
     * Bounded DFS over the digit string producing up to [MAX_T9_EXPANSIONS] letter-strings.
     * Digits with no letters (e.g. '1') contribute nothing and are skipped.
     */
    private fun expand(digits: String): List<String> {
        if (digits.isEmpty()) return emptyList()
        val results = ArrayList<String>()
        val sb = StringBuilder()

        fun dfs(index: Int) {
            if (results.size >= MAX_T9_EXPANSIONS) return
            if (index == digits.length) {
                if (sb.isNotEmpty()) results.add(sb.toString())
                return
            }
            val letters = DIGIT_LETTERS[digits[index]]
            if (letters == null) {
                // Digit without letters: skip it but continue the rest.
                dfs(index + 1)
                return
            }
            for (ch in letters) {
                if (results.size >= MAX_T9_EXPANSIONS) return
                sb.append(ch)
                dfs(index + 1)
                sb.deleteCharAt(sb.length - 1)
            }
        }

        dfs(0)
        return results
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val composing = content.composingText.filter { it in T9_CHARS }
        if (composing.isEmpty()) {
            return emptyList()
        }
        val expansions = expand(composing)
        if (expansions.isEmpty()) {
            return emptyList()
        }
        // Search every expansion and keep its ranked candidate list. Then merge across all
        // expansions ordered by (a) decoder rank within the expansion, then (b) hanzi length
        // descending. A longer hanzi result consumes more of the typed digits — i.e. it is a
        // fuller, more likely reading of the whole input (e.g. 你好 for "nihao") — so it should
        // beat single-char results of other readings (没/美…) that only cover part of the input.
        // This keeps a valid full reading from being crowded out by the DFS expansion order.
        data class Ranked(val word: String, val letters: String, val rank: Int)
        val ranked = ArrayList<Ranked>()
        for (letters in expansions) {
            // Cancellation point: a long digit run yields many expansions, each a native
            // search. Bail promptly if the composing/suggestion coroutine was cancelled.
            currentCoroutineContext().ensureActive()
            val results = PinyinNativeBridge.search(letters, maxCandidateCount)
            results.forEachIndexed { rank, (word, _) ->
                ranked.add(Ranked(word, letters, rank))
            }
        }
        // Stable sort: lower rank first, then longer hanzi first. Stable preserves decoder/DFS
        // order among otherwise-equal entries.
        val sorted = ranked.sortedWith(
            compareBy<Ranked> { it.rank }.thenByDescending { it.word.length },
        )
        val merged = LinkedHashMap<String, String>() // hanzi -> producing letter-string
        for (entry in sorted) {
            if (entry.word !in merged) {
                merged[entry.word] = entry.letters
            }
            if (merged.size >= maxCandidateCount) break
        }
        val final = merged.entries.take(maxCandidateCount)
        val suggestions = final.mapIndexed { index, entry ->
            WordSuggestionCandidate(
                text = entry.key,
                secondaryText = if (index == 0) entry.value else null,
                confidence = (final.size - index).toDouble() / final.size,
                isEligibleForAutoCommit = false,
                isEligibleForUserRemoval = false,
                sourceProvider = this,
            )
        }
        flogDebug { "T9 '$composing' -> ${expansions.size} expansions -> ${suggestions.size} candidates" }
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
        // Treat the trailing run of T9 digit keys as the composing region.
        return breakIterators.character(subtype.primaryLocale) {
            it.setText(textBeforeSelection.toString())
            val end = it.last()
            var start = end
            var next = it.previous()
            while (next != BreakIterator.DONE && start > localLastCommitPosition) {
                val sub = textBeforeSelection.substring(next, start)
                if (!sub.all { char -> char in T9_CHARS }) break
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
