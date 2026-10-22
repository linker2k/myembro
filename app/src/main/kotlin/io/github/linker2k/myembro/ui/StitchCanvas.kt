// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import io.github.linker2k.myembro.engine.model.Bounds

/** Rendered thread width in 0.1 mm; real 40wt thread is about 0.4 mm. */
private const val THREAD_WIDTH = 4f

/**
 * Draws the design sewn up to [progress] inside the [hoop] outline. Pinch to zoom, drag to pan,
 * double-tap to fit. Coordinates are drawn in design units (0.1 mm) so thread width scales with zoom.
 */
@Composable
fun StitchCanvas(
    data: RenderData,
    progress: Int,
    hoop: Hoop,
    showJumps: Boolean,
    fitRequest: Int,
    modifier: Modifier = Modifier,
    backgroundColor: Int,
    hoopColor: Int,
) {
    val fabricColor = MaterialTheme.colorScheme.surface.toArgb()
    val travelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f).toArgb()
    val needleColor = MaterialTheme.colorScheme.primary.toArgb()
    val bounds = data.pattern.bounds ?: Bounds(0, 0, 1, 1)
    val cx = (bounds.minX + bounds.maxX) / 2f
    val cy = (bounds.minY + bounds.maxY) / 2f

    // User zoom/pan on top of the fit-to-hoop transform. Reset whenever the design or fit request changes.
    var zoom by remember(data, fitRequest) { mutableFloatStateOf(1f) }
    var pan by remember(data, fitRequest) { mutableStateOf(Offset.Zero) }

    val stitchPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = THREAD_WIDTH
        }
    }
    val jumpPaint = remember(travelColor) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = travelColor
            pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        }
    }
    val hoopPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    val fabricPaint = remember { Paint().apply { style = Paint.Style.FILL } }
    val needlePaint = remember(needleColor) { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = needleColor } }

    Canvas(
        modifier
            .pointerInput(data, fitRequest) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val newZoom = (zoom * zoomChange).coerceIn(0.5f, 40f)
                    // Keep the point under the fingers fixed while zooming.
                    val center = Offset(size.width / 2f, size.height / 2f)
                    pan = (pan - (centroid - center)) * (newZoom / zoom) + (centroid - center) + panChange
                    zoom = newZoom
                }
            }
            .pointerInput(data, fitRequest) {
                detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero })
            },
    ) {
        val hoopW = hoop.widthMm * 10f
        val hoopH = hoop.heightMm * 10f
        val viewW = maxOf(hoopW, bounds.width.toFloat())
        val viewH = maxOf(hoopH, bounds.height.toFloat())
        val fit = minOf(size.width / viewW, size.height / viewH) * 0.92f
        val scale = fit * zoom

        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            canvas.drawColor(backgroundColor)
            canvas.save()
            canvas.translate(size.width / 2f + pan.x, size.height / 2f + pan.y)
            canvas.scale(scale, scale)
            canvas.translate(-cx, -cy)

            // Fabric inside the hoop, then the hoop ring.
            fabricPaint.color = fabricColor
            canvas.drawRoundRect(cx - hoopW / 2, cy - hoopH / 2, cx + hoopW / 2, cy + hoopH / 2, 60f, 60f, fabricPaint)
            hoopPaint.color = hoopColor
            hoopPaint.strokeWidth = 3f / scale * 2
            canvas.drawRoundRect(cx - hoopW / 2, cy - hoopH / 2, cx + hoopW / 2, cy + hoopH / 2, 60f, 60f, hoopPaint)

            // Keep threads visible when zoomed far out.
            stitchPaint.strokeWidth = maxOf(THREAD_WIDTH, 1.2f / scale)
            val sewn = data.segmentsUpTo(data.stitchLineEnds, progress)
            for (run in data.runs) {
                if (run.start >= sewn) break
                val end = minOf(run.end, sewn)
                stitchPaint.color = run.color
                canvas.drawLines(data.stitchLines, run.start * 4, (end - run.start) * 4, stitchPaint)
            }

            if (showJumps) {
                jumpPaint.strokeWidth = 1.5f / scale
                val jumps = data.segmentsUpTo(data.jumpLineEnds, progress)
                if (jumps > 0) canvas.drawLines(data.jumpLines, 0, jumps * 4, jumpPaint)
            }

            // Needle position while the design is only partly sewn.
            val stitches = data.pattern.stitches
            if (progress < stitches.lastIndex) {
                val s = stitches[progress.coerceIn(0, stitches.lastIndex)]
                canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), 6f / scale, needlePaint)
            }
            canvas.restore()
        }
    }
}
