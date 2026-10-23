// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/satin_column.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import kotlin.math.abs

/** Embroidery parameters of one SVG element: the `inkstitch:*` attributes, unparsed. */
class InkStitchParams(private val values: Map<String, String>) {
    operator fun get(name: String): String? = values[name]?.trim()?.takeIf { it.isNotEmpty() }
    fun bool(name: String, default: Boolean): Boolean = when (get(name)?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> default
    }
    /** First value of a number that Ink/Stitch also allows as "a b" (one per side). */
    fun number(name: String): Double? = values(name).firstOrNull()
    fun values(name: String): List<Double> = get(name)?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }
        ?.filter { it.isFinite() }.orEmpty()
    fun mean(name: String): Double? = values(name).takeIf { it.isNotEmpty() }?.average()
    val names: Set<String> get() = values.keys
    fun plus(name: String, value: String): InkStitchParams = InkStitchParams(values + (name to value))
}

/** One embroidered element, in SVG user units (px after the document's viewBox), +y down. */
sealed interface InkStitchElement {
    val color: Int
    val params: InkStitchParams
    /** Source node id, for diagnostics. */
    val id: String?
}

/**
 * Satin column as Ink/Stitch's SatinColumn sees its path: the two [rails] in subpath order (not yet
 * swapped or reversed), the explicit [rungs], and each rail's own nodes ([railNodes], for the implied
 * rungs of a two-path column). A single subpath is a centre line of [strokeWidth] px.
 */
data class InkSatin(
    val rails: List<List<Point>>,
    val rungs: List<List<Point>>,
    val railNodes: List<List<Point>>,
    val strokeWidth: Double,
    override val color: Int,
    override val params: InkStitchParams,
    override val id: String? = null,
) : InkStitchElement

/** Fill region given by its subpaths and SVG fill rule. */
data class InkFill(
    val paths: List<Polyline>,
    val rule: FillRule,
    override val color: Int,
    override val params: InkStitchParams,
    override val id: String? = null,
) : InkStitchElement

/** Running stitch along every subpath. */
data class InkRun(
    val paths: List<Polyline>,
    override val color: Int,
    override val params: InkStitchParams,
    override val id: String? = null,
) : InkStitchElement

/**
 * SatinColumn.filtered_subpaths / rail_indices / rungs: which subpaths of a satin path are the rails
 * and which the rungs. Two subpaths are both rails; with more, rails are the paths crossing the
 * others as a ladder's sides do (the two longest when ambiguous).
 */
internal object InkSatinRails {
    fun select(paths: List<Polyline>, nodes: List<Polyline>, strokeWidth: Double, params: InkStitchParams, color: Int, id: String?): InkSatin? {
        val usable = paths.indices.filter { paths[it].points.size > 1 }
        if (usable.isEmpty()) return null
        val lines = usable.map { paths[it].points }
        if (lines.size == 1) return InkSatin(lines, emptyList(), emptyList(), strokeWidth, color, params, id)
        val railIndices = railIndices(lines)
        val rungs = lines.indices.filter { it !in railIndices }.map { lines[it] }
        val railNodes = railIndices.map { nodes.getOrNull(usable[it])?.points.orEmpty() }
        return InkSatin(railIndices.map { lines[it] }, rungs, railNodes, strokeWidth, color, params, id)
    }

    private fun railIndices(lines: List<List<Point>>): List<Int> {
        if (lines.size <= 2) return lines.indices.toList()
        val counts = lines.indices.map { i -> lines.indices.count { j -> j != i && intersects(lines[i], lines[j]) } }
        val candidates = lines.indices.filter { i ->
            (if (lines.size == 3) counts[i] == 1 else counts[i] > 2) && length(lines[i]) > 0.1
        }
        return if (candidates.size == 2) candidates else lines.indices.sortedByDescending { length(lines[it]) }.take(2).sorted()
    }

    private fun length(p: List<Point>) = p.zipWithNext().sumOf { (a, b) -> a.distance(b) }

    private fun intersects(a: List<Point>, b: List<Point>): Boolean {
        for ((c, d) in b.zipWithNext()) for ((p, q) in a.zipWithNext()) {
            val rx = q.x - p.x; val ry = q.y - p.y; val sx = d.x - c.x; val sy = d.y - c.y
            val den = rx * sy - ry * sx
            if (abs(den) < 1e-12) continue
            val t = ((c.x - p.x) * sy - (c.y - p.y) * sx) / den
            val u = ((c.x - p.x) * ry - (c.y - p.y) * rx) / den
            if (t in 0.0..1.0 && u in 0.0..1.0) return true
        }
        return false
    }
}
