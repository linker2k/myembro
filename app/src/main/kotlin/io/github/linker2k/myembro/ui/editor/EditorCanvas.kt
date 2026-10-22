// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.model.Bounds
import io.github.linker2k.myembro.engine.edit.sewingArea
import io.github.linker2k.myembro.ui.RenderData
import kotlin.math.abs

private fun Bounds.corners() = listOf(
    Offset(minX.toFloat(), minY.toFloat()), Offset(maxX.toFloat(), minY.toFloat()),
    Offset(maxX.toFloat(), maxY.toFloat()), Offset(minX.toFloat(), maxY.toFloat()),
)

/** The gesture target is fixed at pointer-down: object/handle edits, empty fabric navigates. */
@Composable
internal fun EditorCanvas(vm: EditorViewModel, fitRequest: Int, modifier: Modifier = Modifier) {
    val hoop = vm.project.hoop
    var zoom by remember(hoop, fitRequest) { mutableFloatStateOf(1f) }
    var pan by remember(hoop, fitRequest) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val handleRadius = with(density) { 7.dp.toPx() }
    val handleTouch = with(density) { 24.dp.toPx() }
    val accent = Color(0xFFC4552D)
    val description = stringResource(R.string.editor_canvas_description)
    val renders = vm.project.objects.filter { it.visible }.map { obj ->
        key(obj.id) { remember(obj) { obj.id to RenderData(obj.placedPattern()) } }
    }
    val selectedBounds = remember(renders, vm.selectedIds) {
        val boxes = renders.filter { it.first in vm.selectedIds }.mapNotNull { it.second.pattern.bounds }
        if (boxes.isEmpty()) null else Bounds(boxes.minOf { it.minX }, boxes.minOf { it.minY },
            boxes.maxOf { it.maxX }, boxes.maxOf { it.maxY })
    }
    val snapDistance = with(density) { 8.dp.toPx() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }

    Canvas(modifier.semantics { contentDescription = description }
        .pointerInput(hoop, fitRequest) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (vm.busy) return@awaitEachGesture
                val fit = minOf(size.width.toFloat() / hoop.width, size.height.toFloat() / hoop.height) * 0.82f
                val scaleAtStart = fit * zoom
                val center = Offset(size.width / 2f, size.height / 2f)
                fun toWorld(point: Offset) = (point - center - pan) / (fit * zoom)
                val selecting = vm.selectionMode
                val selected = vm.selected?.takeIf { it.visible }
                val bounds = if (selecting) null else vm.project.selectionBounds(vm.selectedIds)
                val corner = bounds?.corners()?.firstOrNull {
                    (it * scaleAtStart + center + pan - down.position).getDistance() <= handleTouch
                }
                val point = toWorld(down.position)
                val hit = if (corner != null) selected else vm.project.objects.asReversed().firstOrNull {
                    it.visible && it.contains(point.x.toDouble(), point.y.toDouble(), handleTouch / scaleAtStart / 2.0)
                }
                if (!selecting) vm.selectForGesture(hit?.id)
                val editing = hit != null
                var started = false
                var completed = false
                var travel = Offset.Zero
                var handlePosition = down.position
                try {
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) { completed = true; break }
                        val delta = event.calculatePan()
                        val zoomChange = event.calculateZoom()
                        val rotation = event.calculateRotation()
                        travel += delta
                        if (!started && (travel.getDistance() > viewConfiguration.touchSlop ||
                                (pressed > 1 && (abs(zoomChange - 1f) > 0.001f || abs(rotation) > 0.1f)))) {
                            started = true
                            if (editing) {
                                if (selecting) vm.selectForGesture(hit?.id)
                                vm.beginGesture()
                            }
                        }
                        if (started) {
                            if (editing) {
                                if (corner != null && pressed == 1) {
                                    val group = vm.project.selectionBounds(vm.selectedIds) ?: break
                                    val pivot = Offset((group.minX + group.maxX) / 2f, (group.minY + group.maxY) / 2f)
                                    val origin = pivot * (fit * zoom) + center + pan
                                    val oldDistance = (handlePosition - origin).getDistance().coerceAtLeast(1f)
                                    handlePosition += delta
                                    val newDistance = (handlePosition - origin).getDistance().coerceAtLeast(1f)
                                    vm.transformBy(0.0, 0.0, (newDistance / oldDistance).toDouble(), 0.0, 0.0)
                                } else {
                                    vm.transformBy((delta.x / (fit * zoom)).toDouble(), (delta.y / (fit * zoom)).toDouble(),
                                        zoomChange.toDouble(), rotation.toDouble(),
                                        if (pressed == 1) (snapDistance / (fit * zoom)).toDouble() else 0.0)
                                }
                            } else {
                                val centroid = event.changes.filter { it.pressed && it.previousPressed }
                                    .map { it.previousPosition }.let { points ->
                                        if (points.isEmpty()) down.position else points.reduce { a, b -> a + b } / points.size.toFloat()
                                    }
                                val nextZoom = (zoom * zoomChange).coerceIn(0.3f, 20f)
                                pan = (pan - (centroid - center)) * (nextZoom / zoom) + (centroid - center) + delta
                                zoom = nextZoom
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                } finally {
                    if (editing && started) vm.endGesture()
                }
                if (selecting && !started && completed && hit != null) vm.toggleSelection(hit.id)
            }
        }) {
        drawRect(Color(0xFFF6F0E6))
        val scale = minOf(size.width / hoop.width, size.height / hoop.height) * 0.82f * zoom
        val origin = center + pan
        fun screen(p: Offset) = origin + p * scale
        val topLeft = screen(Offset(-hoop.width / 2f, -hoop.height / 2f))
        val hoopSize = Size(hoop.width * scale, hoop.height * scale)
        drawRoundRect(Color(0xFFFFFCF6), topLeft, hoopSize, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
        // A quiet 10 mm grid gives users a sense of the real sewing dimensions.
        for (x in (-hoop.width / 2)..(hoop.width / 2) step 100) {
            drawLine(Color(0x142B2420), screen(Offset(x.toFloat(), -hoop.height / 2f)), screen(Offset(x.toFloat(), hoop.height / 2f)))
        }
        for (y in (-hoop.height / 2)..(hoop.height / 2) step 100) {
            drawLine(Color(0x142B2420), screen(Offset(-hoop.width / 2f, y.toFloat())), screen(Offset(hoop.width / 2f, y.toFloat())))
        }
        drawLine(Color(0x302B2420), screen(Offset(-20f, 0f)), screen(Offset(20f, 0f)), 1.dp.toPx())
        drawLine(Color(0x302B2420), screen(Offset(0f, -20f)), screen(Offset(0f, 20f)), 1.dp.toPx())
        drawRoundRect(Color(0xFF806B5C), topLeft, hoopSize, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()), style = Stroke(2.dp.toPx()))
        // The inset rectangle is the actual snap/warning boundary; the ring is decorative.
        val area = hoop.sewingArea.snapBounds
        drawRect(Color(0x66806B5C), screen(Offset(area.minX.toFloat(), area.minY.toFloat())),
            Size(area.width * scale, area.height * scale),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            canvas.save()
            canvas.translate(origin.x, origin.y)
            canvas.scale(scale, scale)
            paint.strokeWidth = maxOf(3f, 1f / scale)
            for ((_, data) in renders) {
                for (run in data.runs) {
                    paint.color = run.color
                    canvas.drawLines(data.stitchLines, run.start * 4, (run.end - run.start) * 4, paint)
                }
            }
            canvas.restore()
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()))
        vm.snapGuides.x?.let { x ->
            val px = screen(Offset(x.toFloat(), 0f)).x
            drawLine(accent, Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx(), pathEffect = dash)
        }
        vm.snapGuides.y?.let { y ->
            val py = screen(Offset(0f, y.toFloat())).y
            drawLine(accent, Offset(0f, py), Offset(size.width, py), 1.5.dp.toPx(), pathEffect = dash)
        }
        if (vm.selectedIds.size > 1) renders.filter { it.first in vm.selectedIds }.forEach { (_, data) ->
            data.pattern.bounds?.let { b ->
                val a = screen(Offset(b.minX.toFloat(), b.minY.toFloat()))
                drawRect(accent.copy(alpha = 0.5f), a, Size(maxOf(1f, b.width * scale), maxOf(1f, b.height * scale)), style = Stroke(1.dp.toPx()))
            }
        }
        selectedBounds?.let { b ->
            val a = screen(Offset(b.minX.toFloat(), b.minY.toFloat()))
            val z = screen(Offset(b.maxX.toFloat(), b.maxY.toFloat()))
            drawRect(accent, a, Size(maxOf(1f, z.x - a.x), maxOf(1f, z.y - a.y)), style = Stroke(2.dp.toPx()))
            b.corners().forEach { corner ->
                drawCircle(Color.White, handleRadius, screen(corner))
                drawCircle(accent, handleRadius, screen(corner), style = Stroke(2.dp.toPx()))
            }
        }
    }
}
