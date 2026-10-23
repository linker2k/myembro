// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/lettering/font.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.inkstitch.elements.InkElementSewing

import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Sews text in an [InkStitchFont] with this engine's own satin, tatami and running-stitch
 * generators, driven by each element's `inkstitch:*` parameters (Ink/Stitch defaults when absent).
 * Layout follows Ink/Stitch lettering: glyph extents, `horiz_adv_x`, kerning pairs, word spacing
 * and leading from `font.json`. Geometry scales; stitch lengths and spacings stay in mm.
 */
object InkStitchLettering {
    /** One glyph placed on the page: font user units are mapped by x * scale + dx, y * scale + dy (mm). */
    data class Placement(val glyph: InkStitchGlyph, val dx: Double, val dy: Double, val scale: Double) {
        val mapping: InkElementSewing.Mapping get() = InkElementSewing.Mapping(dx, dy, scale)
    }

    /**
     * [heightMm] is the ink height of the font's reference glyph ('H'), as for every other font.
     * [thread] sews everything in one thread; null keeps the font's own colors.
     */
    fun render(
        font: InkStitchFont,
        text: String,
        heightMm: Double,
        thread: EmbThread? = null,
        letterSpacingMm: Double = 0.0,
        lineHeightMm: Double? = null,
        alignment: TextAlignment = TextAlignment.LEFT,
        backAndForth: Boolean = true,
    ): EmbPattern {
        val placements = layout(font, text, heightMm, letterSpacingMm, lineHeightMm, alignment, backAndForth)
        val sewer = InkElementSewing.Sewer(thread)
        for (p in placements) for (element in p.glyph.elements) sewer.add(element, p.mapping)
        return sewer.finish(mapOf("name" to text, "font" to font.info.name))
    }

    /**
     * Lines step by [lineHeightMm], or by the font's `leading` when null. [alignment] moves each
     * line by its own ink-box width as Ink/Stitch's text_align does. With [backAndForth] (Ink/Stitch's
     * default) every second line of a reversible font is sewn right to left; positions stay.
     */
    fun layout(
        font: InkStitchFont, text: String, heightMm: Double, letterSpacingMm: Double = 0.0,
        lineHeightMm: Double? = null, alignment: TextAlignment = TextAlignment.LEFT, backAndForth: Boolean = true,
    ): List<Placement> {
        require(heightMm.isFinite() && heightMm > 0) { "Height must be positive" }
        require(letterSpacingMm.isFinite() && (lineHeightMm == null || lineHeightMm.isFinite() && lineHeightMm > 0))
        val reference = font.referenceHeightMm ?: return emptyList()
        val mmPerUnit = InkStitchFont.MM_PER_PX * heightMm / reference
        val info = font.info
        val letterSpacing = letterSpacingMm / mmPerUnit
        val out = ArrayList<Placement>()
        var y = 0.0
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        for ((index, rawLine) in normalized.lines().withIndex()) {
            val line = rawLine.trim()
            val placed = ArrayList<Placement>()
            var x = 0.0
            var left = Double.POSITIVE_INFINITY
            var right = Double.NEGATIVE_INFINITY
            for (rawWord in line.split(' ')) {
                val word = when (info.letterCase) { "upper" -> rawWord.uppercase(); "lower" -> rawWord.lowercase(); else -> rawWord }
                var last: String? = null
                var i = 0
                while (i < word.length) {
                    var (glyph, length) = font.glyphAt(word, i)
                    if (glyph == null && info.defaultGlyph != " ") glyph = info.defaultGlyph?.let { font.glyphs[it] }
                    i += length
                    if (glyph == null) { x += info.wordSpacing; last = null; continue }
                    if (last != null) {
                        val kern = info.kerning["$last ${glyph.name}"] ?: info.kerning[last + glyph.name] ?: 0.0
                        x += glyph.minX - kern + letterSpacing
                    }
                    placed += Placement(glyph, (x - glyph.minX) * mmPerUnit, (y - font.baseline) * mmPerUnit, mmPerUnit)
                    left = min(left, x); right = max(right, x + glyph.maxX - glyph.minX)
                    x += (info.advances[glyph.name] ?: info.defaultAdvance ?: glyph.maxX) - glyph.minX
                    last = glyph.name
                }
                x += info.wordSpacing
            }
            val width = if (right > left) (right - left) * mmPerUnit else 0.0
            val shift = when (alignment) {
                TextAlignment.LEFT -> 0.0
                TextAlignment.CENTER -> -width / 2
                TextAlignment.RIGHT -> -width
            }
            val shifted = placed.map { it.copy(dx = it.dx + shift) }
            out += if (backAndForth && info.reversible && index % 2 == 1) shifted.asReversed() else shifted
            y += lineHeightMm?.let { it / mmPerUnit } ?: info.leading
        }
        return out
    }
}

/**
 * Installed Ink/Stitch fonts by id, for [io.github.linker2k.myembro.engine.edit.TextObject]s that
 * name one. The app sets [loader] (parsing takes up to a second, so results are kept).
 */
object InkStitchFontRegistry {
    @Volatile var loader: ((String) -> InkStitchFont?)? = null
    private val fonts = java.util.concurrent.ConcurrentHashMap<String, InkStitchFont>()

    operator fun get(id: String): InkStitchFont? = fonts[id] ?: loader?.invoke(id)?.also { fonts[id] = it }
    fun put(id: String, font: InkStitchFont) { fonts[id] = font }
    fun invalidate(id: String) { fonts.remove(id) }
}
