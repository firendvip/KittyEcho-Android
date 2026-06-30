package com.wordtaker.keyboard.wordtaker.voice

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wordtaker.keyboard.editorInstance
import com.wordtaker.keyboard.ime.keyboard.FlorisImeSizing
import com.wordtaker.keyboard.wordtaker.cat.CatSkin
import com.wordtaker.keyboard.wordtaker.cat.CatState
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.speech.MicPermissionActivity
import com.wordtaker.keyboard.wordtaker.ui.WordTakerSettingsActivity

/**
 * The cat voice-input OVERLAY. Rendered on top of the keyboard (not as a separate
 * IME mode) whenever [VoiceOverlayController.visible] is true.
 *
 * Lifecycle:
 *  1. Appears -> auto-starts recording (a walking cat + "正在倾听...点击结束").
 *  2. User taps anywhere -> stops + runs recognition / polish.
 *  3. On terminal state the polished text is committed + written to history by the
 *     VM, then the overlay auto-dismisses ([VoiceOverlayController.hide]) and the
 *     keyboard returns. Blank / error results also auto-dismiss (no dead end).
 *
 * The cat uses a single medium size, horizontally centred in the keyboard area —
 * between the previous oversized sleep sprite and the tiny walking sprite.
 */
@Composable
fun CatVoiceOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val editorInstance by context.editorInstance()

    val vm: VoiceViewModel = viewModel(
        factory = VoiceViewModel.Factory(
            speechEngine = AppGraph.speechEngine,
            polisher = AppGraph.polisher,
            historyRepository = AppGraph.historyRepository,
            settingsRepository = AppGraph.settingsRepository,
            toneController = AppGraph.toneController,
        ),
    )

    val state by vm.state.collectAsStateWithLifecycle()

    // Per-instance guard: true once recording has actually begun, so the initial
    // pre-onTap Idle does not immediately dismiss the overlay.
    val hasStarted = remember { mutableStateOf(false) }

    // Auto-start recording exactly once when the overlay appears.
    LaunchedEffect(Unit) {
        if (state.phase == VoicePhase.Idle) vm.onTap()
    }

    // When the flow returns to Idle AFTER we have started (recording cleared and
    // not busy), the run is over — commit already happened in the VM, so dismiss.
    LaunchedEffect(state.phase) {
        if (state.phase != VoicePhase.Idle) {
            hasStarted.value = true
        } else if (hasStarted.value && !state.recording && !state.busy) {
            VoiceOverlayController.hide()
        }
    }

    // Commit polished text into the focused input field.
    LaunchedEffect(vm) {
        vm.committed.collect { text ->
            editorInstance.commitText(text)
        }
    }

    // One-shot events: missing mic permission / model not ready.
    LaunchedEffect(vm) {
        vm.event.collect { event ->
            when (event) {
                VoiceEvent.PermissionRequired ->
                    launchWordTaker(context, MicPermissionActivity::class.java)
                VoiceEvent.ModelRequired ->
                    launchWordTaker(context, WordTakerSettingsActivity::class.java)
            }
            VoiceOverlayController.hide()
        }
    }

    // Surface transient toasts (e.g. "未识别到语音").
    val toast by vm.toast.collectAsStateWithLifecycle()
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    val noRipple = remember { MutableInteractionSource() }
    // Overlay covers the keyboard area; a translucent scrim dims the keys beneath.
    val panelHeight = FlorisImeSizing.keyboardUiHeight()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = OVERLAY_SCRIM_ALPHA))
            .clickable(
                interactionSource = noRipple,
                indication = null,
                onClick = vm::onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "正在倾听...点击结束",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Medium, centred cat — sized off the panel height but capped so it is
            // neither the oversized sleep sprite nor the tiny walking sprite.
            val catSize = (panelHeight * CAT_FILL_FRACTION)
                .coerceIn(CAT_MIN_DP.dp, CAT_MAX_DP.dp)
            CatSkin(
                state = CatState(
                    recording = state.recording,
                    level = state.level,
                    busy = state.busy,
                    error = false,
                ),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(width = catSize * CAT_ASPECT_RATIO, height = catSize),
            )
        }
    }
}

private const val OVERLAY_SCRIM_ALPHA = 0.96f
private const val CAT_ASPECT_RATIO = 5f / 6f
private const val CAT_FILL_FRACTION = 0.62f // between sleep(0.82) and tiny walk
private const val CAT_MIN_DP = 96
private const val CAT_MAX_DP = 150

private fun launchWordTaker(context: Context, target: Class<*>) {
    runCatching {
        context.startActivity(
            Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
