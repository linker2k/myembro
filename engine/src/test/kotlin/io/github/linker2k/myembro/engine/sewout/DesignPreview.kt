// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.model.Command
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/** Review PNGs for vector-art designs: source picture and sewn PES side by side. */
internal object DesignPreview {
    /** Left: the vector picture; right: the PES stitches as sewn lines in thread colors, same scale. */
    fun sideBySide(art: VectorArt.Art, pes: ByteArray): BufferedImage {
        val size = 600
        val image = BufferedImage(size * 2, size, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xF6EFE6); g.fillRect(0, 0, size * 2, size)
        // Content bounds (stroke bands included) so both halves use the same 80% framing.
        val all = art.shapes.flatMap { sh -> SvgPath.flatten(sh.d, sh.transform, tolerance = 0.05).flatMap { it.points }.map { q -> Triple(q.x, q.y, if (sh.stroke != null) sh.strokeWidth / 2 else 0.0) } }
        val bx = all.minOf { it.first - it.third }; val by = all.minOf { it.second - it.third }
        val k = size * 0.8 / maxOf(all.maxOf { it.first + it.third } - bx, all.maxOf { it.second + it.third } - by)
        val box = listOf(bx - size * 0.05 / k, by - size * 0.05 / k)
        for (shape in art.shapes) {
            val path = Path2D.Double()
            for (line in SvgPath.flatten(shape.d, shape.transform, tolerance = 0.05)) line.points.forEachIndexed { i, q ->
                val x = (q.x - box[0]) * k + size * 0.05; val y = (q.y - box[1]) * k + size * 0.05
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            shape.fill?.let { g.color = Color(it); g.fill(path) }
            shape.stroke?.let {
                g.color = Color(it)
                g.stroke = BasicStroke((shape.strokeWidth * k).toFloat(),
                    if (shape.lineCap == "round") BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                g.draw(path)
            }
        }
        val stitches = io.github.linker2k.myembro.engine.io.PesReader.read(pes)
        val pts = stitches.stitches.filter { it.command == Command.STITCH || it.command == Command.JUMP }
        val minX = pts.minOf { it.x }; val minY = pts.minOf { it.y }
        val span = maxOf(pts.maxOf { it.x } - minX, pts.maxOf { it.y } - minY).toDouble()
        val s = size * 0.8 / span
        var thread = 0; var last: io.github.linker2k.myembro.engine.model.Stitch? = null
        g.stroke = BasicStroke(1.2f)
        for (st in stitches.stitches) {
            when (st.command) {
                Command.COLOR_CHANGE -> { thread++; last = null }
                Command.STITCH -> {
                    g.color = Color(stitches.threads.getOrNull(thread)?.color ?: 0)
                    last?.let { a -> g.drawLine((size * 1.1 + (a.x - minX) * s).toInt(), (size * .1 + (a.y - minY) * s).toInt(),
                        (size * 1.1 + (st.x - minX) * s).toInt(), (size * .1 + (st.y - minY) * s).toInt()) }
                    last = st
                }
                else -> last = st
            }
        }
        g.dispose()
        return image
    }
}
