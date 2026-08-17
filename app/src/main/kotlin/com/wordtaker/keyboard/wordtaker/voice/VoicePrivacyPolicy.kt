package com.wordtaker.keyboard.wordtaker.voice

import com.wordtaker.keyboard.ime.editor.InputAttributes

data class VoicePrivacyContext(
    val inputType: Int,
    val noPersonalizedLearning: Boolean,
    val incognito: Boolean,
    val editorSessionToken: Long = TEST_SESSION_TOKEN,
) {
    val hasVerifiedEditor: Boolean
        get() = editorSessionToken != INVALID_SESSION_TOKEN &&
            (inputType and android.text.InputType.TYPE_MASK_CLASS) !=
            android.text.InputType.TYPE_NULL

    companion object {
        private const val TEST_SESSION_TOKEN = 0L
        const val INVALID_SESSION_TOKEN = Long.MIN_VALUE

        val NORMAL = VoicePrivacyContext(
            inputType = android.text.InputType.TYPE_CLASS_TEXT,
            noPersonalizedLearning = false,
            incognito = false,
        )

        val STRICT = VoicePrivacyContext(
            inputType = android.text.InputType.TYPE_NULL,
            noPersonalizedLearning = true,
            incognito = true,
            editorSessionToken = INVALID_SESSION_TOKEN,
        )

        fun fromEditor(
            inputType: Int,
            noPersonalizedLearning: Boolean,
            incognito: Boolean,
            editorSessionToken: Long,
        ): VoicePrivacyContext {
            if (
                (inputType and android.text.InputType.TYPE_MASK_CLASS) ==
                    android.text.InputType.TYPE_NULL ||
                editorSessionToken <= 0L
            ) {
                return STRICT
            }
            return VoicePrivacyContext(
                inputType = inputType,
                noPersonalizedLearning = noPersonalizedLearning,
                incognito = incognito,
                editorSessionToken = editorSessionToken,
            )
        }
    }
}

fun interface VoicePrivacySource {
    fun current(): VoicePrivacyContext

    companion object {
        val NORMAL = VoicePrivacySource { VoicePrivacyContext.NORMAL }
        val STRICT = VoicePrivacySource { VoicePrivacyContext.STRICT }
    }
}

data class VoicePrivacyDecision(
    val allowCloudPolish: Boolean,
    val saveHistory: Boolean,
) {
    fun restrictWith(other: VoicePrivacyDecision): VoicePrivacyDecision = VoicePrivacyDecision(
        allowCloudPolish = allowCloudPolish && other.allowCloudPolish,
        saveHistory = saveHistory && other.saveHistory,
    )
}

object VoicePrivacyPolicy {
    fun decide(
        inputType: Int,
        noPersonalizedLearning: Boolean,
        incognito: Boolean,
        localRecognitionOnly: Boolean,
    ): VoicePrivacyDecision {
        val variation = InputAttributes.wrap(inputType).variation
        val missingEditor = (inputType and android.text.InputType.TYPE_MASK_CLASS) ==
            android.text.InputType.TYPE_NULL
        val sensitive = missingEditor || noPersonalizedLearning || incognito ||
            variation in PASSWORD_VARIATIONS
        return VoicePrivacyDecision(
            allowCloudPolish = !localRecognitionOnly && !sensitive,
            saveHistory = !sensitive,
        )
    }

    fun decide(
        context: VoicePrivacyContext,
        localRecognitionOnly: Boolean,
    ): VoicePrivacyDecision = decide(
        inputType = context.inputType,
        noPersonalizedLearning = context.noPersonalizedLearning,
        incognito = context.incognito,
        localRecognitionOnly = localRecognitionOnly,
    )

    private val PASSWORD_VARIATIONS = setOf(
        InputAttributes.Variation.PASSWORD,
        InputAttributes.Variation.VISIBLE_PASSWORD,
        InputAttributes.Variation.WEB_PASSWORD,
    )
}
