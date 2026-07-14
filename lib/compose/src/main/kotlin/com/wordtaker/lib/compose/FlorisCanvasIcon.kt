/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package com.wordtaker.lib.compose

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap

@Composable
fun FlorisCanvasIcon(
    @DrawableRes iconId: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    ResourcesCompat.getDrawable(
        LocalContext.current.resources,
        iconId,
        null,
    )?.let { drawable ->
        FlorisCanvasIcon(
            drawable = drawable,
            modifier = modifier,
            contentDescription = contentDescription,
        )
    }
}

@Composable
fun FlorisCanvasIcon(
    drawable: Drawable,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    // Perf: rasterizing `drawable` into a fresh bitmap is real CPU work (allocate +
    // software-canvas draw). It previously ran unconditionally on every composition of
    // this composable (e.g. every time the caller recomposes for an unrelated reason),
    // adding avoidable main-thread cost during the very first frame the settings screen
    // draws (BrandHeader's cat icon). The source `drawable` is stable across recompositions
    // for a given call site, so `remember` it once instead of re-rasterizing every time.
    val bitmap = remember(drawable) {
        val bmp = createBitmap(
            width = drawable.intrinsicWidth,
            height = drawable.intrinsicHeight,
        )
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bmp
    }
    Image(
        modifier = modifier,
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
    )
}
