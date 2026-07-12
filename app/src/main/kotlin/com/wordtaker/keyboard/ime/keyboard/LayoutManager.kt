/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package com.wordtaker.keyboard.ime.keyboard

import android.content.Context
import com.wordtaker.keyboard.app.FlorisPreferenceStore
import com.wordtaker.keyboard.appContext
import com.wordtaker.keyboard.extensionManager
import com.wordtaker.keyboard.ime.core.Subtype
import com.wordtaker.keyboard.ime.popup.PopupMapping
import com.wordtaker.keyboard.ime.popup.PopupMappingComponent
import com.wordtaker.keyboard.ime.text.key.KeyType
import com.wordtaker.keyboard.ime.text.keyboard.TextKey
import com.wordtaker.keyboard.ime.text.keyboard.TextKeyData
import com.wordtaker.keyboard.ime.text.keyboard.TextKeyboard
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.lib.devtools.LogTopic
import com.wordtaker.keyboard.lib.devtools.flogDebug
import com.wordtaker.keyboard.lib.devtools.flogWarning
import com.wordtaker.keyboard.lib.ext.ExtensionComponentName
import com.wordtaker.keyboard.lib.io.ZipUtils
import com.wordtaker.keyboard.lib.io.loadJsonAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.wordtaker.lib.kotlin.DeferredResult
import com.wordtaker.lib.kotlin.runCatchingAsync

private data class LTN(
    val type: LayoutType,
    val name: ExtensionComponentName,
)

data class CachedLayout(
    val type: LayoutType,
    val name: ExtensionComponentName,
    val meta: LayoutArrangementComponent,
    val arrangement: LayoutArrangement,
)

private data class CachedPopupMapping(
    val name: ExtensionComponentName,
    val meta: PopupMappingComponent,
    val mapping: PopupMapping,
)

/**
 * WordTaker WeChat/iOS-style 全拼 QWERTY hints. Each letter key permanently shows the mapped
 * number/symbol at its top-right, and long-pressing the key inputs that number/symbol.
 *
 * This is the single source of truth for the per-letter hints on the [pinyin_qwerty] layout.
 * Editing a value here changes both the displayed hint and the long-press output.
 * `code` is the character actually committed on long-press; `label` is what is rendered.
 */
private val PINYIN_QWERTY_KEY_HINTS: Map<Int, TextKeyData> by lazy {
    fun hint(code: Int, label: String) = TextKeyData(code = code, label = label)
    mapOf(
        // Row 1 -> digits 1234567890
        'q'.code to hint(49, "1"),
        'w'.code to hint(50, "2"),
        'e'.code to hint(51, "3"),
        'r'.code to hint(52, "4"),
        't'.code to hint(53, "5"),
        'y'.code to hint(54, "6"),
        'u'.code to hint(55, "7"),
        'i'.code to hint(56, "8"),
        'o'.code to hint(57, "9"),
        'p'.code to hint(48, "0"),
        // Row 2 -> symbols (provisional, trivially editable)
        'a'.code to hint(45, "-"),
        's'.code to hint(47, "/"),
        'd'.code to hint(58, ":"),
        'f'.code to hint(59, ";"),
        'g'.code to hint(40, "("),
        'h'.code to hint(41, ")"),
        'j'.code to hint(126, "~"),
        'k'.code to hint(8220, "“"), // “
        'l'.code to hint(8221, "”"), // ”
        // Row 3 -> symbols (对齐参考图: @ . # ` ? ! …)
        'z'.code to hint(64, "@"),
        'x'.code to hint(46, "."),
        'c'.code to hint(35, "#"),
        'v'.code to hint(96, "`"),
        'b'.code to hint(63, "?"),
        'n'.code to hint(33, "!"),
        'm'.code to hint(8230, "…"),
    )
}

data class DebugLayoutComputationResult(
    val main: Result<CachedLayout?>,
    val mod: Result<CachedLayout?>,
    val ext: Result<CachedLayout?>,
) {
    fun allLayoutsSuccess(): Boolean {
        return main.isSuccess && mod.isSuccess && ext.isSuccess
    }
}

/**
 * Class which manages layout loading and caching.
 */
class LayoutManager(context: Context) {
    private val prefs by FlorisPreferenceStore
    private val appContext by context.appContext()
    private val extensionManager by context.extensionManager()
    private val keyboardManager by context.keyboardManager()

    private val layoutCache: HashMap<LTN, DeferredResult<CachedLayout>> = hashMapOf()
    private val layoutCacheGuard: Mutex = Mutex(locked = false)
    private val popupMappingCache: HashMap<ExtensionComponentName, DeferredResult<CachedPopupMapping>> = hashMapOf()
    private val popupMappingCacheGuard: Mutex = Mutex(locked = false)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val debugLayoutComputationResultFlow = MutableStateFlow<DebugLayoutComputationResult?>(null)

    /**
     * Loads the layout for the specified type and name.
     *
     * @return A deferred result for a layout.
     */
    private fun loadLayoutAsync(ltn: LTN?, allowNullLTN: Boolean) = ioScope.runCatchingAsync {
        if (!allowNullLTN) {
            requireNotNull(ltn) { "Invalid argument value for 'ltn': null" }
        }
        if (ltn == null) {
            return@runCatchingAsync null
        }
        layoutCacheGuard.withLock {
            val cached = layoutCache[ltn]
            if (cached != null) {
                flogDebug(LogTopic.LAYOUT_MANAGER) { "Using cache for '${ltn.name}'" }
                return@withLock cached
            } else {
                flogDebug(LogTopic.LAYOUT_MANAGER) { "Loading '${ltn.name}'" }
                val meta = keyboardManager.resources.layouts.value[ltn.type]?.get(ltn.name)
                    ?: error("No indexed entry found for ${ltn.type} - ${ltn.name}")
                val ext = extensionManager.getExtensionById(ltn.name.extensionId)
                    ?: error("Extension ${ltn.name.extensionId} not found")
                val path = meta.arrangementFile(ltn.type)
                val layout = async {
                    runCatching {
                        val jsonStr = ZipUtils.readFileFromArchive(appContext, ext.sourceRef!!, path).getOrThrow()
                        val arrangement = loadJsonAsset<LayoutArrangement>(jsonStr).getOrThrow()
                        CachedLayout(ltn.type, ltn.name, meta, arrangement)
                    }
                }
                layoutCache[ltn] = layout
                return@withLock layout
            }
        }.await().getOrThrow()
    }

    private fun loadPopupMappingAsync(subtype: Subtype? = null) = ioScope.runCatchingAsync {
        val name = subtype?.popupMapping ?: extCorePopupMapping("default")
        popupMappingCacheGuard.withLock {
            val cached = popupMappingCache[name]
            if (cached != null) {
                flogDebug(LogTopic.LAYOUT_MANAGER) { "Using cache for '$name'" }
                return@withLock cached
            } else {
                flogDebug(LogTopic.LAYOUT_MANAGER) { "Loading '$name'" }
                val meta = keyboardManager.resources.popupMappings.value[name]
                    ?: error("No indexed entry found for $name")
                val ext = extensionManager.getExtensionById(name.extensionId)
                    ?: error("Extension ${name.extensionId} not found")
                val path = meta.mappingFile()
                val popupMapping = async {
                    runCatching {
                        val jsonStr = ZipUtils.readFileFromArchive(appContext, ext.sourceRef!!, path).getOrThrow()
                        val mapping = loadJsonAsset<PopupMapping>(jsonStr).getOrThrow()
                        CachedPopupMapping(name, meta, mapping)
                    }
                }
                popupMappingCache[name] = popupMapping
                return@withLock popupMapping
            }
        }.await().getOrThrow()
    }

    /**
     * Merges the specified layouts (LTNs) and returns the computed layout.
     * The computed layout may looks like this:
     *   e e e e e e e e e e      e = extension
     *   c c c c c c c c c c      c = main
     *    c c c c c c c c c       m = mod
     *   m c c c c c c c c m
     *   m m m m m m m m m m
     *
     * @param keyboardMode The keyboard mode for the returning [TextKeyboard].
     * @param subtype The subtype used for populating the extended popups.
     * @param main The main layout type and name.
     * @param modifier The modifier (mod) layout type and name.
     * @param extension The extension layout type and name.
     * @return a [TextKeyboard] object, regardless of the specified LTNs or errors.
     */
    private suspend fun mergeLayouts(
        keyboardMode: KeyboardMode,
        subtype: Subtype,
        main: LTN? = null,
        modifier: LTN? = null,
        extension: LTN? = null,
    ): TextKeyboard {
        val extendedPopupsDefault = loadPopupMappingAsync()
        val extendedPopups = loadPopupMappingAsync(subtype)

        val mainLayoutResult = loadLayoutAsync(main, allowNullLTN = false).await()
        val mainLayout = mainLayoutResult.onFailure {
            flogWarning { "$keyboardMode - main - $it" }
        }.getOrNull()
        val modifierToLoad = if (mainLayout?.meta?.modifier != null) {
            val layoutType = when (mainLayout.type) {
                LayoutType.SYMBOLS -> {
                    LayoutType.SYMBOLS_MOD
                }
                LayoutType.SYMBOLS2 -> {
                    LayoutType.SYMBOLS2_MOD
                }
                else -> {
                    LayoutType.CHARACTERS_MOD
                }
            }
            LTN(layoutType, mainLayout.meta.modifier)
        } else {
            modifier
        }
        val modifierLayoutResult = loadLayoutAsync(modifierToLoad, allowNullLTN = true).await()
        val modifierLayout = modifierLayoutResult.onFailure {
            flogWarning { "$keyboardMode - mod - $it" }
        }.getOrNull()
        val extensionLayoutResult = loadLayoutAsync(extension, allowNullLTN = true).await()
        val extensionLayout = extensionLayoutResult.onFailure {
            flogWarning { "$keyboardMode - ext - $it" }
        }.getOrNull()

        debugLayoutComputationResultFlow.value = DebugLayoutComputationResult(
            main = mainLayoutResult,
            mod = modifierLayoutResult,
            ext = extensionLayoutResult,
        )

        val computedArrangement: ArrayList<Array<TextKey>> = arrayListOf()

        if (extensionLayout != null) {
            for (row in extensionLayout.arrangement) {
                val rowArray = Array(row.size) { TextKey(row[it]) }
                computedArrangement.add(rowArray)
            }
        }

        if (mainLayout != null && modifierLayout != null) {
            for (mainRowI in mainLayout.arrangement.indices) {
                val mainRow = mainLayout.arrangement[mainRowI]
                if (mainRowI + 1 < mainLayout.arrangement.size) {
                    val rowArray = Array(mainRow.size) { TextKey(mainRow[it]) }
                    computedArrangement.add(rowArray)
                } else {
                    // merge main and mod here
                    val rowArray = arrayListOf<TextKey>()
                    val firstModRow = modifierLayout.arrangement.firstOrNull()
                    for (modKey in (firstModRow ?: listOf())) {
                        if (modKey is TextKeyData && modKey.code == 0) {
                            rowArray.addAll(mainRow.map { TextKey(it) })
                        } else {
                            rowArray.add(TextKey(modKey))
                        }
                    }
                    val temp = Array(rowArray.size) { rowArray[it] }
                    computedArrangement.add(temp)
                }
            }
            for (modRowI in 1 until modifierLayout.arrangement.size) {
                val modRow = modifierLayout.arrangement[modRowI]
                val rowArray = Array(modRow.size) { TextKey(modRow[it]) }
                computedArrangement.add(rowArray)
            }
        } else if (mainLayout != null && modifierLayout == null) {
            for (mainRow in mainLayout.arrangement) {
                val rowArray = Array(mainRow.size) { TextKey(mainRow[it]) }
                computedArrangement.add(rowArray)
            }
        } else if (mainLayout == null && modifierLayout != null) {
            for (modRow in modifierLayout.arrangement) {
                val rowArray = Array(modRow.size) { TextKey(modRow[it]) }
                computedArrangement.add(rowArray)
            }
        }

        // WordTaker: 全拼 QWERTY gets explicit WeChat-style per-letter number/symbol hints
        // (data-driven, see [PINYIN_QWERTY_KEY_HINTS]) instead of the positional symbols-layout
        // hints. Each hint shows top-right and is long-press inputtable.
        val isPinyinQwerty = main?.name?.componentId == "pinyin_qwerty"
        // WordTaker: the 全拼 QWERTY shows a small gray number/symbol hint above every letter
        // key (WeChat/iOS pinyin style, see the reference). The per-letter mapping lives in
        // [PINYIN_QWERTY_KEY_HINTS]; applyPinyinQwertyHints stores it as each key's symbol hint,
        // which renders top-center and is long-press inputtable.
        if (keyboardMode == KeyboardMode.CHARACTERS && computedArrangement.isNotEmpty() && isPinyinQwerty &&
            prefs.keyboard.hintedSymbolsEnabled.get()) {
            applyPinyinQwertyHints(computedArrangement)
        } else if (keyboardMode == KeyboardMode.CHARACTERS && computedArrangement.isNotEmpty()) {
            // WordTaker: only the 全拼 pinyin_qwerty layout shows the WeChat-style per-letter
            // symbol hints (handled in the branch above). Other character layouts (e.g. the
            // latin/English keyboard) stay clean — no positional symbol hints — preserving the
            // minimal WeChat look. The number-row hint remains pref-gated (off by default).
            if (prefs.keyboard.hintedNumberRowEnabled.get()) {
                val symbolsComputedArrangement =
                    computeKeyboardAsync(KeyboardMode.SYMBOLS, subtype).await().arrangement
                if (symbolsComputedArrangement.isNotEmpty()) {
                    addRowHints(computedArrangement[0], symbolsComputedArrangement[0], KeyType.NUMERIC)
                }
            }
        }

        val array = Array(computedArrangement.size) { computedArrangement[it] }
        return TextKeyboard(
            arrangement = array,
            mode = keyboardMode,
            extendedPopupMapping = extendedPopups.await().onFailure {
                flogWarning(LogTopic.LAYOUT_MANAGER) { it.toString() }
            }.getOrNull()?.mapping,
            extendedPopupMappingDefault = extendedPopupsDefault.await().onFailure {
                flogWarning(LogTopic.LAYOUT_MANAGER) { it.toString() }
            }.getOrNull()?.mapping
        )
    }

    /**
     * Applies the explicit WeChat-style [PINYIN_QWERTY_KEY_HINTS] to every letter key of the
     * computed 全拼 arrangement. The hint is stored as the key's symbol hint, so it renders at the
     * top-right and is merged into the key's long-press popups by [TextKey.compute].
     */
    private fun applyPinyinQwertyHints(arrangement: List<Array<TextKey>>) {
        for (row in arrangement) {
            for (key in row) {
                val computed = key.data.compute(DefaultComputingEvaluator) ?: continue
                if (computed.type != KeyType.CHARACTER) {
                    continue
                }
                // Normalize to the lowercase code so the lookup is independent of the default
                // evaluator's shift state (a'..'z' / 'A'..'Z' both map to the same hint).
                val lookupCode = if (computed.code in 'A'.code..'Z'.code) {
                    computed.code + ('a'.code - 'A'.code)
                } else {
                    computed.code
                }
                PINYIN_QWERTY_KEY_HINTS[lookupCode]?.let { hint ->
                    key.computedSymbolHint = hint
                }
            }
        }
    }

    private fun addRowHints(main: Array<TextKey>, hint: Array<TextKey>, hintType: KeyType) {
        for ((k,key) in main.withIndex()) {
            val hintKey = hint.getOrNull(k)?.data?.compute(DefaultComputingEvaluator)
            if (hintKey?.type != hintType) {
                continue
            }

            when (hintType) {
                KeyType.CHARACTER -> {
                    key.computedSymbolHint = hintKey
                }
                KeyType.NUMERIC -> {
                    key.computedNumberHint = hintKey
                }
                else -> {
                    // do nothing
                }
            }
        }
    }

    /**
     * Computes a layout for [keyboardMode] based on the given [subtype] and returns it.
     *
     * @param keyboardMode The keyboard mode for which the layout should be computed.
     * @param subtype The subtype which localizes the computed layout.
     */
    fun computeKeyboardAsync(
        keyboardMode: KeyboardMode,
        subtype: Subtype,
    ): Deferred<TextKeyboard> = ioScope.async {
        var main: LTN? = null
        var modifier: LTN? = null
        var extension: LTN? = null

        when (keyboardMode) {
            KeyboardMode.CHARACTERS -> {
                if (prefs.keyboard.numberRow.get()) {
                    extension = LTN(LayoutType.NUMERIC_ROW, subtype.layoutMap.numericRow)
                }
                main = LTN(LayoutType.CHARACTERS, subtype.layoutMap.characters)
                modifier = LTN(LayoutType.CHARACTERS_MOD, extCoreLayout("default"))
            }
            KeyboardMode.EDITING -> {
                // Layout for this mode is defined in custom layout xml file.
                return@async TextKeyboard(arrayOf(), keyboardMode, null, null)
            }
            KeyboardMode.NUMERIC -> {
                main = LTN(LayoutType.NUMERIC, subtype.layoutMap.numeric)
            }
            KeyboardMode.NUMERIC_ADVANCED -> {
                main = LTN(LayoutType.NUMERIC_ADVANCED, subtype.layoutMap.numericAdvanced)
            }
            KeyboardMode.PHONE -> {
                main = LTN(LayoutType.PHONE, subtype.layoutMap.phone)
            }
            KeyboardMode.PHONE2 -> {
                main = LTN(LayoutType.PHONE2, subtype.layoutMap.phone2)
            }
            KeyboardMode.SYMBOLS -> {
                extension = LTN(LayoutType.NUMERIC_ROW, subtype.layoutMap.numericRow)
                main = LTN(LayoutType.SYMBOLS, subtype.layoutMap.symbols)
                modifier = LTN(LayoutType.SYMBOLS_MOD, extCoreLayout("default"))
            }
            KeyboardMode.SYMBOLS2 -> {
                main = LTN(LayoutType.SYMBOLS2, subtype.layoutMap.symbols2)
                modifier = LTN(LayoutType.SYMBOLS2_MOD, extCoreLayout("default"))
            }
            KeyboardMode.SMARTBAR_CLIPBOARD_CURSOR_ROW -> {
                extension = LTN(LayoutType.EXTENSION, extCoreLayout("clipboard_cursor_row"))
            }
            KeyboardMode.SMARTBAR_NUMBER_ROW -> {
                extension = LTN(LayoutType.NUMERIC_ROW, subtype.layoutMap.numericRow)
            }
            else -> {
                // Default values are already provided
            }
        }

        return@async mergeLayouts(keyboardMode, subtype, main, modifier, extension)
    }

    /**
     * Called when the application is destroyed. Used to cancel any pending coroutines.
     */
    fun onDestroy() {
        ioScope.cancel()
    }
}
