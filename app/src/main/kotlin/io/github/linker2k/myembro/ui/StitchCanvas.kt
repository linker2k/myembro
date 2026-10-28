// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.sewout.Highlight
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.fabricColorArgb
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
    frame: HoopSpec? = null,
    preservePlacement: Boolean = false,
    highlight: Highlight? = null,
    focusRequest: Int = 0,
    blockerHighlights: List<Highlight> = emptyList(),
    onBlockerTap: ((Highlight) -> Unit)? = null,
) {
    val fabricColor = data.pattern.fabricColorArgb ?: MaterialTheme.colorScheme.surface.toArgb()
    val travelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f).toArgb()
    val needleColor = MaterialTheme.colorScheme.primary.toArgb()
    val blockingColor = MaterialTheme.colorScheme.error.toArgb()
    val highlightSchemeColor = if (highlight != null && highlight in blockerHighlights) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val highlightColor = highlightSchemeColor.toArgb()
    val highlightFill = highlightSchemeColor.copy(alpha = .16f).toArgb()
    val highlightDescription = stringResource(R.string.sewout_highlight_description)
    val blockerAreas = remember(data, blockerHighlights) {
        blockerHighlights.mapNotNull { h -> viewerHighlightArea(data.pattern, h)?.let { h to it } }
    }
    val blockerDescription = stringResource(R.string.playback_blocker_description, blockerAreas.size)
    val currentBlockerTap by rememberUpdatedState(onBlockerTap)
    val bounds = remember(data) { viewerMovementBounds(data.pattern) ?: Bounds(0, 0, 1, 1) }
    val cx = if (preservePlacement) 0f else (bounds.minX.toLong() + bounds.maxX) / 2f
    val cy = if (preservePlacement) 0f else (bounds.minY.toLong() + bounds.maxY) / 2f

    // User zoom/pan on top of the fit-to-hoop transform. Reset whenever the design or fit request changes.
    var zoom by remember(data, fitRequest) { mutableFloatStateOf(1f) }
    var pan by remember(data, fitRequest) { mutableStateOf(Offset.Zero) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val highlightArea = remember(data, highlight) { highlight?.let { viewerHighlightArea(data.pattern, it) } }
    LaunchedEffect(data, fitRequest, focusRequest, highlightArea, canvasSize, frame, preservePlacement) {
        val area = highlightArea ?: return@LaunchedEffect
        if (canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
        val fit = viewerCanvasFit(bounds, frame?.width?.toFloat() ?: hoop.widthMm * 10f,
            frame?.height?.toFloat() ?: hoop.heightMm * 10f, preservePlacement, canvasSize.width.toFloat(), canvasSize.height.toFloat())
        val camera = viewerFocusCamera(area, fit, cx, cy, canvasSize.width.toFloat(), canvasSize.height.toFloat())
        zoom = camera.zoom
        pan = Offset(camera.panX, camera.panY)
    }

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
        modifier.clipToBounds().onSizeChanged { canvasSize = it }.semantics {
            if (blockerAreas.isNotEmpty()) contentDescription = blockerDescription
            else if (highlightArea != null) contentDescription = highlightDescription
        }
            .pointerInput(data, fitRequest) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val newZoom = (zoom * zoomChange).coerceIn(0.5f, 40f)
                    // Keep the point under the fingers fixed while zooming.
                    val center = Offset(size.width / 2f, size.height / 2f)
                    pan = (pan - (centroid - center)) * (newZoom / zoom) + (centroid - center) + panChange
                    zoom = newZoom
                }
            }
            .pointerInput(data, fitRequest, blockerAreas, frame, hoop, preservePlacement) {
                detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero }, onTap = { tap ->
                    val fit = viewerCanvasFit(bounds, frame?.width?.toFloat() ?: hoop.widthMm * 10f,
                        frame?.height?.toFloat() ?: hoop.heightMm * 10f, preservePlacement, size.width.toFloat(), size.height.toFloat())
                    val scale = fit * zoom
                    val nearest = blockerAreas.minByOrNull { (_, area) ->
                        val x = ((area.minX + area.maxX) / 2 - cx) * scale + size.width / 2f + pan.x
                        val y = ((area.minY + area.maxY) / 2 - cy) * scale + size.height / 2f + pan.y
                        kotlin.math.hypot(x - tap.x, y - tap.y)
                    }
                    if (nearest != null) {
                        val area = nearest.second
                        val x = ((area.minX + area.maxX) / 2 - cx) * scale + size.width / 2f + pan.x
                        val y = ((area.minY + area.maxY) / 2 - cy) * scale + size.height / 2f + pan.y
                        if (kotlin.math.hypot(x - tap.x, y - tap.y) <= 24.dp.toPx()) currentBlockerTap?.invoke(nearest.first)
                    }
                })
            },
    ) {
        val hoopW = frame?.width?.toFloat() ?: (hoop.widthMm * 10f)
        val hoopH = frame?.height?.toFloat() ?: (hoop.heightMm * 10f)
        val fit = viewerCanvasFit(bounds, hoopW, hoopH, preservePlacement, size.width, size.height)
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

            val blockerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = blockingColor; style = Paint.Style.STROKE; strokeWidth = 2.dp.toPx() / scale
            }
            blockerAreas.forEach { (_, area) ->
                canvas.drawCircle(((area.minX + area.maxX) / 2).toFloat(), ((area.minY + area.maxY) / 2).toFloat(),
                    10.dp.toPx() / scale, blockerPaint)
            }

            highlightArea?.let { area ->
                val left = area.minX.toFloat(); val top = area.minY.toFloat()
                val right = maxOf(area.maxX.toFloat(), left + 1); val bottom = maxOf(area.maxY.toFloat(), top + 1)
                val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = highlightFill; style = Paint.Style.FILL }
                canvas.drawRect(left, top, right, bottom, overlay)
                overlay.color = highlightColor; overlay.style = Paint.Style.STROKE; overlay.strokeWidth = 2.dp.toPx() / scale
                canvas.drawRect(left, top, right, bottom, overlay)
                val from = highlight?.from; val to = highlight?.to
                if (from != null && to != null) canvas.drawLine(from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat(), overlay)
                canvas.drawCircle(((area.minX + area.maxX) / 2).toFloat(), ((area.minY + area.maxY) / 2).toFloat(), 10.dp.toPx() / scale, overlay)
            }

            // Needle position while the design is only partly sewn.
            val stitches = data.pattern.stitches
            if (stitches.isNotEmpty() && progress < data.playback.endIndex) {
                val s = stitches[progress.coerceIn(0, stitches.lastIndex)]
                canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), 6f / scale, needlePaint)
            }
            canvas.restore()
        }
    }
}
