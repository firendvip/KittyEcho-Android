package com.wordtaker.keyboard.ime.nlp.pinyin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PinyinSegmentedCompositionTest : FunSpec({

    fun state(raw: String, vararg syllableEnds: Int) =
        PinyinSegmentedCompositionState.initial(raw)!!
            .withSyllableEndOffsets(syllableEnds.toList())

    test("nihao selecting 你 keeps preedit and selecting 好 commits exactly once") {
        val initial = state("nihao", 2, 5)
        val first = segmentedSelectionForLocalCandidate(
            state = initial,
            candidateText = "你",
            isFullSentenceCandidate = false,
        )
        val firstPlan = planPinyinCandidateSelection("你", first, initial, "nihao")
            .shouldBeInstanceOf<PinyinCandidateSelectionPlan.Continue>()

        firstPlan.state.displayText shouldBe "你hao"
        firstPlan.state.originalRaw shouldBe "nihao"
        firstPlan.state.selectedSegments shouldContainExactly listOf(
            PinyinSelectedSegment(raw = "ni", text = "你"),
        )

        val remainder = firstPlan.state.withSyllableEndOffsets(listOf(3))
        val last = segmentedSelectionForLocalCandidate(
            state = remainder,
            candidateText = "好",
            isFullSentenceCandidate = true,
        )
        planPinyinCandidateSelection("好", last, remainder, "你hao") shouldBe
            PinyinCandidateSelectionPlan.Commit("你好")
    }

    test("full phrase candidate preserves direct final commit") {
        val initial = state("nihao", 2, 5)
        val selection = segmentedSelectionForLocalCandidate(
            state = initial,
            candidateText = "你好",
            isFullSentenceCandidate = true,
        )

        planPinyinCandidateSelection("你好", selection, initial, "nihao") shouldBe
            PinyinCandidateSelectionPlan.Commit("你好")
    }

    test("apostrophe separator is consumed with the selected syllable") {
        val initial = state("ni'hao", 2, 6)
        val selection = segmentedSelectionForCloudCandidate(initial, "你")
        val plan = planPinyinCandidateSelection("你", selection, initial, "ni'hao")
            .shouldBeInstanceOf<PinyinCandidateSelectionPlan.Continue>()

        plan.state.remainingRaw shouldBe "hao"
        plan.state.originalRaw shouldBe "ni'hao"
        plan.state.displayText shouldBe "你hao"
    }

    test("cloud and local candidates use their own snapshot instead of visible index") {
        val initial = state("nihao", 2, 5)
        val cloudFirst = segmentedSelectionForCloudCandidate(initial, "你")
        val localPhrase = segmentedSelectionForLocalCandidate(initial, "你好", true)

        planPinyinCandidateSelection("你", cloudFirst, initial, "nihao")
            .shouldBeInstanceOf<PinyinCandidateSelectionPlan.Continue>()
            .state.remainingRaw shouldBe "hao"
        planPinyinCandidateSelection("你好", localPhrase, initial, "nihao") shouldBe
            PinyinCandidateSelectionPlan.Commit("你好")
    }

    test("stale mismatched empty and oversized candidates reject without consuming input") {
        val initial = state("nihao", 2, 5)
        val valid = segmentedSelectionForCloudCandidate(initial, "你")

        planPinyinCandidateSelection("你", valid, initial, "nih") shouldBe
            PinyinCandidateSelectionPlan.Reject
        planPinyinCandidateSelection(
            "你",
            valid,
            initial.copy(remainingRaw = "nih"),
            "nihao",
        ) shouldBe PinyinCandidateSelectionPlan.Reject
        segmentedSelectionForCloudCandidate(initial, "").shouldBeNull()
        segmentedSelectionForCloudCandidate(initial, "你".repeat(65)).shouldBeNull()
        segmentedSelectionForCloudCandidate(initial, "你好吗").shouldBeNull()
        segmentedSelectionForLocalCandidate(
            state = initial,
            candidateText = "你",
            isFullSentenceCandidate = true,
        ).shouldBeNull()
    }

    test("malformed syllable boundaries fail closed") {
        PinyinSegmentedCompositionState.initial("nihao")!!
            .withSyllableEndOffsets(listOf(2, 2, 8))
            .syllableEndOffsets shouldBe emptyList()
        PinyinSegmentedCompositionState.initial("nihao")!!
            .withSyllableEndOffsets(emptyList())
            .syllableEndOffsets shouldBe emptyList()
        PinyinSegmentedCompositionState.initial("nihao")!!
            .withSyllableEndOffsets(listOf(-1, 2))
            .syllableEndOffsets shouldBe emptyList()
        PinyinSegmentedCompositionState.initial("ni hao").shouldBeNull()
        PinyinSegmentedCompositionState.initial("n".repeat(257)).shouldBeNull()
    }

    test("session keeps selected prefix while remaining raw is edited") {
        val session = PinyinCompositionSession()
        val initial = session.observe("nihao")!!
        val withEnds = session.publishSyllableEndOffsets(initial, listOf(2, 5))!!
        val selection = segmentedSelectionForCloudCandidate(withEnds, "你")!!
        val partial = planPinyinCandidateSelection("你", selection, withEnds, "nihao")
            .shouldBeInstanceOf<PinyinCandidateSelectionPlan.Continue>()
        session.transition(withEnds, partial.state) shouldBe true

        session.observe("你ha")!!.apply {
            displayText shouldBe "你ha"
            originalRaw shouldBe "niha"
            syllableEndOffsets shouldBe emptyList()
        }
        session.remainingRawFor("你ha") shouldBe "ha"
    }

    test("selected segment retains enough raw input for a lossless undo") {
        val initial = state("nihao", 2, 5)
        val selection = segmentedSelectionForCloudCandidate(initial, "你")
        val partial = planPinyinCandidateSelection("你", selection, initial, "nihao")
            .shouldBeInstanceOf<PinyinCandidateSelectionPlan.Continue>()

        partial.state.undoLastSelection() shouldBe PinyinSegmentedCompositionState.initial("nihao")
    }

    test("explicit raw commit recovers the original latin spelling from mixed preedit") {
        val session = PinyinCompositionSession()
        val initial = session.observe("nihao")!!
        val withEnds = session.publishSyllableEndOffsets(initial, listOf(2, 5))!!
        val selection = segmentedSelectionForCloudCandidate(withEnds, "你")!!
        val partial = planPinyinCandidateSelection("你", selection, withEnds, "nihao")
            .shouldBeInstanceOf<PinyinCandidateSelectionPlan.Continue>()
        session.transition(withEnds, partial.state) shouldBe true

        session.originalRawFor("你hao") shouldBe "nihao"
        session.originalRawFor("stale") shouldBe null
    }

    test("first character backspace undoes the selected segment before raw deletion") {
        val initial = state("nihao", 2, 5)
        val selection = segmentedSelectionForCloudCandidate(initial, "你")
        val partial = planPinyinCandidateSelection("你", selection, initial, "nihao")
            .shouldBeInstanceOf<PinyinCandidateSelectionPlan.Continue>()

        val undo = planPinyinBackspace(
            state = partial.state,
            currentComposingText = "你hao",
            isCharacterDelete = true,
        ).shouldBeInstanceOf<PinyinBackspacePlan.UndoSelection>()
        undo.state shouldBe PinyinSegmentedCompositionState.initial("nihao")

        planPinyinBackspace(
            state = undo.state,
            currentComposingText = "nihao",
            isCharacterDelete = true,
        ) shouldBe PinyinBackspacePlan.DeleteNormally
    }

    test("multi segment backspace restores exactly one most recent raw segment at a time") {
        val selectedTwice = PinyinSegmentedCompositionState(
            selectedSegments = listOf(
                PinyinSelectedSegment(raw = "ni", text = "你"),
                PinyinSelectedSegment(raw = "men", text = "们"),
            ),
            remainingRaw = "hao",
        )

        val firstUndo = planPinyinBackspace(
            state = selectedTwice,
            currentComposingText = "你们hao",
            isCharacterDelete = true,
        ).shouldBeInstanceOf<PinyinBackspacePlan.UndoSelection>()
        firstUndo.state.apply {
            displayText shouldBe "你menhao"
            originalRaw shouldBe "nimenhao"
            selectedSegments shouldContainExactly listOf(
                PinyinSelectedSegment(raw = "ni", text = "你"),
            )
        }

        val secondUndo = planPinyinBackspace(
            state = firstUndo.state,
            currentComposingText = "你menhao",
            isCharacterDelete = true,
        ).shouldBeInstanceOf<PinyinBackspacePlan.UndoSelection>()
        secondUndo.state shouldBe PinyinSegmentedCompositionState.initial("nimenhao")
    }

    test("stale composing and non character deletion retain the standard delete path") {
        val selected = PinyinSegmentedCompositionState(
            selectedSegments = listOf(PinyinSelectedSegment(raw = "ni", text = "你")),
            remainingRaw = "hao",
        )

        planPinyinBackspace(selected, "stale", isCharacterDelete = true) shouldBe
            PinyinBackspacePlan.DeleteNormally
        planPinyinBackspace(selected, "你hao", isCharacterDelete = false) shouldBe
            PinyinBackspacePlan.DeleteNormally
        planPinyinBackspace(null, "nihao", isCharacterDelete = true) shouldBe
            PinyinBackspacePlan.DeleteNormally
    }

    test("session rejects stale transitions and clears across lifecycle reset") {
        val session = PinyinCompositionSession()
        val initial = session.observe("nihao")!!
        val newer = initial.copy(remainingRaw = "niha")

        session.transition(initial, newer) shouldBe true
        session.transition(initial, null) shouldBe false
        session.clear()
        session.current().shouldBeNull()
        session.remainingRawFor("niha").shouldBeNull()
    }

    test("selected prefix expands the composing range without crossing last commit") {
        val session = PinyinCompositionSession()
        val initial = session.observe("nihao")!!
        val withEnds = session.publishSyllableEndOffsets(initial, listOf(2, 5))!!
        val selection = segmentedSelectionForCloudCandidate(withEnds, "你")!!
        val partial = planPinyinCandidateSelection("你", selection, withEnds, "nihao")
            .shouldBeInstanceOf<PinyinCandidateSelectionPlan.Continue>()
        session.transition(withEnds, partial.state) shouldBe true

        session.expandedComposingStart("已发你hao", trailingRawStart = 3, localLastCommitPosition = 2) shouldBe 2
        session.expandedComposingStart("已发你hao", trailingRawStart = 3, localLastCommitPosition = 3)
            .shouldBeNull()
    }

    test("all malformed and stale session boundaries fail closed") {
        PinyinSegmentedCompositionState.initial("NIHAO")!!.remainingRaw shouldBe "nihao"
        PinyinSegmentedCompositionState.initial("").shouldBeNull()
        PinyinSegmentedCompositionState.initial("ni")!!.undoLastSelection().shouldBeNull()
        segmentedSelectionForCloudCandidate(null, "你").shouldBeNull()
        segmentedSelectionForLocalCandidate(
            PinyinSegmentedCompositionState.initial("ni")!!,
            "你",
            isFullSentenceCandidate = true,
        ).shouldBeNull()
        planPinyinCandidateSelection("你", null, null, "ni") shouldBe
            PinyinCandidateSelectionPlan.Reject

        val initial = state("nihao", 2, 5)
        planPinyinCandidateSelection(
            "",
            PinyinCandidateSelection(initial, 2),
            initial,
            "nihao",
        ) shouldBe PinyinCandidateSelectionPlan.Reject
        planPinyinCandidateSelection(
            "你".repeat(65),
            PinyinCandidateSelection(initial, 2),
            initial,
            "nihao",
        ) shouldBe PinyinCandidateSelectionPlan.Reject
        planPinyinCandidateSelection(
            "你",
            PinyinCandidateSelection(initial, 0),
            initial,
            "nihao",
        ) shouldBe PinyinCandidateSelectionPlan.Reject
        planPinyinCandidateSelection(
            "你",
            PinyinCandidateSelection(initial, 6),
            initial,
            "nihao",
        ) shouldBe PinyinCandidateSelectionPlan.Reject

        val session = PinyinCompositionSession()
        session.observe("").shouldBeNull()
        val observed = session.observe("nihao")!!
        session.observe("nihao") shouldBe observed
        session.publishSyllableEndOffsets(
            expected = observed.copy(remainingRaw = "nih"),
            offsets = listOf(2),
        ).shouldBeNull()
        val edited = observed.copy(remainingRaw = "niha")
        session.transition(observed, edited) shouldBe true
        session.restore(edited, observed)
        session.current() shouldBe observed
        session.remainingRawFor("different").shouldBeNull()
        session.expandedComposingStart("nihao", 0, -1).shouldBeNull()
        session.observe("你-hao").shouldBeNull()
        session.current().shouldBeNull()
        PinyinCompositionSession().apply {
            observe("nihao")
            observe("nih") shouldBe PinyinSegmentedCompositionState.initial("nih")
        }

        val selectedSession = PinyinCompositionSession()
        val selectedInitial = selectedSession.observe("nihao")!!
        val selectedWithEnds = selectedSession.publishSyllableEndOffsets(
            selectedInitial,
            listOf(2, 5),
        )!!
        val selectedPlan = planPinyinCandidateSelection(
            "你",
            segmentedSelectionForCloudCandidate(selectedWithEnds, "你"),
            selectedWithEnds,
            "nihao",
        ).shouldBeInstanceOf<PinyinCandidateSelectionPlan.Continue>()
        selectedSession.transition(selectedWithEnds, selectedPlan.state) shouldBe true
        selectedSession.restore(selectedInitial, selectedInitial)
        selectedSession.expandedComposingStart("你hao", 0, -1).shouldBeNull()
        selectedSession.observe("你").shouldBeNull()
        selectedSession.transition(selectedPlan.state, selectedPlan.state) shouldBe false
    }
})
