/*
 * Copyright (C) 2026 The WordTaker Contributors
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

package com.wordtaker.keyboard.wordtaker.handwriting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordtaker.keyboard.ime.keyboard.FlorisImeSizing
import com.wordtaker.keyboard.ime.nlp.WordSuggestionCandidate
import com.wordtaker.keyboard.ime.nlp.handwriting.HandwritingRecognizer
import com.wordtaker.keyboard.ime.text.key.KeyCode
import com.wordtaker.keyboard.ime.text.key.KeyType
import com.wordtaker.keyboard.ime.text.keyboard.TextKeyData
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.nlpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Brand green, matching the rest of the WordTaker UI.
private val BrandGreen = Color(0xFF07C160)
private val PadSurface = Color(0xFFFAFAFA)
private val InkColor = Color(0xFF1A1A1A)
private val ButtonRowBg = Color(0xFFF2F3F5)

// Recognition fires this long after the last pen lift (so multi-stroke characters aren't
// recognized mid-write). Re-armed on each new stroke.
private const val RECOGNIZE_DEBOUNCE_MS = 600L
private const val MAX_CANDIDATES = 10
private const val FUNCTION_ROW_HEIGHT_DP = 48

/**
 * The 手写 (handwriting) input surface. Replaces the normal key grid for the handwriting subtype.
 * Captures multi-stroke ink on a [Canvas], renders it live, debounces recognition against the
 * offline [HandwritingRecognizer], and pushes results to the candidate bar. Tapping a candidate
 * commits it (standard candidate-commit flow) and clears the pad.
 *
 * Bottom function row mirrors the essentials of a normal keyboard: 退格 / 清除 / 空格 / 中英 /
 * 话筒 / 换行.
 */
@Composable
fun HandwritingInputLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val nlpManager by context.nlpManager()
    val scope = rememberCoroutineScope()

    val recognizer = remember { nlpManager.handwritingProvider.recognizer }

    // Completed strokes (each a list of points) + the in-progress stroke.
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var recognizeJob by remember { mutableStateOf<Job?>(null) }

    // Preload the DB as soon as the pad appears (no-op if already loaded).
    LaunchedEffect(Unit) {
        if (!recognizer.isLoaded) {
            withContext(Dispatchers.Default) { recognizer.preload() }
        }
    }

    fun clearAll() {
        strokes.clear()
        current = emptyList()
        recognizeJob?.cancel()
        nlpManager.clearSuggestions()
    }

    fun runRecognition() {
        val snapshot = strokes.map { stroke -> stroke.map { HandwritingRecognizer.Point(it.x, it.y) } }
        if (snapshot.isEmpty()) return
        recognizeJob?.cancel()
        recognizeJob = scope.launch {
            val results = withContext(Dispatchers.Default) {
                recognizer.recognize(snapshot, MAX_CANDIDATES)
            }
            if (results.isEmpty()) {
                nlpManager.clearSuggestions()
                return@launch
            }
            val candidates = results.mapIndexed { index, r ->
                WordSuggestionCandidate(
                    text = r.char,
                    secondaryText = null,
                    confidence = (results.size - index).toDouble() / results.size,
                    isEligibleForAutoCommit = false,
                    isEligibleForUserRemoval = false,
                    sourceProvider = nlpManager.handwritingProvider,
                )
            }
            nlpManager.suggestDirectly(candidates)
        }
    }

    // Clear the pad whenever a committed candidate clears the candidate flow.
    val candidates by nlpManager.activeCandidatesFlow.collectAsState()
    var hadCandidates by remember { mutableStateOf(false) }
    LaunchedEffect(candidates) {
        val has = candidates.isNotEmpty()
        if (hadCandidates && !has) {
            // Candidate flow went empty — most likely a commit. Reset ink for the next character.
            strokes.clear()
            current = emptyList()
            recognizeJob?.cancel()
        }
        hadCandidates = has
    }

    // item3: 手写面板总高必须 == 普通键盘体高 (keyboardUiHeight)，否则切手写时 IME 窗口
    // 会高出功能行一截 (48dp) 产生跳动。墨迹区 = keyboardUiHeight - 功能行高。
    val padHeight = FlorisImeSizing.keyboardUiHeight() - FUNCTION_ROW_HEIGHT_DP.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PadSurface),
    ) {
        // Drawing area.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(padHeight)
                .padding(8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            recognizeJob?.cancel()
                            current = listOf(offset)
                        },
                        onDrag = { change, _ ->
                            current = current + change.position
                        },
                        onDragEnd = {
                            val finished = current
                            if (finished.size >= 2) {
                                strokes.add(finished)
                            } else if (finished.size == 1) {
                                val p = finished.first()
                                strokes.add(listOf(p, p.copy(x = p.x + 1f)))
                            }
                            current = emptyList()
                            recognizeJob?.cancel()
                            recognizeJob = scope.launch {
                                delay(RECOGNIZE_DEBOUNCE_MS)
                                runRecognition()
                            }
                        },
                        onDragCancel = {
                            current = emptyList()
                        },
                    )
                },
        ) {
            HandwritingCanvas(strokes = strokes, current = current)
            if (strokes.isEmpty() && current.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "在此手写汉字", color = Color(0xFFBBBBBB), fontSize = 16.sp)
                }
            }
        }

        // Function row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FUNCTION_ROW_HEIGHT_DP.dp)
                .background(ButtonRowBg)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PadButton(label = "退格", weight = 1.2f) {
                keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.DELETE)
            }
            PadButton(label = "清除", weight = 1f) { clearAll() }
            PadButton(label = "空格", weight = 2f) {
                keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.SPACE)
            }
            PadButton(label = "中英", weight = 1f) {
                keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.IME_NEXT_SUBTYPE)
            }
            PadButton(label = "话筒", weight = 1f) {
                keyboardManager.activeState.imeUiMode =
                    com.wordtaker.keyboard.ime.ImeUiMode.CAT_VOICE
            }
            PadButton(label = "换行", weight = 1.2f, accent = true) {
                keyboardManager.inputEventDispatcher.sendDownUp(
                    TextKeyData(type = KeyType.ENTER_EDITING, code = KeyCode.ENTER, label = "enter"),
                )
            }
        }
    }
}

@Composable
private fun HandwritingCanvas(strokes: List<List<Offset>>, current: List<Offset>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (s in strokes) {
            drawPolyline(s, stroke)
        }
        drawPolyline(current, stroke)
    }
}

private fun DrawScope.drawPolyline(points: List<Offset>, stroke: Stroke) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
    }
    drawPath(path = path, color = InkColor, style = stroke)
}

@Composable
private fun RowScope.PadButton(
    label: String,
    weight: Float,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) BrandGreen else Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (accent) Color.White else Color(0xFF333333),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
