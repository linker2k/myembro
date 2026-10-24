// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.*

/** Font outlines are supplied by the platform; no Android or java.awt dependency in this API. */
object GlyphStrokeWidths {
    /** Flattened closed contours in font/pixel units, including holes (even-odd fill). */
    data class Glyph(val characterIndex: Int, val codePoint: Int,
        val contours: List<List<Pair<Double, Double>>>)

    /** [sourceHeight] must match LetteringEngine.fromContours, e.g. the font's ‘자’ reference. */
    data class FontOutlines(val fontId: String, val sourceHeight: Double, val glyphs: List<Glyph>)

    data class Row(val characterIndex: Int, val codePoint: Int,
        /** Null means no measurable ink (space, missing outline, or collapsed geometry), never zero. */
        val medianWidthMm: Double?, val sampleCount: Int)

    data class Table(val fontId: String, val heightMm: Double, val boundarySpacingMm: Double,
        val rows: List<Row>)

    /**
     * Median of sampled interior medial-circle diameters, in mm, at the requested physical height.
     * Reuses StrokeWidthMeasurement: boundary Voronoi vertices, diameter=2*boundary distance.
     * This is a sampled statistic, not a minimum width or a validated satin/fill threshold.
     * Run off the UI thread; callers may cache by font revision, text, height and sample spacing.
     * Malformed inputs fail explicitly; unmeasurable empty ink returns a null row.
     */
    fun measure(font: FontOutlines, heightMm: Double, boundarySpacingMm: Double = 0.1): Table {
        require(font.fontId.isNotBlank())
        require(font.sourceHeight.isFinite() && font.sourceHeight > 0)
        require(heightMm.isFinite() && heightMm > 0)
        require(boundarySpacingMm.isFinite() && boundarySpacingMm > 0)
        require(font.glyphs.map { it.characterIndex }.distinct().size == font.glyphs.size)
        val scale = heightMm / font.sourceHeight
        require(scale.isFinite() && scale > 0)
        val rows = font.glyphs.map { glyph ->
            require(glyph.characterIndex >= 0 && Character.isValidCodePoint(glyph.codePoint) &&
                glyph.codePoint !in 0xD800..0xDFFF)
            require(glyph.contours.all { path -> path.all { (x,y) -> x.isFinite() && y.isFinite() } })
            val region = PolygonRegion.fromPaths(glyph.contours.map { contour ->
                Polyline(contour.map { (x,y) -> Point(x * scale, y * scale) }, closed = true)
            }, FillRule.EVEN_ODD)
            if (region.isEmpty) Row(glyph.characterIndex, glyph.codePoint, null, 0)
            else {
                val result = StrokeWidthMeasurement.measure(region.geometry, boundarySpacingMm)
                Row(glyph.characterIndex, glyph.codePoint, result.medianMm, result.samples)
            }
        }
        return Table(font.fontId, heightMm, boundarySpacingMm, rows)
    }
}
