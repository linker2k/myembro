// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from DrawingBotV3 pfm/helpers/{SimpleLineSampler,LuminanceTestLine,LuminanceTest,BresenhamHelper}.java
// @0e7c900638def19b9a8d16445099f3f1129aee52 (GPL-3.0), Copyright DrawingBotV3 contributors.
// Bresenham line/circle primitives credited upstream to Alois Zingl; MIT, Copyright (c) 2020 zingl.
// License verified at https://github.com/zingl/Bresenham/blob/master/LICENSE (2026-10-27).
// Full MIT permission/warranty notice: resources/licenses/thread-texture/ZINGL-MIT.txt.
package io.github.linker2k.myembro.engine.texture

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Integer processing-pixel coordinate. This is not a needle penetration. */
data class PixelPoint(val x: Int, val y: Int)
internal object SketchBresenham {
    fun line(start: PixelPoint, end: PixelPoint, visit: (Int, Int) -> Unit) {
        var x = start.x; var y = start.y
        val dx = abs(end.x - x); val sx = if (x < end.x) 1 else -1
        val dy = -abs(end.y - y); val sy = if (y < end.y) 1 else -1
        var error = dx + dy
        while (true) {
            visit(x, y)
            val twice = 2 * error
            if (twice >= dy) { if (x == end.x) break; error += dy; x += sx }
            if (twice <= dx) { if (y == end.y) break; error += dx; y += sy }
        }
    }
    fun circle(cx: Int, cy: Int, radius: Int, visit: (Int, Int) -> Unit) {
        var x = -radius; var y = 0; var error = 2 - 2 * radius
        do {
            visit(cx - x, cy + y); visit(cx - y, cy - x)
            visit(cx + x, cy - y); visit(cx + y, cy + x)
            val r = error
            if (r <= y) { y++; error += y * 2 + 1 }
            if (r > x || error > y) { x++; error += x * 2 + 1 }
        } while (x < 0)
    }
    fun circleSize(radius: Int): Int {
        if (radius >= 1024) return Int.MAX_VALUE
        var size = 0
        circle(0, 0, radius) { _, _ -> size++ }
        return size
    }
}

internal data class LineSearchResult(val destination: PixelPoint, val average: Float)
internal class SketchLineSampler(private val raster: SketchRaster, private val checkpoint: () -> Unit = {}) {
    /** One shared winner across all prefixes/directions. Strict '<' preserves upstream tie order. */
    fun find(start: PixelPoint, minLength: Int, maxLength: Int, tests: Int,
             startAngle: Float, deltaAngle: Float, shading: Boolean): LineSearchResult? {
        var best = -1f
        var destination = PixelPoint(0, 0)
        fun candidate(x: Int, y: Int) {
            checkpoint()
            var sum = axisPenalty(start, PixelPoint(x, y))
            var count = 0
            var last = PixelPoint(-1, -1)
            SketchBresenham.line(start, PixelPoint(x, y)) { px, py ->
                if (!raster.drawable(px, py)) {
                    if (best == -1f && count > minLength) { destination = last; best = sum.toFloat() / count }
                } else {
                    sum += raster.luminance(px, py); count++; last = PixelPoint(px, py)
                    val sample = sum.toFloat() / count
                    if ((best == -1f || sample < best) && count > minLength) {
                        destination = last; best = sample
                    }
                }
            }
        }
        if (deltaAngle == 360f && !shading && (tests == -1 || SketchBresenham.circleSize(maxLength) <= tests)) {
            SketchBresenham.circle(start.x, start.y, maxLength, ::candidate)
        } else {
            val delta = if (shading) deltaAngle else deltaAngle / tests.toFloat()
            repeat(if (shading) 2 else tests) { d ->
                val angle = Math.toRadians((delta * d + startAngle).toDouble())
                candidate((cos(angle) * maxLength).toInt() + start.x, (sin(angle) * maxLength).toInt() + start.y)
            }
        }
        return if (best == -1f) null else LineSearchResult(destination, best)
    }
    companion object {
        fun axisPenalty(start: PixelPoint, end: PixelPoint) = if (start.x == end.x || start.y == end.y) 1 else 0
    }
}
