// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.render.realistic

/** Platform boundary: rasterize a filled Bezier path to coverage bytes 0..255.
 * JVM tests use Java2D; Android can later implement this with Canvas into an alpha mask.
 * Neither backend is imported into the pure-Kotlin filter implementation.
 */
fun interface ThreadAlphaRasterizer {
    fun rasterize(path: List<ThreadPathCommand>, width: Int, height: Int): IntArray
}

/** Experimental CPU reference prototype, NOT connected to app/export or claimed pixel-equivalent.
 * Full-page intermediate masks deliberately prioritize debuggability over large-design performance.
 * No automatic changes to embroidery coordinates/threads/locks or gate policies.
 */
object RealisticThreadRenderer {
    fun render(width: Int, height: Int, dpi: Int, segments: List<RenderThreadSegment>,
               rasterizer: ThreadAlphaRasterizer): IntArray {
        require(width >= 3 && height >= 3)
        require(dpi > 0)
        val scale = dpi / 96.0 // CSS px/inch, upstream svg/units.py:12.
        val output = IntArray(Math.multiplyExact(width, height)) { -1 }
        for (segment in segments) {
            if (segment.start == segment.end) continue // A display segment is not a new needle penetration.
            val mask = rasterizer.rasterize(ThreadShape.path(segment, scale), width, height)
            require(mask.size == output.size && mask.all { it in 0..255 })
            val blur = InkscapeGaussian.blur(mask, width, height, ThreadFilterMath.SIGMA * scale)
            val light = ThreadFilterMath.specular(blur, width, height, scale)
            ThreadFilterMath.composite(mask, light, segment.displayRgb, output)
        }
        return output
    }
}
