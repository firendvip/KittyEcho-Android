/*
 * Copyright (C) 2026 The WordTaker Contributors
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

import com.wordtaker.keyboard.ime.nlp.SuggestionCandidate
import com.wordtaker.keyboard.ime.nlp.WordSuggestionCandidate
import java.util.Locale

private const val MAX_RAW_PINYIN_LENGTH = 256
private const val MAX_CANDIDATE_CODE_POINTS = 64
private val SEGMENTED_PINYIN_CHARS = "abcdefghijklmnopqrstuvwxyz'".toSet()

internal data class PinyinSelectedSegment(
    val raw: String,
    val text: String,
)

internal data class PinyinSegmentedCompositionState(
    val selectedSegments: List<PinyinSelectedSegment>,
    val remainingRaw: String,
    val syllableEndOffsets: List<Int> = emptyList(),
) {
    val selectedText: String
        get() = selectedSegments.joinToString(separator = "") { it.text }

    val displayText: String
        get() = selectedText + remainingRaw

    val originalRaw: String
        get() = selectedSegments.joinToString(separator = "") { it.raw } + remainingRaw

    fun withSyllableEndOffsets(offsets: List<Int>): PinyinSegmentedCompositionState {
        val valid = offsets.isNotEmpty() &&
            offsets.zipWithNext().all { (left, right) -> left < right } &&
            offsets.all { it in 1..remainingRaw.length }
        return copy(syllableEndOffsets = offsets.takeIf { valid } ?: emptyList())
    }

    fun undoLastSelection(): PinyinSegmentedCompositionState? {
        val last = selectedSegments.lastOrNull() ?: return null
        return copy(
            selectedSegments = selectedSegments.dropLast(1),
            remainingRaw = last.raw + remainingRaw,
            syllableEndOffsets = emptyList(),
        )
    }

    companion object {
        fun initial(raw: String): PinyinSegmentedCompositionState? {
            val normalized = raw.lowercase(Locale.ROOT)
            return normalized
                .takeIf {
                    it.isNotEmpty() &&
                        it.length <= MAX_RAW_PINYIN_LENGTH &&
                        it.all(SEGMENTED_PINYIN_CHARS::contains)
                }
                ?.let {
                    PinyinSegmentedCompositionState(
                        selectedSegments = emptyList(),
                        remainingRaw = it,
                    )
                }
        }
    }
}

internal data class PinyinCandidateSelection(
    val state: PinyinSegmentedCompositionState,
    val rawEndExclusive: Int,
)

/**
 * Marker shared by local and cloud candidates. The selection snapshot, rather than the
 * candidate's visible list index, is the authority for a segmented choice.
 */
internal interface PinyinSegmentedSuggestionCandidate : SuggestionCandidate {
    val selection: PinyinCandidateSelection?
}

internal data class LocalPinyinSegmentedSuggestionCandidate(
    private val delegate: WordSuggestionCandidate,
    override val selection: PinyinCandidateSelection?,
) : PinyinSegmentedSuggestionCandidate, SuggestionCandidate by delegate

internal class ResolvedPinyinSuggestionCandidate(
    private val delegate: SuggestionCandidate,
    committedText: String,
) : SuggestionCandidate by delegate {
    override val text: CharSequence = committedText
}

internal sealed interface PinyinCandidateSelectionPlan {
    data class Continue(
        val state: PinyinSegmentedCompositionState,
    ) : PinyinCandidateSelectionPlan

    data class Commit(
        val text: String,
    ) : PinyinCandidateSelectionPlan

    data object Reject : PinyinCandidateSelectionPlan
}

internal sealed interface PinyinBackspacePlan {
    data class UndoSelection(
        val state: PinyinSegmentedCompositionState,
    ) : PinyinBackspacePlan

    data object DeleteNormally : PinyinBackspacePlan
}

internal fun planPinyinBackspace(
    state: PinyinSegmentedCompositionState?,
    currentComposingText: CharSequence,
    isCharacterDelete: Boolean,
): PinyinBackspacePlan {
    if (
        !isCharacterDelete ||
        state == null ||
        state.displayText != currentComposingText.toString()
    ) {
        return PinyinBackspacePlan.DeleteNormally
    }
    val restored = state.undoLastSelection() ?: return PinyinBackspacePlan.DeleteNormally
    return PinyinBackspacePlan.UndoSelection(restored)
}

internal fun segmentedSelectionForLocalCandidate(
    state: PinyinSegmentedCompositionState,
    candidateText: CharSequence,
    isFullSentenceCandidate: Boolean,
): PinyinCandidateSelection? {
    val inferredEnd = rawEndForCandidateText(state, candidateText)
    val end = if (isFullSentenceCandidate) {
        state.syllableEndOffsets.lastOrNull()?.takeIf { it == inferredEnd }
    } else {
        inferredEnd
    }
    return end?.let { PinyinCandidateSelection(state, consumeFollowingSeparator(state.remainingRaw, it)) }
}

internal fun segmentedSelectionForCloudCandidate(
    state: PinyinSegmentedCompositionState?,
    candidateText: CharSequence,
): PinyinCandidateSelection? {
    if (state == null) return null
    return rawEndForCandidateText(state, candidateText)
        ?.let { PinyinCandidateSelection(state, consumeFollowingSeparator(state.remainingRaw, it)) }
}

private fun rawEndForCandidateText(
    state: PinyinSegmentedCompositionState,
    candidateText: CharSequence,
): Int? {
    val text = candidateText.toString()
    val codePointCount = text.codePointCount(0, text.length)
    if (text.isBlank() || codePointCount !in 1..MAX_CANDIDATE_CODE_POINTS) return null
    return state.syllableEndOffsets.getOrNull(codePointCount - 1)
}

private fun consumeFollowingSeparator(raw: String, initialEnd: Int): Int {
    var end = initialEnd
    while (end < raw.length && raw[end] == '\'') {
        end += 1
    }
    return end
}

internal fun planPinyinCandidateSelection(
    candidateText: CharSequence,
    selection: PinyinCandidateSelection?,
    currentState: PinyinSegmentedCompositionState?,
    currentComposingText: CharSequence,
): PinyinCandidateSelectionPlan {
    if (
        selection == null ||
        currentState != selection.state ||
        currentComposingText.toString() != selection.state.displayText
    ) {
        return PinyinCandidateSelectionPlan.Reject
    }
    val candidate = candidateText.toString()
    val rawEnd = selection.rawEndExclusive
    if (
        candidate.isBlank() ||
        candidate.codePointCount(0, candidate.length) > MAX_CANDIDATE_CODE_POINTS ||
        rawEnd !in 1..selection.state.remainingRaw.length
    ) {
        return PinyinCandidateSelectionPlan.Reject
    }
    val consumedRaw = selection.state.remainingRaw.take(rawEnd)
    val remainingRaw = selection.state.remainingRaw.drop(rawEnd)
    val selected = selection.state.selectedSegments + PinyinSelectedSegment(
        raw = consumedRaw,
        text = candidate,
    )
    return if (remainingRaw.isEmpty()) {
        PinyinCandidateSelectionPlan.Commit(
            text = selected.joinToString(separator = "") { it.text },
        )
    } else {
        PinyinCandidateSelectionPlan.Continue(
            state = PinyinSegmentedCompositionState(
                selectedSegments = selected,
                remainingRaw = remainingRaw,
            ),
        )
    }
}

/**
 * One process has one active IME composing transaction. This store keeps only reversible
 * in-memory state and rejects stale candidate snapshots.
 */
internal class PinyinCompositionSession {
    private var state: PinyinSegmentedCompositionState? = null

    @Synchronized
    fun current(): PinyinSegmentedCompositionState? = state

    @Synchronized
    fun observe(composingText: CharSequence): PinyinSegmentedCompositionState? {
        val composing = composingText.toString()
        if (composing.isEmpty()) {
            state = null
            return null
        }
        val current = state
        val observed = when {
            current == null -> PinyinSegmentedCompositionState.initial(composing)
            composing == current.displayText -> current
            current.selectedText.isNotEmpty() && composing.startsWith(current.selectedText) -> {
                val remaining = composing.removePrefix(current.selectedText).lowercase(Locale.ROOT)
                remaining
                    .takeIf {
                        it.isNotEmpty() &&
                            it.length <= MAX_RAW_PINYIN_LENGTH &&
                            it.all(SEGMENTED_PINYIN_CHARS::contains)
                    }
                    ?.let { current.copy(remainingRaw = it, syllableEndOffsets = emptyList()) }
            }
            else -> PinyinSegmentedCompositionState.initial(composing)
        }
        state = observed
        return observed
    }

    @Synchronized
    fun publishSyllableEndOffsets(
        expected: PinyinSegmentedCompositionState,
        offsets: List<Int>,
    ): PinyinSegmentedCompositionState? {
        if (state != expected) return null
        return expected.withSyllableEndOffsets(offsets).also { state = it }
    }

    @Synchronized
    fun transition(
        expected: PinyinSegmentedCompositionState?,
        next: PinyinSegmentedCompositionState?,
    ): Boolean {
        if (state != expected) return false
        state = next
        return true
    }

    @Synchronized
    fun restore(
        expectedCurrent: PinyinSegmentedCompositionState,
        previous: PinyinSegmentedCompositionState,
    ) {
        if (state == expectedCurrent) {
            state = previous
        }
    }

    @Synchronized
    fun remainingRawFor(composingText: CharSequence): String? {
        return state?.takeIf { it.displayText == composingText.toString() }?.remainingRaw
    }

    @Synchronized
    fun originalRawFor(composingText: CharSequence): String? {
        return state?.takeIf { it.displayText == composingText.toString() }?.originalRaw
    }

    @Synchronized
    fun expandedComposingStart(
        textBeforeSelection: CharSequence,
        trailingRawStart: Int,
        localLastCommitPosition: Int,
    ): Int? {
        val selectedText = state?.selectedText.orEmpty()
        if (selectedText.isEmpty() || trailingRawStart < selectedText.length) return null
        val selectedStart = trailingRawStart - selectedText.length
        if (selectedStart < localLastCommitPosition) return null
        return selectedStart.takeIf {
            textBeforeSelection.substring(selectedStart, trailingRawStart) == selectedText
        }
    }

    @Synchronized
    fun clear() {
        state = null
    }
}

internal val activePinyinCompositionSession = PinyinCompositionSession()
