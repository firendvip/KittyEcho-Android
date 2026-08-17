package com.wordtaker.keyboard.ime.nlp

import com.wordtaker.keyboard.ime.nlp.pinyin.CloudDictRequest
import com.wordtaker.keyboard.ime.nlp.pinyin.CloudDictionaryAugmenter
import com.wordtaker.keyboard.wordtaker.backend.DictCandidate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SuggestionRequestGateTest : FunSpec({

    test("id2 cloud stays alive when a later-finishing id1 reaches the former invalidate path") { runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val mergedRequestIds = mutableListOf<Long>()
        lateinit var augmenter: CloudDictionaryAugmenter
        val gate = SuggestionRequestGate { augmenter.invalidate() }
        augmenter = CloudDictionaryAugmenter(
            scope = CoroutineScope(backgroundScope.coroutineContext + io),
            suggest = { pinyin, _ ->
                delay(300)
                listOf(DictCandidate(pinyin, 0.9, "cloud"))
            },
            isAllowed = { true },
            onMerged = { request, _ -> mergedRequestIds += request.requestId },
            ioContext = io,
        )

        val id1 = gate.beginRequest()
        val id2 = gate.beginRequest()

        gate.runIfLatest(id2) {
            augmenter.request(CloudDictRequest(id2, "new", emptyList()))
        }.shouldBeTrue()
        advanceTimeBy(200) // id2 has passed debounce and is now in-flight.

        gate.runIfLatest(id1) {
            // This is the former stale-completion path which killed id2 before id1 was rejected.
            augmenter.invalidate()
            augmenter.request(CloudDictRequest(id1, "old", emptyList()))
        }.shouldBeFalse()
        advanceTimeBy(1_000)

        mergedRequestIds shouldBe listOf(id2)
    } }

    test("beginning a newest but inapplicable request still immediately cancels the previous cloud command") {
        var invalidations = 0
        val events = mutableListOf<String>()
        val gate = SuggestionRequestGate {
            invalidations += 1
        }

        val oldId = gate.beginRequest()
        gate.runIfLatest(oldId) {
            events += "old-cloud-started"
        }.shouldBeTrue()

        gate.beginRequest() // The new input is inapplicable, so it never publishes or requests cloud.

        invalidations shouldBe 2
        events shouldBe listOf("old-cloud-started")
    }

    test("shared suggestion state can be read under the same gate lock") {
        val gate = SuggestionRequestGate { }
        val state = mutableListOf("local")

        val snapshot = gate.withLock {
            state.toList()
        }

        snapshot shouldBe listOf("local")
    }
})
