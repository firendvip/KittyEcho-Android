package com.wordtaker.keyboard.wordtaker.cat

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

// ---- Constants (verbatim from cat.js) ----
private const val VOICE_THR = 0.35f
private const val LOUD_THR = 0.7f
private const val HOLD = 1000f
private const val HYST = 0.05f
private const val FX_DWELL = 300f
private const val ENTER_MS = 1400f
private const val RETURN_MS = 800f
private const val WALK_W = 0.022f
private const val PROC_W = 0.04f
// 需求4：所有头顶效果(音符/灯泡/星星/汗滴)必须出现在「猫运动方向的斜上方」。
// 运行猫 bottom-anchored 于 y=DEMO_H-6=66、高 RUN_VB_H=32 ⇒ 头顶 ≈ y=34。
// FX 基点 = (rt.x + lastDir*FX_DIAG_SIDE_X, (DEMO_H-30)+FRONT_UP_Y+FX_DIAG_UP_Y)。
// - FX_DIAG_SIDE_X：朝运动方向那一侧的水平偏移，靠头部外上角，由 rt.lastDir 镜像(右行=右、左行=左)。
//   取 18f：在头部上方偏向运动侧，且对小条幅(28dp)与大画布(200dp)都不会飞出画布。
// - FX_DIAG_UP_Y：在原 FRONT_UP_Y(-16) 基础上再抬一点，让效果明确高于头顶、形成「斜上方」。
private const val FRONT_SIDE_X = 13f
private const val FRONT_UP_Y = -16f
private const val FX_DIAG_SIDE_X = 18f
private const val FX_DIAG_UP_Y = -8f
// 睡眠精灵的头部位于 demo 中心左侧约 11f 处（精灵 left = g.c - SLEEP_VB_W/2，头在左半）。
// 睡眠态 rt.x = 中心，直接用会让 bulb/sparkle 飘到身体右侧，故睡眠态以头部为 FX 锚点。
private const val FX_SLEEP_HEAD_DX = 11f
// 需求4：音符在已对角化的基点上，再向运动方向额外偏置，整团音符明显偏运动侧上方。
// 由 4→9→13：进一步偏向运动方向，配合单向散开确保音符不覆盖头部。
private const val NOTE_FRONT_BIAS = 13f

private val NOTE_GLYPHS = listOf("♪", "♫", "♩", "♬") // ♪ ♫ ♩ ♬
private const val NOTE_MAX = 8
private const val NOTE_SPAWN_NORMAL = 330f
private const val NOTE_SPAWN_LOUD = 150f
private const val NOTE_SPREAD = 14f
private const val NOTE_SIZE_MIN = 11f
private const val NOTE_SIZE_MAX = 15f
private const val NOTE_DX_MAX = 12f
private const val NOTE_DY_MIN = -22f
private const val NOTE_DY_MAX = -12f
private const val NOTE_ROT_MAX = 40f
private const val NOTE_DUR_MIN = 1.0f
private const val NOTE_DUR_MAX = 1.7f
private const val NOTE_DELAY_MAX = 0.25f

private const val ZZZ_BASE_LEFT = 4f
private const val ZZZ_STEP = 4f
// item2: 抬到趴睡猫头顶之上 (head top demo-y≈49.5；py = DEMO_H - ZZZ_BOTTOM)。
// 22→36 ⇒ Z 基座 demo-y≈36，明确高于头顶、从头顶上方自然冒出，不再压在身体上。
private const val ZZZ_BOTTOM = 36f
private val ZZZ_SIZES = floatArrayOf(8f, 10f, 12f) // s/m/l
private val ZZZ_DELAYS_MS = floatArrayOf(0f, 700f, 1400f)
private const val ZZZ_DUR = 3000f // csfxZz 3s loop

// ---------------------------------------------------------------------------
// Unified "demo world" coordinate space.
//
// The Mac CatSkinFx works in ONE flat coordinate space: a container ~180px wide,
// cat sprite ~32px tall anchored at bottom:6px, the FX layer at bottom:30px, and
// notes/Zzz positioned relative to the cat *within that same space*. Anchors like
// FRONT_UP_Y(-10) and ZZZ_BOTTOM(22) only make sense in that flat space.
//
// To get #10 (note position) pixel-correct we replicate that flat space verbatim:
// draw the cat + every FX in demo coordinates, then apply ONE uniform `worldScale`
// + bottom-center translate so the whole scene fills the panel. This keeps every
// effect rigidly attached to the cat's head exactly like the Mac version, instead
// of each layer carrying its own independent scale (the previous bug).
//
// DEMO_H is the logical height of that flat world. The cat sits at bottom:6 and is
// ~32 tall (head top ~ y=DEMO_H-29); notes rise to ~bottom:52. 72 leaves headroom
// for the rising notes / Zzz without clipping.
private const val DEMO_H = 72f

private const val SLEEP_BREATHE_MS = 3200f
private const val STAR_TW_MS = 1100f
private const val BOB_MS = 1000f

private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)
private fun rand(min: Float, max: Float) = min + Math.random().toFloat() * (max - min)
private fun <T> pick(list: List<T>): T =
    list[(Math.random() * list.size).toInt().coerceIn(0, list.size - 1)]

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** A live music-note particle (mirrors a `.cs-fxnt` span). */
private class Note(
    val glyph: String,
    val color: Color,
    val sizePx: Float,
    val leftPx: Float,
    val dx: Float,
    val dy: Float,
    val rot: Float,
    val durMs: Float,
    val delayMs: Float,
    val bornAt: Float,
)

/** A floating Z (mirrors a `.cs-fxzz` span). */
private class Zzz(val sizePx: Float, val delayMs: Float)

/** Mutable, non-recomposing animation holder, updated inside the frame loop. */
private class CatRuntime {
    var mode = "sleep"          // idle | enter | walk | settle | sleep
    var view = "none"           // none | run | sleep
    var x = 0f
    var wp = 0f
    var t0 = 0f
    var xRet = 0f
    var lastDir = 1f
    var zzzPlaced = false
    var runScale = 1f
    var now = 0f
    var widthPx = 0f

    var prevBusy = false
    var prevErr = false
    var successUntil = 0f
    var errorUntil = 0f
    var lastVoice = -1e9f
    var loudState = false

    var fxShownType: String? = null
    var fxPendingType: String? = null
    var fxPendingSince = 0f
    var lastNoteSpawn = 0f

    val notes = ArrayList<Note>()
    var zzz: Array<Zzz>? = null
    var zzzStart = 0f
}

@Composable
fun CatSkin(state: CatState, modifier: Modifier = Modifier) {
    val rt = remember { CatRuntime() }
    val paths = remember { CatPaths() }
    val measurer = rememberTextMeasurer()
    val latest by rememberUpdatedState(state)

    // Drives redraw: mutate rt in the loop, then bump ticker to invalidate Canvas.
    var ticker by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (isActive) {
            val nanos = awaitFrame()
            tick(rt, latest, nanos / 1_000_000f)
            ticker = nanos
        }
    }

    Canvas(modifier = modifier) {
        // Read ticker so this draw lambda re-runs each frame.
        @Suppress("UNUSED_EXPRESSION") ticker
        // tick() runs in the demo world coordinate space (width in demo units), so
        // convert the real canvas width to demo units via the uniform worldScale.
        val worldScale = size.height / DEMO_H
        rt.widthPx = if (worldScale > 0f) size.width / worldScale else size.width
        drawScene(rt, paths, measurer, worldScale)
    }
}

private class Geom(val w: Float, val c: Float, val amp: Float, val enterFrom: Float)

private fun geom(width: Float): Geom {
    val w = if (width > 0f) width else 180f
    val c = w / 2f
    val amp = (w / 2f - 30f).coerceIn(28f, 56f)
    val enterFrom = (c - amp - 6f).coerceAtLeast(14f)
    return Geom(w, c, amp, enterFrom)
}

// ---------------------------------------------------------------------------
// tick(): verbatim port of cat.js frame()
// ---------------------------------------------------------------------------
private fun tick(rt: CatRuntime, s: CatState, now: Float) {
    val g = geom(rt.widthPx)
    val c = g.c; val amp = g.amp; val enterFrom = g.enterFrom

    val busyRef = s.busy
    val errRef = s.error
    val recRef = s.recording
    val lvlRef = s.level

    // edges
    if (!busyRef && rt.prevBusy) rt.successUntil = now + 1200f
    rt.prevBusy = busyRef
    if (errRef && !rt.prevErr) rt.errorUntil = now + 1500f
    rt.prevErr = errRef

    if (lvlRef > VOICE_THR) rt.lastVoice = now
    val voice = now - rt.lastVoice < HOLD
    val active = recRef || busyRef

    if (!rt.loudState && lvlRef > LOUD_THR + HYST) rt.loudState = true
    else if (rt.loudState && lvlRef < LOUD_THR - HYST) rt.loudState = false

    // effect priority
    val priority: String? = when {
        now < rt.successUntil -> "sparkle"
        now < rt.errorUntil -> "sweat"
        busyRef -> "bulb"
        recRef && voice -> "notes"
        else -> null
    }

    // dwell gating
    val immediate = priority == "sparkle" || priority == "sweat" || priority == null
    if (priority != rt.fxShownType) {
        if (immediate) {
            rt.fxShownType = priority
            rt.fxPendingType = null
        } else {
            if (rt.fxPendingType != priority) {
                rt.fxPendingType = priority
                rt.fxPendingSince = now
            } else if (now - rt.fxPendingSince >= FX_DWELL) {
                rt.fxShownType = priority
                rt.fxPendingType = null
            }
        }
    } else {
        rt.fxPendingType = null
    }

    // notes spawn
    if (rt.fxShownType == "notes") {
        val interval = if (rt.loudState) NOTE_SPAWN_LOUD else NOTE_SPAWN_NORMAL
        if (now - rt.lastNoteSpawn >= interval) {
            spawnNote(rt, now)
            rt.lastNoteSpawn = now
        }
    }
    if (rt.notes.isNotEmpty()) {
        rt.notes.removeAll { n -> now - n.bornAt >= n.delayMs + n.durMs }
    }

    val want = if (busyRef || (active && voice)) "walk" else "rest"

    // mode machine
    when (rt.mode) {
        "idle" -> {
            if (active) { rt.mode = "enter"; rt.t0 = now; rt.view = "run" }
            else { rt.mode = "sleep"; rt.view = "sleep"; rt.x = c; rt.zzzPlaced = false }
        }
        "enter" -> {
            val te = ((now - rt.t0) / ENTER_MS).coerceAtMost(1f)
            val ke = easeOut(te)
            rt.x = enterFrom + (c - enterFrom) * ke
            rt.runScale = 0.32f + 0.68f * ke
            rt.lastDir = 1f
            if (te >= 1f) {
                rt.wp = 0f
                if (!active) rt.mode = "idle"
                else {
                    rt.mode = if (want == "rest") "settle" else "walk"
                    rt.t0 = now; rt.xRet = rt.x
                }
            }
        }
        "walk" -> {
            if (!active || want == "rest") { rt.mode = "settle"; rt.t0 = now; rt.xRet = rt.x }
            else {
                rt.wp += if (busyRef || rt.loudState) PROC_W else WALK_W
                rt.x = c + amp * sin(rt.wp)
                rt.lastDir = if (cos(rt.wp) >= 0f) 1f else -1f
                rt.runScale = 1f
            }
        }
        "settle" -> {
            if (active && want != "rest") { rt.mode = "walk"; rt.wp = 0f; rt.view = "run" }
            else {
                val tr = ((now - rt.t0) / RETURN_MS).coerceAtMost(1f)
                val kr = easeOut(tr)
                rt.x = rt.xRet + (c - rt.xRet) * kr
                rt.lastDir = if (c - rt.x >= 0f) 1f else -1f
                // 需求#6：离场(走回)时由大变小，像走向远处 —— 与 enter 的 0.32→1.0 对称。
                rt.runScale = 1f - 0.68f * kr
                if (tr >= 1f) { rt.mode = "sleep"; rt.view = "sleep"; rt.x = c; rt.zzzPlaced = false }
            }
        }
        "sleep" -> {
            rt.x = c
            rt.view = "sleep"
            if (priority == null) {
                if (!rt.zzzPlaced) {
                    rt.zzz = arrayOf(
                        Zzz(ZZZ_SIZES[0], ZZZ_DELAYS_MS[0]),
                        Zzz(ZZZ_SIZES[1], ZZZ_DELAYS_MS[1]),
                        Zzz(ZZZ_SIZES[2], ZZZ_DELAYS_MS[2]),
                    )
                    rt.zzzStart = now
                    rt.zzzPlaced = true
                }
            } else if (rt.zzzPlaced) { rt.zzz = null; rt.zzzPlaced = false }
            if (active && want != "rest") {
                rt.mode = "walk"; rt.view = "run"; rt.wp = 0f
                rt.zzzPlaced = false; rt.zzz = null
            }
        }
    }
    if (rt.mode != "sleep") rt.zzz = null

    rt.now = now
}

private fun spawnNote(rt: CatRuntime, now: Float) {
    if (rt.notes.size >= NOTE_MAX) return
    rt.notes.add(
        Note(
            glyph = pick(NOTE_GLYPHS),
            color = pick(NOTE_COLORS),
            sizePx = rand(NOTE_SIZE_MIN, NOTE_SIZE_MAX),
            // 需求#7：水平偏移单向朝运动方向，音符不会散回头顶上方。
            leftPx = rt.lastDir * (NOTE_FRONT_BIAS + rand(0f, NOTE_SPREAD / 2f)),
            dx = rand(-NOTE_DX_MAX, NOTE_DX_MAX),
            dy = rand(NOTE_DY_MIN, NOTE_DY_MAX),
            rot = rand(-NOTE_ROT_MAX, NOTE_ROT_MAX),
            durMs = rand(NOTE_DUR_MIN, NOTE_DUR_MAX) * 1000f,
            delayMs = rand(0f, NOTE_DELAY_MAX) * 1000f,
            bornAt = now,
        )
    )
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------
private fun DrawScope.drawScene(
    rt: CatRuntime,
    paths: CatPaths,
    measurer: TextMeasurer,
    worldScale: Float,
) {
    // One uniform transform maps the flat demo world (height DEMO_H, bottom-anchored)
    // onto the real canvas, bottom-aligned and horizontally centred. Everything inside
    // — cat, FX, notes, Zzz — is drawn in demo coordinates, so all of the Mac anchor
    // constants (FRONT_SIDE_X / FRONT_UP_Y / ZZZ_BOTTOM …) apply 1:1.
    val s = if (worldScale > 0f) worldScale else 1f
    // bottom-left of the demo world in canvas px (world is `geom.w` wide, centred).
    val g = geom(rt.widthPx)
    val worldWpx = g.w * s
    val originX = (size.width - worldWpx) / 2f
    val originY = size.height - DEMO_H * s
    translate(originX, originY) {
        scale(s, s, pivot = Offset.Zero) {
            drawDemoWorld(rt, paths, measurer, g)
        }
    }
}

/** Draws the entire scene in flat demo coordinates (bottom = DEMO_H). */
private fun DrawScope.drawDemoWorld(
    rt: CatRuntime,
    paths: CatPaths,
    measurer: TextMeasurer,
    g: Geom,
) {
    val bottomY = DEMO_H - 6f // demo .cs-runner / .cs-sleeper bottom:6px

    when (rt.view) {
        "sleep" -> {
            val phase = (rt.now % SLEEP_BREATHE_MS) / SLEEP_BREATHE_MS
            // Native sprite size, bottom-centred at the demo origin — identical scale to
            // the running cat so sleep <-> walk never changes size (the previous version
            // blew the sleeping cat up independently, which also detached the FX anchor).
            val left = g.c - SLEEP_VB_W / 2f
            translate(left, bottomY - SLEEP_VB_H) {
                drawSleepCat(paths, phase)
            }
        }
        "run" -> {
            val sc = rt.runScale.coerceAtLeast(0.01f)
            val left = rt.x - 16f
            translate(left, bottomY - RUN_VB_H) {
                scale(sc, sc, pivot = Offset(RUN_VB_W / 2f, RUN_VB_H)) {
                    scale(rt.lastDir, 1f, pivot = Offset(RUN_VB_W / 2f, RUN_VB_H / 2f)) {
                        drawRunCat(paths, rt.wp, stepActive = rt.mode == "walk")
                    }
                }
            }
        }
    }

    // ---- FX layer (需求4): 所有效果出现在运动方向的「斜上方」 ----
    // 水平偏移由 rt.lastDir 驱动 ⇒ 右行=右上、左行=左上，自动镜像。
    // 垂直在 FRONT_UP_Y 基础上再抬 FX_DIAG_UP_Y，让效果明确高于头顶形成对角线。
    // bulb / sparkle / sweat / notes 全部读取同一 fxBaseX/fxBaseY，故对角放置统一生效。
    // 运行态以 rt.x 为头部中心；睡眠态精灵头部在 g.c - FX_SLEEP_HEAD_DX，避免 FX 飘离身体。
    val catHeadX = if (rt.view == "sleep") g.c - FX_SLEEP_HEAD_DX else rt.x
    val fxBaseX = catHeadX + rt.lastDir * FX_DIAG_SIDE_X
    val fxBaseY = (DEMO_H - 30f) + FRONT_UP_Y + FX_DIAG_UP_Y

    when (rt.fxShownType) {
        "bulb" -> translate(fxBaseX, fxBaseY + bob(rt.now)) { drawBulb(paths) }
        "sparkle" -> {
            val t = (rt.now % STAR_TW_MS) / STAR_TW_MS
            val k = 0.5f - 0.5f * cos(t * 2f * Math.PI.toFloat())
            val sc = 0.8f + 0.35f * k
            val rot = 45f * k
            translate(fxBaseX, fxBaseY) {
                scale(sc, sc, pivot = Offset(7f, 7f)) {
                    rotate(rot, pivot = Offset(7f, 7f)) { drawStar(paths) }
                }
            }
        }
        "sweat" -> translate(fxBaseX, fxBaseY + bob(rt.now)) { drawSweat(paths) }
        "notes" -> drawNotes(rt, fxBaseX, fxBaseY, measurer)
    }

    rt.zzz?.let { drawZzz(rt, it, g, measurer) }
}

// csfxBob: 0/100 y0 ; 50 y-3. period 1s.
private fun bob(now: Float): Float {
    val t = (now % BOB_MS) / BOB_MS
    return -3f * (0.5f - 0.5f * cos(t * 2f * Math.PI.toFloat()))
}

private fun DrawScope.drawNotes(rt: CatRuntime, baseX: Float, baseY: Float, measurer: TextMeasurer) {
    for (n in rt.notes) {
        val age = rt.now - n.bornAt - n.delayMs
        if (age < 0f) continue
        val p = (age / n.durMs).coerceIn(0f, 1f)
        // csfxRiseRand: translate(0,2)scale.55 -> translate(dx,dy)scale1 rot; op fade in@25% out@100%
        val tx = lerp(0f, n.dx, p)
        val ty = lerp(2f, n.dy, p)
        val sc = lerp(0.55f, 1f, p)
        val rot = lerp(0f, n.rot, p)
        val alpha = if (p < 0.25f) p / 0.25f else 1f - (p - 0.25f) / 0.75f
        translate(baseX + n.leftPx + tx, baseY + ty) {
            rotate(rot, pivot = Offset.Zero) {
                scale(sc, sc, pivot = Offset.Zero) {
                    val layout = measurer.measure(
                        text = n.glyph,
                        style = TextStyle(color = n.color, fontSize = n.sizePx.sp),
                    )
                    drawText(layout, topLeft = Offset.Zero, alpha = alpha.coerceIn(0f, 1f))
                }
            }
        }
    }
}

private fun DrawScope.drawZzz(rt: CatRuntime, zs: Array<Zzz>, g: Geom, measurer: TextMeasurer) {
    // 趴睡猫 (SLEEP_SVG, viewBox 0..44) 头部在左侧 (cx≈11)，画在 left = g.c - 22 处，
    // 故头中心 ≈ g.c - 11、头顶 demo-y ≈ 49.5。Zzz 必须从「头顶上方」自然冒出 (item2)：
    //  - 水平锚到头部、并向头朝外侧 (左) 错峰升起，不再压在身体上；
    //  - 垂直抬到头顶之上 (py 用更大的 ZZZ_BOTTOM ⇒ demo-y 更小=更高)。
    val dir = -1f // 趴睡猫头朝左，Zzz 向左上方飘
    val headX = g.c - 11f
    for (k in zs.indices) {
        val z = zs[k]
        val cycleTime = rt.now - rt.zzzStart - z.delayMs
        if (cycleTime < 0f) continue
        val p = (cycleTime % ZZZ_DUR) / ZZZ_DUR
        // csfxZz: translate(0,1)scale.85 -> translate(zdir*3,-5)scale1 ; op in@25% out@100%
        val tx = lerp(0f, dir * 3f, p)
        val ty = lerp(1f, -5f, p)
        val sc = lerp(0.85f, 1f, p)
        val alpha = if (p < 0.25f) p / 0.25f else 1f - (p - 0.25f) / 0.75f
        val left = headX + dir * (ZZZ_BASE_LEFT + k * ZZZ_STEP)
        val bottom = ZZZ_BOTTOM + k * 3f
        val px = left + tx
        val py = (DEMO_H - bottom) + ty
        scale(sc, sc, pivot = Offset(px, py)) {
            val layout = measurer.measure(
                text = "Z",
                style = TextStyle(
                    color = ZZZ_COLOR,
                    fontSize = z.sizePx.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            drawText(layout, topLeft = Offset(px, py), alpha = alpha.coerceIn(0f, 1f))
        }
    }
}
