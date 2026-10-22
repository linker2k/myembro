// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.digitizing

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.model.*
import kotlin.math.*

/** Lengths use engine units (0.1 mm); default running stitch is 2.5 mm. */
data class RunningSettings(val stitchLength: Double = 25.0, val cornerAngleDegrees: Double = 45.0) {
    init { require(stitchLength.isFinite() && stitchLength > 0 && cornerAngleDegrees.isFinite() && cornerAngleDegrees in 0.0..180.0) }
}

object RunningStitch {
    /** Arc-length sampling, anchored at endpoints and corners turning by at least the threshold. */
    fun sample(path: Polyline, settings: RunningSettings = RunningSettings()): Polyline {
        val source = if (path.closed) path.closedPoints() else path.points
        val points = source.filterIndexed { i, p -> i == 0 || p != source[i-1] }
        require(points.size <= 200_000)
        if (points.size < 2) return Polyline(points, path.closed)
        val anchors = mutableListOf(0)
        for (i in 1 until points.lastIndex) {
            val a = points[i-1]; val b = points[i]; val c = points[i+1]
            val cosine = ((b.x-a.x)*(c.x-b.x)+(b.y-a.y)*(c.y-b.y))/(a.distance(b)*b.distance(c))
            if (Math.toDegrees(acos(cosine.coerceIn(-1.0, 1.0))) + 1e-9 >= settings.cornerAngleDegrees) anchors += i
        }
        anchors += points.lastIndex
        val result = mutableListOf(points.first())
        for ((start, end) in anchors.zipWithNext()) {
            val distances = DoubleArray(end-start+1)
            for (i in 1 until distances.size) distances[i] = distances[i-1] + points[start+i-1].distance(points[start+i])
            val length = distances.last()
            val count = ceil(length/settings.stitchLength)
            require(count <= 1_000_000 - result.size) { "Running stitch exceeds 1000000 points" }
            var segment = 1
            for (step in 1..max(1, count.toInt())) {
                val distance = length*step/max(1, count.toInt())
                while (segment < distances.lastIndex && distances[segment] < distance) segment++
                val fraction = (distance-distances[segment-1])/(distances[segment]-distances[segment-1])
                result += points[start+segment-1].interpolate(points[start+segment], fraction.coerceIn(0.0, 1.0))
            }
        }
        return Polyline(result, path.closed)
    }

    fun generate(paths: List<Polyline>, settings: RunningSettings = RunningSettings(),
        thread: EmbThread = EmbPattern.DEFAULT_PALETTE.first()): EmbPattern {
        val builder = StitchRuns(thread)
        paths.forEach { builder.add(sample(it, settings).points) }
        return builder.finish(mapOf("name" to "Running stitch"))
    }
}

/** Quantize only at the machine boundary. Never sew across a disconnected path. */
internal class StitchRuns(private val thread: EmbThread) {
    private val stitches = mutableListOf<Stitch>()
    fun add(points: List<Point>, connect: Boolean = false) {
        if (points.isEmpty()) return
        val quantized = points.map { p ->
            require(p.x in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() && p.y in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
            Stitch(p.x.roundToInt(), p.y.roundToInt(), Command.STITCH)
        }.filterIndexed { i, s -> i == 0 || s.x != points[i-1].x.roundToInt() || s.y != points[i-1].y.roundToInt() }
        val first = quantized.first()
        val last = stitches.lastOrNull()
        if (last == null || !connect) {
            if (last != null) stitches += last.copy(command = Command.TRIM)
            stitches += first.copy(command = Command.JUMP)
            stitches += first
        } else if (last.x != first.x || last.y != first.y) stitches += first
        stitches += quantized.drop(1)
        require(stitches.size <= 1_000_000) { "Digitizing exceeds 1000000 machine commands" }
    }
    fun finish(metadata: Map<String, String>): EmbPattern {
        val last = stitches.lastOrNull()
        return EmbPattern(stitches + Stitch(last?.x ?: 0, last?.y ?: 0, Command.END),
            if (last == null) emptyList() else listOf(thread), metadata)
    }
}
