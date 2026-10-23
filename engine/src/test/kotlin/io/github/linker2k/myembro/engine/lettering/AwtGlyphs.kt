// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.PathIterator
import java.io.File

/** One font's 10 mm / 20 mm analysis, as the app's LetterFontGeometry.analyze produces it. */
internal data class FontAnalysis(val at10: FontQualityReport, val at20: FontQualityReport, val hangul: Boolean, val grade: Grade) {
    val recommendedMinHeightMm get() = at10.recommendedMinHeightMm
}

/**
 * JVM stand-in for the app's glyph geometry in tests and the catalog tool: the same outlines
 * (java.awt maps characters through the font's own cmap, without system fallback), the same
 * reference glyph for scaling ("자", else "H") and the same script sample.
 */
internal object AwtGlyphs {
    fun load(file: File): Font = Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(1000f)

    /** Closed contours of [text], +y down, at size 1000 like AndroidGlyphOutlines. */
    fun contours(font: Font, text: String): List<List<Pair<Double, Double>>> {
        val iterator = font.createGlyphVector(FontRenderContext(null, false, true), text).outline.getPathIterator(null, 0.5)
        val result = mutableListOf<List<Pair<Double, Double>>>()
        var current = mutableListOf<Pair<Double, Double>>()
        val c = DoubleArray(6)
        while (!iterator.isDone) {
            when (iterator.currentSegment(c)) {
                PathIterator.SEG_MOVETO -> {
                    if (current.size >= 2) result += current
                    current = mutableListOf(c[0] to c[1])
                }
                PathIterator.SEG_LINETO -> if (current.lastOrNull() != c[0] to c[1]) current += c[0] to c[1]
                PathIterator.SEG_CLOSE -> {
                    if (current.size >= 2) result += current
                    current = mutableListOf()
                }
            }
            iterator.next()
        }
        if (current.size >= 2) result += current
        return result
    }

    fun analyze(file: File): FontAnalysis {
        val font = load(file)
        val hangul = font.canDisplay('가')
        val chars = FontQuality.sample(hangul).filter(font::canDisplay)
        val reference = listOf('자', 'H').firstOrNull(font::canDisplay)?.let { ref ->
            contours(font, ref.toString()).flatten().takeIf { it.isNotEmpty() }?.let { p -> p.maxOf { it.second } - p.minOf { it.second } }
        }?.coerceAtLeast(1.0) ?: 1000.0
        fun at(heightMm: Double) = FontQuality.analyze(
            chars.map { ch -> contours(font, ch.toString()).map { c -> c.map { (x, y) -> x * heightMm / reference to y * heightMm / reference } } },
            heightMm, outline = true, detailFloorMm = if (hangul) FontQuality.HANGUL_DETAIL_MM else FontQuality.LATIN_DETAIL_MM,
        )
        val at10 = at(10.0)
        val at20 = at(20.0)
        return FontAnalysis(at10, at20, hangul, FontQuality.fontGrade(at10, at20, !hangul || !font.canDisplay('A')))
    }

    fun analyzeSvg(svg: SvgFont): FontAnalysis {
        val hangul = svg.supports('가')
        val chars = FontQuality.sample(hangul).filter(svg::supports)
        val points = (svg.glyph('H') ?: svg.glyph('가') ?: svg.glyphs.values.firstOrNull { it.strokes.isNotEmpty() })?.strokes?.flatten().orEmpty()
        val reference = if (points.isEmpty()) svg.unitsPerEm * 0.7 else (points.maxOf { it.y } - points.minOf { it.y }).coerceAtLeast(1.0)
        fun at(heightMm: Double) = FontQuality.analyze(
            chars.map { ch -> svg.glyph(ch)?.strokes.orEmpty().map { s -> s.map { it.x * heightMm / reference to -it.y * heightMm / reference } } },
            heightMm, outline = false, detailFloorMm = if (hangul) FontQuality.HANGUL_DETAIL_MM else FontQuality.LATIN_DETAIL_MM,
        )
        val at10 = at(10.0)
        val at20 = at(20.0)
        return FontAnalysis(at10, at20, hangul, FontQuality.fontGrade(at10, at20, !hangul || !svg.supports('A')))
    }
}
