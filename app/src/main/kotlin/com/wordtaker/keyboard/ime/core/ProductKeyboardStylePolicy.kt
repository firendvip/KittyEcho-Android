package com.wordtaker.keyboard.ime.core

private const val LEGACY_HANDWRITING_STYLE = "handwriting"
private const val PRODUCT_DEFAULT_STYLE = "qwerty_pinyin"
private const val HANDWRITING_CHARACTER_LAYOUT = "handwriting_pad"

/**
 * Handwriting remains an internal engine capability, but it is no longer a user-selectable
 * product style. Normalize the legacy preference at every read boundary so old installs
 * safely return to full pinyin without changing any other current or future style.
 */
internal fun normalizePersistedProductKeyboardStyle(style: String): String =
    if (style == LEGACY_HANDWRITING_STYLE) PRODUCT_DEFAULT_STYLE else style

/**
 * Old installs can also have the retired choice embedded in the serialized subtype list.
 * Replace only handwriting subtypes with the canonical full-pinyin subtype and retain their
 * IDs so the active-subtype reference remains valid. Returning legal subtypes unchanged makes
 * the migration idempotent and preserves T9 and user-curated non-handwriting choices.
 */
internal fun migratePersistedProductSubtypes(subtypes: List<Subtype>): List<Subtype> =
    subtypes.map { subtype ->
        val isHandwriting = subtype.primaryLocale.variant == LEGACY_HANDWRITING_STYLE ||
            subtype.layoutMap.characters.componentId == HANDWRITING_CHARACTER_LAYOUT
        if (isHandwriting) {
            Subtype.PINYIN_DEFAULT.copy(id = subtype.id)
        } else {
            subtype
        }
    }

/**
 * The generic subtype editor is deep-link reachable. Keep the internal handwriting layout
 * resource loaded for existing engine code while excluding it from that product-facing picker.
 */
internal fun isProductSelectableCharacterLayout(componentId: String): Boolean =
    componentId != HANDWRITING_CHARACTER_LAYOUT
