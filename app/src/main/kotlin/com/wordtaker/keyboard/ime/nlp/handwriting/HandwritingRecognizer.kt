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

package com.wordtaker.keyboard.ime.nlp.handwriting

import android.content.Context
import android.util.Base64
import com.wordtaker.keyboard.lib.devtools.flogDebug
import com.wordtaker.keyboard.lib.devtools.flogError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline Chinese handwriting recognizer.
 *
 * Loads the [HandwritingTemplateDb] from `assets/handwriting/mmah.json` once (lazily, on a
 * background thread), then matches user ink (a list of pen strokes, each a list of [PointF]-like
 * x/y pairs) against every template, returning the best-scoring characters.
 *
 * The matcher is a self-contained implementation of analyzed-sub-stroke matching:
 *  1. Normalize all ink points into a unit box (translate + uniform scale to fit 0..1).
 *  2. Decompose each pen stroke into straight sub-strokes by resampling + corner detection.
 *  3. Describe each sub-stroke by (direction, length, center) — same feature shape as the DB.
 *  4. Greedily align the input sub-stroke sequence to each template's sequence and accumulate a
 *     cost from direction-difference, length-difference and center-difference; prefer templates
 *     with a similar stroke count.
 */
class HandwritingRecognizer(private val context: Context) {
    companion object {
        // The Make-Me-a-Hanzi-derived template DB (JSON content, .hwr extension so it can stay
        // uncompressed in the APK without forcing every layout .json uncompressed too).
        private const val ASSET_PATH = "handwriting/mmah.hwr"

        // Feature weights for sub-stroke distance.
        private const val W_DIRECTION = 1.0
        private const val W_LENGTH = 0.6
        private const val W_CENTER = 1.6

        // Penalty applied per unmatched (skipped) sub-stroke when the two sequences differ in
        // length. Keeps wildly different stroke counts from matching.
        private const val SKIP_PENALTY = 2.4

        // How strongly a stroke-count mismatch is penalized (added to the final score).
        private const val STROKE_COUNT_PENALTY = 0.9

        // Resampling resolution per pen stroke before corner splitting.
        private const val RESAMPLE_POINTS = 24

        // A turn sharper than this (radians) splits a pen stroke into two sub-strokes.
        private const val CORNER_THRESHOLD = PI / 4.0  // 45°

        private const val TWO_PI = 2.0 * PI

        /** Smallest absolute angular difference between two directions, 0..π. */
        private fun angleDiff(a: Double, b: Double): Double {
            var d = abs(a - b) % TWO_PI
            if (d > PI) d = TWO_PI - d
            return d
        }
    }

    private val loadMutex = Mutex()
    @Volatile
    private var db: HandwritingTemplateDb? = null

    val isLoaded: Boolean
        get() = db != null

    /** Loads the template DB if not already loaded. Safe to call repeatedly. */
    suspend fun preload() {
        if (db != null) return
        loadMutex.withLock {
            if (db != null) return
            db = runCatching { loadDb() }.getOrElse {
                flogError { "Failed to load handwriting template DB: ${it.message}" }
                HandwritingTemplateDb(emptyList())
            }
            flogDebug { "Handwriting DB loaded: ${db?.templates?.size ?: 0} templates" }
        }
    }

    private fun loadDb(): HandwritingTemplateDb {
        val raw = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val charsArr = root.getJSONArray("chars")
        val bytes = Base64.decode(root.getString("substrokes"), Base64.DEFAULT)

        val templates = ArrayList<CharTemplate>(charsArr.length())
        for (i in 0 until charsArr.length()) {
            val entry = charsArr.getJSONArray(i)
            val char = entry.getString(0)
            val strokeCount = entry.getInt(1)
            val subCount = entry.getInt(2)
            val offset = entry.getInt(3)
            val subStrokes = ArrayList<SubStroke>(subCount)
            var p = offset
            for (s in 0 until subCount) {
                if (p + 2 >= bytes.size) break
                val dirByte = bytes[p].toInt() and 0xFF
                val lenByte = bytes[p + 1].toInt() and 0xFF
                val ctrByte = bytes[p + 2].toInt() and 0xFF
                p += 3
                subStrokes.add(
                    SubStroke(
                        direction = dirByte / 256.0 * TWO_PI,
                        length = lenByte / 255.0,
                        centerX = ((ctrByte ushr 4) and 0x0F) / 15.0,
                        centerY = (ctrByte and 0x0F) / 15.0,
                    )
                )
            }
            templates.add(CharTemplate(char, strokeCount, subStrokes))
        }
        return HandwritingTemplateDb(templates)
    }

    /** A single point of user ink. */
    data class Point(val x: Float, val y: Float)

    /** A recognition result: the candidate character and its score (lower is better). */
    data class Result(val char: String, val score: Double)

    /**
     * Recognizes the given ink strokes and returns up to [maxResults] best candidates, ordered
     * best-first. Returns an empty list if the DB is not loaded or there is no usable ink.
     */
    fun recognize(strokes: List<List<Point>>, maxResults: Int = 8): List<Result> {
        val database = db ?: return emptyList()
        if (database.templates.isEmpty()) return emptyList()

        val input = analyzeInput(strokes)
        if (input.isEmpty()) return emptyList()
        val inputStrokeCount = strokes.count { it.size >= 2 }

        // Score every template; keep the best maxResults by score.
        val scored = ArrayList<Result>(database.templates.size)
        for (t in database.templates) {
            if (t.subStrokes.isEmpty()) continue
            // Cheap pre-filter: skip templates whose sub-stroke count is wildly different.
            val diff = abs(t.subStrokes.size - input.size)
            if (diff > input.size + 2) continue
            val cost = matchCost(input, t.subStrokes) +
                STROKE_COUNT_PENALTY * abs(t.strokeCount - inputStrokeCount)
            scored.add(Result(t.char, cost))
        }
        scored.sortBy { it.score }
        // De-duplicate by char (the DB may have variants), keep first (best) occurrence.
        val seen = HashSet<String>()
        val out = ArrayList<Result>(maxResults)
        for (r in scored) {
            if (seen.add(r.char)) {
                out.add(r)
                if (out.size >= maxResults) break
            }
        }
        return out
    }

    /** Converts raw ink into a normalized sub-stroke feature sequence. */
    private fun analyzeInput(strokes: List<List<Point>>): List<SubStroke> {
        // 1. Bounding box over all points.
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var pointCount = 0
        for (stroke in strokes) {
            for (pt in stroke) {
                if (pt.x < minX) minX = pt.x
                if (pt.y < minY) minY = pt.y
                if (pt.x > maxX) maxX = pt.x
                if (pt.y > maxY) maxY = pt.y
                pointCount++
            }
        }
        if (pointCount < 2) return emptyList()
        val w = (maxX - minX)
        val h = (maxY - minY)
        // Uniform scale to fit the larger extent; keep aspect ratio. Guard tiny extents.
        val scale = 1.0 / (maxOf(w, h).coerceAtLeast(1f))
        // Center the (possibly non-square) glyph inside the unit box.
        val offX = (1.0 - w * scale) / 2.0
        val offY = (1.0 - h * scale) / 2.0

        val result = ArrayList<SubStroke>()
        for (stroke in strokes) {
            if (stroke.size < 2) continue
            val norm = stroke.map {
                val nx = (it.x - minX) * scale + offX
                // The mmah template directions use a y-UP (mathematical) coordinate system, while
                // Android touch coordinates are y-DOWN (origin top-left). Flip Y so stroke
                // directions match the template space (e.g. a top->bottom vertical stroke maps to
                // the same direction the DB encodes for 丨).
                val ny = 1.0 - ((it.y - minY) * scale + offY)
                Point(nx.toFloat(), ny.toFloat())
            }
            val resampled = resample(norm, RESAMPLE_POINTS)
            result.addAll(decompose(resampled))
        }
        return result
    }

    /** Resamples a polyline into [n] evenly spaced points. */
    private fun resample(points: List<Point>, n: Int): List<Point> {
        if (points.size <= 2) return points
        var total = 0.0
        for (i in 1 until points.size) {
            total += hypot((points[i].x - points[i - 1].x).toDouble(), (points[i].y - points[i - 1].y).toDouble())
        }
        if (total <= 0.0) return listOf(points.first())
        val interval = total / (n - 1)
        val out = ArrayList<Point>(n)
        out.add(points.first())
        var accumulated = 0.0
        var i = 1
        var prev = points.first()
        while (i < points.size) {
            val cur = points[i]
            val d = hypot((cur.x - prev.x).toDouble(), (cur.y - prev.y).toDouble())
            if (accumulated + d >= interval && d > 0.0) {
                val t = (interval - accumulated) / d
                val nx = (prev.x + t * (cur.x - prev.x)).toFloat()
                val ny = (prev.y + t * (cur.y - prev.y)).toFloat()
                val np = Point(nx, ny)
                out.add(np)
                prev = np
                accumulated = 0.0
            } else {
                accumulated += d
                prev = cur
                i++
            }
            if (out.size >= n) break
        }
        if (out.size < n) out.add(points.last())
        return out
    }

    /**
     * Splits a resampled stroke into straight sub-strokes at sharp corners, then describes each
     * sub-stroke as a [SubStroke] feature.
     */
    private fun decompose(points: List<Point>): List<SubStroke> {
        if (points.size < 2) return emptyList()
        // Find split indices where the local direction turns sharply.
        val splits = ArrayList<Int>()
        splits.add(0)
        for (i in 1 until points.size - 1) {
            val a = atan2((points[i].y - points[i - 1].y).toDouble(), (points[i].x - points[i - 1].x).toDouble())
            val b = atan2((points[i + 1].y - points[i].y).toDouble(), (points[i + 1].x - points[i].x).toDouble())
            if (angleDiff(a, b) > CORNER_THRESHOLD) {
                splits.add(i)
            }
        }
        splits.add(points.size - 1)

        val out = ArrayList<SubStroke>()
        for (k in 0 until splits.size - 1) {
            val startIdx = splits[k]
            val endIdx = splits[k + 1]
            if (endIdx <= startIdx) continue
            val start = points[startIdx]
            val end = points[endIdx]
            val dx = (end.x - start.x).toDouble()
            val dy = (end.y - start.y).toDouble()
            val len = hypot(dx, dy)
            if (len < 1e-4) continue
            var dir = atan2(dy, dx)
            if (dir < 0) dir += TWO_PI
            // Center is the midpoint of the segment.
            val cx = (start.x + end.x) / 2.0
            val cy = (start.y + end.y) / 2.0
            out.add(SubStroke(direction = dir, length = min(len, 1.0), centerX = cx, centerY = cy))
        }
        return out
    }

    /** Distance between two sub-strokes (lower = more similar). */
    private fun subStrokeDistance(a: SubStroke, b: SubStroke): Double {
        val dirCost = angleDiff(a.direction, b.direction) / PI            // 0..1
        val lenCost = abs(a.length - b.length)                            // 0..~1
        val ctrCost = sqrt(
            (a.centerX - b.centerX) * (a.centerX - b.centerX) +
                (a.centerY - b.centerY) * (a.centerY - b.centerY)
        )                                                                  // 0..~1.4
        return W_DIRECTION * dirCost + W_LENGTH * lenCost + W_CENTER * ctrCost
    }

    /**
     * Greedy edit-distance-style alignment cost between the input sub-stroke sequence and a
     * template's. Uses a banded DP over the two sequences with substitution / skip moves.
     */
    private fun matchCost(input: List<SubStroke>, template: List<SubStroke>): Double {
        val n = input.size
        val m = template.size
        // DP table where dp[i][j] = best cost aligning first i input subs to first j template subs.
        val dp = Array(n + 1) { DoubleArray(m + 1) { Double.MAX_VALUE / 4 } }
        dp[0][0] = 0.0
        for (i in 1..n) dp[i][0] = dp[i - 1][0] + SKIP_PENALTY
        for (j in 1..m) dp[0][j] = dp[0][j - 1] + SKIP_PENALTY
        for (i in 1..n) {
            for (j in 1..m) {
                val sub = dp[i - 1][j - 1] + subStrokeDistance(input[i - 1], template[j - 1])
                val skipInput = dp[i - 1][j] + SKIP_PENALTY
                val skipTemplate = dp[i][j - 1] + SKIP_PENALTY
                dp[i][j] = minOf(sub, skipInput, skipTemplate)
            }
        }
        // Normalize by alignment length so longer characters aren't unfairly penalized.
        return dp[n][m] / maxOf(n, m)
    }
}
