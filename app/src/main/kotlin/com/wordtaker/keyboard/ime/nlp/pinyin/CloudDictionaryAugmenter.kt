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
import com.wordtaker.keyboard.ime.nlp.SuggestionCandidate
import com.wordtaker.keyboard.ime.nlp.WordSuggestionCandidate
import com.wordtaker.keyboard.lib.devtools.flogDebug
import com.wordtaker.keyboard.wordtaker.backend.DictCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/**
 * 一次云词库联想请求：composing 拼音 + 该请求对应的本地候选（用于合并）+ reqTime（用于回填时
 * 判断"仍是最新"，与 [com.wordtaker.keyboard.ime.nlp.NlpManager] 现有的 reqTime 写入守卫复用同一时间戳）。
 */
data class CloudDictRequest(
    val reqTime: Long,
    val pinyin: String,
    val localCandidates: List<SuggestionCandidate>,
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
 * 任何失败（未启用/超时/网络错/无网络）一律静默降级为「不合并」，从不阻塞或替换本地候选。
 */
@OptIn(FlowPreview::class)
class CloudDictionaryAugmenter(
    scope: CoroutineScope,
    private val suggest: suspend (pinyin: String, limit: Int) -> List<DictCandidate>,
    private val isEnabled: () -> Boolean,
    private val onMerged: suspend (reqTime: Long, merged: List<SuggestionCandidate>) -> Unit,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private val latestRequest = MutableStateFlow<CloudDictRequest?>(null)

    // 会话内缓存：pinyin -> 云候选文本列表（已去重），减少重复请求与流量消耗。
    private val cache = LruCache<String, List<String>>(CACHE_SIZE)

    init {
        scope.launch {
            latestRequest.filterNotNull()
                .debounce(DEBOUNCE_MS)
                .collectLatest { req -> handle(req) }
        }
    }

    /** 投递一次云联想请求（非阻塞）。同一 [scope] 内的 debounce+collectLatest 负责去抖与取消在途查询。 */
    fun request(req: CloudDictRequest) {
        if (req.pinyin.isEmpty()) return
        latestRequest.value = req
    }

    private suspend fun handle(req: CloudDictRequest) {
        if (!isEnabled()) return
        val cloudTexts = cache.get(req.pinyin) ?: fetchAndCache(req.pinyin) ?: return
        if (cloudTexts.isEmpty()) return
        onMerged(req.reqTime, merge(req.localCandidates, cloudTexts))
    }

    private suspend fun fetchAndCache(pinyin: String): List<String>? = withContext(ioContext) {
        val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            runCatching { suggest(pinyin, CANDIDATE_LIMIT) }.getOrNull()
        }
        if (result == null) {
            flogDebug { "CloudDict '$pinyin' fetch failed/timeout, silent fallback to local-only" }
            return@withContext null
        }
        val texts = result.map { it.text }.distinct()
        cache.put(pinyin, texts)
        flogDebug { "CloudDict '$pinyin' -> ${texts.size} cloud candidates" }
        texts
    }

    /** 本地候选保持在前，云候选按序插入尾部并去重；不改变本地候选的相对顺序或置信度。 */
    private fun merge(local: List<SuggestionCandidate>, cloudTexts: List<String>): List<SuggestionCandidate> {
        val seen = local.mapTo(mutableSetOf()) { it.text.toString() }
        val extra = cloudTexts.filter { seen.add(it) }.map { text ->
            WordSuggestionCandidate(
                text = text,
                confidence = CLOUD_CONFIDENCE,
                isEligibleForAutoCommit = false,
                isEligibleForUserRemoval = false,
                sourceProvider = null,
            )
        }
        return if (extra.isEmpty()) local else local + extra
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
        const val FETCH_TIMEOUT_MS = 500L
        const val CACHE_SIZE = 200
        const val CANDIDATE_LIMIT = 10

        // 云候选置信度固定低于本地（本地候选通常用递减的 (size-index)/size，最低也 > 0），
        // 保证云候选视觉上排在本地候选之后。
        const val CLOUD_CONFIDENCE = 0.01
    }
}
