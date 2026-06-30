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

package com.wordtaker.keyboard.ime.core

import com.wordtaker.keyboard.ime.keyboard.LayoutType
import com.wordtaker.keyboard.ime.keyboard.LayoutTypeId
import com.wordtaker.keyboard.ime.keyboard.extCoreComposer
import com.wordtaker.keyboard.ime.keyboard.extCoreCurrencySet
import com.wordtaker.keyboard.ime.keyboard.extCoreLayout
import com.wordtaker.keyboard.ime.keyboard.extCorePopupMapping
import com.wordtaker.keyboard.ime.keyboard.extCorePunctuationRule
import com.wordtaker.keyboard.ime.nlp.latin.LatinLanguageProvider
import com.wordtaker.keyboard.ime.nlp.han.HanShapeBasedLanguageProvider
import com.wordtaker.keyboard.ime.nlp.handwriting.HandwritingLanguageProvider
import com.wordtaker.keyboard.lib.FlorisLocale
import com.wordtaker.keyboard.lib.ext.ExtensionComponentName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data class which represents an user-specified set of language and layout. String representations
 * of this object are stored as an Json array in the preference datastore.
 *
 * @property id The ID of this subtype.
 * @property primaryLocale The primary locale of this subtype.
 * @property secondaryLocales The secondary locales of this subtype. May be an empty list.
 * @property nlpProviders The NLP provider map to instantiate the correct provider for each category.
 * @property composer The composer name to composer characters the way they should.
 * @property currencySet The currency set name to display the correct currency symbols for this subtype.
 * @property punctuationRule The punctuation rule to correctly insert auto-spaces.
 * @property popupMapping The popup mapping name to correctly show popups for this subtype.
 * @property layoutMap The layout map to properly display the correct layout for each layout type.
 */
@Serializable
data class Subtype(
    val id: Long,
    val primaryLocale: FlorisLocale,
    val secondaryLocales: List<FlorisLocale>,
    val nlpProviders: SubtypeNlpProviderMap = SubtypeNlpProviderMap(),
    val composer: ExtensionComponentName,
    val currencySet: ExtensionComponentName,
    val punctuationRule: ExtensionComponentName = extCorePunctuationRule("default"),
    val popupMapping: ExtensionComponentName,
    val layoutMap: SubtypeLayoutMap,
) {
    companion object {
        /**
         * Subtype to use when prefs do not contain any valid subtypes.
         */
        val DEFAULT = Subtype(
            id = -1,
            primaryLocale = FlorisLocale.from("en", "US"),
            secondaryLocales = emptyList(),
            nlpProviders = SubtypeNlpProviderMap(),
            composer = extCoreComposer("appender"),
            currencySet = extCoreCurrencySet("dollar"),
            punctuationRule = extCorePunctuationRule("default"),
            popupMapping = extCorePopupMapping("en"),
            layoutMap = SubtypeLayoutMap(characters = extCoreLayout("qwerty")),
        )

        /**
         * Hardcoded Chinese pinyin subtype used as the first-launch default so the keyboard
         * boots into pinyin even before the async subtype-preset flow has emitted (which would
         * otherwise leave [DEFAULT] / en-US active and produce no Chinese candidates).
         *
         * Field values mirror the `zh-CN-pinyin` preset in
         * `assets/ime/keyboard/org.florisboard.localization/extension.json`. Keep them in sync.
         */
        val PINYIN_DEFAULT = Subtype(
            id = -2,
            primaryLocale = FlorisLocale.from("zh", "CN", "pinyin"),
            secondaryLocales = emptyList(),
            nlpProviders = SubtypeNlpProviderMap(
                spelling = "org.florisboard.nlp.providers.pinyin",
                suggestion = "org.florisboard.nlp.providers.pinyin",
            ),
            composer = extCoreComposer("appender"),
            currencySet = extCoreCurrencySet("yen"),
            punctuationRule = extCorePunctuationRule("default"),
            popupMapping = extCorePopupMapping("cjk"),
            layoutMap = SubtypeLayoutMap(
                characters = extCoreLayout("pinyin_qwerty"),
                symbols = extCoreLayout("cjk"),
                symbols2 = extCoreLayout("cjk"),
            ),
        )

        /**
         * Hardcoded 小鹤双拼 (Xiaohe Shuangpin) subtype. Mirrors [PINYIN_DEFAULT] but routes
         * to the shuangpin NLP provider and a dedicated full-keyboard layout. The `zh-CN-shuangpin`
         * locale variant lets break-iterators and provider routing distinguish it from plain pinyin.
         */
        val SHUANGPIN_DEFAULT = Subtype(
            id = -3,
            primaryLocale = FlorisLocale.from("zh", "CN", "shuangpin"),
            secondaryLocales = emptyList(),
            nlpProviders = SubtypeNlpProviderMap(
                spelling = "org.florisboard.nlp.providers.shuangpin",
                suggestion = "org.florisboard.nlp.providers.shuangpin",
            ),
            composer = extCoreComposer("appender"),
            currencySet = extCoreCurrencySet("yen"),
            punctuationRule = extCorePunctuationRule("default"),
            popupMapping = extCorePopupMapping("cjk"),
            layoutMap = SubtypeLayoutMap(
                characters = extCoreLayout("shuangpin_qwerty"),
                symbols = extCoreLayout("cjk"),
                symbols2 = extCoreLayout("cjk"),
            ),
        )

        /**
         * Hardcoded 九宫格拼音 (T9) subtype. Mirrors [PINYIN_DEFAULT] but routes to the T9 NLP
         * provider and the nine-key digit grid layout.
         */
        val T9_DEFAULT = Subtype(
            id = -4,
            primaryLocale = FlorisLocale.from("zh", "CN", "t9"),
            secondaryLocales = emptyList(),
            nlpProviders = SubtypeNlpProviderMap(
                spelling = "org.florisboard.nlp.providers.t9",
                suggestion = "org.florisboard.nlp.providers.t9",
            ),
            composer = extCoreComposer("appender"),
            currencySet = extCoreCurrencySet("yen"),
            punctuationRule = extCorePunctuationRule("default"),
            popupMapping = extCorePopupMapping("cjk"),
            layoutMap = SubtypeLayoutMap(
                characters = extCoreLayout("pinyin_t9"),
                symbols = extCoreLayout("cjk"),
                symbols2 = extCoreLayout("cjk"),
            ),
        )

        /**
         * Hardcoded 五笔 (Wubi-86) subtype. Routes to the shared Han shape-based provider, which
         * prefix-matches the `wubi` table inside `han.sqlite3`. Reuses the standard qwerty layout
         * (Wubi codes are typed on the a-z letter keys). The `zh-CN-wubi` locale variant lets the
         * provider pick the wubi language-pack component (and thus the `wubi` table).
         */
        val WUBI_DEFAULT = Subtype(
            id = -5,
            primaryLocale = FlorisLocale.from("zh", "CN", "wubi"),
            secondaryLocales = emptyList(),
            nlpProviders = SubtypeNlpProviderMap(
                spelling = HanShapeBasedLanguageProvider.ProviderId,
                suggestion = HanShapeBasedLanguageProvider.ProviderId,
            ),
            composer = extCoreComposer("appender"),
            currencySet = extCoreCurrencySet("yen"),
            punctuationRule = extCorePunctuationRule("default"),
            popupMapping = extCorePopupMapping("cjk"),
            layoutMap = SubtypeLayoutMap(
                characters = extCoreLayout("qwerty"),
                symbols = extCoreLayout("cjk"),
                symbols2 = extCoreLayout("cjk"),
            ),
        )

        /**
         * Hardcoded 笔画 (Stroke) subtype. Routes to the shared Han shape-based provider, which
         * prefix-matches the `bihua` table inside `han.sqlite3` using the five basic strokes
         * encoded as h(横)/s(竖)/p(撇)/n(捺)/z(折). Uses the dedicated 5-key stroke layout. The
         * `zh-CN-stroke` locale variant selects the stroke language-pack component, whose
         * `hanShapeBasedTable` override points at the `bihua` table.
         */
        val STROKE_DEFAULT = Subtype(
            id = -6,
            primaryLocale = FlorisLocale.from("zh", "CN", "stroke"),
            secondaryLocales = emptyList(),
            nlpProviders = SubtypeNlpProviderMap(
                spelling = HanShapeBasedLanguageProvider.ProviderId,
                suggestion = HanShapeBasedLanguageProvider.ProviderId,
            ),
            composer = extCoreComposer("appender"),
            currencySet = extCoreCurrencySet("yen"),
            punctuationRule = extCorePunctuationRule("default"),
            popupMapping = extCorePopupMapping("cjk"),
            layoutMap = SubtypeLayoutMap(
                characters = extCoreLayout("stroke_5key"),
                symbols = extCoreLayout("cjk"),
                symbols2 = extCoreLayout("cjk"),
            ),
        )

        /**
         * Hardcoded 手写 (Handwriting) subtype. Routes to the offline [HandwritingLanguageProvider],
         * which recognizes ink drawn on the handwriting pad against the bundled `mmah.json` template
         * database. The `zh-CN-handwriting` locale variant lets the IME render the handwriting pad
         * instead of a normal key grid. Its characters layout is a one-key placeholder
         * (`handwriting_pad`) — the pad UI replaces the key area entirely.
         */
        val HANDWRITING_DEFAULT = Subtype(
            id = -7,
            primaryLocale = FlorisLocale.from("zh", "CN", "handwriting"),
            secondaryLocales = emptyList(),
            nlpProviders = SubtypeNlpProviderMap(
                spelling = HandwritingLanguageProvider.ProviderId,
                suggestion = HandwritingLanguageProvider.ProviderId,
            ),
            composer = extCoreComposer("appender"),
            currencySet = extCoreCurrencySet("yen"),
            punctuationRule = extCorePunctuationRule("default"),
            popupMapping = extCorePopupMapping("cjk"),
            layoutMap = SubtypeLayoutMap(
                characters = extCoreLayout("handwriting_pad"),
                symbols = extCoreLayout("cjk"),
                symbols2 = extCoreLayout("cjk"),
            ),
        )

        /**
         * Returns the default Chinese subtype for the onboarding-selected keyboard [style].
         * "shuangpin" -> 小鹤双拼; "t9_pinyin" -> 九宫格拼音; "wubi" -> 五笔; "stroke" -> 笔画;
         * anything else -> full-keyboard pinyin. Pinyin styles share the proven AOSP pinyin
         * decoder; wubi/stroke share the Han shape-based provider over `han.sqlite3`.
         */
        fun pinyinDefaultFor(style: String): Subtype = when (style) {
            "shuangpin" -> SHUANGPIN_DEFAULT
            "t9_pinyin" -> T9_DEFAULT
            "wubi" -> WUBI_DEFAULT
            "stroke" -> STROKE_DEFAULT
            "handwriting" -> HANDWRITING_DEFAULT
            else -> PINYIN_DEFAULT
        }
    }

    /**
     * Returns an accumulated list of all locales of this subtype.
     */
    fun locales(): List<FlorisLocale> {
        val locales = mutableListOf(primaryLocale)
        locales.addAll(secondaryLocales)
        return locales
    }

    /**
     * Converts this object into its short string representation, used for debugging. Format:
     *  <id>/<language_tag>/<currency_set_name>
     */
    fun toShortString(): String {
        val languageTag = primaryLocale.languageTag()
        return "$id/$languageTag/$currencySet/${layoutMap.characters}"
    }

    fun equalsExcludingId(other: Subtype): Boolean {
        if (other.primaryLocale != primaryLocale) return false
        if (other.secondaryLocales != secondaryLocales) return false
        if (other.nlpProviders != nlpProviders) return false
        if (other.composer != composer) return false
        if (other.currencySet != currencySet) return false
        if (other.punctuationRule != punctuationRule) return false
        if (other.popupMapping != popupMapping) return false
        if (other.layoutMap != layoutMap) return false

        return true
    }
}

@Serializable
data class SubtypeLayoutMap(
    @SerialName(LayoutTypeId.CHARACTERS)
    val characters: ExtensionComponentName = CHARACTERS_DEFAULT,
    @SerialName(LayoutTypeId.SYMBOLS)
    val symbols: ExtensionComponentName = SYMBOLS_DEFAULT,
    @SerialName(LayoutTypeId.SYMBOLS2)
    val symbols2: ExtensionComponentName = SYMBOLS2_DEFAULT,
    @SerialName(LayoutTypeId.NUMERIC)
    val numeric: ExtensionComponentName = NUMERIC_DEFAULT,
    @SerialName(LayoutTypeId.NUMERIC_ADVANCED)
    val numericAdvanced: ExtensionComponentName = NUMERIC_ADVANCED_DEFAULT,
    @SerialName(LayoutTypeId.NUMERIC_ROW)
    val numericRow: ExtensionComponentName = NUMERIC_ROW_DEFAULT,
    @SerialName(LayoutTypeId.PHONE)
    val phone: ExtensionComponentName = PHONE_DEFAULT,
    @SerialName(LayoutTypeId.PHONE2)
    val phone2: ExtensionComponentName = PHONE2_DEFAULT,
) {
    companion object {
        private const val EQUALS =                      "="
        private const val DELIMITER =                   ","

        private val CHARACTERS_DEFAULT =          extCoreLayout("qwerty")
        private val SYMBOLS_DEFAULT =             extCoreLayout("western")
        private val SYMBOLS2_DEFAULT =            extCoreLayout("western")
        private val NUMERIC_DEFAULT =             extCoreLayout("western_arabic")
        private val NUMERIC_ADVANCED_DEFAULT =    extCoreLayout("western_arabic")
        private val NUMERIC_ROW_DEFAULT =         extCoreLayout("western_arabic")
        private val PHONE_DEFAULT =               extCoreLayout("telpad")
        private val PHONE2_DEFAULT =              extCoreLayout("telpad")
    }

    operator fun get(layoutType: LayoutType): ExtensionComponentName? {
        return when (layoutType) {
            LayoutType.CHARACTERS -> characters
            LayoutType.SYMBOLS -> symbols
            LayoutType.SYMBOLS2 -> symbols2
            LayoutType.NUMERIC -> numeric
            LayoutType.NUMERIC_ADVANCED -> numericAdvanced
            LayoutType.NUMERIC_ROW -> numericRow
            LayoutType.PHONE -> phone
            LayoutType.PHONE2 -> phone2
            else -> null
        }
    }

    fun copy(layoutType: LayoutType, componentName: ExtensionComponentName): SubtypeLayoutMap? {
        return when (layoutType) {
            LayoutType.CHARACTERS -> copy(characters = componentName)
            LayoutType.SYMBOLS -> copy(symbols = componentName)
            LayoutType.SYMBOLS2 -> copy(symbols2 = componentName)
            LayoutType.NUMERIC -> copy(numeric = componentName)
            LayoutType.NUMERIC_ADVANCED -> copy(numericAdvanced = componentName)
            LayoutType.NUMERIC_ROW -> copy(numericRow = componentName)
            LayoutType.PHONE -> copy(phone = componentName)
            LayoutType.PHONE2 -> copy(phone2 = componentName)
            else -> null
        }
    }

    override fun toString() = buildString(128) {
        append(LayoutTypeId.CHARACTERS)
        append(EQUALS)
        append(characters)

        append(DELIMITER)

        append(LayoutTypeId.SYMBOLS)
        append(EQUALS)
        append(symbols)

        append(DELIMITER)

        append(LayoutTypeId.SYMBOLS2)
        append(EQUALS)
        append(symbols2)

        append(DELIMITER)

        append(LayoutTypeId.NUMERIC_ROW)
        append(EQUALS)
        append(numericRow)

        append(DELIMITER)

        append(LayoutTypeId.NUMERIC)
        append(EQUALS)
        append(numeric)

        append(DELIMITER)

        append(LayoutTypeId.NUMERIC_ADVANCED)
        append(EQUALS)
        append(numericAdvanced)

        append(DELIMITER)

        append(LayoutTypeId.PHONE)
        append(EQUALS)
        append(phone)

        append(DELIMITER)

        append(LayoutTypeId.PHONE2)
        append(EQUALS)
        append(phone2)
    }
}

@Serializable
data class SubtypeNlpProviderMap(
    val spelling: String = LatinLanguageProvider.ProviderId,
    val suggestion: String = LatinLanguageProvider.ProviderId,
) {
    inline fun forEach(action: (String, String) -> Unit) {
        action("spelling", spelling)
        action("suggestion", suggestion)
    }
}

/**
 * Data class which represents a predefined set of language and preferred layout.
 *
 * @property locale The locale of this subtype. Beware its different name in json: 'languageTag'.
 * @property currencySet The currency set name of this subtype.
 * @property preferred The preferred layout map for this subtype's locale.
 */
@Serializable
data class SubtypePreset(
    @Serializable(with = FlorisLocale.Serializer::class)
    @SerialName("languageTag")
    val locale: FlorisLocale,
    val nlpProviders: SubtypeNlpProviderMap = SubtypeNlpProviderMap(),
    val composer: ExtensionComponentName,
    val currencySet: ExtensionComponentName,
    val punctuationRule: ExtensionComponentName = extCorePunctuationRule("default"),
    val popupMapping: ExtensionComponentName = extCorePopupMapping("default"),
    val preferred: SubtypeLayoutMap,
) {
    fun toSubtype(): Subtype {
        return Subtype(
            id = -1,
            primaryLocale = locale,
            secondaryLocales = emptyList(),
            nlpProviders = nlpProviders,
            composer = composer,
            currencySet = currencySet,
            punctuationRule = punctuationRule,
            popupMapping = popupMapping,
            layoutMap = preferred,
        )
    }
}
