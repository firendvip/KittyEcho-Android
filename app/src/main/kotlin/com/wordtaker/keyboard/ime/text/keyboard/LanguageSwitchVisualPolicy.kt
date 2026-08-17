package com.wordtaker.keyboard.ime.text.keyboard

import kotlin.math.min

internal const val LANGUAGE_SWITCH_VISUAL_WIDTH_RATIO = 0.0852f
internal const val LANGUAGE_SWITCH_CONTENT_PADDING_DP = 1f
private const val LANGUAGE_SWITCH_CONTENT_ENVELOPE_FACTOR = 1.44f

internal fun languageSwitchVisualWidthPx(logicalKeyboardWidthPx: Float): Float =
    if (logicalKeyboardWidthPx.isFinite() && logicalKeyboardWidthPx > 0f) {
        logicalKeyboardWidthPx * LANGUAGE_SWITCH_VISUAL_WIDTH_RATIO
    } else {
        0f
    }

/**
 * Clamps the diagonal two-glyph treatment to its real pixel envelope. Converting through
 * scaled density keeps the result stable when either display density or font scale changes.
 */
internal fun languageSwitchBaseSizeSp(
    requestedSizeSp: Float,
    availableWidthPx: Float,
    availableHeightPx: Float,
    density: Float,
    fontScale: Float,
): Float {
    if (!requestedSizeSp.isFinite() || requestedSizeSp <= 0f ||
        !availableWidthPx.isFinite() || availableWidthPx <= 0f ||
        !availableHeightPx.isFinite() || availableHeightPx <= 0f ||
        !density.isFinite() || density <= 0f ||
        !fontScale.isFinite() || fontScale <= 0f
    ) {
        return 0f
    }
    val scaledDensity = density * fontScale
    if (!scaledDensity.isFinite() || scaledDensity <= 0f) return 0f
    val paddedLimitPx = min(availableWidthPx, availableHeightPx) -
        2f * LANGUAGE_SWITCH_CONTENT_PADDING_DP * density
    if (paddedLimitPx <= 0f) return 0f
    val requestedPx = requestedSizeSp * scaledDensity
    val maximumBasePx = paddedLimitPx / LANGUAGE_SWITCH_CONTENT_ENVELOPE_FACTOR
    return min(requestedPx, maximumBasePx) / scaledDensity
}

internal fun languageSwitchContentFootprintPx(
    baseSizeSp: Float,
    density: Float,
    fontScale: Float,
): Float =
    if (baseSizeSp.isFinite() && baseSizeSp > 0f &&
        density.isFinite() && density > 0f &&
        fontScale.isFinite() && fontScale > 0f
    ) {
        baseSizeSp * density * fontScale * LANGUAGE_SWITCH_CONTENT_ENVELOPE_FACTOR
    } else {
        0f
    }
