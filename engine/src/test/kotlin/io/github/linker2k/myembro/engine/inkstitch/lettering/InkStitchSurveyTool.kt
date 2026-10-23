// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.lettering.*

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.Test

/**
 * Opt-in developer survey of a local inkstitch/embroidery-fonts checkout (data only; nothing from it
 * runs). INKSTITCH_FONTS=<checkout>/src INKSTITCH_SURVEY=<out dir>. Output stays outside the repo.
 */
class InkStitchSurveyTool {
    @Test fun survey() {
        val src = System.getenv("INKSTITCH_FONTS")?.let(::File) ?: return
        val out = System.getenv("INKSTITCH_SURVEY")?.let(::File) ?: return
        out.mkdirs()
        val text = System.getenv("INKSTITCH_TEXT") ?: "Hamburg 2024"
        val rows = arrayListOf(listOf("font", "glyphs", "satins", "fills", "runs", "unsupported", "sizeMm", "refHeightMm",
            "minScale", "maxScale", "baselineFromGuide", "guide", "refTop", "refBottom", "parseMs", "stitches", "trims", "colors", "maxStitchMm", "shortLt03",
            "renderMs", "lower", "upper", "digits", "hangul", "error").joinToString("\t"))
        for (dir in src.listFiles().orEmpty().filter { File(it, "ltr.svg").isFile && File(it, "font.json").isFile }.sortedBy { it.name }) {
            val row = runCatching {
                val t0 = System.nanoTime()
                val font = File(dir, "ltr.svg").inputStream().use { InkStitchFont.parse(it, File(dir, "font.json").readText()) }
                val t1 = System.nanoTime()
                val elements = font.glyphs.values.flatMap { it.elements }
                val ref = font.referenceGlyph
                val height = font.referenceHeightMm ?: error("no sewable glyph: ${font.unsupported}")
                val pattern = InkStitchLettering.render(font, text, height)
                val t2 = System.nanoTime()
                draw(pattern, File(out, "${dir.name}.png"))
                val lengths = pattern.stitches.zipWithNext().filter { (a, b) -> b.command == Command.STITCH && a.command == Command.STITCH }
                    .map { (a, b) -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) / 10 }
                listOf(dir.name, font.glyphs.size, elements.count { it is InkSatin }, elements.count { it is InkFill },
                    elements.count { it is InkRun }, font.unsupported.entries.joinToString(",") { "${it.key}=${it.value}" },
                    font.info.sizeMm, "%.2f".format(height), font.info.minScale, font.info.maxScale,
                    ref != null && font.baseline != ref.inkBottom, font.baselineGuide?.let { "%.1f".format(it) }, "%.1f".format(ref?.inkTop), "%.1f".format(ref?.inkBottom), (t1 - t0) / 1_000_000, pattern.stitchCount, pattern.trimCount,
                    pattern.threads.size, "%.2f".format(lengths.maxOrNull() ?: 0.0), lengths.count { it < 0.3 },
                    (t2 - t1) / 1_000_000, ('a'..'z').count { it.toString() in font.glyphs }, ('A'..'Z').count { it.toString() in font.glyphs },
                    ('0'..'9').count { it.toString() in font.glyphs }, font.glyphs.keys.count { k -> k.any { it in '\uAC00'..'\uD7A3' } }, "").joinToString("\t")
            }.getOrElse { e -> listOf(dir.name, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", e.toString().replace('\t', ' ')).joinToString("\t") }
            rows += row
            println(row)
        }
        File(out, "survey.tsv").writeText(rows.joinToString("\n") + "\n")
    }

    /**
     * Opt-in: PES files plus a quality-gate matrix. INKSTITCH_FONTS=<checkout>/src INKSTITCH_GATE=<out dir>
     * INKSTITCH_GATE_FONTS=a,b INKSTITCH_GATE_HEIGHTS=15,30 (mm, 'H' basis).
     */
    @Test fun gateMatrix() {
        val src = System.getenv("INKSTITCH_FONTS")?.let(::File) ?: return
        val out = System.getenv("INKSTITCH_GATE")?.let(::File) ?: return
        val fonts = System.getenv("INKSTITCH_GATE_FONTS")?.split(',') ?: return
        val heights = (System.getenv("INKSTITCH_GATE_HEIGHTS") ?: "15,30").split(',').map { it.trim().toDouble() }
        val text = System.getenv("INKSTITCH_TEXT") ?: "Hamburg 2024"
        val cases = arrayListOf<String>()
        for (name in fonts) {
            val dir = File(src, name)
            val font = File(dir, "ltr.svg").inputStream().use { InkStitchFont.parse(it, File(dir, "font.json").readText()) }
            for (h in heights) {
                val pattern = InkStitchLettering.render(font, text, h, io.github.linker2k.myembro.engine.model.EmbThread(0xFF000000.toInt()))
                val file = File(out, "$name/${h.toInt()}mm-satin.pes").apply { parentFile.mkdirs() }
                file.writeBytes(io.github.linker2k.myembro.engine.io.PesWriter.write(pattern))
                cases += """{"font": "$name", "height_mm": ${h.toInt()}, "style": "satin", "path": "${file.absolutePath}"}"""
            }
        }
        File(out, "matrix.json").writeText("""{"axes": {"fonts": [${fonts.joinToString { "\"$it\"" }}], "heights_mm": [${heights.joinToString { it.toInt().toString() }}], "styles": ["satin"]},
 "cases": [
  ${cases.joinToString(",\n  ")}
 ]}
""")
    }

    /**
     * Opt-in: card previews of the real stitches, one transparent PNG per font folder named by catalog id.
     * INKSTITCH_FONTS=<checkout>/src INKSTITCH_PREVIEWS=<out dir> [INKSTITCH_PREVIEW_IDS=a,b (folder names)].
     */
    @Test fun previews() {
        val src = System.getenv("INKSTITCH_FONTS")?.let(::File) ?: return
        val out = System.getenv("INKSTITCH_PREVIEWS")?.let(::File) ?: return
        val only = System.getenv("INKSTITCH_PREVIEW_IDS")?.split(',')?.toSet()
        out.mkdirs()
        for (dir in src.listFiles().orEmpty().filter { File(it, "ltr.svg").isFile && (only == null || it.name in only) }.sortedBy { it.name }) {
            runCatching {
                val font = File(dir, "ltr.svg").inputStream().use { InkStitchFont.parse(it, File(dir, "font.json").readText()) }
                val text = if (('a'..'c').all { it.toString() in font.glyphs }) "Abc" else "ABC"
                val pattern = InkStitchLettering.render(font, text, 10.0)
                val id = "inkstitch-" + dir.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                draw(pattern, File(out, "$id.png"), heightPx = 72, transparent = true)
            }.onFailure { println("preview ${dir.name}: $it") }
        }
    }

    private fun draw(pattern: EmbPattern, file: File, heightPx: Int? = null, transparent: Boolean = false) {
        val sewn = pattern.stitches.filter { it.command == Command.STITCH }
        if (sewn.isEmpty()) return
        val minX = sewn.minOf { it.x }; val maxX = sewn.maxOf { it.x }; val minY = sewn.minOf { it.y }; val maxY = sewn.maxOf { it.y }
        val margin = if (heightPx != null) 4 else 20
        // px per 0.1 mm
        val scale = heightPx?.let { (it - 2.0 * margin) / (maxY - minY).coerceAtLeast(1) }?.coerceAtMost(4.0) ?: 2.0
        val w = ((maxX - minX) * scale + 2 * margin).toInt().coerceIn(40, if (heightPx != null) 480 else 6000)
        val h = heightPx ?: ((maxY - minY) * scale + 40).toInt().coerceIn(40, 3000)
        val image = BufferedImage(w, h, if (transparent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        if (!transparent) { g.color = Color.WHITE; g.fillRect(0, 0, w, h) }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = BasicStroke(if (heightPx != null) 0.8f else 0.6f)
        var thread = 0
        var previous: io.github.linker2k.myembro.engine.model.Stitch? = null
        for (s in pattern.stitches) {
            if (s.command == Command.COLOR_CHANGE) thread++
            val t = pattern.threads.getOrNull(thread)
            g.color = Color(t?.color ?: 0).let { if (it.red + it.green + it.blue > 600) Color.GRAY else it }
            if (s.command == Command.STITCH && previous?.command == Command.STITCH) {
                g.drawLine(((previous.x - minX) * scale + margin).toInt(), ((previous.y - minY) * scale + margin).toInt(),
                    ((s.x - minX) * scale + margin).toInt(), ((s.y - minY) * scale + margin).toInt())
            }
            previous = s
        }
        g.dispose()
        ImageIO.write(image, "png", file)
    }
}
