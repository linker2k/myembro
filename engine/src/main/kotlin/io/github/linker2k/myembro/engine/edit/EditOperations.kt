// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.Bounds
import kotlin.math.abs

/** A mirror alone does not change density. Warn on cumulative scale outside 80–120%. */
val DesignObject.densityWarning: Boolean
    get() = abs(transform.scaleX) < 0.8 || abs(transform.scaleX) > 1.2 ||
        abs(transform.scaleY) < 0.8 || abs(transform.scaleY) > 1.2

fun DesignObject.mirrorX(): DesignObject = withTransform(transform.copy(scaleX = -transform.scaleX))
fun DesignObject.mirrorY(): DesignObject = withTransform(transform.copy(scaleY = -transform.scaleY))
fun DesignObject.rotateBy(degrees: Double): DesignObject =
    withTransform(transform.copy(rotationDeg = transform.rotationDeg + degrees))
fun DesignObject.scaleBy(factorX: Double, factorY: Double = factorX): DesignObject =
    withTransform(transform.copy(scaleX = transform.scaleX * factorX, scaleY = transform.scaleY * factorY))

/** Bounds of actual needle-down positions, after scale/rotation/translation; empty = null. */
fun DesignObject.placedBounds(): Bounds? = placedPattern().bounds

internal fun List<Bounds>.unionBounds(): Bounds? = if (isEmpty()) null else Bounds(
    minOf { it.minX }, minOf { it.minY }, maxOf { it.maxX }, maxOf { it.maxY },
)

fun HoopSpec.contains(bounds: Bounds): Boolean =
    bounds.minX >= -width / 2.0 && bounds.maxX <= width / 2.0 &&
        bounds.minY >= -height / 2.0 && bounds.maxY <= height / 2.0

/** Hidden and empty objects do not contribute to export bounds or hoop overflow. */
fun Project.objectsOutsideHoop(): List<DesignObject> = objects.filter {
    it.visible && it.placedBounds()?.let { bounds -> !hoop.contains(bounds) } == true
}
fun Project.exceedsHoop(): Boolean = objectsOutsideHoop().isNotEmpty()

enum class Alignment { LEFT, CENTER_X, RIGHT, TOP, CENTER_Y, BOTTOM }
enum class Axis { HORIZONTAL, VERTICAL }

/** Align selected placed bounds to the selection's outer bounds; sewing order stays intact. */
fun Project.align(ids: Set<String>, alignment: Alignment): Project {
    val selected = objects.filter { it.id in ids }.mapNotNull { obj -> obj.placedBounds()?.let { obj to it } }
    val all = selected.map { it.second }.unionBounds() ?: return this
    var result = this
    for ((obj, b) in selected) {
        val dx = when (alignment) {
            Alignment.LEFT -> all.minX.toDouble() - b.minX
            Alignment.CENTER_X -> (all.minX.toDouble() + all.maxX - b.minX - b.maxX) / 2
            Alignment.RIGHT -> all.maxX.toDouble() - b.maxX
            else -> 0.0
        }
        val dy = when (alignment) {
            Alignment.TOP -> all.minY.toDouble() - b.minY
            Alignment.CENTER_Y -> (all.minY.toDouble() + all.maxY - b.minY - b.maxY) / 2
            Alignment.BOTTOM -> all.maxY.toDouble() - b.maxY
            else -> 0.0
        }
        result = result.replace(obj.withTransform(obj.transform.copy(x = obj.transform.x + dx, y = obj.transform.y + dy)))
    }
    return result
}

/** Equal gaps between bounds. Outer objects stay fixed; negative gaps allow overlap. */
fun Project.distribute(ids: Set<String>, axis: Axis): Project {
    fun Bounds.start(): Int = if (axis == Axis.HORIZONTAL) minX else minY
    fun Bounds.end(): Int = if (axis == Axis.HORIZONTAL) maxX else maxY
    val selected = objects.filter { it.id in ids }.mapNotNull { obj -> obj.placedBounds()?.let { obj to it } }
        .sortedBy { it.second.start() }
    if (selected.size < 3) return this
    val span = selected.last().second.end().toDouble() - selected.first().second.start()
    val widths = selected.sumOf { it.second.end().toDouble() - it.second.start() }
    val gap = (span - widths) / (selected.size - 1)
    var cursor = selected.first().second.start().toDouble()
    var result = this
    selected.forEachIndexed { index, (obj, b) ->
        if (index != 0 && index != selected.lastIndex) {
            val delta = cursor - b.start()
            val t = obj.transform
            result = result.replace(obj.withTransform(if (axis == Axis.HORIZONTAL) t.copy(x = t.x + delta) else t.copy(y = t.y + delta)))
        }
        cursor += b.end().toDouble() - b.start() + gap
    }
    return result
}
