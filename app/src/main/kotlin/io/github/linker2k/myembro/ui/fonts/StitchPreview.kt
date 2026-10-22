// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.fonts

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Draws polylines (any units, +y down) scaled to fit and left-aligned, dashed like running
 * stitches. Used for font cards: outlines and single-line strokes both show what will be sewn.
 */
@Composable
fun StrokeText(strokes: List<List<Pair<Double, Double>>>, modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val pts = strokes.flatten()
        if (pts.isEmpty()) return@Canvas
        val minX = pts.minOf { it.first }
        val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }
        val maxY = pts.maxOf { it.second }
        val w = (maxX - minX).coerceAtLeast(1.0)
        val h = (maxY - minY).coerceAtLeast(1.0)
        val scale = min(size.width / w, size.height / h).toFloat()
        val top = (size.height - h.toFloat() * scale) / 2
        val path = Path()
        for (stroke in strokes) {
            stroke.forEachIndexed { i, (x, y) ->
                val px = ((x - minX) * scale).toFloat()
                val py = top + ((y - minY) * scale).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
        }
        drawPath(
            path, color,
            style = Stroke(
                width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 1.2.dp.toPx())),
            ),
        )
    }
}

