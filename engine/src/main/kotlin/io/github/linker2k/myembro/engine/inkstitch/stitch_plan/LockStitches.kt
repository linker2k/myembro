// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/lock_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.svg.PathEndPoints
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** SVG attribute spellings and units match elements/element.py. */
data class LockStitchParameters(
    val ties: Int = 0,
    val force_lock_stitches: Boolean = false,
    val lock_start: String = "half_stitch",
    val lock_custom_start: String = "",
    val lock_start_scale_mm: Double = 0.7,
    val lock_start_scale_percent: Double = 100.0,
    val lock_end: String = "half_stitch",
    val lock_custom_end: String = "",
    val lock_end_scale_mm: Double = 0.7,
    val lock_end_scale_percent: Double = 100.0,
) {
    companion object {
        fun fromAttributes(attributes: Map<String, String>): LockStitchParameters {
            fun raw(key: String) = attributes["{http://inkstitch.org/namespace}$key"]
                ?: attributes["inkstitch:$key"] ?: attributes[key]
            return LockStitchParameters(
                ties = raw("ties")?.toIntOrNull() ?: 0,
                force_lock_stitches = raw("force_lock_stitches")?.lowercase() in setOf("true", "t", "yes", "y", "1"),
                lock_start = raw("lock_start") ?: "half_stitch",
                lock_custom_start = raw("lock_custom_start") ?: "",
                lock_start_scale_mm = raw("lock_start_scale_mm")?.toDoubleOrNull() ?: 0.7,
                lock_start_scale_percent = raw("lock_start_scale_percent")?.toDoubleOrNull() ?: 100.0,
                lock_end = raw("lock_end") ?: "half_stitch",
                lock_custom_end = raw("lock_custom_end") ?: "",
                lock_end_scale_mm = raw("lock_end_scale_mm")?.toDoubleOrNull() ?: 0.7,
                lock_end_scale_percent = raw("lock_end_scale_percent")?.toDoubleOrNull() ?: 100.0,
            )
        }
    }
}

data class LockStitchPair(val start: LockStitch?, val end: LockStitch?)

object LockStitches {
    val types: List<String> = listOf("half_stitch", "arrow", "back_forth", "bowtie", "cross", "star", "simple", "triangle", "zigzag", "custom")

    fun fromParameters(parameters: LockStitchParameters): LockStitchPair = with(parameters) {
        LockStitchPair(
            if (ties == 0 || ties == 1) InkLockStitch(lock_start, lock_start_scale_percent, lock_start_scale_mm, lock_custom_start) else null,
            if (ties == 0 || ties == 2 || force_lock_stitches) InkLockStitch(lock_end, lock_end_scale_percent, lock_end_scale_mm, lock_custom_end) else null,
        )
    }
}

/** Coordinates are SVG px; the public absolute scale remains in mm, just like the SVG attribute. */
class InkLockStitch(
    val lock_id: String = "half_stitch",
    val scale_percent: Double = 100.0,
    val scale_mm: Double = 0.7,
    val custom_path: String = "",
) : LockStitch {
    init { require(scale_percent.isFinite() && scale_mm.isFinite()) { "Non-finite lock scale" } }

    override fun stitches(stitches: List<Stitch>, position: LockPosition): List<Stitch> {
        // The source StitchGroup makes this check before invoking a lock definition.
        if (stitches.size < 2) return emptyList()
        val input = if (position == LockPosition.END) stitches.asReversed() else stitches
        return when (lock_id) {
            "back_forth" -> absolute(input, position, listOf(1.0, 1.0, -1.0, -1.0))
            "custom" -> custom(input, position)
            in svgPaths -> svg(input, position, PathEndPoints.parse(svgPaths.getValue(lock_id)))
            else -> relative(input, position)
        }
    }

    private fun custom(input: List<Stitch>, position: LockPosition): List<Stitch> {
        if (custom_path.isEmpty()) return relative(input, position)
        if (numericPath.matches(custom_path)) {
            val path = custom_path.split(" ").mapNotNull { it.toDoubleOrNull()?.takeIf(Double::isFinite) }
            return if (path.isEmpty()) relative(input, position) else absolute(input, position, path)
        }
        val path = PathEndPoints.parse(custom_path)
        return if (path.size < 3) relative(input, position) else svg(input, position, path)
    }

    private fun relative(input: List<Stitch>, position: LockPosition): List<Stitch> {
        val origin = input[0].point
        val candidate = input.drop(1).take(15).map { it.point - origin }.firstOrNull { it.length() > Units.mmToPixels(0.5) }
        // Source fallback is 0.5 px, deliberately not 0.5 mm.
        val direction = candidate?.unit() ?: Point(1.0, 0.0)
        val length = candidate?.length()?.coerceIn(Units.mmToPixels(0.5), Units.mmToPixels(1.5)) ?: 0.5
        val path = if (position == LockPosition.END) listOf(0.5, 1.0, 0.5, 0.0) else listOf(0.0, 0.5, 1.0, 0.5, 0.0)
        return path.map { Stitch(origin + direction * (it * length), tags = lockTag) }
    }

    private fun absolute(input: List<Stitch>, position: LockPosition, path: List<Double>): List<Stitch> {
        val scale = Units.mmToPixels(scale_mm)
        var accumulated = 0.0
        val positions = if (position == LockPosition.START) {
            path.asReversed().map { accumulated -= it * scale; accumulated }.asReversed()
        } else path.map { accumulated += it * scale; accumulated }
        val maxLength = positions.maxOrNull()!!
        val upcoming = mutableListOf(input.first().point)
        for (stitch in input.drop(1)) {
            val distance = stitch.point.distance(upcoming.last())
            upcoming += stitch.point
            // Source tests this segment, rather than accumulated polyline length.
            if (distance >= maxLength) break
        }
        return positions.map { distance ->
            if (distance < 0.0) {
                // Preserve the source's untagged negative-distance branch.
                Stitch(input[0].point + (input[1].point - input[0].point).unit() * distance)
            } else Stitch(interpolate(upcoming, distance), tags = lockTag)
        }
    }

    private fun svg(input: List<Stitch>, position: LockPosition, points: List<Point>): List<Stitch> {
        val scaled = points.map { it * (Units.PIXELS_PER_MM * scale_percent / 100.0) }
        val anchor = scaled[scaled.size - 2]
        val lockDirection = scaled.last() - anchor
        val stitchDirection = input[1].point - input[0].point
        val angle = atan2(stitchDirection.y, stitchDirection.x) - atan2(lockDirection.y, lockDirection.x)
        val cosine = cos(angle)
        val sine = sin(angle)
        val result = scaled.dropLast(2).map {
            val delta = it - anchor
            Stitch(input[0].point + Point(delta.x * cosine - delta.y * sine, delta.x * sine + delta.y * cosine), tags = lockTag)
        }
        return if (position == LockPosition.END) result.asReversed() else result
    }

    private fun interpolate(points: List<Point>, distance: Double): Point {
        var remaining = distance
        for ((start, end) in points.zipWithNext()) {
            val length = start.distance(end)
            if (length > 0.0 && remaining <= length) return start + (end - start) * (remaining / length)
            remaining -= length
        }
        return points.last()
    }

    companion object {
        private val lockTag = setOf("lock_stitch")
        private val numericPath = Regex(" *[0-9 .,-]*")
        private val svgPaths = mapOf(
            "arrow" to "M 0.5,0.3 0.3,1.31 -0.11,0.68 H 0.9 L 0.5,1.31 0.4,0.31 V 0.31 1.3",
            "bowtie" to "M 0,0 -0.39,0.97 0.3,0.03 0.14,1.02 0,0 V 0.15",
            "cross" to "M 0,0 -0.7,-0.7 0.7,0.7 0,0 -0.7,0.7 0.7,-0.7 0,0 -0,-0.7",
            "star" to "M 0.67,-0.2 C 0.27,-0.06 -0.22,0.11 -0.67,0.27 L 0.57,0.33 -0.5,-0.27 0,0.67 V 0 -0.5",
            "simple" to "M -0.03,0 0.09,0.81 0,1.49 V 0 0.48",
            "triangle" to "M -0.26,0.33 H 0.55 L 0,0.84 V 0 L 0.34,0.82",
            "zigzag" to "M -0.25,0.2 0.17,0.77 -0.22,1.45 0.21,2.05 -0.03,3 0,0",
        )
    }
}
