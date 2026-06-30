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

/**
 * Converts 小鹤双拼 (Xiaohe Shuangpin) keystrokes into full Hanyu Pinyin so the
 * standard AOSP pinyin decoder can search on the expanded spelling.
 *
 * Each Chinese syllable is two keystrokes: the first selects the initial (声母),
 * the second selects the final (韵母). Zero-initial syllables (those that start
 * with a/e/o in full pinyin) have their own two-key encodings.
 *
 * The tables below are hand-built from the public Xiaohe layout (key positions),
 * not copied from any GPL implementation. The mapping is validated by the self-test
 * cases documented in [toPinyin].
 */
object ShuangpinConverter {
    /** Initial (声母) keys -> full-pinyin initial. */
    private val initialTable: Map<Char, String> = mapOf(
        'b' to "b", 'p' to "p", 'm' to "m", 'f' to "f",
        'd' to "d", 't' to "t", 'n' to "n", 'l' to "l",
        'g' to "g", 'k' to "k", 'h' to "h",
        'j' to "j", 'q' to "q", 'x' to "x",
        'r' to "r", 'z' to "z", 'c' to "c", 's' to "s",
        'y' to "y", 'w' to "w",
        'v' to "zh", 'i' to "ch", 'u' to "sh",
    )

    /** Final (韵母) keys -> primary full-pinyin final (before dual-key resolution). */
    private val finalTable: Map<Char, String> = mapOf(
        'a' to "a", 'b' to "in", 'c' to "ao", 'd' to "ai", 'e' to "e",
        'f' to "en", 'g' to "eng", 'h' to "ang", 'i' to "i", 'j' to "an",
        'k' to "ing", 'l' to "iang", 'm' to "ian", 'n' to "iao", 'o' to "uo",
        'p' to "ie", 'q' to "iu", 'r' to "uan", 's' to "ong", 't' to "ue",
        'u' to "u", 'v' to "ui", 'w' to "ei", 'x' to "ia", 'y' to "un", 'z' to "ou",
    )

    /** Zero-initial (a/e/o leading) two-key encodings -> full pinyin. */
    private val zeroInitialMap: Map<String, String> = mapOf(
        // Canonical Xiaohe zero-initial codes only: a/e/o lead key + Xiaohe final key.
        // Non-canonical literals (ai/an/ou) were removed; they either shadowed the real
        // codes (ad/aj/oz) or are no-ops — the general a/e/o fallthrough already yields
        // the correct pinyin for those keystroke pairs.
        "aa" to "a", "ah" to "ang", "ac" to "ao", "ad" to "ai", "aj" to "an",
        "ee" to "e", "ew" to "ei", "ef" to "en", "eg" to "eng", "er" to "er",
        "oo" to "o", "oz" to "ou",
    )

    private val velarAndRetroflex = setOf("g", "k", "h", "zh", "ch", "sh", "r")

    /**
     * Resolves a final key into its full-pinyin final, taking the chosen [initial]
     * into account for the ambiguous Xiaohe keys.
     */
    private fun resolveFinal(key: Char, initial: String?): String {
        return when (key) {
            'l' -> if (initial in velarAndRetroflex) "uang" else "iang"
            'o' -> if (initial in setOf("b", "p", "m", "f")) "o" else "uo"
            'r' -> if (initial == null) "er" else "uan"
            's' -> if (initial in setOf("j", "q", "x", "y")) "iong" else "ong"
            't' -> "ue"
            'x' -> if (initial in velarAndRetroflex) "ua" else "ia"
            else -> finalTable[key] ?: key.toString()
        }
    }

    /**
     * Converts a run of Xiaohe Shuangpin [keys] into a continuous full-pinyin string
     * (no separators), suitable for the AOSP pinyin decoder.
     *
     * Self-test (must hold exactly):
     *  - toPinyin("nihc") == "nihao"
     *  - toPinyin("vswf") == "zhongwen"
     *  - toPinyin("pbyb") == "pinyin"
     */
    fun toPinyin(keys: String): String {
        if (keys.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < keys.length) {
            val c1 = keys[i]
            if (i + 1 >= keys.length) {
                // Trailing single keystroke: append raw so partial typing still searches.
                sb.append(c1)
                break
            }
            val c2 = keys[i + 1]
            if (c1 == 'a' || c1 == 'e' || c1 == 'o') {
                // Zero-initial syllable.
                sb.append(zeroInitialMap["$c1$c2"] ?: (c1 + resolveFinal(c2, null)))
            } else {
                val initial = initialTable[c1] ?: c1.toString()
                sb.append(initial).append(resolveFinal(c2, initial))
            }
            i += 2
        }
        return sb.toString()
    }
}
