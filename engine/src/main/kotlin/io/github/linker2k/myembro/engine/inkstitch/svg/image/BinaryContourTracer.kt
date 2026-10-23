// SPDX-License-Identifier: GPL-2.0-or-later
// Ported from Potrace src/decompose.c, src/bitmap.h@886392d351417d6753aff61de08e294d1506e502
// (GPL-2.0-or-later), Copyright (C) 2001-2019 Peter Selinger.
// Kotlin translation, 2026 myembro contributors. See resources/licenses/potrace/COPYING.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

/** Integer pixel corners in the original tracer's coordinate system: origin bottom-left, y up. */
internal data class PixelPoint(val x: Int, val y: Int) {
    operator fun minus(other: PixelPoint) = PixelPoint(x - other.x, y - other.y)
}

/** Intermediate closed boundary, with no duplicated closing point. Not a finished vector curve. */
internal data class BinaryContour(val points: List<PixelPoint>, val area: Long, val positive: Boolean)

/**
 * Engine-only partial port: findpath, majority, xor_path and bitmap decomposition.
 * Input rows are bottom-up, like potrace_bitmap_t. The caller's mask is never modified.
 * Only the upstream default MINORITY turn policy is exposed. Tree ordering is not yet ported; curve fitting is a separate stage.
 */
internal object BinaryContourTracer {
    fun trace(width: Int, height: Int, pixels: BooleanArray, turdsize: Int = 2): List<BinaryContour> {
        require(width >= 0 && height >= 0 && width.toLong() * height == pixels.size.toLong())
        require(turdsize >= 0)
        val bitmap = Mask(width, height, pixels.copyOf())
        val result = ArrayList<BinaryContour>()
        // Same top-down, left-right pixel search as findnext; Boolean storage needs no word padding.
        for (y in height - 1 downTo 0) {
            for (x in 0 until width) {
                if (!bitmap[x, y]) continue
                val contour = findPath(bitmap, x, y + 1, pixels[y * width + x])
                xorPath(bitmap, contour.points)
                if (contour.area > turdsize) result += contour
            }
        }
        return result
    }

    private class Mask(val width: Int, val height: Int, val pixels: BooleanArray) {
        operator fun get(x: Int, y: Int): Boolean =
            x >= 0 && y >= 0 && x < width && y < height && pixels[y * width + x]
        fun invert(x: Int, y: Int) { pixels[y * width + x] = !pixels[y * width + x] }
    }

    private fun majority(bitmap: Mask, x: Int, y: Int): Boolean {
        for (i in 2 until 5) {
            var count = 0
            fun vote(px: Int, py: Int) { count += if (bitmap[px, py]) 1 else -1 }
            for (a in -i + 1 until i) {
                vote(x + a, y + i - 1)
                vote(x + i - 1, y + a - 1)
                vote(x + a - 1, y - i)
                vote(x - i, y + a)
            }
            if (count > 0) return true
            if (count < 0) return false
        }
        return false
    }

    private fun findPath(bitmap: Mask, x0: Int, y0: Int, positive: Boolean): BinaryContour {
        var x = x0
        var y = y0
        var dx = 0
        var dy = -1
        var area = 0L
        val points = ArrayList<PixelPoint>()
        while (true) {
            points += PixelPoint(x, y)
            x += dx
            y += dy
            area += x.toLong() * dy
            if (x == x0 && y == y0) break
            val c = bitmap[x + (dx + dy - 1) / 2, y + (dy - dx - 1) / 2]
            val d = bitmap[x + (dx - dy - 1) / 2, y + (dy + dx - 1) / 2]
            val right = if (c && !d) !majority(bitmap, x, y) else c
            if (right) {
                val tmp = dx
                dx = dy
                dy = -tmp
            } else if (!d || c) {
                val tmp = dx
                dx = -dy
                dy = tmp
            }
        }
        return BinaryContour(points, area, positive)
    }

    private fun xorPath(bitmap: Mask, points: List<PixelPoint>) {
        var previousY = points.last().y
        // xor_to_ref's word-aligned reference is arbitrary; reference x=0 gives identical parity.
        for (point in points) {
            if (point.y != previousY) {
                for (x in 0 until point.x) bitmap.invert(x, minOf(point.y, previousY))
                previousY = point.y
            }
        }
    }
}
