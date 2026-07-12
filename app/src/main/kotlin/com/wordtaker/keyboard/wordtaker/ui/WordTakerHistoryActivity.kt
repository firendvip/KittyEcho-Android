package com.wordtaker.keyboard.wordtaker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.history.HistoryEntity
import com.wordtaker.keyboard.wordtaker.history.HistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Standalone WordTaker history screen. Lists every voice-to-text record with
 * search, per-item copy/delete and a clear-all action. Backed by [HistoryRepository].
 */
class WordTakerHistoryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)
        setContent {
            WordTakerTheme {
                WordTakerHistoryScreen(
                    repository = AppGraph.historyRepository,
                    onBack = { finish() },
                )
            }
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
private fun WordTakerHistoryScreen(
    repository: HistoryRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Seed sample data once.
    androidx.compose.runtime.LaunchedEffect(Unit) { repository.seedIfNeeded() }

    val query = remember { MutableStateFlow("") }
    val queryValue by query.collectAsState()
    val items by remember {
        query.flatMapLatest { q ->
            if (q.isBlank()) repository.observeAll() else repository.search(q.trim())
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }
    val palette = rememberWeChatPalette()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        // #4: WeChat-style header — back + title on the left, clear-all on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = palette.textPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "历史记录",
                color = palette.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Text(
                text = "清空全部",
                color = palette.danger,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showClearConfirm = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Search field — WeChat pill on a white card.
        SearchField(
            value = queryValue,
            onValueChange = { query.value = it },
            palette = palette,
        )

        if (items.isEmpty()) {
            EmptyState(blankQuery = queryValue.isBlank(), palette = palette)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { entry ->
                    HistoryCard(
                        entry = entry,
                        palette = palette,
                        onCopy = {
                            copyToClipboard(context, entry.polished)
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = { scope.launch { repository.remove(entry.id) } },
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        ClearAllDialog(
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

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    palette: WeChatPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = palette.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(text = "搜索历史…", color = palette.textMuted, fontSize = 15.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = palette.textPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmptyState(blankQuery: Boolean, palette: WeChatPalette) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = if (blankQuery) "🐾" else "🔍", fontSize = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (blankQuery) "还没有历史记录" else "没有匹配的记录",
                color = palette.textSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (blankQuery) "语音转写后会自动出现在这里" else "换个关键词试试",
                color = palette.textMuted,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntity,
    palette: WeChatPalette,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .clickable(onClick = onCopy)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp),
    ) {
        Column(modifier = Modifier.padding(end = 30.dp)) {
            // Polished result — prominent, this is what gets copied on tap.
            Text(
                text = entry.polished,
                color = palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "原文  ${entry.raw}",
                color = palette.textSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = fmtTime(entry.createdAt),
                color = palette.textMuted,
                fontSize = 12.sp,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除",
                tint = palette.textMuted,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun ClearAllDialog(
    palette: WeChatPalette,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.card)
                .clickable(enabled = false) {}
                .padding(22.dp),
        ) {
            Text(
                text = "清空全部",
                color = palette.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "确定要删除所有历史记录吗？此操作不可撤销。",
                color = palette.textSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "取消",
                    color = palette.textSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "清空",
                    color = palette.danger,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("WordTaker", text))
}

/** Formats an epoch-millis timestamp as 今天/昨天/M月D日 HH:mm. */
fun fmtTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

    val hh = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val mm = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
    val time = "$hh:$mm"

    return when {
        isSameDay(cal, today) -> "今天 $time"
        isSameDay(cal, yesterday) -> "昨天 $time"
        else -> {
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            "${month}月${day}日 $time"
        }
    }
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
