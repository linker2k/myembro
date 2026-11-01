// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Local opt-in axes: resolution (piece area at default) and piece area (resolution at default) for colour drawing and silhouette, through the production
 * trace/generate/PES/check route at 99.9mm. Rasters follow PhotoViewModel.prepare (colour: crop at source
 * resolution, never scaled up; mono: scaled to the step on white, median 3, Wu pixel limit). The 0.2mm/px
 * mono cap is NOT applied here, so higher steps show what the cap currently prevents. Inputs are never copied.
 */
object DetailSplitCli {
    private const val WIDTH_MM = 99.9
    private const val CELL = 360
    private const val ZOOM = 420

    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "--single") return single(File(args[1]), File(args[2]))
        if (args.firstOrNull() == "--probe") return probe(File(args[1]), args[2].toDouble())
        require(args.size >= 2) { "OUTPUT_DIRECTORY INPUT_PNG..." }
        val out = File(args[0]).apply { mkdirs() }
        val p = PhotoPreparationPolicy
        val rows = mutableListOf("axis\tinput\tmode\tresolution_step\ttraced_px\tpiece_area_mm2\tcontours\tbudget_contours\tover_128\tsewn_regions\texcluded\tstitches\tsewout\ttrace_ms\tstitch_ms")
        val inputs = args.drop(1).map { File(it).nameWithoutExtension to ImageIO.read(File(it)) }
        for (color in listOf(true, false)) {
            val mode = if (color) "color" else "silhouette"
            val defaultEdge = if (color) p.COLOR_DEFAULT_EDGE else p.MONO_DEFAULT_EDGE
            val defaultArea = if (color) p.COLOR_DEFAULT_PIECE_AREA_MM2 else p.MONO_DEFAULT_PIECE_AREA_MM2
            for (axis in listOf("resolution", "piece_area")) {
                val steps: List<Any> = if (axis == "resolution") p.resolutionSteps(defaultEdge) else p.pieceAreaSteps(defaultArea)
                val width = if (axis == "resolution") ZOOM else CELL
                val sheet = BufferedImage(90 + width * steps.size, 40 + width * inputs.size, BufferedImage.TYPE_INT_RGB)
                val g = sheet.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color.WHITE; g.fillRect(0, 0, sheet.width, sheet.height); g.color = Color.BLACK
                steps.forEachIndexed { i, s -> g.drawString(label(s, s == defaultEdge || s == defaultArea), 90 + i * width + 8, 26) }
                for ((row, input) in inputs.withIndex()) {
                    val (name, source) = input
                    g.color = Color.BLACK; g.drawString(name, 6, 40 + row * width + width / 2)
                    for ((i, step) in steps.withIndex()) {
                        val size = if (step is Int) step else defaultEdge
                        val area = if (step is Double) step else defaultArea
                        val raster = raster(source, size, color)
                        val cell = run(raster, color, area)
                        val line = listOf(axis, name, mode, size, maxOf(raster.w, raster.h), "%.4f".format(java.util.Locale.ROOT, area), cell.contours, cell.budget,
                            cell.budget > 128, cell.regions ?: "", cell.excluded ?: "", cell.stitches ?: "", cell.sewout, cell.traceMs, cell.stitchMs ?: "").joinToString("\t")
                        rows += line; println(line); System.out.flush()
                        if (axis == "resolution") { if (cell.pattern != null) drawZoom(g, cell.pattern, 90 + i * width, 40 + row * width) else draw(g, cell, 90 + i * width, 40 + row * width) }
                        else draw(g, cell, 90 + i * width, 40 + row * width)
                    }
                }
                g.dispose()
                ImageIO.write(sheet, "png", File(out, "$axis-$mode.png"))
            }
        }
        File(out, "axes.tsv").writeText(rows.joinToString("\n", postfix = "\n"))
        // Large hoops: 1024px at each hoop's maximum picture width (first input only), mode default piece area.
        val hoopRows = mutableListOf("input\tmode\thoop\twidth_mm\ttraced\tmm_per_px\tcontours\tbudget_contours\tover_128\tstitches\tsewout\ttrace_ms\tstitch_ms")
        val (name, source) = inputs.first()
        for (hoop in HoopSpec.ALL) for (color in listOf(true, false)) {
            val r = raster(source, 1024, color, hoop)
            val width = p.photoMaxWidthMm(hoop)
            val cell = run(r, color, if (color) p.COLOR_DEFAULT_PIECE_AREA_MM2 else p.MONO_DEFAULT_PIECE_AREA_MM2, hoop, width)
            val row = listOf(name, if (color) "color" else "silhouette", hoop.name, width, "${r.w}x${r.h}", "%.3f".format(java.util.Locale.ROOT, width / r.w),
                cell.contours, cell.budget, cell.budget > 128, cell.stitches ?: "", cell.sewout, cell.traceMs, cell.stitchMs ?: "").joinToString("\t")
            hoopRows += row; println(row); System.out.flush()
        }
        File(out, "hoops.tsv").writeText(hoopRows.joinToString("\n", postfix = "\n"))
    }

    /**
     * One picture, three modes, one axis at a time (the other at its mode default). The whole picture is fitted
     * into the 100×100 frame ("contain"); the margin takes the top-left pixel colour as on the photo screen.
     * Each step shows the whole design and the same 15mm window around the design centre at 20 px/mm.
     */
    private fun single(out: File, input: File) {
        out.mkdirs()
        val source = ImageIO.read(input)
        val p = PhotoPreparationPolicy
        val modes = listOf("color", "silhouette", "outline")
        val rows = mutableListOf("axis\tmode\tresolution_step\ttraced_px\tpiece_area_mm2\tcontours\tbudget_contours\tover_128\tsewn_regions\texcluded\tstitches\tsewout\ttrace_ms\tstitch_ms")
        val areaSteps = listOf(0.0, 0.25, 0.5, 1.0, 2.0, p.COLOR_DEFAULT_PIECE_AREA_MM2, 4.0, 8.0, 16.0)
        val sizeSteps = p.RESOLUTION_STEPS.let { (it + p.MONO_DEFAULT_EDGE).sorted() }
        val side = 300
        for (axis in listOf("piece_area", "resolution")) {
            val steps: List<Any> = if (axis == "piece_area") areaSteps else sizeSteps
            val sheet = BufferedImage(110 + side * steps.size, 40 + side * 2 * modes.size, BufferedImage.TYPE_INT_RGB)
            val g = sheet.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE; g.fillRect(0, 0, sheet.width, sheet.height); g.color = Color.BLACK
            steps.forEachIndexed { i, s -> g.drawString(label(s, false), 110 + i * side + 8, 26) }
            for ((m, mode) in modes.withIndex()) {
                val color = mode == "color"
                val defaultEdge = if (color) p.COLOR_DEFAULT_EDGE else p.MONO_DEFAULT_EDGE
                val defaultArea = if (color) p.COLOR_DEFAULT_PIECE_AREA_MM2 else p.MONO_DEFAULT_PIECE_AREA_MM2
                g.color = Color.BLACK
                g.drawString(mode, 6, 40 + m * 2 * side + side / 2); g.drawString("$mode 15mm", 6, 40 + (m * 2 + 1) * side + side / 2)
                for ((i, step) in steps.withIndex()) {
                    // App rules: no step above the picture's long side (mode default kept), colour has no 'off'.
                    if (step is Int && step > maxOf(source.width, source.height) && step != defaultEdge) continue
                    if (step == 0.0 && color) continue
                    val size = if (step is Int) step else defaultEdge
                    val area = if (step is Double) step else defaultArea
                    val r = contain(source, size, color)
                    val cell = run(r, color, area, outlineStyle = mode == "outline")
                    val line = listOf(axis, mode, size, maxOf(r.w, r.h), "%.4f".format(java.util.Locale.ROOT, area), cell.contours, cell.budget,
                        cell.budget > 128, cell.regions ?: "", cell.excluded ?: "", cell.stitches ?: "", cell.sewout, cell.traceMs, cell.stitchMs ?: "").joinToString("\t")
                    rows += line; println(line); System.out.flush()
                    val x = 110 + i * side; val y = 40 + m * 2 * side
                    cellView(g, cell, x, y, side)
                    cell.pattern?.let { window(g, it, x, y + side, side) }
                }
            }
            g.dispose()
            ImageIO.write(sheet, "png", File(out, "$axis.png"))
        }
        File(out, "axes.tsv").writeText(rows.joinToString("\n", postfix = "\n"))
    }

    /** Which regions reach the extreme x/y of a colour result (hoop overflow diagnosis). */
    private fun probe(input: File, area: Double) {
        val r = contain(ImageIO.read(input), PhotoPreparationPolicy.COLOR_DEFAULT_EDGE, true)
        val preview = ColorDrawingPipeline.traceRegions(r.w, r.h, r.pixels, WIDTH_MM, 4, maxContours = Int.MAX_VALUE, minPieceAreaMm2 = area)
        val result = ColorDrawingPipeline.generate(preview, HoopSpec.H100)
        val b = result.pattern.bounds!!
        println("pattern bounds=$b span=${b.width}x${b.height} regions=${result.regions.size} fragments=${result.regionPatterns.size}")
        for ((id, frag) in result.regionPatterns) {
            val fb = frag.bounds ?: continue
            if (fb.minX == b.minX || fb.maxX == b.maxX || fb.minY == b.minY || fb.maxY == b.maxY) {
                val index = id.removePrefix("region-").toIntOrNull()
                val sewn = result.regions.filter { !it.excluded && it.groups > 0 }
                val region = index?.let { sewn.getOrNull(it) }
                println("$id bounds=$fb stitches=${frag.stitchCount} type=${region?.method} maxWidthMm=${region?.widths?.maxMm}")
            }
        }
    }

    private fun contain(source: BufferedImage, step: Int, color: Boolean): Raster {
        val view = maxOf(source.width, source.height)
        val long = if (color) minOf(step, view) else step
        val image = BufferedImage(long, long, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            if (!color) { this.color = Color.WHITE; fillRect(0, 0, long, long) }
            this.color = Color(source.getRGB(0, 0), true); fillRect(0, 0, long, long)
            val s = long.toDouble() / view
            val w = (source.width * s).toInt(); val h = (source.height * s).toInt()
            drawImage(source, (long - w) / 2, (long - h) / 2, w, h, null); dispose()
        }
        val corrected = PhotoCorrections.adjust(long, long, image.getRGB(0, 0, long, long, null, 0, long), !color, if (color) 0 else 3, 1f)
        return Raster(long, long, corrected.pixels)
    }

    private fun cellView(g: java.awt.Graphics2D, cell: Cell, x: Int, y: Int, side: Int) {
        g.color = Color(0xF4F1EA); g.fillRect(x + 3, y + 3, side - 6, side - 6)
        cell.pattern?.let { lines(g, it, x + 8, y + 8, (side - 16.0) / maxOf(it.bounds!!.width, it.bounds!!.height, 1), it.bounds!!.minX.toDouble(), it.bounds!!.minY.toDouble(), side) }
            ?: cell.preview?.let { g.drawImage(it, x + 8, y + 8, side - 16, side - 16, null) }
        g.color = if (cell.budget > 128 || cell.sewout != "PASS") Color(0xB00020) else Color.BLACK
        g.drawString("${cell.contours}/${cell.budget}" + (cell.stitches?.let { " · $it st" } ?: "") + " · ${cell.sewout}", x + 8, y + side - 22)
        g.drawString("trace ${cell.traceMs}ms" + (cell.stitchMs?.let { " stitch ${it}ms" } ?: ""), x + 8, y + side - 8)
    }

    /** 15mm square around the design centre, 20 px/mm. */
    private fun window(g: java.awt.Graphics2D, pattern: EmbPattern, x: Int, y: Int, side: Int) {
        val b = pattern.bounds ?: return
        val scale = side / 150.0
        val cx = (b.minX + b.maxX) / 2.0; val cy = (b.minY + b.maxY) / 2.0
        val clip = g.clip; g.clipRect(x + 3, y + 3, side - 6, side - 6)
        g.color = Color(0xF4F1EA); g.fillRect(x + 3, y + 3, side - 6, side - 6)
        lines(g, pattern, x, y, scale, cx - 75, cy - 75, side)
        g.clip = clip
    }

    private fun lines(g: java.awt.Graphics2D, pattern: EmbPattern, x: Int, y: Int, scale: Double, ox: Double, oy: Double, side: Int) {
        g.stroke = BasicStroke(0.7f)
        var last: io.github.linker2k.myembro.engine.model.Stitch? = null; var thread = 0
        for (s in pattern.stitches) {
            if (s.command == Command.COLOR_CHANGE) thread++
            if (s.command == Command.STITCH && last != null && last.command == Command.STITCH) {
                g.color = Color(pattern.threads.getOrNull(thread)?.color ?: 0)
                g.drawLine((x + (last.x - ox) * scale).toInt(), (y + (last.y - oy) * scale).toInt(),
                    (x + (s.x - ox) * scale).toInt(), (y + (s.y - oy) * scale).toInt())
            }
            last = s
        }
    }

    private fun label(step: Any, default: Boolean) = (when (step) {
        is Int -> "$step px"
        0.0 -> "off"
        else -> "%.3g mm²".format(java.util.Locale.ROOT, step as Double)
    }) + if (default) " (default)" else ""

    private class Raster(val w: Int, val h: Int, val pixels: IntArray)
    private class Cell(val contours: Int, val budget: Int, val regions: Int?, val excluded: Int?, val stitches: Int?,
        val sewout: String, val traceMs: Long, val stitchMs: Long?, val pattern: EmbPattern?, val preview: BufferedImage?)

    /** Cover crop to the hoop aspect, as the photo screen's default zoom; colour never scales up. */
    private fun raster(source: BufferedImage, step: Int, color: Boolean, hoop: HoopSpec = HoopSpec.H100): Raster {
        val aspect = hoop.width.toDouble() / hoop.height
        val cw = minOf(source.width.toDouble(), source.height * aspect).toInt()
        val ch = minOf(source.height.toDouble(), source.width / aspect).toInt()
        val left = (source.width - cw) / 2; val top = (source.height - ch) / 2
        val long = if (color) minOf(step, maxOf(cw, ch)) else step
        val w = if (aspect >= 1) long else (long * aspect).toInt().coerceAtLeast(1)
        val h = if (aspect >= 1) (long / aspect).toInt().coerceAtLeast(1) else long
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            if (!color) { this.color = Color.WHITE; fillRect(0, 0, w, h) }
            drawImage(source, 0, 0, w, h, left, top, left + cw, top + ch, null); dispose()
        }
        val argb = image.getRGB(0, 0, w, h, null, 0, w)
        val corrected = PhotoCorrections.adjust(w, h, argb, !color, if (color) 0 else 3, 1f)
        return Raster(w, h, corrected.pixels)
    }

    private fun run(r: Raster, color: Boolean, area: Double, hoop: HoopSpec = HoopSpec.H100, widthMm: Double = WIDTH_MM, outlineStyle: Boolean = false): Cell {
        val thread = EmbThread(0xFFB5492A.toInt(), "Photo")
        val t0 = System.nanoTime()
        return try {
            if (color) {
                val preview = ColorDrawingPipeline.traceRegions(r.w, r.h, r.pixels, widthMm, 4, maxContours = Int.MAX_VALUE, minPieceAreaMm2 = area)
                val traceMs = (System.nanoTime() - t0) / 1_000_000
                val budget = preview.layers.sumOf { it.outline.contourBudgetCount }
                val image = BufferedImage(preview.width, preview.height, BufferedImage.TYPE_INT_ARGB).apply {
                    setRGB(0, 0, preview.width, preview.height, preview.previewPixels(), 0, preview.width) }
                if (budget > 128) return Cell(preview.inkOutline.contourCount, budget, null, null, null, "too complex", traceMs, null, null, image)
                val t1 = System.nanoTime()
                val result = ColorDrawingPipeline.generate(preview, hoop)
                val stitchMs = (System.nanoTime() - t1) / 1_000_000
                Cell(preview.inkOutline.contourCount, budget, result.regions.count { !it.excluded && it.groups > 0 }, result.excludedCount,
                    result.pattern.stitchCount, verdict(result.pattern, hoop), traceMs, stitchMs, result.pattern, null)
            } else {
                val outline = RedworkPipeline.trace(r.w, r.h, r.pixels.map { it and 0xFFFFFF }.toIntArray(), widthMm, 0, thread,
                    minAreaMm2 = area, maxContours = Int.MAX_VALUE)
                val traceMs = (System.nanoTime() - t0) / 1_000_000
                if (outline.contourCount > 128) return Cell(outline.contourCount, outline.contourCount, null, null, null, "too complex", traceMs, null, null, null)
                val t1 = System.nanoTime()
                val result = RedworkPipeline.generate(outline, thread, photoMode = true, hoop = hoop,
                    style = if (outlineStyle) PhotoStyle.OUTLINE else PhotoStyle.SILHOUETTE)
                val stitchMs = (System.nanoTime() - t1) / 1_000_000
                Cell(outline.contourCount, outline.contourCount, null, null, result.pattern.stitchCount, verdict(result.pattern, hoop), traceMs, stitchMs, result.pattern, null)
            }
        } catch (e: IllegalArgumentException) {
            Cell(0, 0, null, null, null, "failed: ${e.message}", (System.nanoTime() - t0) / 1_000_000, null, null, null)
        }
    }

    private fun verdict(pattern: EmbPattern, hoop: HoopSpec = HoopSpec.H100): String {
        val report = SewoutCheck.checkPes(PesWriter.write(pattern, io.github.linker2k.myembro.engine.io.PesWriteSettings(hoop = hoop)),
            SewoutMachine("output", "Selected hoop", Specification(), Specification(), knownHoopsMm = emptySet()),
            SewoutOptions(hoop = hoop, origin = DesignOrigin.APP_GENERATED))
        return if (!report.analysisComplete) "incomplete" else if (report.hasBlockingIssues) "BLOCK" else "PASS"
    }

    /** The middle third of the design (upper half), 20 px per mm. */
    private fun drawZoom(g: java.awt.Graphics2D, pattern: EmbPattern, x: Int, y: Int) {
        val b = pattern.bounds ?: return
        val scale = 2.0 // px per 0.1mm unit = 20 px/mm; the 21mm window straddles the left edge at mid-height
        val ox = b.minX - 20.0; val oy = b.minY + b.height * 0.45 - ZOOM / scale / 2
        val clip = g.clip; g.clipRect(x + 2, y, ZOOM - 4, ZOOM)
        g.color = Color(0xF4F1EA); g.fillRect(x + 2, y, ZOOM - 4, ZOOM)
        g.stroke = BasicStroke(0.8f)
        var last: io.github.linker2k.myembro.engine.model.Stitch? = null; var thread = 0
        for (s in pattern.stitches) {
            if (s.command == Command.COLOR_CHANGE) thread++
            if (s.command == Command.STITCH && last != null && last.command == Command.STITCH) {
                g.color = Color(pattern.threads.getOrNull(thread)?.color ?: 0)
                g.drawLine((x + (last.x - ox) * scale).toInt(), (y + (last.y - oy) * scale).toInt(),
                    (x + (s.x - ox) * scale).toInt(), (y + (s.y - oy) * scale).toInt())
            }
            last = s
        }
        g.clip = clip
    }

    private fun draw(g: java.awt.Graphics2D, cell: Cell, x: Int, y: Int) {
        g.color = Color(0xF4F1EA); g.fillRect(x + 4, y + 4, CELL - 8, CELL - 8)
        val pattern = cell.pattern
        if (pattern != null && pattern.stitchCount > 0) {
            val b = pattern.bounds!!
            val scale = (CELL - 24.0) / maxOf(b.width, b.height, 1)
            g.stroke = BasicStroke(0.6f)
            var last: io.github.linker2k.myembro.engine.model.Stitch? = null
            var thread = 0
            for (s in pattern.stitches) {
                if (s.command == Command.COLOR_CHANGE) thread++
                if (s.command == Command.STITCH && last != null && last.command == Command.STITCH) {
                    g.color = Color(pattern.threads.getOrNull(thread)?.color ?: 0)
                    g.drawLine((x + 12 + (last.x - b.minX) * scale).toInt(), (y + 12 + (last.y - b.minY) * scale).toInt(),
                        (x + 12 + (s.x - b.minX) * scale).toInt(), (y + 12 + (s.y - b.minY) * scale).toInt())
                }
                last = s
            }
        } else cell.preview?.let { g.drawImage(it, x + 12, y + 12, CELL - 24, CELL - 24, null) }
        g.color = if (cell.budget > 128 || cell.sewout != "PASS") Color(0xB00020) else Color.BLACK
        g.drawString("contours ${cell.contours} / budget ${cell.budget}" + (cell.stitches?.let { " / $it st" } ?: ""), x + 10, y + CELL - 24)
        g.drawString(cell.sewout + "  trace ${cell.traceMs}ms" + (cell.stitchMs?.let { " stitch ${it}ms" } ?: ""), x + 10, y + CELL - 10)
    }
}
