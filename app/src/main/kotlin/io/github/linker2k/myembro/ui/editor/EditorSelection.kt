// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.Bounds
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal fun Project.selectionBounds(ids: Set<String>): Bounds? {
    val bounds = objects.filter { it.visible && it.id in ids }.mapNotNull { it.placedBounds() }
    if (bounds.isEmpty()) return null
    return Bounds(bounds.minOf { it.minX }, bounds.minOf { it.minY }, bounds.maxOf { it.maxX }, bounds.maxOf { it.maxY })
}

/** Scale and rotate about the selection center, preserving sewing order and editable types. */
internal fun Project.transformGroup(ids: Set<String>, dx: Double, dy: Double, factor: Double, degrees: Double): Project {
    if (ids.isEmpty()) return this
    if (factor == 1.0 && degrees == 0.0) {
        if (dx == 0.0 && dy == 0.0) return this
        return copy(objects = objects.map { obj ->
            if (obj.id !in ids || !obj.visible) obj else obj.withTransform(obj.transform.copy(
                x = (obj.transform.x + dx).coerceIn(-100000.0, 100000.0),
                y = (obj.transform.y + dy).coerceIn(-100000.0, 100000.0)))
        })
    }
    val bounds = selectionBounds(ids) ?: return this
    val cx = (bounds.minX.toDouble() + bounds.maxX) / 2
    val cy = (bounds.minY.toDouble() + bounds.maxY) / 2
    val selected = objects.filter { it.id in ids && it.visible }
    val low = selected.maxOf { 0.05 / minOf(abs(it.transform.scaleX), abs(it.transform.scaleY)) }
    val high = selected.minOf { 20.0 / maxOf(abs(it.transform.scaleX), abs(it.transform.scaleY)) }
    val scale = if (factor == 1.0 || !factor.isFinite() || factor <= 0 || low > high) 1.0 else factor.coerceIn(low, high)
    val radians = Math.toRadians(degrees)
    return copy(objects = objects.map { obj ->
        if (obj.id !in ids || !obj.visible) obj else {
            val t = obj.transform
            val x = (t.x - cx) * scale
            val y = (t.y - cy) * scale
            obj.withTransform(t.copy(
                x = (cx + x * cos(radians) - y * sin(radians) + dx).coerceIn(-100000.0, 100000.0),
                y = (cy + x * sin(radians) + y * cos(radians) + dy).coerceIn(-100000.0, 100000.0),
                rotationDeg = (t.rotationDeg + degrees) % 360,
                scaleX = t.scaleX * scale, scaleY = t.scaleY * scale))
        }
    })
}

internal data class SnapTargets(val xs: List<Double>, val ys: List<Double>)
internal data class SnapGuides(val x: Double? = null, val y: Double? = null)
internal data class SnappedSelection(val project: Project, val guides: SnapGuides)

internal fun Project.snapTargets(ids: Set<String>): SnapTargets {
    val others = objects.filter { it.visible && it.id !in ids }.mapNotNull { it.placedBounds() }
    return SnapTargets(listOf(0.0) + others.flatMap { listOf(it.minX.toDouble(), it.maxX.toDouble(), (it.minX.toDouble() + it.maxX) / 2) },
        listOf(0.0) + others.flatMap { listOf(it.minY.toDouble(), it.maxY.toDouble(), (it.minY.toDouble() + it.maxY) / 2) })
}

/** Use the unsnapped draft so a series of small motions can escape a magnet. */
internal fun Project.snapSelection(ids: Set<String>, targets: SnapTargets, tolerance: Double): SnappedSelection {
    val b = selectionBounds(ids) ?: return SnappedSelection(this, SnapGuides())
    val area = hoop.sewingArea
    val edge = area.snapBounds
    fun nearest(anchors: List<Double>, lines: List<Double>, min: Int, max: Int,
        innerMin: Int, innerMax: Int, fits: (Double, Double) -> Boolean): Pair<Double, Double>? {
        // Hoop boundaries match the same-side edge only; matching the opposite edge would
        // put the entire design outside. Object/center magnets must also leave it inside.
        val candidates = listOf((innerMin.toDouble() - min) to innerMin.toDouble(),
            (innerMax.toDouble() - max) to innerMax.toDouble()) +
            lines.flatMap { line -> anchors.map { anchor -> (line - anchor) to line } }
        return candidates.filter { abs(it.first) <= tolerance && fits(min + it.first, max + it.first) }
            .minByOrNull { abs(it.first) }
    }
    val x = nearest(listOf((b.minX.toDouble() + b.maxX) / 2, b.minX.toDouble(), b.maxX.toDouble()),
        targets.xs, b.minX, b.maxX, edge.minX, edge.maxX, area::containsX)
    val y = nearest(listOf((b.minY.toDouble() + b.maxY) / 2, b.minY.toDouble(), b.maxY.toDouble()),
        targets.ys, b.minY, b.maxY, edge.minY, edge.maxY, area::containsY)
    fun translated(dx: Double, dy: Double) = copy(objects = objects.map { obj ->
        if (obj.id !in ids || !obj.visible) obj else obj.withTransform(obj.transform.copy(
            x = obj.transform.x + dx, y = obj.transform.y + dy))
    })
    val dx = x?.first ?: 0.0
    val dy = y?.first ?: 0.0
    val placed = translated(dx, dy)
    val rounded = placed.selectionBounds(ids) ?: return SnappedSelection(placed, SnapGuides())
    // A half-unit object-center snap may round again on placement. Correct only snapped
    // axes inward; a free drag outside must remain outside and warn after pointer-up.
    fun inward(min: Int, max: Int, low: Int, high: Int): Double = when {
        max.toLong() - min > high.toLong() - low -> 0.0
        min < low -> (low.toDouble() - min)
        max > high -> (high.toDouble() - max)
        else -> 0.0
    }
    val cx = if (x != null) inward(rounded.minX, rounded.maxX, edge.minX, edge.maxX) else 0.0
    val cy = if (y != null) inward(rounded.minY, rounded.maxY, edge.minY, edge.maxY) else 0.0
    val gx = if (cx == 0.0) x?.second else if (cx > 0) edge.minX.toDouble() else edge.maxX.toDouble()
    val gy = if (cy == 0.0) y?.second else if (cy > 0) edge.minY.toDouble() else edge.maxY.toDouble()
    return SnappedSelection(if (cx == 0.0 && cy == 0.0) placed else translated(dx + cx, dy + cy), SnapGuides(gx, gy))
}

/** UI uses millimeters and positive percentages; mirror signs are retained separately. */
internal fun parseEditorTransform(x: String, y: String, rotation: String, sx: String, sy: String, original: Transform): Transform? {
    fun String.number() = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    val px = x.number()?.takeIf { it in -10000.0..10000.0 } ?: return null
    val py = y.number()?.takeIf { it in -10000.0..10000.0 } ?: return null
    val r = rotation.number() ?: return null
    val scaleX = sx.number()?.takeIf { it in 5.0..2000.0 } ?: return null
    val scaleY = sy.number()?.takeIf { it in 5.0..2000.0 } ?: return null
    return Transform(px * 10, py * 10, r % 360, scaleX / 100 * if (original.scaleX < 0) -1 else 1,
        scaleY / 100 * if (original.scaleY < 0) -1 else 1)
}
