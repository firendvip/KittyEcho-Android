/*
 * WordTaker 符号页 (SYMBOLS2) category system.
 *
 * The SYMBOLS2 keyboard shows 26 symbol slots (10 + 10 + 6) in its top three rows and a
 * category tab row at the bottom. Selecting a tab swaps the whole symbol set while the key
 * grid geometry stays pixel-identical. The RECENT category is backed by a small MRU list
 * persisted to a private file; while it is shorter than 26 entries it is padded with a
 * fallback set of common symbols.
 */
package com.wordtaker.keyboard.wordtaker.symbols

import android.content.Context
import com.wordtaker.keyboard.ime.text.key.KeyCode
import com.wordtaker.keyboard.ime.text.key.KeyType
import com.wordtaker.keyboard.ime.text.keyboard.TextKey
import com.wordtaker.keyboard.ime.text.keyboard.TextKeyData
import com.wordtaker.keyboard.ime.text.keyboard.TextKeyboard
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Number of substitutable symbol slots on the SYMBOLS2 keyboard (10 + 10 + 6). */
const val SYM2_SLOT_COUNT = 26

enum class Sym2Category(val keyCode: Int) {
    RECENT(KeyCode.SYM2_CAT_RECENT),
    CJK(KeyCode.SYM2_CAT_CJK),
    EN(KeyCode.SYM2_CAT_EN),
    BRACKET(KeyCode.SYM2_CAT_BRACKET),
    CURRENCY(KeyCode.SYM2_CAT_CURRENCY),
    MATH(KeyCode.SYM2_CAT_MATH),
    DASH(KeyCode.SYM2_CAT_DASH),
    CIRCLED(KeyCode.SYM2_CAT_CIRCLED);

    companion object {
        fun fromKeyCode(code: Int): Sym2Category? = entries.firstOrNull { it.keyCode == code }
    }
}

/** Static 26-symbol sets per category (rows of 10 / 10 / 6). Single code point each. */
private val SYM2_SETS: Map<Sym2Category, List<String>> = mapOf(
    // 中文标点 — identical to the default layout content of the 符号 page.
    Sym2Category.CJK to listOf(
        "_", "—", ";", "#", "%", "&", "^", "*", "+", "=",
        "-", "/", ":", "~", "(", ")", "…", "@", "“", "”",
        "。", "，", "、", "？", "！", ".",
    ),
    // 英文符号
    Sym2Category.EN to listOf(
        "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
        "'", "\"", "=", "_", ":", ";", "?", "~", "`", "|",
        ",", ".", "<", ">", "/", "\\",
    ),
    // 括号类
    Sym2Category.BRACKET to listOf(
        "（", "）", "「", "」", "【", "】", "《", "》", "〈", "〉",
        "(", ")", "[", "]", "{", "}", "<", ">", "‹", "›",
        "〔", "〕", "［", "］", "｛", "｝",
    ),
    // 货币
    Sym2Category.CURRENCY to listOf(
        "¥", "$", "€", "£", "¢", "₩", "₹", "₽", "₿", "￥",
        "₪", "₫", "₴", "₦", "₱", "₲", "₵", "₺", "₼", "₾",
        "＄", "￡", "￠", "₭", "₸", "₮",
    ),
    // 数学
    Sym2Category.MATH to listOf(
        "+", "-", "×", "÷", "=", "≠", "≈", "±", "√", "%",
        "<", ">", "≤", "≥", "∞", "π", "∑", "∫", "°", "‰",
        "½", "⅓", "¼", "¾", "²", "³",
    ),
    // 破折号 / 连接号
    Sym2Category.DASH to listOf(
        "—", "–", "‐", "‑", "‒", "―", "﹘", "﹣", "－", "_",
        "~", "～", "·", "•", "‧", "…", "‥", "｜", "|", "¦",
        "/", "\\", "／", "＼", "‖", "∥",
    ),
    // 带圈序号
    Sym2Category.CIRCLED to listOf(
        "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
        "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳",
        "⑴", "⑵", "⑶", "⑷", "⑸", "⑹",
    ),
)

/** Fallback fill for the RECENT category while fewer than 26 symbols were recorded. */
private val SYM2_RECENT_FALLBACK: List<String> = listOf(
    "。", "，", "、", "？", "！", "：", "；", "…", "“", "”",
    "～", "·", "（", "）", "《", "》", "「", "」", "【", "】",
    "＠", "＃", "￥", "％", "＆", "＊",
)

private const val RECENT_FILE_NAME = "wt_sym2_recent.txt"
private const val RECENT_MAX = SYM2_SLOT_COUNT

/**
 * Global state of the 符号页: selected category, recent-symbol MRU list and the frozen
 * snapshot that the RECENT grid renders from. The snapshot is only refreshed when the user
 * enters the SYMBOLS2 keyboard or switches category, so keys never shuffle mid-typing.
 */
object Symbols2State {
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    val categoryFlow: StateFlow<Sym2Category>
        field = MutableStateFlow(Sym2Category.CJK)

    private var recent: MutableList<String>? = null // lazy-loaded MRU, most recent first
    private var recentSnapshot: List<String> = SYM2_RECENT_FALLBACK
    private var cachedSymbols: List<String> = SYM2_SETS.getValue(Sym2Category.CJK)

    /** Symbols (size 26) for the currently selected category. Stable list reference. */
    fun currentSymbols(): List<String> = synchronized(lock) { cachedSymbols }

    fun selectCategory(category: Sym2Category, context: Context) {
        synchronized(lock) {
            categoryFlow.value = category
            refreshLocked(context)
        }
    }

    /** Called when the SYMBOLS2 keyboard is (re-)entered: freeze the RECENT snapshot. */
    fun refreshSnapshot(context: Context) {
        synchronized(lock) { refreshLocked(context) }
    }

    /** Records a symbol committed from the symbol/number pages into the MRU list. */
    fun recordSymbol(symbol: String, context: Context) {
        if (symbol.isEmpty() || symbol.codePointCount(0, symbol.length) != 1) return
        if (Character.isLetterOrDigit(symbol.codePointAt(0))) return
        synchronized(lock) {
            val list = loadRecentLocked(context)
            list.remove(symbol)
            list.add(0, symbol)
            while (list.size > RECENT_MAX) list.removeAt(list.size - 1)
            val copy = list.toList()
            ioScope.launch {
                runCatching {
                    File(context.filesDir, RECENT_FILE_NAME).writeText(copy.joinToString("\n"))
                }
            }
        }
    }

    private fun refreshLocked(context: Context) {
        val category = categoryFlow.value
        cachedSymbols = if (category == Sym2Category.RECENT) {
            val list = loadRecentLocked(context).toMutableList()
            for (s in SYM2_RECENT_FALLBACK) {
                if (list.size >= SYM2_SLOT_COUNT) break
                if (s !in list) list.add(s)
            }
            recentSnapshot = list.take(SYM2_SLOT_COUNT)
            recentSnapshot
        } else {
            SYM2_SETS.getValue(category)
        }
    }

    /**
     * Builds a copy of the computed SYMBOLS2 [base] keyboard with its 26 symbol slots
     * replaced (in reading order) by [symbols]. System keys (123/⌫/tabs/←) are kept as-is.
     * Fresh [TextKey] instances are created so the shared layout cache is never mutated.
     */
    fun substituteSymbols(base: TextKeyboard, symbols: List<String>): TextKeyboard {
        var slot = 0
        val arrangement = Array(base.arrangement.size) { r ->
            val row = base.arrangement[r]
            Array(row.size) { k ->
                val data = row[k].data
                val plain = data as? TextKeyData
                if (plain != null && plain.type == KeyType.CHARACTER && plain.code > 0 &&
                    slot < symbols.size
                ) {
                    val s = symbols[slot++]
                    TextKey(TextKeyData(type = KeyType.CHARACTER, code = s.codePointAt(0), label = s))
                } else {
                    TextKey(data)
                }
            }
        }
        return TextKeyboard(
            arrangement = arrangement,
            mode = base.mode,
            extendedPopupMapping = base.extendedPopupMapping,
            extendedPopupMappingDefault = base.extendedPopupMappingDefault,
        )
    }

    private fun loadRecentLocked(context: Context): MutableList<String> {
        recent?.let { return it }
        val loaded = runCatching {
            File(context.filesDir, RECENT_FILE_NAME).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.codePointCount(0, it.length) == 1 }
                .distinct()
                .take(RECENT_MAX)
        }.getOrDefault(emptyList()).toMutableList()
        recent = loaded
        return loaded
    }
}
