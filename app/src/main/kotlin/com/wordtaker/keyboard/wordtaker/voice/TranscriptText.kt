package com.wordtaker.keyboard.wordtaker.voice

import java.util.regex.Pattern

internal data class PreparedTranscript(
    val text: String,
    val visibleGraphemeCount: Int,
)

/**
 * Preserves ASR text exactly except for leading/trailing Unicode White_Space.
 *
 * The threshold counts Unicode extended grapheme clusters (`\X`) and ignores clusters
 * made exclusively from White_Space, control, or format code points.
 */
internal object TranscriptText {
    private val graphemePattern = Pattern.compile("\\X")

    fun prepare(raw: String): PreparedTranscript {
        val text = raw.trimUnicodeWhiteSpace()
        val matcher = graphemePattern.matcher(text)
        var visibleGraphemeCount = 0
        while (matcher.find()) {
            if (!matcher.group().isThresholdIgnorable()) {
                visibleGraphemeCount += 1
            }
        }
        return PreparedTranscript(text, visibleGraphemeCount)
    }

    private fun String.trimUnicodeWhiteSpace(): String {
        var start = 0
        while (start < length) {
            val codePoint = codePointAt(start)
            if (!codePoint.isUnicodeWhiteSpace()) break
            start += Character.charCount(codePoint)
        }

        var end = length
        while (end > start) {
            val codePoint = codePointBefore(end)
            if (!codePoint.isUnicodeWhiteSpace()) break
            end -= Character.charCount(codePoint)
        }
        return substring(start, end)
    }

    private fun String.isThresholdIgnorable(): Boolean {
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val type = Character.getType(codePoint)
            val ignorable = codePoint.isUnicodeWhiteSpace() ||
                type == Character.CONTROL.toInt() ||
                type == Character.FORMAT.toInt()
            if (!ignorable) return false
            index += Character.charCount(codePoint)
        }
        return true
    }

    private fun Int.isUnicodeWhiteSpace(): Boolean = when (this) {
        in 0x0009..0x000D,
        0x0020,
        0x0085,
        0x00A0,
        0x1680,
        in 0x2000..0x200A,
        0x2028,
        0x2029,
        0x202F,
        0x205F,
        0x3000,
        -> true
        else -> false
    }
}
