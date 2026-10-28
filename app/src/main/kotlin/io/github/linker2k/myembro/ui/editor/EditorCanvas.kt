// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.graphics.Paint
import io.github.linker2k.myembro.engine.edit.applyThreadColors
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
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
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.DesignObject
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.Transform
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import io.github.linker2k.myembro.ui.RenderData
import io.github.linker2k.myembro.ui.ThreadRendering
import kotlin.math.abs
import kotlin.math.roundToInt
import io.github.linker2k.myembro.ui.format.Dimensions
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.ui.settings.withSewingDefaults

private fun Bounds.corners() = listOf(
    Offset(minX.toFloat(), minY.toFloat()), Offset(maxX.toFloat(), minY.toFloat()),
    Offset(maxX.toFloat(), maxY.toFloat()), Offset(minX.toFloat(), maxY.toFloat()),
)

/** The gesture target is fixed at pointer-down: object/handle edits, empty fabric navigates. */
@Composable
internal fun EditorCanvas(vm: EditorViewModel, fitRequest: Int, modifier: Modifier = Modifier,
    navigationOnly: Boolean = false, showJumps: Boolean = false, preview: Float = 1f) {
    val hoop = vm.project.hoop
    var zoom by remember(hoop, fitRequest) { mutableFloatStateOf(1f) }
    var pan by remember(hoop, fitRequest) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val handleRadius = with(density) { 7.dp.toPx() }
    val handleTouch = with(density) { 24.dp.toPx() }
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val textSize = with(density) { MaterialTheme.typography.labelSmall.fontSize.toPx() }
    val description = stringResource(R.string.editor_canvas_description)
    val baseRenders = rememberObjectRenders(vm.project.objects.filter { it.visible }, vm.settings.sewing,vm.gestureInProgress) { vm.rendering=it }
    val renders = remember(baseRenders,vm.project.threadColors) {
        if(vm.project.threadColors.isEmpty()) baseRenders else baseRenders.map { (obj,data) -> obj to RenderData(vm.project.applyThreadColors(data.pattern)) }
    }
    val selectedBounds = remember(vm.project,vm.selectedIds) { vm.project.selectionBounds(vm.selectedIds,fastPreview=true) }
    val selectedOutputBounds = rememberSelectionOutputBounds(vm.project, vm.selectedIds, vm.settings.sewing, vm.gestureInProgress)
    val snapDistance = with(density) { 8.dp.toPx() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }

    Canvas(modifier.semantics { contentDescription = description }
        .pointerInput(hoop, fitRequest, navigationOnly) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (vm.interactionBlocked) return@awaitEachGesture
                val fit = minOf(size.width.toFloat() / hoop.width, size.height.toFloat() / hoop.height) * 0.82f
                val scaleAtStart = fit * zoom
                val center = Offset(size.width / 2f, size.height / 2f)
                fun toWorld(point: Offset) = (point - center - pan) / (fit * zoom)
                val selecting = vm.selectionMode
                val selected = vm.selected?.takeIf { it.visible }
                val bounds = if (selecting || navigationOnly) null else vm.project.selectionBounds(vm.selectedIds,fastPreview=true)
                val corner = bounds?.corners()?.firstOrNull {
                    (it * scaleAtStart + center + pan - down.position).getDistance() <= handleTouch
                }
                val point = toWorld(down.position)
                val hit = if (navigationOnly) null else if (corner != null) selected else vm.project.objects.asReversed().firstOrNull {
                    it.visible && it.contains(point.x.toDouble(), point.y.toDouble(), handleTouch / scaleAtStart / 2.0)
                }
                if (!selecting && !navigationOnly) vm.selectForGesture(hit?.id)
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
                                    val group = vm.project.selectionBounds(vm.selectedIds,fastPreview=true) ?: break
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
                else if (!selecting && !navigationOnly && !started && completed && vm.selectedIds.size == 1 && hit is TextObject) vm.openLettering(hit.id)
            }
        }) {
        drawRect(colors.background)
        // Fine woven lines stay quiet at any zoom; they are UI chrome, not stitch data.
        val weave = colors.outline.copy(alpha = 0.12f)
        for (x in 0..size.width.toInt() step maxOf(1, 4.dp.toPx().toInt())) drawLine(weave, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height))
        for (y in 0..size.height.toInt() step maxOf(1, 4.dp.toPx().toInt())) drawLine(weave, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()))
        val scale = minOf(size.width / hoop.width, size.height / hoop.height) * 0.82f * zoom
        val origin = center + pan
        fun screen(p: Offset) = origin + p * scale
        val topLeft = screen(Offset(-hoop.width / 2f, -hoop.height / 2f))
        val hoopSize = Size(hoop.width * scale, hoop.height * scale)
        drawRoundRect(vm.project.fabricColorArgb?.let { androidx.compose.ui.graphics.Color(it) } ?: colors.surface, topLeft, hoopSize, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
        // A quiet 10 mm grid gives users a sense of the real sewing dimensions.
        for (x in (-hoop.width / 2)..(hoop.width / 2) step 100) {
            drawLine(colors.outline.copy(alpha = 0.35f), screen(Offset(x.toFloat(), -hoop.height / 2f)), screen(Offset(x.toFloat(), hoop.height / 2f)))
        }
        for (y in (-hoop.height / 2)..(hoop.height / 2) step 100) {
            drawLine(colors.outline.copy(alpha = 0.35f), screen(Offset(-hoop.width / 2f, y.toFloat())), screen(Offset(hoop.width / 2f, y.toFloat())))
        }
        drawLine(colors.onSurfaceVariant.copy(alpha = 0.35f), screen(Offset(-20f, 0f)), screen(Offset(20f, 0f)), 1.dp.toPx())
        drawLine(colors.onSurfaceVariant.copy(alpha = 0.35f), screen(Offset(0f, -20f)), screen(Offset(0f, 20f)), 1.dp.toPx())
        // Position centers are supplied by the verified machine setup, never guessed from frame size.
        vm.sewingPositions.forEachIndexed { index, position ->
            val origin = screen(Offset(position.originX - position.width / 2f, position.originY - position.height / 2f))
            val areaSize = Size(position.width * scale, position.height * scale)
            val shade = listOf(colors.secondary, colors.tertiary, colors.primary)[index % 3]
            drawRect(shade.copy(alpha = 0.12f), origin, areaSize)
            drawRect(shade.copy(alpha = 0.65f), origin, areaSize, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))))
            drawIntoCanvas { canvas ->
                paint.color = colors.onSurface.toArgb(); paint.textSize = textSize; paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                canvas.nativeCanvas.drawText("${index + 1} · ${position.name}", origin.x + 8.dp.toPx(), origin.y + 18.dp.toPx(), paint)
                paint.typeface = android.graphics.Typeface.DEFAULT
            }
        }
        drawRoundRect(colors.outline, topLeft, hoopSize, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()), style = Stroke(5.dp.toPx()))
        // The inset rectangle is the actual snap/warning boundary; the ring is decorative.
        val area = hoop.sewingArea.snapBounds
        drawRect(colors.onSurfaceVariant.copy(alpha = 0.5f), screen(Offset(area.minX.toFloat(), area.minY.toFloat())),
            Size(area.width * scale, area.height * scale),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            canvas.save()
            canvas.translate(origin.x, origin.y)
            canvas.scale(scale, scale)
            paint.strokeWidth = ThreadRendering.widthPx(scale, 1.dp.toPx()) / scale
            val progress = (renders.sumOf { it.second.pattern.stitches.size } * preview.coerceIn(0f, 1f)).toInt()
            var offset = 0
            for ((base, data) in renders) {
                val current=vm.project.find(base.id) ?: continue
                canvas.save()
                // Current affine matrix times inverse of the cached render's affine matrix.
                val t=current.transform; val old=base.transform
                canvas.translate(t.x.toFloat(),t.y.toFloat())
                canvas.rotate(t.rotationDeg.toFloat())
                canvas.scale((t.scaleX/old.scaleX).toFloat(),(t.scaleY/old.scaleY).toFloat())
                canvas.rotate(-old.rotationDeg.toFloat())
                canvas.translate(-old.x.toFloat(),-old.y.toFloat())
                val cutoff = progress - offset
                offset += data.pattern.stitches.size
                val sewn = data.segmentsUpTo(data.stitchLineEnds, cutoff)
                for (run in data.runs) {
                    val end = minOf(run.end, sewn)
                    if (end <= run.start) continue
                    paint.color = run.color
                    canvas.drawLines(data.stitchLines, run.start * 4, (end - run.start) * 4, paint)
                }
                if (showJumps) {
                    paint.color = colors.onSurfaceVariant.copy(alpha = 0.3f).toArgb()
                    val jumps = data.segmentsUpTo(data.jumpLineEnds, cutoff)
                    if (jumps > 0) canvas.drawLines(data.jumpLines, 0, jumps * 4, paint)
                }
                canvas.restore()
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
        if (vm.selectedIds.size > 1) renders.filter { it.first.id in vm.selectedIds }.forEach { (base, _) ->
            vm.project.find(base.id)?.previewBounds()?.let { b ->
                val a = screen(Offset(b.minX.toFloat(), b.minY.toFloat()))
                drawRect(accent.copy(alpha = 0.5f), a, Size(maxOf(1f, b.width * scale), maxOf(1f, b.height * scale)), style = Stroke(1.dp.toPx()))
            }
        }
        selectedBounds?.let { b ->
            val a = screen(Offset(b.minX.toFloat(), b.minY.toFloat()))
            val z = screen(Offset(b.maxX.toFloat(), b.maxY.toFloat()))
            drawRect(accent, a, Size(maxOf(1f, z.x - a.x), maxOf(1f, z.y - a.y)), style = Stroke(2.dp.toPx()))
            val dimensionGap = 14.dp.toPx()
            val dimY = (z.y + dimensionGap).coerceIn(0f, size.height)
            val dimX = (z.x + dimensionGap).coerceIn(0f, size.width)
            drawLine(accent, Offset(a.x, dimY), Offset(z.x, dimY), 1.dp.toPx())
            drawLine(accent, Offset(dimX, a.y), Offset(dimX, z.y), 1.dp.toPx())
            listOf(a.x, z.x).forEach { x -> drawLine(accent, Offset(x, dimY - 3.dp.toPx()), Offset(x, dimY + 3.dp.toPx()), 1.dp.toPx()) }
            listOf(a.y, z.y).forEach { y -> drawLine(accent, Offset(dimX - 3.dp.toPx(), y), Offset(dimX + 3.dp.toPx(), y), 1.dp.toPx()) }
            drawIntoCanvas { c ->
                val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize; color = colors.onPrimary.toArgb() }
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent.toArgb() }
                fun pill(text: String, x: Float, y: Float) {
                    val w = label.measureText(text) + 12.dp.toPx()
                    val h = 22.dp.toPx()
                    val cx = x.coerceIn(w / 2 + 2.dp.toPx(), maxOf(w / 2 + 2.dp.toPx(), size.width - w / 2 - 2.dp.toPx()))
                    val cy = y.coerceIn(h / 2 + 2.dp.toPx(), maxOf(h / 2 + 2.dp.toPx(), size.height - h / 2 - 2.dp.toPx()))
                    c.nativeCanvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 6.dp.toPx(), 6.dp.toPx(), fill)
                    c.nativeCanvas.drawText(text, cx - label.measureText(text) / 2, cy - (label.ascent() + label.descent()) / 2, label)
                }
                selectedOutputBounds?.let { dimensions ->
                    pill(Dimensions.length(dimensions.width / 10.0), (a.x + z.x) / 2, dimY + 15.dp.toPx())
                    val heightLabel = Dimensions.length(dimensions.height / 10.0)
                    pill(heightLabel, dimX + label.measureText(heightLabel) / 2 + 10.dp.toPx(), (a.y + z.y) / 2)
                }
            }
            b.corners().forEach { corner ->
                drawCircle(colors.surface, handleRadius, screen(corner))
                drawCircle(accent, handleRadius, screen(corner), style = Stroke(2.dp.toPx()))
            }
        }
        val ruler = 24.dp.toPx()
        drawRect(colors.background.copy(alpha = 0.96f), size = Size(size.width, ruler))
        drawRect(colors.background.copy(alpha = 0.96f), size = Size(ruler, size.height))
        drawIntoCanvas { c ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.onSurfaceVariant.toArgb(); this.textSize = textSize * 0.85f; strokeWidth = 1.dp.toPx() }
            c.nativeCanvas.drawText("mm", 3.dp.toPx(), 14.dp.toPx(), p)
            for (x in (-hoop.width / 2)..(hoop.width / 2) step 50) {
                val px = screen(Offset(x.toFloat(), 0f)).x
                if (px < ruler || px > size.width) continue
                val major = (x + hoop.width / 2) % 100 == 0
                c.nativeCanvas.drawLine(px, ruler - (if (major) 6 else 3).dp.toPx(), px, ruler, p)
                if (major) c.nativeCanvas.drawText("${(x + hoop.width / 2) / 10}", px + 2.dp.toPx(), 12.dp.toPx(), p)
            }
            for (y in (-hoop.height / 2)..(hoop.height / 2) step 50) {
                val py = screen(Offset(0f, y.toFloat())).y
                if (py < ruler || py > size.height) continue
                val major = (y + hoop.height / 2) % 100 == 0
                c.nativeCanvas.drawLine(ruler - (if (major) 6 else 3).dp.toPx(), py, ruler, py, p)
                if (major) c.nativeCanvas.drawText("${(y + hoop.height / 2) / 10}", 2.dp.toPx(), py - 2.dp.toPx(), p)
            }
            c.nativeCanvas.drawText("${(zoom * 100).roundToInt()}%", 32.dp.toPx(), size.height - 10.dp.toPx(), p)
        }
    }
}

/** Rigid moves never invalidate render arrays. A scaled render is rebuilt once, 300ms after pointer-up. */
@Composable
private fun rememberObjectRenders(objects: List<DesignObject>, sewingOptions: SewingOptions,
    gesturing: Boolean, onRendering: (Boolean) -> Unit): List<Pair<DesignObject,RenderData>> {
    val cache = remember { mutableMapOf<String,Pair<Pair<DesignObject,SewingOptions>,Pair<DesignObject,RenderData>>>() }
    var renders by remember { mutableStateOf<List<Pair<DesignObject,RenderData>>>(emptyList()) }
    val keys=objects.map { obj -> obj.withTransform(Transform(scaleX=obj.transform.scaleX,scaleY=obj.transform.scaleY)) }
    LaunchedEffect(keys,sewingOptions,gesturing) {
        if(gesturing) return@LaunchedEffect
        val previous=cache.toMap()
        val needsBuild=keys.any { previous[it.id]?.first != (it to sewingOptions) }
        onRendering(needsBuild)
        try {
            if(needsBuild && previous.isNotEmpty()) delay(300)
            val next=withContext(Dispatchers.Default) {
                objects.zip(keys).associate { (obj,key) ->
                    currentCoroutineContext().ensureActive()
                    val cacheKey=key to sewingOptions
                    obj.id to (cacheKey to (previous[obj.id]?.takeIf { it.first==cacheKey }?.second
                        ?: (obj to RenderData(ShapeStitchGuard.placed(obj).pattern.withSewingDefaults(sewingOptions)).also {
                            it.pattern.bounds
                            if(io.github.linker2k.myembro.BuildConfig.DEBUG) android.util.Log.d("EditorTransform",
                                "render_build id=${obj.id} scale=${obj.transform.scaleX},${obj.transform.scaleY}")
                        })))
                }
            }
            cache.clear();cache.putAll(next)
            renders=objects.map { next.getValue(it.id).second }
        } finally { onRendering(false) }
    }
    return renders
}

/** Measure only settled selections on a worker; never replan stitches in the pointer loop.
 * Keyed state starts empty so labels cannot show an older project's size while awaiting a result.
 */
@Composable
private fun rememberSelectionOutputBounds(project: Project, ids: Set<String>, sewing: SewingOptions,
    gesturing: Boolean): Bounds? {
    var bounds by remember(project, ids, sewing, gesturing) { mutableStateOf<Bounds?>(null) }
    LaunchedEffect(project, ids, sewing, gesturing) {
        if (!gesturing && ids.isNotEmpty()) bounds = withContext(Dispatchers.Default) {
            project.selectionOutputBounds(ids, sewing)
        }
    }
    return bounds
}
