package com.wordtaker.keyboard.ime.editor

/**
 * Enter is deliberately different from candidate selection: an active Chinese composing region
 * contributes its exact raw spelling. No trimming or candidate lookup is allowed at this boundary.
 */
internal fun rawCompositionForEnter(
    primaryLanguage: String,
    composingText: String,
): String? =
    composingText.takeIf {
        primaryLanguage.startsWith("zh") && it.isNotEmpty()
    }

internal enum class EnterKeyFollowUp {
    FINISH_AFTER_RAW,
    INSERT_NEWLINE,
    PERFORM_EDITOR_ACTION,
}

/**
 * A key which visibly represents a newline stops after committing raw Chinese composing.
 * Non-newline editor actions retain their normal action after that raw commit.
 */
internal fun enterKeyFollowUp(
    flagNoEnterAction: Boolean,
    isMultiline: Boolean,
    isShiftPressed: Boolean,
    action: ImeOptions.Action,
    hadRawComposition: Boolean,
): EnterKeyFollowUp {
    val isEditorAction = when (action) {
        ImeOptions.Action.DONE,
        ImeOptions.Action.GO,
        ImeOptions.Action.NEXT,
        ImeOptions.Action.PREVIOUS,
        ImeOptions.Action.SEARCH,
        ImeOptions.Action.SEND,
        -> true
        ImeOptions.Action.NONE,
        ImeOptions.Action.UNSPECIFIED,
        -> false
    }
    val isNewline = flagNoEnterAction || isMultiline && isShiftPressed || !isEditorAction
    return when {
        isNewline && hadRawComposition -> EnterKeyFollowUp.FINISH_AFTER_RAW
        isNewline -> EnterKeyFollowUp.INSERT_NEWLINE
        else -> EnterKeyFollowUp.PERFORM_EDITOR_ACTION
    }
}
