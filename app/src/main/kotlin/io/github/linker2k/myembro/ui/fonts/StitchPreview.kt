// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.fonts

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.ui.theme.DarkHoopArgb
import io.github.linker2k.myembro.ui.theme.LightHoopArgb
import kotlin.math.min

/** Hoop ring color token from the theme (no Material role exists for it). */
@Composable
fun hoopColor(): Color = Color(if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkHoopArgb else LightHoopArgb)

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

/**
 * The actual stitches of [pattern] (0.1 mm units, centered on 0,0) inside a [hoopMm] hoop, at true
 * scale, so size warnings can be judged by eye. Jumps and trims are not drawn.
 */
@Composable
fun HoopPatternPreview(pattern: EmbPattern?, hoopWidthMm: Double, hoopHeightMm: Double, modifier: Modifier) {
    val ring = hoopColor()
    val fabric = MaterialTheme.colorScheme.surfaceContainerLow
    val screw = MaterialTheme.colorScheme.tertiary
    val thread = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val ringWidth = 8.dp.toPx()
        val pad = ringWidth + 10.dp.toPx()
        val scale = min((size.width - 2 * pad) / hoopWidthMm.toFloat(), (size.height - 2 * pad) / hoopHeightMm.toFloat())
        val hw = hoopWidthMm.toFloat() * scale
        val hh = hoopHeightMm.toFloat() * scale
        val cx = size.width / 2
        val cy = size.height / 2 + 4.dp.toPx()
        val radius = CornerRadius(min(hw, hh) / 2)
        drawRoundRect(fabric, Offset(cx - hw / 2, cy - hh / 2), Size(hw, hh), radius)
        drawLine(guide, Offset(cx, cy - hh / 2), Offset(cx, cy + hh / 2), 1.dp.toPx())
        drawLine(guide, Offset(cx - hw / 2, cy), Offset(cx + hw / 2, cy), 1.dp.toPx())
        drawRoundRect(ring, Offset(cx - hw / 2, cy - hh / 2), Size(hw, hh), radius, style = Stroke(ringWidth))
        drawRoundRect(screw, Offset(cx - 12.dp.toPx(), cy - hh / 2 - ringWidth - 8.dp.toPx()), Size(24.dp.toPx(), 10.dp.toPx()), CornerRadius(3.dp.toPx()))
        val stitches = pattern?.stitches ?: return@Canvas
        val path = Path()
        var pen = false
        val unit = scale / 10f // pattern units are 0.1 mm
        for (s in stitches) {
            val p = Offset(cx + s.x * unit, cy + s.y * unit)
            when (s.command) {
                Command.STITCH -> if (pen) path.lineTo(p.x, p.y) else { path.moveTo(p.x, p.y); pen = true }
                else -> { pen = false; if (s.command == Command.JUMP) path.moveTo(p.x, p.y) }
            }
        }
        drawPath(path, thread, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
