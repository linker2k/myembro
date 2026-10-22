// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.ui.ThreadRendering

/** Actual stitches for lettering/catalog cards; thread width uses the same physical scale as the editor. */
@Composable
fun StitchPatternPreview(pattern: EmbPattern?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = pattern ?: return@Canvas
        val b = p.bounds ?: return@Canvas
        val scale = minOf(size.width / (b.width + 8).coerceAtLeast(10), size.height / (b.height + 8).coerceAtLeast(10))
        val cx = (b.minX.toDouble() + b.maxX) / 2
        val cy = (b.minY.toDouble() + b.maxY) / 2
        fun point(x: Int, y: Int) = center + Offset(((x - cx) * scale).toFloat(), ((y - cy) * scale).toFloat())
        var previous = p.stitches.firstOrNull() ?: return@Canvas
        var colorIndex = 0
        for (s in p.stitches.drop(1)) {
            if (s.command == Command.COLOR_CHANGE) colorIndex++
            if (previous.command == Command.STITCH && s.command == Command.STITCH) {
                drawLine(Color(p.threadAt(colorIndex).color), point(previous.x, previous.y), point(s.x, s.y),
                    ThreadRendering.widthPx(scale, 1.dp.toPx()), StrokeCap.Round)
            }
            previous = s
        }
    }
}
