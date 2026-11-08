// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.inkstitch.stitches.FillGeometry
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test

/**
 * Opt-in: records every cross stitch call made while rendering each font's single-character glyphs
 * (input polygons as exact doubles, settings, start/end, port cycles) for tools/cross-stitch/oracle.py.
 * INKSTITCH_FONTS=<embroidery-fonts>/src CROSS_ORACLE_CASES=<out.json> CROSS_ORACLE_FONTS=a,b [CROSS_ORACLE_SCALES=1,1.5]
 */
class CrossStitchFontOracleExport {
    @Test fun export() {
        val src = System.getenv("INKSTITCH_FONTS")?.let(::File) ?: return
        val out = System.getenv("CROSS_ORACLE_CASES")?.let(::File) ?: return
        val fonts = System.getenv("CROSS_ORACLE_FONTS")?.split(',') ?: return
        val scales = (System.getenv("CROSS_ORACLE_SCALES") ?: "1").split(',').map { it.toDouble() }
        fun pt(x: Double, y: Double) = buildJsonArray { add(x); add(y) }
        val cases = buildJsonArray {
            for (name in fonts) {
                val dir = File(src, name)
                val font = File(dir, "ltr.svg").inputStream().use { InkStitchFont.parse(it, File(dir, "font.json").readText()) }
                val text = font.glyphs.keys.filter { it.length == 1 && !it[0].isWhitespace() }.sorted().joinToString("")
                for (scale in scales) {
                    val height = font.referenceHeightMm!! * scale
                    var n = 0
                    FontCrossStitch.trace = { shape, s, start, end, cycles ->
                        add(buildJsonObject {
                            put("id", "$name@$scale#${n++}")
                            put("polygons", buildJsonArray {
                                for (poly in FillGeometry.polygons(shape)) add(buildJsonArray {
                                    for (ring in listOf(poly.exteriorRing) + (0 until poly.numInteriorRing).map(poly::getInteriorRingN))
                                        add(buildJsonArray { ring.coordinates.forEach { add(pt(it.x, it.y)) } })
                                })
                            })
                            put("method", s.method); put("pattern", pt(s.patternSizePx.first, s.patternSizePx.second))
                            put("coverage", s.coverage); put("offset", pt(s.offsetPx.first, s.offsetPx.second))
                            put("canvas_grid_origin", s.canvasGridOrigin); put("thread_count", s.threadCount)
                            put("max_length", s.maxStitchLengthPx)
                            put("start", start?.let { pt(it.x, it.y) } ?: JsonNull); put("end", end?.let { pt(it.x, it.y) } ?: JsonNull)
                            put("port", buildJsonArray { cycles.forEach { c -> add(buildJsonArray { c.forEach { add(pt(it.x, it.y)) } }) } })
                        })
                    }
                    try { InkStitchLettering.render(font, text, height) } finally { FontCrossStitch.trace = null }
                    println("cross oracle export $name scale $scale: $n calls")
                }
            }
        }
        out.writeText(cases.toString())
    }
}
