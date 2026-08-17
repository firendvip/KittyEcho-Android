package com.wordtaker.keyboard.ime.nlp.pinyin

import com.wordtaker.keyboard.ime.nlp.SuggestionCandidate
import com.wordtaker.keyboard.ime.nlp.WordSuggestionCandidate
import com.wordtaker.keyboard.wordtaker.backend.DictCandidate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * 覆盖：150ms debounce、500ms 超时、唯一请求失效、会话内缓存保分、请求/响应双重门控、
 * score 稳定排序、NFC 去重、异常/空结果静默回退，以及固定 limit=10。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudDictionaryAugmenterTest : FunSpec({

    fun local(vararg texts: String): List<SuggestionCandidate> =
        texts.map { WordSuggestionCandidate(text = it, confidence = 0.8) }

    test("debounce: rapid inputs collapse into a single fetch for the last pinyin") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<Pair<String, Int>>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, limit ->
                calls += p to limit
                listOf(DictCandidate(p.uppercase(), 0.5, "cloud"))
            },
            isAllowed = { true },
            onMerged = { req, m -> merged += req.requestId to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "a", emptyList()))
        advanceTimeBy(50)
        aug.request(CloudDictRequest(2, "ab", emptyList()))
        advanceTimeBy(50)
        aug.request(CloudDictRequest(3, "abc", emptyList()))
        advanceTimeBy(49)
        calls.shouldBeEmpty()
        merged.shouldBeEmpty()
        advanceTimeBy(2_000)

        calls shouldBe listOf("abc" to 10)
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
            isAllowed = { true },
            onMerged = { req, m -> merged += req.requestId to m },
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

    test("a lower request ID arriving late cannot replace the newest request") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<String>()
        val merged = mutableListOf<Long>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ ->
                calls += p
                listOf(DictCandidate(p, 0.5, "cloud"))
            },
            isAllowed = { true },
            onMerged = { req, _ -> merged += req.requestId },
            ioContext = io,
        )

        aug.request(CloudDictRequest(2, "new", emptyList()))
        aug.request(CloudDictRequest(1, "old", emptyList()))
        advanceTimeBy(2_000)

        calls shouldBe listOf("new")
        merged shouldBe listOf(2L)
    } }

    test("LRU cache hit: repeated pinyin is fetched once but still merged each time") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ -> calls += p; listOf(DictCandidate("R", 0.73, "cloud")) },
            isAllowed = { true },
            onMerged = { req, m -> merged += req.requestId to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", emptyList()))
        advanceTimeBy(2_000)
        aug.request(CloudDictRequest(2, "ni", emptyList()))
        advanceTimeBy(2_000)

        calls shouldBe listOf("ni") // second served from cache
        merged shouldHaveSize 2
        merged.map { it.second.first().confidence } shouldBe listOf(0.73, 0.73)
    } }

    test("merge: valid cloud candidates lead by stable score order and NFC-deduplicate cloud and local text") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { _, _ ->
                listOf(
                    DictCandidate("低分云", 0.4, "cloud"),
                    DictCandidate("", 100.0, "cloud"),
                    DictCandidate("e\u0301", 0.9, "cloud"),
                    DictCandidate("高分云", 0.9, "cloud"),
                    DictCandidate("高分云", 0.8, "cloud"),
                    DictCandidate("同分云", 0.9, "cloud"),
                    DictCandidate("非数", Double.NaN, "cloud"),
                    DictCandidate("无限", Double.POSITIVE_INFINITY, "cloud"),
                    DictCandidate("负无限", Double.NEGATIVE_INFINITY, "cloud"),
                )
            },
            isAllowed = { true },
            onMerged = { req, m -> merged += req.requestId to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地一", "é", "低分云", "本地一", "本地二")))
        advanceTimeBy(2_000)

        merged shouldHaveSize 1
        val out = merged[0].second
        out.map { it.text.toString() } shouldBe
            listOf("e\u0301", "高分云", "同分云", "低分云", "本地一", "本地二")
        out.take(4).map { it.confidence } shouldBe listOf(0.9, 0.9, 0.9, 0.4)
        out.take(4).all { !it.isEligibleForAutoCommit && !it.isEligibleForUserRemoval } shouldBe true
    } }

    test("cloud reorder and dedupe preserve segmented selection metadata instead of visible indices") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val initial = PinyinSegmentedCompositionState.initial("nihao")!!
            .withSyllableEndOffsets(listOf(2, 5))
        val localPhrase = LocalPinyinSegmentedSuggestionCandidate(
            delegate = WordSuggestionCandidate(text = "你好", confidence = 1.0),
            selection = segmentedSelectionForLocalCandidate(initial, "你好", true),
        )
        var merged: List<SuggestionCandidate> = emptyList()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { _, _ ->
                listOf(
                    DictCandidate("你", 1.0, "jieba"),
                    DictCandidate("你好", 0.9, "jieba"),
                )
            },
            isAllowed = { true },
            onMerged = { _, candidates -> merged = candidates },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "nihao", listOf(localPhrase)))
        advanceTimeBy(2_000)

        merged.map { it.text.toString() } shouldBe listOf("你", "你好")
        val partial = merged[0] as PinyinSegmentedSuggestionCandidate
        val phrase = merged[1] as PinyinSegmentedSuggestionCandidate
        planPinyinCandidateSelection("你", partial.selection, initial, "nihao")
            .shouldBeInstanceOf<PinyinCandidateSelectionPlan.Continue>()
            .state.remainingRaw shouldBe "hao"
        planPinyinCandidateSelection("你好", phrase.selection, initial, "nihao") shouldBe
            PinyinCandidateSelectionPlan.Commit("你好")
    } }

    test("cloud duplicate preserves validated local decoder selection during deduplication") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val initial = PinyinSegmentedCompositionState.initial("nihao")!!
            .withSyllableEndOffsets(listOf(2, 5))
        val localFullSentence = LocalPinyinSegmentedSuggestionCandidate(
            delegate = WordSuggestionCandidate(text = "你好", confidence = 1.0),
            selection = segmentedSelectionForLocalCandidate(initial, "你好", true),
        )
        var merged: List<SuggestionCandidate> = emptyList()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { _, _ -> listOf(DictCandidate("你好", 1.0, "jieba")) },
            isAllowed = { true },
            onMerged = { _, candidates -> merged = candidates },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "nihao", listOf(localFullSentence)))
        advanceTimeBy(2_000)

        val cloudDuplicate = merged.single() as PinyinSegmentedSuggestionCandidate
        cloudDuplicate.selection shouldBe localFullSentence.selection
        planPinyinCandidateSelection("你好", cloudDuplicate.selection, initial, "nihao") shouldBe
            PinyinCandidateSelectionPlan.Commit("你好")
    } }

    test("request-time gate denial performs no fetch and no merge") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ -> calls += p; listOf(DictCandidate("R", 0.5, "cloud")) },
            isAllowed = { false },
            onMerged = { req, m -> merged += req.requestId to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地")))
        advanceTimeBy(2_000)

        calls.shouldBeEmpty()
        merged.shouldBeEmpty()
    } }

    test("response-time gate denial discards a completed response") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        var allowed = true
        var calls = 0
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { _, _ ->
                calls += 1
                allowed = false
                listOf(DictCandidate("不得回填", 0.9, "cloud"))
            },
            isAllowed = { allowed },
            onMerged = { req, m -> merged += req.requestId to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地")))
        advanceTimeBy(2_000)

        calls shouldBe 1
        merged.shouldBeEmpty()
    } }

    test("explicit invalidation makes an in-flight request stale") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val completed = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ ->
                delay(300)
                completed += p
                listOf(DictCandidate("旧响应", 0.9, "cloud"))
            },
            isAllowed = { true },
            onMerged = { req, m -> merged += req.requestId to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地")))
        advanceTimeBy(200)
        aug.invalidate()
        advanceTimeBy(2_000)

        completed.shouldBeEmpty()
        merged.shouldBeEmpty()
    } }

    test("fetch exceeding the 500ms timeout leaves local candidates untouched") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ -> calls += p; delay(600); listOf(DictCandidate("迟到", 0.5, "cloud")) },
            isAllowed = { true },
            onMerged = { req, m -> merged += req.requestId to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地")))
        advanceTimeBy(649)

        calls shouldBe listOf("ni")
        merged.shouldBeEmpty()

        advanceTimeBy(2)
        merged.shouldBeEmpty() // withTimeoutOrNull(500) < 600 -> silent fallback
    } }

    test("fetch failure is swallowed and does not merge") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        var calls = 0
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { _, _ ->
                calls += 1
                throw RuntimeException("network down")
            },
            isAllowed = { true },
            onMerged = { req, m -> merged += req.requestId to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地")))
        advanceTimeBy(2_000)

        calls shouldBe 1
        merged.shouldBeEmpty()
    } }

    test("empty cloud result does not merge") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { _, _ -> emptyList() },
            isAllowed = { true },
            onMerged = { req, m -> merged += req.requestId to m },
            ioContext = io,
        )

        aug.request(CloudDictRequest(1, "ni", local("本地")))
        advanceTimeBy(2_000)

        merged.shouldBeEmpty()
    } }

    test("non-contract pinyin requests are ignored entirely") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val calls = mutableListOf<String>()
        val merged = mutableListOf<Pair<Long, List<SuggestionCandidate>>>()
        val aug = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { p, _ -> calls += p; listOf(DictCandidate("R", 0.5, "cloud")) },
            isAllowed = { true },
            onMerged = { req, m -> merged += req.requestId to m },
            ioContext = io,
        )

        listOf("", "ni'hao", "ni好", "NIHAO").forEachIndexed { index, pinyin ->
            aug.request(CloudDictRequest(index.toLong(), pinyin, local("本地")))
        }
        advanceTimeBy(2_000)

        calls.shouldBeEmpty()
        merged.shouldBeEmpty()
    } }
})
