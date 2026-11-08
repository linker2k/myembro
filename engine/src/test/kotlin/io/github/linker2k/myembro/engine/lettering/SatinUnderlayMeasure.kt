// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.render.realistic.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * Offline before/after for default satin underlay. "Before" is main's own output: the bundled
 * sample PES and the Android-captured lettering PES (font-snapshots); "after" is this branch.
 * args: <sample-svg-dir> <out-dir>. Writes before/after PES, per-case JSON and realistic PNGs.
 * Generation never relaxes checks: a blocking finding is recorded and reported, not hidden.
 */
object SatinUnderlayMeasure {
    private fun bytes(name: String) = checkNotNull(javaClass.getResourceAsStream("/font-snapshots/$name")).use { it.readBytes() }

    private fun png(pattern: io.github.linker2k.myembro.engine.model.EmbPattern, file: File) {
        val edge = 108 * 16
        val segments = PatternThreadSegments.from(pattern, -40.0, -40.0).toList()
        val pixels = RealisticThreadRenderer.render(edge, edge, FixedRealisticPreview.dpi, segments,
            io.github.linker2k.myembro.engine.render.realistic.RealisticThreadRendererTest().rasterizer, pixelsPerSvgUnit = FixedRealisticPreview.svgScale)
        val image = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, edge, edge, pixels, 0, edge)
        ImageIO.write(image, "png", file)
    }

    private fun record(out: File, key: String, kind: String, before: ByteArray, after: ByteArray, satinParts: Int, report: SewoutReport?, error: String?) {
        File(out, "$key-before.pes").writeBytes(before)
        File(out, "$key-after.pes").writeBytes(after)
        File(out, "$key.json").writeText(buildJsonObject {
            put("id", key); put("kind", kind); put("satin_parts", satinParts)
            put("changed", !before.contentEquals(after))
            put("blockers", report?.let { it.severityCounts[Severity.BLOCKER] ?: 0 } ?: -1)
            put("analysis_complete", report?.analysisComplete ?: false)
            put("blocking_codes", JsonArray(report?.findings.orEmpty().filter { it.severity == Severity.BLOCKER }.map { JsonPrimitive(it.code.toString()) }))
            error?.let { put("error", it) }
        }.toString() + "\n")
    }

    @JvmStatic fun main(args: Array<String>) {
        if (args[0] == "png") {
            // png <out-dir> <id,...>: render existing before/after PES for the comparison sheet.
            val out = File(args[1])
            for (id in args[2].split(',')) for (side in listOf("before", "after"))
                png(PesReader.read(File(out, "$id-$side.pes").readBytes()), File(out, "$id-$side.png"))
            return
        }
        val input = File(args[0]); val out = File(args[1]).apply { mkdirs() }
        val manifest = Json.parseToJsonElement(File("app/src/main/assets/samples/manifest.json").readText())
            .jsonObject.getValue("samples").jsonArray.map { it.jsonObject }
        val catalog = Json.parseToJsonElement(File("tools/samples/catalog.json").readText()).jsonObject.getValue("designs").jsonArray.map { it.jsonObject }
        for (entry in catalog) {
            val id = entry.getValue("id").jsonPrimitive.content
            val source = File(input, "$id.svg")
            val sample = manifest.first { it.getValue("file") == entry.getValue("file") }
            val before = File("app/src/main/assets/samples", sample.getValue("file").jsonPrimitive.content).readBytes()
            if (!source.isFile) { println("SKIP $id no svg (${entry.getValue("kind").jsonPrimitive.content})"); continue }
            val fit = sample.getValue("fitMm").jsonArray.map { it.jsonPrimitive.double }
            val art = VectorArt.parseSvg(source.readText())
            val width = min(fit[0], fit[1] / VectorArt.aspectRatio(art))
            // Each sample's own catalog hoop, as SamplePackTest maps it.
            val hoop = mapOf(listOf(100, 100) to HoopSpec.H100, listOf(130, 180) to HoopSpec.H130x180)
                .getValue(entry.getValue("hoopMm").jsonArray.map { it.jsonPrimitive.int })
            val result = VectorArt.render(art, width, name = id, hoop = hoop)
            val satin = result.parts.count { it.method == StitchTypeRule.Type.SATIN }
            val checked = runCatching { PesWriter.writeChecked(result.pattern, PesWriteSettings(hoop = hoop), origin = DesignOrigin.APP_GENERATED) }
            // A rejected write is recorded with its findings (never relaxed); the raw pattern is kept for inspection.
            val after = checked.getOrNull()?.bytes ?: PesWriter.prepareForOutput(result.pattern).let { raw ->
                io.github.linker2k.myembro.engine.io.PesWriter.write(raw, PesWriteSettings(hoop = HoopSpec.H130x180)) }
            val report = checked.getOrNull()?.report ?: SewoutCheck.checkPes(after, options = SewoutOptions(hoop = hoop, origin = DesignOrigin.APP_GENERATED))
            record(out, id, "sample", before, after, satin, report, checked.exceptionOrNull()?.message)
            println("DONE $id satin=$satin changed=${!before.contentEquals(after)} blockers=${report.severityCounts[Severity.BLOCKER] ?: 0}")
            System.out.flush()
        }
        // Lettering representatives: the same preparation as AndroidFontSnapshotTest.
        val cases = Json.parseToJsonElement(bytes("cases.json").decodeToString()).jsonArray
        for (value in cases) {
            val c = value.jsonObject
            fun field(key: String) = c.getValue(key).jsonPrimitive.content
            val style = StitchStyle.valueOf(field("style")); val text = field("text"); val fontId = field("fontId")
            val s = TtfFontSnapshotCodec.decode(GZIPInputStream(bytes("$fontId-${if (style == StitchStyle.SATIN) "satin" else "outline"}.json.gz").inputStream())
                .bufferedReader(Charsets.UTF_8).use { it.readText() })
            val height = c.getValue("heightMm").jsonPrimitive.double
            val request = TextPreparationRequest(text, fontId, height, 1.0, LetteringAlignment.CENTER, 9.0,
                EmbThread(0xff000000.toInt(), "Black 40wt"), stitchStyle = style)
            val centerline = if (style == StitchStyle.SATIN) {
                val codes = normalizedLetteringText(text).codePoints().toArray()
                val components = mutableListOf<Int>(); val widths = mutableMapOf<Int, Double>()
                val lines = arrayListOf(mutableListOf<LayoutGlyph>())
                codes.forEachIndexed { index, code ->
                    if (code == '\n'.code) lines += mutableListOf<LayoutGlyph>() else {
                        val char = String(Character.toChars(code)); val glyph = s.outline.glyphs.getValue(char)
                        val paths = if (Character.isWhitespace(code)) emptyList() else {
                            val traced = CenterlineGlyph.extract(s.rasters.getValue(char)); components += traced.components
                            widths[index] = HangulSatin.measuredWidthSource(glyph.paths, s.outline.reference)
                            traced.paths
                        }
                        lines.last() += LayoutGlyph(index, glyph.advance, paths)
                    }
                }
                val layout = layoutGlyphs(lines, s.outline.reference, height, 1.0, LetteringAlignment.CENTER, 9.0)
                CenterlineSatinData(layout.contours, layout.characters, components, widths)
            } else null
            val prepared = prepareText(request.copy(centerlineSatin = centerline), s.outline)
            val raw = prepared.localPattern()
            val pattern = if (raw.metadata[GeneratedPatternProcessor.FINISHED_KEY] == "v1") GeneratedPatternProcessor.finish(raw, options = SewingOptions()) else raw
            val after = PesWriter.write(pattern); val before = bytes(field("file"))
            val report = SewoutCheck.checkPes(after, options = SewoutOptions(hoop = HoopSpec.H100, origin = DesignOrigin.APP_GENERATED))
            val key = "lettering-$fontId-${if (text.all { it.code < 128 }) "latin" else text}-$style".lowercase()
            record(out, key, "lettering", before, after, if (style == StitchStyle.SATIN) 1 else 0, report, null)
            png(PesReader.read(before), File(out, "$key-before.png")); png(PesReader.read(after), File(out, "$key-after.png"))
            println("DONE $key changed=${!before.contentEquals(after)} blockers=${report.severityCounts[Severity.BLOCKER] ?: 0}")
        }
    }
}
