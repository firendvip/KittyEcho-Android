package com.wordtaker.keyboard.wordtaker.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordtaker.keyboard.wordtaker.ui.WeChatPalette

/**
 * One-shot guard for a destructive single-row action.
 *
 * A pending id cannot be replaced, confirmation consumes it synchronously, and another target
 * cannot be requested until that exact deletion completes. This keeps rapid taps and list refreshes
 * from ever redirecting a confirmation to a different row.
 */
internal class SingleHistoryDeleteGuard {
    private var pendingId: Long? = null
    private var inFlightId: Long? = null

    fun request(id: Long): Boolean {
        if (pendingId != null || inFlightId != null) return false
        pendingId = id
        return true
    }

    fun dismiss() {
        pendingId = null
    }

    fun confirm(): Long? {
        val id = pendingId ?: return null
        pendingId = null
        inFlightId = id
        return id
    }

    fun complete(id: Long) {
        if (inFlightId == id) inFlightId = null
    }
}

internal object HistoryDeleteCopy {
    const val TITLE = "删除这条历史记录？"
    const val MESSAGE = "仅删除上面这一条，删除后无法恢复。"
    const val CANCEL = "取消"
    const val CONFIRM = "删除"
    const val PANE_TITLE = "单条历史记录删除确认"

    fun recordIdentifier(id: Long): String = "记录 ID $id"

    fun deleteActionDescription(id: Long, preview: String): String =
        "删除历史记录 ID $id：$preview"
}

internal fun historyDeletePreview(text: String): String {
    val singleLine = text.trim().replace(Regex("\\s+"), " ")
    val nonEmpty = singleLine.ifEmpty { "空白记录" }
    val codePointCount = nonEmpty.codePointCount(0, nonEmpty.length)
    if (codePointCount <= MAX_DELETE_PREVIEW_CHARS) return nonEmpty
    return nonEmpty.substring(
        startIndex = 0,
        endIndex = nonEmpty.offsetByCodePoints(0, MAX_DELETE_PREVIEW_CHARS),
    )
}

@Composable
internal fun HistoryDeleteConfirmation(
    target: HistoryEntity,
    palette: WeChatPalette,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val preview = historyDeletePreview(target.polished)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(onDismiss) {
                detectTapGestures(onTap = { onDismiss() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.card)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                }
                .semantics {
                    paneTitle = HistoryDeleteCopy.PANE_TITLE
                }
                .padding(20.dp),
        ) {
            Text(
                text = HistoryDeleteCopy.TITLE,
                color = palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = HistoryDeleteCopy.recordIdentifier(target.id),
                color = palette.textMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "“$preview”",
                color = palette.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    contentDescription = HistoryDeleteCopy.deleteActionDescription(target.id, preview)
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = HistoryDeleteCopy.MESSAGE,
                color = palette.textSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = HistoryDeleteCopy.CANCEL,
                    color = palette.textSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = HistoryDeleteCopy.CANCEL,
                            onClick = onDismiss,
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = HistoryDeleteCopy.CONFIRM,
                    color = palette.danger,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = HistoryDeleteCopy.CONFIRM,
                            onClick = onConfirm,
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private const val MAX_DELETE_PREVIEW_CHARS = 32
