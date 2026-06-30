/*
 * Copyright (C) 2026 The WordTaker Contributors
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

package com.wordtaker.keyboard.ime.nlp.handwriting

import android.content.Context
import com.wordtaker.keyboard.ime.core.Subtype
import com.wordtaker.keyboard.ime.editor.EditorContent
import com.wordtaker.keyboard.ime.nlp.SuggestionCandidate
import com.wordtaker.keyboard.ime.nlp.SuggestionProvider
import com.wordtaker.keyboard.lib.devtools.flogDebug

/**
 * Suggestion provider backing the 手写 (handwriting) subtype.
 *
 * Unlike the pinyin/wubi/stroke providers, handwriting candidates are NOT derived from the
 * editor's composing text — they come from ink drawn on the handwriting pad. So [suggest] (the
 * editor-content-driven hook) returns nothing; the pad UI calls the shared [recognizer] directly
 * and pushes candidates via `NlpManager.suggestDirectly`. This provider still exists so that:
 *  - the subtype's `nlpProviders` resolve to a real provider (clean routing), and
 *  - committed candidates have a non-null [SuggestionCandidate.sourceProvider] for the standard
 *    `commitCandidate` -> `notifySuggestionAccepted` flow.
 *
 * The recognizer (and its template DB) is owned here and preloaded with the subtype.
 */
class HandwritingLanguageProvider(val context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.handwriting"
    }

    override val providerId = ProviderId

    /** Shared recognizer instance, reused across the pad UI and provider lifetime. */
    val recognizer: HandwritingRecognizer = HandwritingRecognizer(context)

    override suspend fun create() {
        // Nothing language-independent to set up; the DB is loaded in preload().
    }

    override suspend fun preload(subtype: Subtype) {
        recognizer.preload()
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        // Handwriting candidates are pushed from the pad, not derived from editor content.
        return emptyList()
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { "handwriting accepted: ${candidate.text}" }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Do nothing
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

    override suspend fun destroy() {
        // Recognizer holds only an in-memory template list; nothing native to free.
    }

    override val forcesSuggestionOn
        get() = true
}
