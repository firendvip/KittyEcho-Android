package com.wordtaker.keyboard.asrbenchmark.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class StrictCanonicalJsonObject(
    val document: Map<String, Any?>,
    val rawSha256: String,
    val canonicalSha256: String,
)

/**
 * Parses the small JSON surface used by the decoder plan without accepting
 * duplicate object keys, non-integer numbers, or bytes after the canonical
 * document. The exact wire form is CanonicalJson plus one trailing newline.
 */
object StrictCanonicalJson {
    fun decodeObject(bytes: ByteArray): StrictCanonicalJsonObject {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw BenchmarkContractException("strict JSON is not valid UTF-8")
        }
        val document = Parser(text).parseDocument()
        val canonicalBytes = (
            CanonicalJson.encode(document) + "\n"
            ).encodeToByteArray()
        if (!bytes.contentEquals(canonicalBytes)) {
            throw BenchmarkContractException(
                "strict JSON bytes differ from the canonical decoder-plan encoding",
            )
        }
        return StrictCanonicalJsonObject(
            document = document,
            rawSha256 = Hashing.sha256(bytes),
            canonicalSha256 = CanonicalJson.sha256(document),
        )
    }

    private class Parser(
        private val source: String,
    ) {
        private var offset = 0

        fun parseDocument(): Map<String, Any?> {
            skipWhitespace()
            if (offset >= source.length || source[offset] != '{') {
                throw BenchmarkContractException(
                    "strict JSON root must be an object",
                )
            }
            val value = parseObject()
            skipWhitespace()
            if (offset != source.length) {
                throw BenchmarkContractException(
                    "strict JSON contains trailing bytes",
                )
            }
            return value
        }

        private fun parseValue(): Any? {
            if (offset >= source.length) {
                throw BenchmarkContractException(
                    "strict JSON value is truncated",
                )
            }
            return when (source[offset]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseInteger()
                else -> throw BenchmarkContractException(
                    "strict JSON contains an invalid value",
                )
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (consume('}')) {
                return result
            }
            while (true) {
                if (offset >= source.length || source[offset] != '"') {
                    throw BenchmarkContractException(
                        "strict JSON object key must be a string",
                    )
                }
                val key = parseString()
                if (result.containsKey(key)) {
                    throw BenchmarkContractException(
                        "strict JSON contains duplicate object key: $key",
                    )
                }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                if (consume('}')) {
                    return result
                }
                expect(',')
                skipWhitespace()
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (consume(']')) {
                return result
            }
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (consume(']')) {
                    return result
                }
                expect(',')
                skipWhitespace()
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (offset < source.length) {
                val character = source[offset++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> appendEscape(result)
                    character.code < 0x20 -> throw BenchmarkContractException(
                        "strict JSON string contains a control character",
                    )
                    else -> result.append(character)
                }
            }
            throw BenchmarkContractException(
                "strict JSON string is unterminated",
            )
        }

        private fun appendEscape(output: StringBuilder) {
            if (offset >= source.length) {
                throw BenchmarkContractException(
                    "strict JSON escape is truncated",
                )
            }
            when (val escaped = source[offset++]) {
                '"' -> output.append('"')
                '\\' -> output.append('\\')
                '/' -> output.append('/')
                'b' -> output.append('\b')
                'f' -> output.append('\u000c')
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                't' -> output.append('\t')
                'u' -> output.append(parseUnicodeEscape())
                else -> throw BenchmarkContractException(
                    "strict JSON escape is invalid: $escaped",
                )
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (offset + 4 > source.length) {
                throw BenchmarkContractException(
                    "strict JSON unicode escape is truncated",
                )
            }
            var code = 0
            repeat(4) {
                val digit = source[offset++].digitToIntOrNull(16)
                    ?: throw BenchmarkContractException(
                        "strict JSON unicode escape is invalid",
                    )
                code = (code shl 4) or digit
            }
            return code.toChar()
        }

        private fun parseInteger(): Long {
            val start = offset
            consume('-')
            if (offset >= source.length) {
                throw BenchmarkContractException(
                    "strict JSON integer is truncated",
                )
            }
            if (source[offset] == '0') {
                offset += 1
                if (offset < source.length && source[offset].isDigit()) {
                    throw BenchmarkContractException(
                        "strict JSON integer has a leading zero",
                    )
                }
            } else {
                if (source[offset] !in '1'..'9') {
                    throw BenchmarkContractException(
                        "strict JSON integer is invalid",
                    )
                }
                while (offset < source.length && source[offset].isDigit()) {
                    offset += 1
                }
            }
            if (
                offset < source.length &&
                source[offset] in charArrayOf('.', 'e', 'E')
            ) {
                throw BenchmarkContractException(
                    "strict decoder JSON supports integers only",
                )
            }
            return source.substring(start, offset).toLongOrNull()
                ?: throw BenchmarkContractException(
                    "strict JSON integer is outside the signed 64-bit range",
                )
        }

        private fun parseLiteral(expected: String, value: Any?): Any? {
            if (!source.regionMatches(offset, expected, 0, expected.length)) {
                throw BenchmarkContractException(
                    "strict JSON literal is invalid",
                )
            }
            offset += expected.length
            return value
        }

        private fun skipWhitespace() {
            while (
                offset < source.length &&
                source[offset] in charArrayOf(' ', '\t', '\r', '\n')
            ) {
                offset += 1
            }
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) {
                throw BenchmarkContractException(
                    "strict JSON expected '$expected'",
                )
            }
        }

        private fun consume(expected: Char): Boolean {
            if (offset < source.length && source[offset] == expected) {
                offset += 1
                return true
            }
            return false
        }
    }
}
