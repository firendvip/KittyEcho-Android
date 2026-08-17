package com.wordtaker.keyboard.wordtaker.ui

import kotlin.math.max

/**
 * Visual tokens measured from Doubao IME 1.3.15 on the project emulator.
 *
 * Keeping the values independent from Compose makes the visual contract cheap to unit-test and
 * prevents the keyboard, candidate strip and voice panel from drifting into separate palettes.
 */
internal object DoubaoImeSkin {
    internal data class Palette(
        val panelArgb: Long,
        val keyArgb: Long,
        val keyPressedArgb: Long,
        val functionalKeyArgb: Long,
        val functionalKeyPressedArgb: Long,
        val primaryArgb: Long,
        val primaryPressedArgb: Long,
        val foregroundArgb: Long,
        val secondaryForegroundArgb: Long,
        val voiceWaveArgb: Long,
        val voiceGradientStartArgb: Long,
        val voiceGradientEndArgb: Long,
    )

    val Light = Palette(
        panelArgb = 0xFFE0E2E6L,
        keyArgb = 0xFFFFFFFFL,
        keyPressedArgb = 0xFFF0F1F3L,
        functionalKeyArgb = 0xFFBDC2C8L,
        functionalKeyPressedArgb = 0xFFAEB4BCL,
        primaryArgb = 0xFF4F84FFL,
        primaryPressedArgb = 0xFF3F74EFL,
        foregroundArgb = 0xFF17181AL,
        secondaryForegroundArgb = 0xFF74787EL,
        voiceWaveArgb = 0xFF16B8F3L,
        voiceGradientStartArgb = 0xFFF3F7FFL,
        voiceGradientEndArgb = 0xFFDDE9FFL,
    )

    val Dark = Palette(
        panelArgb = 0xFF202226L,
        keyArgb = 0xFF34373CL,
        keyPressedArgb = 0xFF41454BL,
        functionalKeyArgb = 0xFF4A4E55L,
        functionalKeyPressedArgb = 0xFF575C64L,
        primaryArgb = 0xFF5C8BFFL,
        primaryPressedArgb = 0xFF4B7AEBL,
        foregroundArgb = 0xFFF3F4F6L,
        secondaryForegroundArgb = 0xFFB7BBC1L,
        voiceWaveArgb = 0xFF67CEFFL,
        voiceGradientStartArgb = 0xFF253047L,
        voiceGradientEndArgb = 0xFF1E2739L,
    )

    const val toolbarHeightRatio = 0.152f
    const val minimumToolbarHeightDp = 54f
    const val keyCornerRadiusDp = 10f
    const val keyLabelSizeSp = 20f
    const val edgeActionKeyWidthFactor = 1.35f
    const val bottomActionKeyWidthFactor = 2f
    const val toolbarTouchTargetDp = 48f
    const val pinyinPreeditHeightDp = 18f
    const val pinyinCandidateRowHeightDp = 36f
    const val candidateLineBoxHeightDp = 24f
    const val candidateVerticalPaddingDp = 2f
    const val candidateVerticalMarginDp = 1f

    fun palette(dark: Boolean): Palette = if (dark) Dark else Light

    /**
     * Pinyin-family providers expose their composing spelling as the first candidate's secondary
     * text. The reference layout lifts that spelling into a dedicated preedit line. Other
     * providers keep their secondary labels inside the candidate row so shape codes and similar
     * hints are never lost.
     */
    fun shouldLiftSecondaryText(providerId: String?): Boolean =
        providerId in liftedSecondaryTextProviderIds

    /**
     * A dedicated preedit line leaves a compact one-line candidate slot. Use it only when every
     * non-empty secondary label can be represented by that shared pinyin line; mixed providers
     * retain the full-height, two-line candidate presentation instead of clipping their labels.
     */
    fun shouldUseDedicatedPreedit(providerIdsWithSecondaryText: List<String?>): Boolean =
        providerIdsWithSecondaryText.isNotEmpty() &&
            providerIdsWithSecondaryText.all(::shouldLiftSecondaryText)

    /**
     * Returns a deterministic, centre-weighted envelope for the recording animation.
     * [level] is only a UI activity signal from VoiceViewModel, so it is clamped and deliberately
     * not presented as a microphone meter.
     */
    fun waveformBarFractions(level: Float): List<Float> {
        val activity = 0.28f + level.coerceIn(0f, 1f) * 0.72f
        return WAVEFORM_ENVELOPE.map { envelope ->
            max(MIN_WAVEFORM_FRACTION, envelope * activity)
        }
    }

    private const val MIN_WAVEFORM_FRACTION = 0.12f
    val liftedSecondaryTextProviderIds = setOf(
        "org.florisboard.nlp.providers.pinyin",
        "org.florisboard.nlp.providers.shuangpin",
        "org.florisboard.nlp.providers.t9",
    )
    private val WAVEFORM_ENVELOPE = listOf(
        0.24f, 0.32f, 0.42f, 0.55f, 0.68f, 0.80f, 0.90f,
        0.76f, 0.88f, 0.96f, 1.00f,
        0.96f, 0.88f, 0.76f, 0.90f, 0.80f, 0.68f, 0.55f,
        0.42f, 0.32f, 0.24f,
    )
}
