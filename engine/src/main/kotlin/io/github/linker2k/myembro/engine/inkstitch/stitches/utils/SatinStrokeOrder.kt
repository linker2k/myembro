// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitches.utils

import io.github.linker2k.myembro.engine.inkstitch.utils.Point

/** User decision 2026-10-24: complete each character, then y/x of stroke start (+y down).
 * Caller supplies glyph ownership from text layout. No jamo recognition or row-width heuristic.
 * This prepares preserve_order=true input; it is not the auto-route algorithm itself.
 */
object SatinStrokeOrder {
    data class Stroke(val characterIndex:Int,val sourceIndex:Int,val points:List<Point>)
    fun order(strokes:List<Stroke>):List<Stroke> {
        require(strokes.all { it.characterIndex>=0 && it.sourceIndex>=0 && it.points.isNotEmpty() &&
            it.points.all { p -> p.x.isFinite() && p.y.isFinite() } })
        require(strokes.map { it.sourceIndex }.distinct().size==strokes.size)
        return strokes.sortedWith(compareBy<Stroke> { it.characterIndex }
            .thenBy { it.points.first().y }.thenBy { it.points.first().x }.thenBy { it.sourceIndex })
    }
}
