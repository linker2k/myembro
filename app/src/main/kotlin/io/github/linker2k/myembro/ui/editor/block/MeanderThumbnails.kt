// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import io.github.linker2k.myembro.engine.inkstitch.stitches.MeanderFill
import java.util.concurrent.ConcurrentHashMap

/**
 * Thumbnails of the bundled Ink/Stitch meander tiles, from the same SVGs and loader the stitch engine uses
 * (MeanderFill.loadTile): the tile's segments repeated along its two shift vectors, normalized to a unit
 * square. Computed once per tile and cached for the process.
 */
object MeanderThumbnails {
    /** Line segments x0,y0,x1,y1 in 0..1 (may extend past the square; drawing clips). */
    class Lines(val id: String, val coords: FloatArray)

    private val cache = ConcurrentHashMap<String, Lines>()

    fun ids(): List<String> = MeanderFill.availablePatterns()

    fun lines(id: String): Lines = cache.getOrPut(id) {
        val tile = MeanderFill.loadTile(id)
        // A window of about two tiles across, centered on the tile, filled by the repeated lattice.
        val xs = tile.segments.flatMap { listOf(it.first.x, it.second.x) }
        val ys = tile.segments.flatMap { listOf(it.first.y, it.second.y) }
        val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min(), tile.shift0.length(), tile.shift1.length()) * 1.6
        val cx = (xs.max() + xs.min()) / 2; val cy = (ys.max() + ys.min()) / 2
        val out = ArrayList<Float>()
        for (i in -3..3) for (j in -3..3) {
            val dx = i * tile.shift0.x + j * tile.shift1.x; val dy = i * tile.shift0.y + j * tile.shift1.y
            tile.segments.forEach { (a, b) ->
                listOf(a.x + dx, a.y + dy, b.x + dx, b.y + dy).forEachIndexed { k, v ->
                    out += (((v - if (k % 2 == 0) cx else cy) / span) + 0.5).toFloat()
                }
            }
        }
        Lines(id, out.toFloatArray())
    }
}
