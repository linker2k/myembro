// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2010 Ink/Stitch Authors (see upstream git history); Kotlin changes: myembro contributors.
// Ported from Ink/Stitch lib/stitch_plan/lock_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// lib/stitch_plan/lock_stitch.py: RelativeLock.stitches/get_direction_and_length,
// AbsoluteLock.stitches, half_stitch, back_forth. No original code is executed at runtime.
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.geometry.Point
import kotlin.math.*

enum class LockKind { HALF_STITCH, BACK_FORTH, COMPACT, ARROW, BOWTIE, CROSS, STAR, SIMPLE, TRIANGLE, ZIGZAG }

/** Input/output mm. COMPACT is upstream's custom AbsoluteLock path "1 -1", app default 0.4mm. */
object InkLock {
    // Original SVG endpoint coordinates, in mm, from lock_stitch.py LOCK_DEFAULTS.
    private val svgDefinitions = mapOf(
        LockKind.ARROW to listOf(Point(0.5,0.3),Point(0.3,1.31),Point(-0.11,0.68),Point(0.9,0.68),Point(0.5,1.31),Point(0.4,0.31),Point(0.4,0.31),Point(0.4,1.3)),
        LockKind.BOWTIE to listOf(Point(0.0,0.0),Point(-0.39,0.97),Point(0.3,0.03),Point(0.14,1.02),Point(0.0,0.0),Point(0.0,0.15)),
        LockKind.CROSS to listOf(Point(0.0,0.0),Point(-0.7,-0.7),Point(0.7,0.7),Point(0.0,0.0),Point(-0.7,0.7),Point(0.7,-0.7),Point(0.0,0.0),Point(0.0,-0.7)),
        LockKind.STAR to listOf(Point(0.67,-0.2),Point(-0.67,0.27),Point(0.57,0.33),Point(-0.5,-0.27),Point(0.0,0.67),Point(0.0,0.0),Point(0.0,-0.5)),
        LockKind.SIMPLE to listOf(Point(-0.03,0.0),Point(0.09,0.81),Point(0.0,1.49),Point(0.0,0.0),Point(0.0,0.48)),
        LockKind.TRIANGLE to listOf(Point(-0.26,0.33),Point(0.55,0.33),Point(0.0,0.84),Point(0.0,0.0),Point(0.34,0.82)),
        LockKind.ZIGZAG to listOf(Point(-0.25,0.2),Point(0.17,0.77),Point(-0.22,1.45),Point(0.21,2.05),Point(-0.03,3.0),Point(0.0,0.0)),
    )
    fun points(path: List<Point>, end: Boolean = false, kind: LockKind = LockKind.COMPACT,
        absoluteMm: Double = 0.4): List<Point> {
        require(absoluteMm.isFinite() && absoluteMm in 0.3..0.5)
        val points = (if (end) path.asReversed() else path).filterIndexed { i, p ->
            val input = if (end) path.asReversed() else path
            i == 0 || p != input[i - 1]
        }
        if (points.size < 2) return emptyList()
        if (kind in svgDefinitions) {
            // SVGLock.stitches: end points of the pinned SVG paths, final two points indicate direction.
            // Cubic control points are not stitch points in Inkex Path.end_points (STAR).
            val svg = svgDefinitions.getValue(kind)
            val origin = svg[svg.lastIndex - 1]; val indicator = svg.last() - origin
            val direction = points[1] - points[0]
            val angle = atan2(direction.y,direction.x) - atan2(indicator.y,indicator.x)
            val result = svg.dropLast(2).map { p ->
                val v = p - origin
                points.first() + Point(v.x*cos(angle)-v.y*sin(angle),v.x*sin(angle)+v.y*cos(angle))
            }
            return if (end) result.asReversed() else result
        }
        if (kind == LockKind.HALF_STITCH) {
            val target = points.drop(1).take(15).firstOrNull { it.distance(points.first()) > 0.5 } ?: points[1]
            val v = target - points.first(); if (v.norm() < 1e-9) return emptyList()
            val length = v.norm().coerceIn(0.5, 1.5)
            val fractions = if (end) listOf(0.5, 1.0, 0.5, 0.0) else listOf(0.0, 0.5, 1.0, 0.5, 0.0)
            return fractions.map { points.first() + v * (length * it / v.norm()) }
        }
        val deltas = if (kind == LockKind.BACK_FORTH) listOf(1.0, 1.0, -1.0, -1.0) else listOf(1.0, -1.0)
        val positions = arrayListOf<Double>()
        var position = 0.0
        if (end) for (delta in deltas) { position += delta * absoluteMm; positions += position }
        else for (delta in deltas.asReversed()) { position -= delta * absoluteMm; positions.add(0, position) }
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + points[i - 1].distance(points[i])
        return positions.map { offset ->
            val d = offset.coerceIn(0.0, cumulative.last())
            val found = cumulative.binarySearch(d)
            if (found >= 0) points[found] else {
                val i = (-found - 1).coerceIn(1, points.lastIndex)
                points[i - 1].interpolate(points[i], (d - cumulative[i - 1]) / (cumulative[i] - cumulative[i - 1]))
            }
        }
    }
}
