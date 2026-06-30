package com.wordtaker.keyboard.wordtaker.history

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
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
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.ui.fmtTime
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
 * The unified toolbar above this view lets the user switch back to keyboard/voice.
 */
@Composable
fun ImeHistoryLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val editorInstance by context.editorInstance()
    val repository = remember { AppGraph.historyRepository }
    val scope = rememberCoroutineScope()

    // Seed sample data once (same behavior as the standalone screen).
    androidx.compose.runtime.LaunchedEffect(Unit) { repository.seedIfNeeded() }

    val itemsFlow = remember { repository.observeAll() }
    val items by itemsFlow.collectAsState(initial = emptyList())

    // #13: clearing all history requires a second confirmation to prevent accidental wipes.
    var showClearConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PANEL_HEIGHT_DP.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Header: title + minimal hint (#11) on the left, clear-all on the right.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text(
                    text = "历史记录",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.size(8.dp))
                // #11: tells the user tapping a record commits it straight into the input field.
                Text(
                    text = "点按任意一条即可上屏",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = "清空",
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showClearConfirm = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有历史记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { entry ->
                    ImeHistoryCard(
                        entry = entry,
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .clickable { showClearConfirm = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(enabled = false) {}
                        .padding(20.dp),
                ) {
                    Text(
                        text = "清空全部历史记录？",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "此操作不可恢复。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = "取消",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showClearConfirm = false }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "确认",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showClearConfirm = false
                                    scope.launch { repository.clear() }
                                    Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImeHistoryCard(
    entry: HistoryEntity,
    onPick: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onPick)
            .padding(12.dp),
    ) {
        Column(modifier = Modifier.padding(end = 24.dp)) {
            // Original ASR transcript (lighter weight), like the desktop client.
            Text(
                text = "原文：${entry.raw}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
            )
            Spacer(Modifier.height(3.dp))
            // AI-polished result (emphasized) — this is what gets committed on tap.
            Text(
                text = "润色：${entry.polished}",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = fmtTime(entry.createdAt),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private const val PANEL_HEIGHT_DP = 260
