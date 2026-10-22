// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.Stitch

/**
 * A pattern flattened into line segments for Canvas.drawLines. Segments are ordered by sewing
 * order, so "sewn up to stitch N" is always a prefix of each color run.
 */
class RenderData(val pattern: EmbPattern) {
    /** x0,y0,x1,y1 per sewn segment, in 0.1 mm. */
    val stitchLines: FloatArray

    /** Index into [EmbPattern.stitches] of each segment's end point. */
    val stitchLineEnds: IntArray

    /** Color runs as [first segment, last segment) with their ARGB color. */
    val runs: List<Run>

    /** Jump (needle-up travel) segments, drawn faintly when enabled. */
    val jumpLines: FloatArray
    val jumpLineEnds: IntArray

    class Run(val color: Int, val start: Int, val end: Int)

    init {
        val stitches = pattern.stitches
        val lines = FloatArrayBuilder()
        val ends = IntArrayBuilder()
        val jumps = FloatArrayBuilder()
        val jumpEnds = IntArrayBuilder()
        val runList = mutableListOf<Run>()
        var block = 0
        var runStart = 0
        // Like pyembroidery's stitch blocks: thread is drawn only between consecutive needle
        // penetrations, so the first STITCH after a jump, trim or color change starts a new path.
        var prev: Stitch? = null
        for ((i, b) in stitches.withIndex()) {
            val a = prev
            when (b.command) {
                Command.STITCH -> if (a != null && a.command == Command.STITCH) {
                    lines.add(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
                    ends.add(i)
                }
                Command.JUMP -> if (a != null && (a.x != b.x || a.y != b.y)) {
                    jumps.add(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
                    jumpEnds.add(i)
                }
                Command.COLOR_CHANGE -> {
                    if (ends.size > runStart) runList += Run(pattern.threadAt(block).color, runStart, ends.size)
                    runStart = ends.size
                    block++
                }
                else -> {}
            }
            prev = b
        }
        if (ends.size > runStart) runList += Run(pattern.threadAt(block).color, runStart, ends.size)
        stitchLines = lines.toArray()
        stitchLineEnds = ends.toArray()
        jumpLines = jumps.toArray()
        jumpLineEnds = jumpEnds.toArray()
        runs = runList
    }

    /** Number of segments in [ends] whose end stitch index is <= [progress]. */
    fun segmentsUpTo(ends: IntArray, progress: Int): Int {
        var lo = 0
        var hi = ends.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ends[mid] <= progress) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(1024)
        var size = 0
        fun add(a: Float, b: Float, c: Float, d: Float) {
            if (size + 4 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = a; data[size++] = b; data[size++] = c; data[size++] = d
        }
        fun toArray() = data.copyOf(size)
    }

    private class IntArrayBuilder {
        private var data = IntArray(256)
        var size = 0
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }
}

/** Common Brother hoop sizes in mm (width x height). */
enum class Hoop(val label: String, val widthMm: Int, val heightMm: Int) {
    H100("100×100", 100, 100),
    SA431("SA431 60×20", 60, 20),
    SA434("SA434 100×170", 100, 170),
    H130("130×180", 130, 180),
    H160("160×260", 160, 260),
    H200("200×200", 200, 200),
    H200x300("200×300", 200, 300);

    fun fits(widthTenthMm: Int, heightTenthMm: Int): Boolean =
        widthTenthMm <= widthMm * 10 && heightTenthMm <= heightMm * 10
}
