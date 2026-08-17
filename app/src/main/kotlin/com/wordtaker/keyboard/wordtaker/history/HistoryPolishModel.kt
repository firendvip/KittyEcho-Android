package com.wordtaker.keyboard.wordtaker.history

import com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind

/**
 * Model attribution for the final text stored in one history row.
 *
 * [NONE] means the final text did not come from any polish model, including short/direct output
 * and every fallback. A database `null` is deliberately not represented here: it is reserved for
 * legacy rows written before attribution existed.
 */
enum class HistoryPolishModel(val storedValue: String) {
    LOCAL("local"),
    CLOUD("cloud"),
    NONE("none"),
    ;

    companion object {
        fun fromStoredValue(value: String?): HistoryPolishModel? =
            entries.firstOrNull { it.storedValue == value }
    }
}

internal fun historyPolishModel(outcome: PolishOutcomeKind): HistoryPolishModel = when (outcome) {
    PolishOutcomeKind.Polished -> HistoryPolishModel.CLOUD
    PolishOutcomeKind.ShortDirect,
    PolishOutcomeKind.OfflineDirect,
    PolishOutcomeKind.FallbackQuota,
    PolishOutcomeKind.FallbackAuthExpired,
    PolishOutcomeKind.FallbackTimeout,
    PolishOutcomeKind.FallbackNetwork,
    PolishOutcomeKind.FallbackServer,
    PolishOutcomeKind.FallbackUnknown,
    -> HistoryPolishModel.NONE
}

/**
 * Mirrors the PC history semantics: known successful engines are named, old/unknown successful
 * rows keep the generic "AI优化" heading, and unchanged legacy rows do not invent model use.
 */
internal fun historyProcessingLabel(entry: HistoryEntity): String? =
    when (HistoryPolishModel.fromStoredValue(entry.polishModel)) {
        HistoryPolishModel.LOCAL -> "AI优化·本地模型"
        HistoryPolishModel.CLOUD -> "AI优化·云端AI"
        HistoryPolishModel.NONE -> "未润色"
        null -> "AI优化".takeIf { entry.polished.trim() != entry.raw.trim() }
    }
