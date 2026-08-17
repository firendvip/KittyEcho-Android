package com.wordtaker.keyboard.wordtaker.speech

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ParaformerModelSettingsSection(
    manager: ParaformerModelManager,
    modifier: Modifier = Modifier,
) {
    val state by manager.state.collectAsStateWithLifecycle()
    LaunchedEffect(manager) { manager.refreshReadiness() }
    val presentation = ParaformerModelPresentation.forState(state)
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Paraformer 本地语音模型 · ${presentation.status}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = presentation.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.phase == ParaformerModelPhase.Downloading) {
            LinearProgressIndicator(
                progress = {
                    if (state.totalBytes <= 0L) 0f else
                        (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FullModelActions(manager, state)
    }
}

@Composable
private fun FullModelActions(
    manager: ParaformerModelManager,
    state: ParaformerLifecycleState,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            ParaformerModelPhase.Uninstalled ->
                Button(onClick = { manager.onVoiceRequested() }) { Text("下载模型") }
            ParaformerModelPhase.AwaitingConfirmation -> {
                Button(onClick = { manager.confirmWifi() }) { Text("仅 Wi-Fi 下载") }
                OutlinedButton(onClick = { manager.confirmMobile() }) { Text("使用移动数据") }
            }
            ParaformerModelPhase.Queued,
            ParaformerModelPhase.Downloading,
            ParaformerModelPhase.Verifying,
            ParaformerModelPhase.Installing,
            -> {
                OutlinedButton(onClick = { manager.pause() }) { Text("暂停") }
                TextButton(onClick = { manager.cancel() }) { Text("取消并删除") }
            }
            ParaformerModelPhase.Paused -> {
                Button(onClick = { manager.resume() }) { Text("继续") }
                TextButton(onClick = { manager.cancel() }) { Text("取消并删除") }
            }
            ParaformerModelPhase.Error ->
                Button(onClick = { manager.retry() }) { Text("重试") }
            ParaformerModelPhase.Initializing,
            ParaformerModelPhase.Ready,
            -> Unit
        }
    }
}

/** Toolbar-only status. Candidate ownership is checked before this is composed. */
@Composable
internal fun ParaformerCompactStatus(
    manager: ParaformerModelManager,
    candidatesOwnToolbar: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by manager.state.collectAsStateWithLifecycle()
    if (!ParaformerCompactStatusPolicy.shouldShow(candidatesOwnToolbar, state.phase)) return
    val presentation = ParaformerModelPresentation.forState(state)
    Text(
        text = presentation.status,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 92.dp)
            .clickable(enabled = state.phase == ParaformerModelPhase.Error) { manager.retry() }
            .padding(horizontal = 4.dp),
    )
    if (state.phase == ParaformerModelPhase.AwaitingConfirmation) {
        AlertDialog(
            onDismissRequest = { manager.cancel() },
            title = { Text("下载本地语音模型") },
            text = {
                Text("约 224 MB。默认仅通过 Wi-Fi 下载，不会上传录音、正文或设备标识。")
            },
            confirmButton = {
                TextButton(onClick = { manager.confirmWifi() }) { Text("仅 Wi-Fi") }
            },
            dismissButton = {
                TextButton(onClick = { manager.confirmMobile() }) { Text("使用移动数据") }
            },
        )
    }
}
