package com.wordtaker.keyboard.wordtaker.speech

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        if (state.phase == ParaformerModelPhase.Error) {
            Button(onClick = manager::retry) { Text("重试") }
        }
    }
}

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
}
