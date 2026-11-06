// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Separate-JVM entry for [SvgCost] (local measurement only; run by /tmp/myembro-svg-cost/run-isolated.sh):
 * `--list`, `--case INDEX` (one synthetic/hostile case), `--koi PICTURE [--unbounded]` (app photo defaults, then
 * the vector result as an SVG; --unbounded lifts only the contour budget, measurement only). Pictures are read only and never copied. */
object SvgCostCli {
    @JvmStatic fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "--list" -> SvgCost.cases.forEachIndexed { i, (g, n, _) -> println("$i\t$g\t$n") }
            "--case" -> {
                SvgCost.stageTimeoutMs = 55_000; SvgCost.repeatBelowMs = 5_000
                val (g, n, make) = SvgCost.cases[args[1].toInt()]
                println(SvgCost.row(g, n, make()))
            }
            "--koi" -> koi(File(args[1]), unbounded = args.getOrNull(2) == "--unbounded")
            else -> error("--list | --case INDEX | --koi PICTURE [--unbounded]")
        }
    }

    /** Photo screen defaults: cover crop to the 100mm hoop, 99.9mm width, 4 colours, mode default
     * resolution and piece area, as PhotoViewModel.prepare/generateResult (see DetailSplitCli). */
    private fun koi(input: File, unbounded: Boolean) {
        val source = ImageIO.read(input)
        val p = PhotoPreparationPolicy
        val hoop = HoopSpec.H100
        val width = p.photoMaxWidthMm(hoop)
        println("KOI input=${input.name} ${source.width}x${source.height} width_mm=$width contour_budget=${if (unbounded) "unbounded (bypass)" else "app default"}")
        println("KOI_HEADER\tmode\traster\tpiece_area_mm2\tcontours\ttrace_ms\tgenerate_ms\tcheck_ms\tstitches\tverdict\tpaths\tsubpaths\tflattened_points\tsvg_bytes\tsvg_policy")
        for (mode in listOf("color", "silhouette", "outline")) {
            val color = mode == "color"
            val area = if (color) p.COLOR_DEFAULT_PIECE_AREA_MM2 else p.MONO_DEFAULT_PIECE_AREA_MM2
            val edge = if (color) p.COLOR_DEFAULT_EDGE else p.photoRasterEdge(p.MONO_DEFAULT_EDGE, width, width)
            val (w, h, pixels, mask) = raster(source, edge, color)
            val thread = EmbThread(0xFFB5492A.toInt(), "Photo")
            var preview: ColorDrawingPipeline.Preview? = null; var outline: RedworkOutline? = null
            val (trace, _) = SvgCost.measure {
                if (color) (if (unbounded) ColorDrawingPipeline.traceRegions(w, h, pixels, width, 4, maxContours = Int.MAX_VALUE, minPieceAreaMm2 = area)
                    else ColorDrawingPipeline.traceRegions(w, h, pixels, width, 4, minPieceAreaMm2 = area)).also { preview = it }
                else (if (mask != null) RedworkPipeline.traceMask(w, h, mask, width, thread, minAreaMm2 = area, maxContours = if (unbounded) Int.MAX_VALUE else 128)
                    else RedworkPipeline.trace(w, h, pixels, width, 0, thread, minAreaMm2 = area, maxContours = if (unbounded) Int.MAX_VALUE else 128)).also { outline = it }
            }
            if (trace.error != null) { println("KOI\t$mode\t${w}x$h\t$area\t${trace.error}"); continue }
            val (gen, pattern) = SvgCost.measure {
                if (color) ColorDrawingPipeline.generate(preview!!, hoop).pattern
                else RedworkPipeline.generate(outline!!, thread, photoMode = true, hoop = hoop,
                    style = if (mode == "outline") PhotoStyle.OUTLINE else PhotoStyle.SILHOUETTE).pattern
            }
            val (chk, report) = if (pattern == null) SvgCost.Stage(null, null, null, "-") to null else SvgCost.measure {
                SewoutCheck.checkPes(PesWriter.write(pattern, PesWriteSettings(hoop = hoop)), SewoutMachines.SE400,
                    SewoutOptions(hoop = hoop, origin = DesignOrigin.APP_GENERATED))
            }
            // Vector result as one SVG (measurement only): colour = one evenodd path per thread layer.
            val layers = if (color) preview!!.layers.map { it.thread.hex to it.outline } else listOf(thread.hex to outline!!)
            val first = layers.firstOrNull()?.second ?: outline ?: preview!!.inkOutline
            val head = Regex("<svg[^>]*>").find(first.outlineSvg)!!.value
            val svg = head + layers.joinToString("") { (hex, o) -> "<path fill-rule=\"evenodd\" fill=\"#${hex.removePrefix("#")}\" d=\"${o.paths.joinToString(" ")}\"/>" } + "</svg>"
            File(SvgCost.out, "koi-$mode${if (unbounded) "-unbounded" else ""}.svg").writeText(svg)
            val ds = layers.flatMap { it.second.paths }
            val subpaths = ds.sumOf { d -> d.count { it == 'M' || it == 'm' } }
            val points = ds.sumOf { d -> SvgPath.flatten(d, maxPoints = Int.MAX_VALUE).sumOf { it.points.size } }
            val bytes = svg.toByteArray()
            val policy = runCatching { io.github.linker2k.myembro.engine.inkstitch.svg.SvgPictureInput.parse(bytes) }.fold({ "default-accept" }, { "default rejects: ${it.message}" })
            println(listOf("KOI", mode, "${w}x$h", area, if (color) preview!!.layers.sumOf { it.outline.contourBudgetCount } else outline!!.contourCount, trace.ms, gen.ms ?: gen.error, chk.ms ?: chk.error, pattern?.stitchCount ?: "-",
                report?.let { r -> r.verdict.name + r.findingSeverities.filterValues { it == Severity.BLOCKER }.keys.joinToString(",", " ").trimEnd() } ?: gen.error,
                layers.size, subpaths, points, bytes.size, policy).joinToString("\t"))
            System.out.flush()
            // The same vector as an SVG picture, through the measurement-only bypass (render at 50mm as the SVG path does).
            println("KOI_SVG\t" + SvgCost.row("koi-svg", "koi-$mode.svg", bytes)); System.out.flush()
        }
    }

    private data class Raster(val w: Int, val h: Int, val pixels: IntArray, val mask: ByteArray?)

    /** Cover crop to the hoop aspect (default zoom, centred); colour never scales up; mono on white. */
    private fun raster(source: BufferedImage, step: Int, color: Boolean): Raster {
        val cw = minOf(source.width, source.height); val ch = cw
        val left = (source.width - cw) / 2; val top = (source.height - ch) / 2
        val long = if (color) minOf(step, cw) else step
        val image = BufferedImage(long, long, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            if (!color) { this.color = Color.WHITE; fillRect(0, 0, long, long) }
            drawImage(source, 0, 0, long, long, left, top, left + cw, top + ch, null); dispose()
        }
        val corrected = PhotoCorrections.adjust(long, long, image.getRGB(0, 0, long, long, null, 0, long), !color, if (color) 0 else 3, 1f)
        return Raster(long, long, if (color) corrected.pixels else corrected.pixels.map { it and 0xFFFFFF }.toIntArray(), corrected.mask)
    }
}
