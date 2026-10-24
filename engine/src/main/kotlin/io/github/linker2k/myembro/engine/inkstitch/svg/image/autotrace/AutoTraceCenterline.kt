// SPDX-License-Identifier: GPL-2.0-or-later
// Ported from AutoTrace src/pxl-outline.c@1a232b27818a46a05cd25edec09e2f77c4e9ac01
// (GPL-2.0-or-later), Copyright (c) AutoTrace contributors.
package io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace

/** Upstream pixel paths, before spline fitting. Coordinates are Cartesian (y upwards), in pixels. */
object AutoTraceCenterline {
    data class Point(val x: Int, val y: Int)
    data class Path(val points: List<Point>, val colorRgb: Int, val open: Boolean, val clockwise: Boolean = false)

    /** Input is an already thinned, top-down bitmap. Does not modify the caller's pixels. */
    fun trace(width: Int, height: Int, pixels: ByteArray, planes: Int = 1, backgroundRgb: Int = 0xFFFFFF): List<Path> {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 1_048_576)
        require(planes == 1 || planes == 3)
        require(pixels.size == width * height * planes && backgroundRgb in 0..0xFFFFFF)
        val colors = IntArray(width * height) { i ->
            val p = i * planes
            fun byte(offset: Int) = pixels[p + offset].toInt() and 255
            if (planes == 1) byte(0) * 0x010101 else (byte(0) shl 16) or (byte(1) shl 8) or byte(2)
        }
        return Tracer(width, height, colors).trace(backgroundRgb)
    }

    private class Tracer(val width: Int, val height: Int, val colors: IntArray) {
        // NORTH, NORTHWEST, WEST, SOUTHWEST, SOUTH, SOUTHEAST, EAST, NORTHEAST.
        val dr = intArrayOf(-1,-1,0,1,1,1,0,-1)
        val dc = intArrayOf(0,-1,-1,-1,0,1,1,1)
        val marked = IntArray(colors.size)
        fun inside(r: Int, c: Int) = r in 0 until height && c in 0 until width
        fun color(r: Int, c: Int) = colors[r * width + c]
        fun mark(r: Int, c: Int, d: Int) { marked[r * width + c] = marked[r * width + c] or (1 shl d) }
        fun other(r: Int, c: Int, d: Int) = marked[r * width + c] and (255 - (1 shl d) - (1 shl ((d+4)%8))) != 0
        fun valid(r: Int, c: Int, d: Int): Boolean {
            val nr = r + dr[d]; val nc = c + dc[d]
            if (!inside(nr,nc)) return false
            // Preserve upstream's strict >0 exclusion of the top and left edges.
            return marked[r * width + c] and (1 shl d) == 0 && nr > 0 && nc > 0 && color(r,c) == color(nr,nc)
        }
        fun neighbors(r: Int, c: Int): Int = (0..7).count { d ->
            val nr = r + dr[d]; val nc = c + dc[d]
            inside(nr,nc) && color(nr,nc) == color(r,c)
        }
        fun start(r: Int, c: Int, d: Int): Boolean {
            if (!valid(r,c,d)) return false
            val nr = r + dr[d]; val nc = c + dc[d]
            return !(!valid(nr,nc,d) && neighbors(r,c)>2) && neighbors(r,c)<=4 && neighbors(nr,nc)<=4 &&
                !(other(r,c,d) && other(nr,nc,d))
        }
        fun one(originalRow: Int, originalCol: Int, originalDir: Int): Path {
            var r = originalRow; var c = originalCol; var dir = originalDir
            val points = mutableListOf(Point(c,height-r-1))
            var open = false
            while (true) {
                val pr = r; val pc = c
                // Exact original search order, excluding the reverse direction.
                val next = intArrayOf(0,2,6,1,7,3,5).map { (dir+it)%8 }.firstOrNull { valid(r,c,it) }
                if (next == null) { open = true; break }
                dir = next
                r += dr[dir]; c += dc[dir]
                // The C function updates row/col even when the final other-dir guard rejects it.
                if (other(pr,pc,dir) && other(r,c,dir)) { open = true; break }
                if (pr != originalRow || pc != originalCol) mark(pr,pc,dir)
                mark(r,c,(dir+4)%8)
                if (r == originalRow && c == originalCol) break
                points.add(Point(c,height-r-1))
            }
            mark(originalRow,originalCol,originalDir)
            return Path(points,color(originalRow,originalCol),open)
        }
        fun trace(background: Int): List<Path> {
            val paths = mutableListOf<Path>()
            for (r in 0 until height) {
                var c = 0
                while (c < width) {
                    if (color(r,c) == background) { c++; continue }
                    val dir = intArrayOf(6,5,4,3).firstOrNull { start(r,c,it) }
                    if (dir == null) { c++; continue }
                    var path = one(r,c,dir)
                    if (path.open) {
                        val alternatives = when (dir) {
                            6 -> intArrayOf(4,3,5)
                            5 -> intArrayOf(3,6,4)
                            4 -> intArrayOf(6,5,3)
                            else -> intArrayOf(5,6,4)
                        }
                        val opposite = alternatives.firstOrNull { valid(r,c,it) }
                        if (opposite != null) {
                            val partial = one(r,c,opposite)
                            if (partial.points.size > 1) path = path.copy(points = partial.points.drop(1).reversed() + path.points)
                        } else c++
                    }
                    if (path.points.size > 1) paths.add(path)
                }
            }
            return paths
        }
    }
}
