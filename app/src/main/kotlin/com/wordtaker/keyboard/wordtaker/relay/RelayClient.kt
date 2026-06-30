package com.wordtaker.keyboard.wordtaker.relay

import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Result of a polish attempt. On any failure [polished] is null and the caller
 * is expected to fall back to the raw transcription.
 */
sealed class RelayResult {
    data class Success(val polished: String) : RelayResult()
    data class Failure(val reason: String) : RelayResult()
}

/**
 * Talks to the self-hosted relay that performs DeepSeek polishing server-side.
 *
 * SECURITY: only the relay URL and the public app token live here. The DeepSeek
 * API key NEVER touches the client — it stays behind the relay.
 */
class RelayClient(private val deviceId: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Sends [rawText] to the relay for polishing under the given [mode]. Blocking —
     * call off the main thread. Validates input and handles errors/timeouts so the
     * caller can fall back safely.
     *
     * SECURITY: the request body carries ONLY { text, mode }. No prompt text is ever
     * sent — the system prompt is selected by the relay from [mode] server-side and
     * stays secret behind the relay.
     */
    fun polish(rawText: String, mode: String): RelayResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return RelayResult.Failure("empty input")
        }

        // Length cap protects the relay and avoids absurd payloads.
        val capped = if (trimmed.length > MAX_INPUT_CHARS) {
            trimmed.substring(0, MAX_INPUT_CHARS)
        } else {
            trimmed
        }

        val payload = JSONObject()
            .put("text", capped)
            .put("mode", mode)
            .toString()

        val request = Request.Builder()
            .url(RELAY_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-App-Token", APP_TOKEN)
            .addHeader("X-Device-Id", deviceId)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return RelayResult.Failure("HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: return RelayResult.Failure("empty response")
                parseResponse(body)
            }
        } catch (e: IOException) {
            RelayResult.Failure("network: ${e.message}")
        } catch (e: Exception) {
            RelayResult.Failure("error: ${e.message}")
        }
    }

    /**
     * Parses the relay envelope. Tolerant of both `success` and `ok` flags —
     * either set true with a non-blank `text` is success.
     */
    private fun parseResponse(body: String): RelayResult {
        return try {
            val json = JSONObject(body)
            val ok = json.optBoolean("ok", false) || json.optBoolean("success", false)
            val text = json.optString("text", "")
            if (ok && text.isNotBlank()) {
                RelayResult.Success(text)
            } else {
                val error = json.optString("error", "")
                RelayResult.Failure(error.ifBlank { "relay reported failure" })
            }
        } catch (e: Exception) {
            RelayResult.Failure("bad json: ${e.message}")
        }
    }

    companion object {
        // Relay URL + public app token only. No DeepSeek key here, ever.
        private const val RELAY_URL =
            "https://1311262545-3ihll1gdlf.ap-guangzhou.tencentscf.com"
        private const val APP_TOKEN =
            "64caa0fbd432f49a65269be31e581b19aceab557205b7b24"

        private const val TIMEOUT_SECONDS = 10L
        private const val MAX_INPUT_CHARS = 4000

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
