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

/**
 * Data models for the offline handwriting recognizer.
 *
 * The recognition template database is `assets/handwriting/mmah.json`, derived from the
 * "Make Me a Hanzi" stroke-median data (Arphic Public License, see NOTICE / about screen).
 * The matching algorithm here is an independent WordTaker implementation of the well-known
 * "analyzed sub-stroke" matching idea (compare a character's strokes as a sequence of
 * direction + length + center-position features); no third-party recognizer code is reused.
 *
 * mmah.json layout:
 *   {
 *     "chars": [ [char, strokeCount, subStrokeCount, byteOffset], ... ],
 *     "substrokes": "<base64>"   // 3 bytes per sub-stroke, packed back to back
 *   }
 * Each sub-stroke triplet decodes as:
 *   byte0 = direction  (0..255  -> angle 0..2π, screen coords, 0 = pointing right / +x)
 *   byte1 = length     (0..255  -> normalized sub-stroke length, 0..1)
 *   byte2 = center     (high nibble = x 0..15, low nibble = y 0..15) in a normalized grid
 */

/** A single analyzed sub-stroke feature: a straight segment of one pen stroke. */
data class SubStroke(
    /** Direction in radians, 0..2π. 0 points to +x (right); increases clockwise in screen space. */
    val direction: Double,
    /** Normalized length, 0..1 (1 ≈ full character box diagonal). */
    val length: Double,
    /** Normalized center X, 0..1. */
    val centerX: Double,
    /** Normalized center Y, 0..1 (0 = top). */
    val centerY: Double,
)

/** A template character: its label plus the flattened sub-stroke feature sequence. */
class CharTemplate(
    val char: String,
    val strokeCount: Int,
    val subStrokes: List<SubStroke>,
)

/** The loaded template database. */
class HandwritingTemplateDb(
    val templates: List<CharTemplate>,
)
