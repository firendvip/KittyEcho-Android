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

import androidx.collection.LruCache
import androidx.compose.ui.graphics.vector.ImageVector
import com.wordtaker.keyboard.ime.nlp.SuggestionCandidate
import com.wordtaker.keyboard.ime.nlp.SuggestionProvider
import com.wordtaker.keyboard.wordtaker.backend.DictCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * 一次云词库联想请求：合法全拼 + 该请求对应的本地候选快照 + 唯一请求 ID。
 */
data class CloudDictRequest(
    val requestId: Long,
    val pinyin: String,
    val localCandidates: List<SuggestionCandidate>,
)

/**
 * Cloud candidate marker. Engine-initiated auto-commit and learning are disabled. Explicit
 * candidate taps and Space may select the visible first candidate; Enter never selects a candidate.
 * A visible newline key with composing commits the exact raw pinyin once without inserting a
 * newline, while non-newline editor actions continue after that raw commit.
 */
internal data class CloudSuggestionCandidate(
    override val text: CharSequence,
    val score: Double,
    override val selection: PinyinCandidateSelection? = null,
) : PinyinSegmentedSuggestionCandidate {
    override val secondaryText: CharSequence? = null
    override val confidence: Double = score.coerceIn(0.0, 1.0)
    override val isEligibleForAutoCommit: Boolean = false
    override val isEligibleForUserRemoval: Boolean = false
    override val icon: ImageVector? = null
    override val sourceProvider: SuggestionProvider? = null
}

private data class CloudDictCommand(
    val generation: Long,
    val request: CloudDictRequest?,
)

/**
 * 云词库「联想」增强层——composing 拼音之外，异步查询云端候选并入候选栏。
 *
 * 边界（务必勿越界）：这里做的是 composing 之后候选栏的联想补充，**不是**中文滑行手势匹配的几何
 * 语料——滑行分类器需要一次性预载的内存词表（[PinyinLanguageProvider.getListOfWords]），云接口不能
 * 按手势实时查，因此滑行拼写集合仍然纯本地（pinyin_glide_words.json）。滑行拼出的拼音一旦落入
 * composing，会走同一条 [request] 路径，因此滑行结果对应的候选栏一样会被云联想补充。
 *
 * 健壮性：debounce 150ms + collectLatest 取消未完成的上一次查询 + 会话内 LRU 缓存 + 500ms 超时；
 * 请求前和响应回填前均 fail-closed 重查全部门控。任何失败一律静默降级为「不合并」，从不阻塞、
 * 替换或重新发布本地候选快照。
 */
@OptIn(FlowPreview::class)
class CloudDictionaryAugmenter(
    scope: CoroutineScope,
    private val suggest: suspend (pinyin: String, limit: Int) -> List<DictCandidate>,
    private val isAllowed: (CloudDictRequest) -> Boolean,
    private val onMerged: suspend (request: CloudDictRequest, merged: List<SuggestionCandidate>) -> Unit,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private val generation = AtomicLong(0)
    private val newestRequestId = AtomicLong(Long.MIN_VALUE)
    private val latestCommand = MutableStateFlow(CloudDictCommand(generation = 0, request = null))

    // 仅当前增强器会话内存缓存：保留已验证候选的 text + score，绝不持久化。
    private val cache = LruCache<String, List<DictCandidate>>(CACHE_SIZE)

    init {
        scope.launch {
            latestCommand.drop(1)
                .debounce(DEBOUNCE_MS)
                .collectLatest { command ->
                    command.request?.let { request -> handle(command, request) }
                }
        }
    }

    /** 投递一次云联想请求（非阻塞），同时使所有更早的请求 ID 失效。 */
    fun request(req: CloudDictRequest) {
        if (!claimNewerRequestId(req.requestId)) return
        val accepted = req.takeIf { CLOUD_PINYIN.matches(it.pinyin) && isAllowed(it) }
        latestCommand.value = CloudDictCommand(generation.incrementAndGet(), accepted)
    }

    /** Invalidates and cancels any pending/in-flight request without starting another one. */
    fun invalidate() {
        latestCommand.value = CloudDictCommand(generation.incrementAndGet(), request = null)
    }

    private suspend fun handle(command: CloudDictCommand, req: CloudDictRequest) {
        if (!isLatest(command, req) || !isAllowed(req)) return
        val cloudCandidates = cache.get(req.pinyin) ?: fetchAndCache(req.pinyin) ?: return
        if (cloudCandidates.isEmpty()) return
        if (!isLatest(command, req) || !isAllowed(req)) return
        onMerged(req, merge(req.localCandidates, cloudCandidates))
    }

    private fun isLatest(command: CloudDictCommand, req: CloudDictRequest): Boolean {
        val latest = latestCommand.value
        return latest.generation == command.generation &&
            latest.request?.requestId == req.requestId
    }

    private fun claimNewerRequestId(requestId: Long): Boolean {
        while (true) {
            val current = newestRequestId.get()
            if (requestId <= current) return false
            if (newestRequestId.compareAndSet(current, requestId)) return true
        }
    }

    private suspend fun fetchAndCache(pinyin: String): List<DictCandidate>? = withContext(ioContext) {
        val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                suggest(pinyin, CANDIDATE_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (result == null) return@withContext null
        val candidates = sanitizeCloudCandidates(result)
        cache.put(pinyin, candidates)
        candidates
    }

    private fun sanitizeCloudCandidates(candidates: List<DictCandidate>): List<DictCandidate> {
        val seen = mutableSetOf<String>()
        return candidates.withIndex()
            .filter { (_, candidate) -> candidate.text.isNotBlank() && candidate.score.isFinite() }
            .sortedWith(
                compareByDescending<IndexedValue<DictCandidate>> { it.value.score }
                    .thenBy { it.index },
            )
            .map(IndexedValue<DictCandidate>::value)
            .filter { candidate -> seen.add(normalizeCandidateText(candidate.text)) }
    }

    /**
     * All valid cloud candidates lead. Remaining local candidates retain their relative
     * order. NFC text identity removes duplicates across and within both sources.
     */
    private fun merge(
        local: List<SuggestionCandidate>,
        cloudCandidates: List<DictCandidate>,
    ): List<SuggestionCandidate> {
        val segmentedState = local.asSequence()
            .filterIsInstance<PinyinSegmentedSuggestionCandidate>()
            .mapNotNull { it.selection?.state }
            .firstOrNull()
        val localSelectionsByText = local.asSequence()
            .filterIsInstance<PinyinSegmentedSuggestionCandidate>()
            .mapNotNull { candidate ->
                candidate.selection?.let {
                    normalizeCandidateText(candidate.text.toString()) to it
                }
            }
            .toMap()
        val seen = cloudCandidates.mapTo(mutableSetOf()) { normalizeCandidateText(it.text) }
        val cloud = cloudCandidates.map { candidate ->
            val normalizedText = normalizeCandidateText(candidate.text)
            val selection = localSelectionsByText[normalizedText]
                ?: segmentedSelectionForCloudCandidate(segmentedState, candidate.text)
            CloudSuggestionCandidate(
                text = candidate.text,
                score = candidate.score,
                selection = selection,
            )
        }
        val remainingLocal = local.filter { candidate ->
            candidate.text.isNotBlank() && seen.add(normalizeCandidateText(candidate.text.toString()))
        }
        return cloud + remainingLocal
    }

    private fun normalizeCandidateText(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
        const val FETCH_TIMEOUT_MS = 500L
        const val CACHE_SIZE = 200
        const val CANDIDATE_LIMIT = 10
        val CLOUD_PINYIN = Regex("^[a-z]+$")
    }
}
