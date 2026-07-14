package com.wordtaker.keyboard.wordtaker.history

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordtaker.keyboard.editorInstance
import com.wordtaker.keyboard.ime.keyboard.FlorisImeSizing
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.toolbar.TOOLBAR_HEIGHT_DP
import com.wordtaker.keyboard.wordtaker.voice.catStripHeight
import com.wordtaker.keyboard.wordtaker.ui.WeChatPalette
import com.wordtaker.keyboard.wordtaker.ui.fmtTime
import com.wordtaker.keyboard.wordtaker.ui.rememberWeChatPalette
import kotlinx.coroutines.launch

/**
 * In-IME history view. Rendered inside the keyboard window (ImeUiMode.HISTORY) —
 * never launches WordTakerHistoryActivity. Reuses the same Room-backed
 * [HistoryRepository] as the standalone screen.
 *
 * Tap a record -> its polished text is committed into the focused input field via
 * EditorInstance.commitText. A per-item delete and a clear-all are available; all
 * minimal to fit the keyboard-height region.
 *
 * #4: restyled to the WeChat list palette (light #F2F3F5 / dark #1C1D1F, white cards,
 * green accent #07C160) so it feels consistent with the keyboard + settings theme.
 * The unified toolbar above this view lets the user switch back to keyboard/voice.
 */
@Composable
fun ImeHistoryLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val editorInstance by context.editorInstance()
    val repository = remember { AppGraph.historyRepository }
    val scope = rememberCoroutineScope()
    val palette = rememberWeChatPalette()

    // Seed sample data once (same behavior as the standalone screen).
    androidx.compose.runtime.LaunchedEffect(Unit) { repository.seedIfNeeded() }

    val itemsFlow = remember { repository.observeAll() }
    val items by itemsFlow.collectAsState(initial = emptyList())

    // #13: clearing all history requires a second confirmation to prevent accidental wipes.
    var showClearConfirm by remember { mutableStateOf(false) }

    // item3: 与设置面板同规则 —— 面板高 = keyboardUiHeight + (顶条 - 工具栏44)，
    // 使 历史态总高 == 键盘态总高，切换零跳动。(batch3-A: 顶条已按屏宽比例化)
    val panelHeight = FlorisImeSizing.keyboardUiHeight() +
        (catStripHeight() - TOOLBAR_HEIGHT_DP.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(panelHeight)
            .background(palette.background),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Header: title + minimal hint (#11) on the left, clear-all on the right.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Text(
                        text = "历史记录",
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(8.dp))
                    // #11: tells the user tapping a record commits it straight into the input field.
                    Text(
                        text = "点按任意一条即可上屏",
                        color = palette.textMuted,
                        fontSize = 11.sp,
                    )
                }
                Text(
                    text = "清空",
                    color = palette.danger,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showClearConfirm = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            if (items.isEmpty()) {
                EmptyState(palette)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.id }) { entry ->
                        ImeHistoryCard(
                            entry = entry,
                            palette = palette,
                            onPick = {
                                editorInstance.commitText(entry.polished)
                                Toast.makeText(context, "已上屏", Toast.LENGTH_SHORT).show()
                            },
                            onDelete = { scope.launch { repository.remove(entry.id) } },
                        )
                    }
                }
            }
        }

        // #13: second confirmation before wiping all history. Rendered INLINE inside the
        // keyboard window (Material3 AlertDialog uses a platform Dialog sub-window, which throws
        // BadTokenException in an IME — there is no Activity token here).
        if (showClearConfirm) {
            ClearAllConfirm(
                palette = palette,
                onDismiss = { showClearConfirm = false },
                onConfirm = {
                    showClearConfirm = false
                    scope.launch { repository.clear() }
                    Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
private fun EmptyState(palette: WeChatPalette) {
    Box(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "🐾", fontSize = 30.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "还没有历史记录",
                color = palette.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "语音转写后会自动出现在这里",
                color = palette.textMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ImeHistoryCard(
    entry: HistoryEntity,
    palette: WeChatPalette,
    onPick: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .clickable(onClick = onPick)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
    ) {
        Column(modifier = Modifier.padding(end = 26.dp)) {
            // AI-polished result (prominent) — this is what gets committed on tap.
            Text(
                text = entry.polished,
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            // Original ASR transcript — secondary, smaller and muted.
            Text(
                text = "原文  ${entry.raw}",
                color = palette.textSecondary,
                fontSize = 12.sp,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = fmtTime(entry.createdAt),
                color = palette.textMuted,
                fontSize = 11.sp,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除",
                tint = palette.textMuted,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun ClearAllConfirm(
    palette: WeChatPalette,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.card)
                .clickable(enabled = false) {}
                .padding(20.dp),
        ) {
            Text(
                text = "清空全部历史记录？",
                color = palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "此操作不可恢复。",
                color = palette.textSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "取消",
                    color = palette.textSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "确认",
                    color = palette.danger,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onConfirm() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
