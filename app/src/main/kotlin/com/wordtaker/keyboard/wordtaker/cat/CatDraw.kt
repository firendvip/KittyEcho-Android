package com.wordtaker.keyboard.wordtaker.cat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.sin

// ---- Hardcoded palette (verbatim from demo cat.js / style.css) ----
internal val CAT_BODY = Color(0xFF1B1B1F)
internal val EYE_YELLOW = Color(0xFFFDE047)
internal val PUPIL = Color(0xFF1B1B1F)
internal val EYE_HIGHLIGHT = Color(0xFFFFFFFF)
internal val NOSE_PINK = Color(0xFFF472B6)
internal val BULB_YELLOW = Color(0xFFFDE047)
internal val BULB_BASE_GRAY = Color(0xFF9CA3AF)
internal val STAR_GOLD = Color(0xFFFCD34D)
internal val SWEAT_BLUE = Color(0xFF60A5FA)
internal val ZZZ_COLOR = Color(0xFF5B6472)

internal val NOTE_COLORS = listOf(
    Color(0xFF7DB4FF), Color(0xFFF7A8CB), Color(0xFFB197FC), Color(0xFF5ED0C5),
    Color(0xFFFCD34D), Color(0xFF86E08C), Color(0xFFFF9F6B), Color(0xFFF472B6),
)

// SVG viewBox dimensions (used to scale a unit-coordinate draw to canvas px).
internal const val RUN_VB_W = 46f
internal const val RUN_VB_H = 32f
internal const val SLEEP_VB_W = 44f
internal const val SLEEP_VB_H = 24f

/**
 * Reusable path scratch space so per-frame draws allocate nothing.
 * Each draw resets() before building.
 */
internal class CatPaths {
    val p1 = Path()
    val p2 = Path()
    val p3 = Path()
    val p4 = Path()
}

// ---------------------------------------------------------------------------
// SLEEP CAT  (viewBox 0 0 44 24)
// breathe: 0..1 progress through cs-breathe -> scale(1) .. scale(1.05, .94)
// ---------------------------------------------------------------------------
internal fun DrawScope.drawSleepCat(paths: CatPaths, @Suppress("UNUSED_PARAMETER") breathe: Float) {
    // Body is completely still — no breathing scale applied (bug fix: was bobbing/squashing).
    // tail: M38 16 C 42 14, 42 20, 37.5 18.5  stroke 3.6 round
    paths.p1.reset()
    paths.p1.moveTo(38f, 16f)
    paths.p1.cubicTo(42f, 14f, 42f, 20f, 37.5f, 18.5f)
    drawPath(paths.p1, CAT_BODY, style = Stroke(width = 3.6f, cap = StrokeCap.Round))

    // body ellipse cx24 cy16 rx15 ry7.5
    drawOval(CAT_BODY, topLeft = Offset(24f - 15f, 16f - 7.5f), size = Size(30f, 15f))
    // head circle cx11 cy15 r7.5
    drawCircle(CAT_BODY, radius = 7.5f, center = Offset(11f, 15f))
    // ear: M6 9 L8 4 L12 8 Z
    paths.p2.reset()
    paths.p2.moveTo(6f, 9f); paths.p2.lineTo(8f, 4f); paths.p2.lineTo(12f, 8f); paths.p2.close()
    drawPath(paths.p2, CAT_BODY, style = Fill)

    // closed eyes (yellow arcs): q-curves
    paths.p3.reset()
    paths.p3.moveTo(7.5f, 14.8f)
    paths.p3.relativeQuadraticBezierTo(1.4f, 1.4f, 2.8f, 0f)
    drawPath(paths.p3, EYE_YELLOW, style = Stroke(width = 1f, cap = StrokeCap.Round))
    paths.p4.reset()
    paths.p4.moveTo(12.5f, 14.8f)
    paths.p4.relativeQuadraticBezierTo(1.3f, 1.2f, 2.6f, 0f)
    drawPath(paths.p4, EYE_YELLOW, style = Stroke(width = 1f, cap = StrokeCap.Round))
}

// ---------------------------------------------------------------------------
// RUN / STAND CAT  (viewBox 0 0 46 32)
// phase: walk phase wp (radians) used for leg / tail animation.
// stepActive: when true (walking) legs + tail animate; otherwise static stand.
// ---------------------------------------------------------------------------
internal fun DrawScope.drawRunCat(paths: CatPaths, phase: Float, stepActive: Boolean) {
    // leg animation: cs-legA 0%/100% y0 50% -2.4 ; cs-legB opposite. period .3s.
    // We drive both off `phase`. legA uses sin, legB uses -sin.
    val legWave = if (stepActive) sin(phase * 6f) else 0f // ~speed; visual only
    val legAOffset = -1.2f + 1.2f * legWave  // oscillate around -1.2 within [-2.4,0]
    val legBOffset = -1.2f - 1.2f * legWave

    // tail wag: cs-wag 0..-15deg, origin 88% 88% of tail bbox.
    // tail path bbox ~ x[3..9], y[8..22]; origin at (88%,88%) ~ (8.28, 19.32)
    val tailDeg = if (stepActive) -7.5f + 7.5f * sin(phase * 3.6f) else 0f

    // tail: M9 22 C 3 20, 3 11, 7 8  stroke 3.6 round  (wagging)
    rotate(tailDeg, pivot = Offset(8.28f, 19.32f)) {
        paths.p1.reset()
        paths.p1.moveTo(9f, 22f)
        paths.p1.cubicTo(3f, 20f, 3f, 11f, 7f, 8f)
        drawPath(paths.p1, CAT_BODY, style = Stroke(width = 3.6f, cap = StrokeCap.Round))
    }

    // legs: rects x12/17/23/28, y24, w3 h5 rx1.5. lb & la alternate.
    // order in svg: lb, la, lb, la
    drawLeg(12f, 24f, legBOffset)
    drawLeg(17f, 24f, legAOffset)
    drawLeg(23f, 24f, legBOffset)
    drawLeg(28f, 24f, legAOffset)

    // body ellipse cx20 cy21 rx10 ry7
    drawOval(CAT_BODY, topLeft = Offset(20f - 10f, 21f - 7f), size = Size(20f, 14f))
    // head circle cx32 cy13 r10
    drawCircle(CAT_BODY, radius = 10f, center = Offset(32f, 13f))
    // ears: M25 6 L27 1 L31 5 Z   and   M39 6 L37 1 L33 5 Z
    paths.p2.reset()
    paths.p2.moveTo(25f, 6f); paths.p2.lineTo(27f, 1f); paths.p2.lineTo(31f, 5f); paths.p2.close()
    paths.p2.moveTo(39f, 6f); paths.p2.lineTo(37f, 1f); paths.p2.lineTo(33f, 5f); paths.p2.close()
    drawPath(paths.p2, CAT_BODY, style = Fill)

    // eyes at cx 28.4 and 35.6
    drawEye(28.4f)
    drawEye(35.6f)

    // nose: M31 16 h2 l-1 1.2 z (pink triangle)
    paths.p3.reset()
    paths.p3.moveTo(31f, 16f)
    paths.p3.relativeLineTo(2f, 0f)
    paths.p3.relativeLineTo(-1f, 1.2f)
    paths.p3.close()
    drawPath(paths.p3, NOSE_PINK, style = Fill)
}

private fun DrawScope.drawLeg(x: Float, y: Float, dy: Float) {
    drawRoundRectPx(x, y + dy, 3f, 5f, 1.5f, CAT_BODY)
}

private fun DrawScope.drawRoundRectPx(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
    )
}

private fun DrawScope.drawEye(cx: Float) {
    // eye(cx): yellow ellipse cx cy13 rx2.6 ry3.1 ; pupil ellipse cx+0.3 cy13.5 rx1 ry1.9 ;
    //          highlight circle cx-0.8 cy11.6 r0.7
    drawOval(EYE_YELLOW, topLeft = Offset(cx - 2.6f, 13f - 3.1f), size = Size(5.2f, 6.2f))
    drawOval(PUPIL, topLeft = Offset((cx + 0.3f) - 1f, 13.5f - 1.9f), size = Size(2f, 3.8f))
    drawCircle(EYE_HIGHLIGHT, radius = 0.7f, center = Offset(cx - 0.8f, 11.6f))
}

// ---------------------------------------------------------------------------
// FX SPRITES — each drawn in its own viewBox coordinate space, scaled by caller.
// ---------------------------------------------------------------------------

// BULB viewBox 0 0 14 16
internal fun DrawScope.drawBulb(paths: CatPaths) {
    drawCircle(BULB_YELLOW, radius = 5.5f, center = Offset(7f, 7f))
    drawRoundRectPx(4.5f, 12f, 5f, 2.6f, 1f, BULB_BASE_GRAY)
    drawLinePx(7f, 0f, 7f, 1.6f, BULB_YELLOW)
    drawLinePx(0.6f, 3.2f, 2f, 4.2f, BULB_YELLOW)
    drawLinePx(13.4f, 3.2f, 12f, 4.2f, BULB_YELLOW)
}

private fun DrawScope.drawLinePx(x1: Float, y1: Float, x2: Float, y2: Float, color: Color) {
    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1f, cap = StrokeCap.Round)
}

// STAR viewBox 0 0 14 14 — M7 0 L8.4 5.6 L14 7 L8.4 8.4 L7 14 L5.6 8.4 L0 7 L5.6 5.6 Z
internal fun DrawScope.drawStar(paths: CatPaths) {
    paths.p1.reset()
    paths.p1.moveTo(7f, 0f)
    paths.p1.lineTo(8.4f, 5.6f)
    paths.p1.lineTo(14f, 7f)
    paths.p1.lineTo(8.4f, 8.4f)
    paths.p1.lineTo(7f, 14f)
    paths.p1.lineTo(5.6f, 8.4f)
    paths.p1.lineTo(0f, 7f)
    paths.p1.lineTo(5.6f, 5.6f)
    paths.p1.close()
    drawPath(paths.p1, STAR_GOLD, style = Fill)
}

// SWEAT viewBox 0 0 10 14 — M5 0 C 5 4, 9 7, 9 10 A 4 4 0 0 1 1 10 C 1 7, 5 4, 5 0 Z
internal fun DrawScope.drawSweat(paths: CatPaths) {
    paths.p1.reset()
    paths.p1.moveTo(5f, 0f)
    paths.p1.cubicTo(5f, 4f, 9f, 7f, 9f, 10f)
    // A 4 4 0 0 1 1 10 : semicircle arc from (9,10) to (1,10), radius 4, sweep=1
    paths.p1.arcToRad(
        rect = Rect(left = 1f, top = 6f, right = 9f, bottom = 14f),
        startAngleRadians = 0f,
        sweepAngleRadians = Math.PI.toFloat(),
        forceMoveTo = false,
    )
    paths.p1.cubicTo(1f, 7f, 5f, 4f, 5f, 0f)
    paths.p1.close()
    drawPath(paths.p1, SWEAT_BLUE, style = Fill)
}

// NOTE glyphs are drawn as text by the composable (Canvas can't easily do glyph fonts
// without a TextMeasurer); see CatSkin which renders notes with drawText.

// ZZZ "Z" is also text; rendered in CatSkin via drawText.
