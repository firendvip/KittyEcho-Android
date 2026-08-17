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
import com.wordtaker.keyboard.lib.devtools.flogError
import com.wordtaker.keyboard.lib.devtools.flogInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.florisboard.libnative.nativeGetCandidate
import org.florisboard.libnative.nativeGetPyStr
import org.florisboard.libnative.nativeGetSplStartPositions
import org.florisboard.libnative.nativeOpenDecoderFd
import org.florisboard.libnative.nativeResetSearch
import org.florisboard.libnative.nativeSearch

internal data class PinyinNativeCandidate(
    val text: String,
    val decoderIndex: Int,
)

internal data class PinyinNativeSearchResult(
    val candidates: List<PinyinNativeCandidate>,
    val decodedPinyin: String,
    val syllableEndOffsets: List<Int>,
)

/**
 * Shared, process-wide gateway to the bundled AOSP Google PinyinIME native decoder
 * (libpinyinime.so).
 *
 * CRITICAL INVARIANT: the native decoder is a SINGLE global instance and is NOT
 * thread-safe. It is shared by all three pinyin-family providers
 * ([PinyinLanguageProvider], [ShuangpinLanguageProvider], [T9LanguageProvider]).
 * Therefore the decoder is opened EXACTLY ONCE here, and every native interaction
 * is serialized through ONE [nativeLock]. Providers must never open or close the
 * decoder themselves — they delegate to this object so the open/search sequence
 * never interleaves across providers.
 */
object PinyinNativeBridge {
    // Dictionary bundled in app assets (see app/src/main/assets/ime/dict).
    private const val DICT_ASSET_PATH = "ime/dict/dict_pinyin.dat"

    // The native decoder is a single global instance and is not thread-safe.
    // Every native interaction is serialized through this mutex.
    private val nativeLock = Mutex()

    @Volatile
    private var isDecoderReady = false

    /**
     * Opens the shared native decoder from app assets. Idempotent: once the decoder
     * is ready, repeated calls (including from a different provider) are no-ops.
     */
    suspend fun preload(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        nativeLock.withLock {
            if (isDecoderReady) return@withLock
            try {
                // detachFd() transfers FD ownership from Java ParcelFileDescriptor to native,
                // preventing fdsan from aborting when libpinyinime calls fdopen() on the same FD.
                // The native decoder takes responsibility for closing the FD.
                val afd = appContext.assets.openFd(DICT_ASSET_PATH)
                val startOffset = afd.startOffset
                val length = afd.length
                val fd = afd.parcelFileDescriptor.detachFd()
                afd.close()
                val opened = nativeOpenDecoderFd(fd, startOffset, length)
                isDecoderReady = opened
                if (opened) {
                    flogInfo { "Pinyin decoder opened (dict length=$length)" }
                } else {
                    flogError { "Pinyin decoder failed to open dictionary" }
                }
            } catch (e: Exception) {
                isDecoderReady = false
                flogError { "Pinyin decoder preload error: $e" }
            }
        }
    }

    /**
     * Runs a single fresh search for [pinyin] (ASCII a-z') and returns up to [maxCount]
     * candidates as (hanzi, segmentedPinyin) pairs. Only the first pair carries the
     * segmented pinyin string (for display above candidates); the rest carry "".
     *
     * The whole search -> getCandidate -> reset sequence runs under the single lock so
     * it can never interleave with another provider's search.
     */
    suspend fun search(pinyin: String, maxCount: Int): List<Pair<String, String>> {
        val result = searchDetailed(pinyin, maxCount)
        return result.candidates.mapIndexed { index, candidate ->
            candidate.text to if (index == 0) result.decodedPinyin else ""
        }
    }

    /**
     * Runs one fresh search and returns candidate decoder indices together with exact raw
     * spelling boundaries. The latter lets segmented selection consume syllables without
     * guessing from candidate width or from its visible position after cloud merging.
     */
    internal suspend fun searchDetailed(pinyin: String, maxCount: Int): PinyinNativeSearchResult {
        if (pinyin.isEmpty() || maxCount <= 0) {
            return PinyinNativeSearchResult(emptyList(), "", emptyList())
        }
        return nativeLock.withLock {
            if (!isDecoderReady) {
                return@withLock PinyinNativeSearchResult(emptyList(), "", emptyList())
            }
            try {
                val pyBytes = pinyin.toByteArray(Charsets.US_ASCII)
                val count = nativeSearch(pyBytes, pyBytes.size)
                if (count <= 0) {
                    nativeResetSearch()
                    return@withLock PinyinNativeSearchResult(emptyList(), "", emptyList())
                }
                val decodedPinyin = runCatching { nativeGetPyStr() }.getOrDefault(pinyin)
                val rawBoundaries = runCatching {
                    nativeGetSplStartPositions().toList()
                }.getOrDefault(emptyList())
                val syllableEndOffsets = rawBoundaries
                    .takeIf {
                        it.size >= 2 &&
                            it.first() == 0 &&
                            it.zipWithNext().all { (left, right) -> left < right } &&
                            it.last() <= pinyin.length
                    }
                    ?.drop(1)
                    .orEmpty()
                val limit = minOf(count, maxCount)
                val candidates = buildList {
                    for (i in 0 until limit) {
                        val word = nativeGetCandidate(i)
                        if (word.isEmpty()) continue
                        add(PinyinNativeCandidate(text = word, decoderIndex = i))
                    }
                }
                nativeResetSearch()
                PinyinNativeSearchResult(
                    candidates = candidates,
                    decodedPinyin = decodedPinyin,
                    syllableEndOffsets = syllableEndOffsets,
                )
            } catch (e: Exception) {
                flogError { "Pinyin bridge search error: $e" }
                runCatching { nativeResetSearch() }
                PinyinNativeSearchResult(emptyList(), "", emptyList())
            }
        }
    }

    /**
     * NO-OP by design.
     *
     * The decoder is shared by three providers ([PinyinLanguageProvider],
     * [ShuangpinLanguageProvider], [T9LanguageProvider]). Closing it from any single
     * provider's destroy() would yank the decoder out from under the others while they
     * are still active. The single native decoder lives for the whole process; the OS
     * reclaims its file descriptor at process death, so there is nothing to release here.
     */
    suspend fun destroy() {
        // Intentionally empty — see KDoc. The shared decoder is never closed by a provider.
    }
}
