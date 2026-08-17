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

package com.wordtaker.keyboard.ime.nlp.pinyin

import java.util.Locale

/**
 * The two representations of one legal full-pinyin composing value.
 *
 * The local AOSP decoder accepts apostrophes as syllable separators. The cloud
 * endpoint accepts letters only, so separators are removed without forwarding
 * any other composing characters.
 */
data class NormalizedFullPinyin(
    val local: String,
    val cloud: String,
)

enum class CloudEditorType {
    NORMAL,
    PASSWORD,
    VISIBLE_PASSWORD,
    WEB_PASSWORD,
}

/**
 * Builds a cloud-only normalized copy without changing local composing text.
 * The complete value must contain only ASCII letters and apostrophe syllable
 * separators; the cloud copy then removes separators and remains lowercase.
 */
fun normalizeFullPinyin(raw: CharSequence): NormalizedFullPinyin? {
    val local = raw.toString().lowercase(Locale.ROOT)
    if (local.isEmpty() || local.any { it !in 'a'..'z' && it != '\'' }) {
        return null
    }
    val cloud = local.filter { it in 'a'..'z' }
    return cloud.takeIf(String::isNotEmpty)?.let {
        NormalizedFullPinyin(local = local, cloud = it)
    }
}

/**
 * Fail-closed request/response-time cloud dictionary policy.
 *
 * Callers create a fresh snapshot both before scheduling and immediately before
 * applying a response. This makes every privacy and routing gate independently
 * enforceable and keeps the augmenter free of Android framework dependencies.
 */
data class CloudDictionaryEnvironment(
    val activeSuggestionProviderId: String?,
    val composingText: CharSequence,
    val cloudEnabled: Boolean,
    val hasValidatedInternet: Boolean,
    val isIncognito: Boolean,
    val editorType: CloudEditorType,
    val noPersonalizedLearning: Boolean,
    val isEnglishMode: Boolean,
) {
    fun allows(requestPinyin: String): Boolean {
        if (activeSuggestionProviderId != PinyinLanguageProvider.ProviderId) return false
        if (!cloudEnabled || !hasValidatedInternet) return false
        if (
            isIncognito ||
            editorType != CloudEditorType.NORMAL ||
            noPersonalizedLearning ||
            isEnglishMode
        ) {
            return false
        }
        return normalizeFullPinyin(composingText)?.cloud == requestPinyin
    }
}
