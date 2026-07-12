package com.wordtaker.keyboard.ime.nlp.pinyin

import com.wordtaker.keyboard.ime.nlp.SuggestionCandidate
import com.wordtaker.keyboard.ime.nlp.WordSuggestionCandidate
import com.wordtaker.keyboard.wordtaker.backend.DictCandidate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/**
 * [CloudDictionaryAugmenter] 行为自测：全部跑在 kotlinx-coroutines-test 的虚拟时钟上，
 * 注入可控的 `suggest` fake（不打真网络），共享同一个 [StandardTestDispatcher] 作为 ioContext，
 * 使 debounce / collectLatest / withTimeoutOrNull 的时序都在虚拟时间内确定可推进。
 *
 * 覆盖：debounce 去抖只发末次、collectLatest 取消在途、LRU 缓存命中不重复请求、
 * 去重合并（云候选排在本地后、去重、低置信度）、isEnabled=false 完全不请求、
 * 超时 / 失败 / 空结果一律不改本地候选、空拼音早退。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudDictionaryAugmenterTest : FunSpec({

    fun local(vararg texts: String): List<SuggestionCandidate> =
        texts.map { WordSuggestionCandidate(text = it, confidence = 0.8) }

    test("debounce: rapid inputs collapse into a single fetch for the last pinyin") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ -> calls += p; listOf(DictCandidate(p.uppercase(), 0.5, "cloud")) },
            isEnabled = { true },
            onMerged = { rt, m -> merged += rt to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "a", emptyList()))
        advanceTimeBy(50)
        aug.request(CloudDictRequest(2, "ab", emptyList()))
        advanceTimeBy(50)
        aug.request(CloudDictRequest(3, "abc", emptyList()))
        advanceTimeBy(2_000)

        calls shouldBe listOf("abc")
        merged shouldHaveSize 1
        merged[0].first shouldBe 3L
    } }

    test("collectLatest cancels an in-flight fetch when a newer request arrives") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val started = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ ->
                started += p
                delay(400) // long-running fetch, still under the 500ms timeout
                completed += p
                listOf(DictCandidate("R-$p", 0.5, "cloud"))
            },
            isEnabled = { true },
            onMerged = { rt, m -> merged += rt to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "a", emptyList()))
        advanceTimeBy(200) // debounce fires at 150; suggest("a") mid-flight (done at ~550)
        aug.request(CloudDictRequest(2, "b", emptyList()))
        advanceTimeBy(2_000)

        started shouldBe listOf("a", "b")
        completed shouldBe listOf("b") // "a" cancelled before completing
        merged.map { it.first } shouldBe listOf(2L)
    } }

    test("LRU cache hit: repeated pinyin is fetched once but still merged each time") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ -> calls += p; listOf(DictCandidate("R", 0.5, "cloud")) },
            isEnabled = { true },
            onMerged = { rt, m -> merged += rt to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", emptyList()))
        advanceTimeBy(2_000)
        aug.request(CloudDictRequest(2, "ni", emptyList()))
        advanceTimeBy(2_000)

        calls shouldBe listOf("ni") // second served from cache
        merged shouldHaveSize 2
    } }

    test("merge: cloud candidates dedup against local and are appended after them with low confidence") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { _, _ ->
                listOf(DictCandidate("你好", 0.9, "cloud"), DictCandidate("你号", 0.4, "cloud"))
            },
            isEnabled = { true },
            onMerged = { rt, m -> merged += rt to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("你好", "你")))
        advanceTimeBy(2_000)

        merged shouldHaveSize 1
        val out = merged[0].second
        out.map { it.text.toString() } shouldBe listOf("你好", "你", "你号") // local order kept, dup dropped
        out[2].confidence shouldBe 0.01 // appended cloud candidate is low-confidence
    } }

    test("isEnabled=false performs no fetch and no merge") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ -> calls += p; listOf(DictCandidate("R", 0.5, "cloud")) },
            isEnabled = { false },
            onMerged = { rt, m -> merged += rt to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地")))
        advanceTimeBy(2_000)

        calls.shouldBeEmpty()
        merged.shouldBeEmpty()
    } }

    test("fetch exceeding the 500ms timeout leaves local candidates untouched") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ -> calls += p; delay(600); listOf(DictCandidate("迟到", 0.5, "cloud")) },
            isEnabled = { true },
            onMerged = { rt, m -> merged += rt to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地")))
        advanceTimeBy(2_000)

        calls shouldBe listOf("ni")
        merged.shouldBeEmpty() // withTimeoutOrNull(500) < 600 -> silent fallback
    } }

    test("fetch failure is swallowed and does not merge") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { _, _ -> throw RuntimeException("network down") },
            isEnabled = { true },
            onMerged = { rt, m -> merged += rt to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地")))
        advanceTimeBy(2_000)

        merged.shouldBeEmpty()
    } }

    test("empty cloud result does not merge") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { _, _ -> emptyList() },
            isEnabled = { true },
            onMerged = { rt, m -> merged += rt to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地")))
        advanceTimeBy(2_000)

        merged.shouldBeEmpty()
    } }

    test("empty pinyin request is ignored entirely") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ -> calls += p; listOf(DictCandidate("R", 0.5, "cloud")) },
            isEnabled = { true },
            onMerged = { rt, m -> merged += rt to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "", local("本地")))
        advanceTimeBy(2_000)

        calls.shouldBeEmpty()
        merged.shouldBeEmpty()
    } }
})
