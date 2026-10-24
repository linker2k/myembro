// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.lettering.ThreadWeight
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.engine.lettering.FillOptions
import io.github.linker2k.myembro.engine.lettering.SatinOptions
import io.github.linker2k.myembro.engine.lettering.FontQualityReport
import io.github.linker2k.myembro.engine.lettering.FontQuality
import io.github.linker2k.myembro.engine.model.EmbPattern

internal typealias LetteringAlignment = io.github.linker2k.myembro.engine.lettering.LetteringAlignment
internal enum class LetteringCurve { STRAIGHT, ARCH, CIRCLE, BELOW }

internal data class LetteringSettings(
    val text: String,
    val fontId: String,
    val heightMm: Double,
    val spacingMm: Double,
    val alignment: LetteringAlignment,
    val curved: Boolean,
    val curve: LetteringCurve,
    val curvature: Double,
    val lineSpacingMm: Double = 9.0,
    val radiusMm: Double = 45.0,
    val characterColors: Map<Int, Int> = emptyMap(),
    val stitchStyle: StitchStyle? = null,
    val fillOptions: FillOptions = FillOptions(),
    val satinOptions: SatinOptions = SatinOptions(),
    val threadWeight: ThreadWeight = ThreadWeight.WT40,
) {
    val arcDeg: Double get() = when {
        !curved || curve == LetteringCurve.STRAIGHT -> 0.0
        curve == LetteringCurve.CIRCLE -> 180.0 + curvature.coerceIn(0.0, 100.0) * 1.8
        curve == LetteringCurve.BELOW -> -curvature.coerceIn(0.0, 100.0) * 1.8
        else -> curvature.coerceIn(0.0, 100.0) * 1.8
    }
}

/** Practical lower bound plus the font's measured stroke requirement; never reduced to fit a hoop. */
internal fun filledLetteringMinimumHeight(report: FontQualityReport, weight: ThreadWeight = ThreadWeight.WT40): Double =
    maxOf(weight.minimumFillHeightMm, report.recommendedMinHeightMm)

/** Offline quality check for the editor property dialog; no original font file is needed. */
internal fun outlineTextQuality(text: TextObject): FontQualityReport {
    val points = text.contours.flatten()
    val extent = if (points.isEmpty()) 1.0 else (points.maxOf { it.second } - points.minOf { it.second }).coerceAtLeast(0.1)
    val scale = text.heightMm / (text.contourSourceHeight ?: extent)
    val glyphs = text.contours.indices.groupBy { text.contourCharacters.getOrNull(it) ?: 0 }.values.map { indices ->
        indices.map { index -> text.contours[index].map { (x, y) -> x * scale to y * scale } }
    }
    return FontQuality.analyze(glyphs, text.heightMm, outline = true, detailFloorMm = FontQuality.detailFloorMm(text.text),
        threadWeight = text.threadWeight)
}

internal fun lineOffset(width: Double, maximum: Double, alignment: LetteringAlignment): Double =
    io.github.linker2k.myembro.engine.lettering.lineOffset(width, maximum, alignment)

/** Align the final centered local pattern inside the hoop, using the exact integer hoop indices. */
internal fun placeLettering(text: TextObject, hoop: HoopSpec, alignment: LetteringAlignment): TextObject {
    val bounds = text.localPattern().bounds ?: return text
    val left=-(hoop.width/2).toDouble();val top=-(hoop.height/2).toDouble()
    val right=left+io.github.linker2k.myembro.engine.sewout.SewoutLimits.hoopMaxXUnits0p1mm(hoop)
    val bottom=top+io.github.linker2k.myembro.engine.sewout.SewoutLimits.hoopMaxYUnits0p1mm(hoop)
    val minX=left-bounds.minX;val maxX=right-bounds.maxX
    val minY=top-bounds.minY;val maxY=bottom-bounds.maxY
    val target=when(alignment) { LetteringAlignment.LEFT->minX;LetteringAlignment.RIGHT->maxX;LetteringAlignment.CENTER->0.0 }
    return text.copy(transform=Transform(x=if(minX<=maxX)target.coerceIn(minX,maxX)else 0.0,
        y=if(minY<=maxY)0.0.coerceIn(minY,maxY)else 0.0))
}

/** The preview and Add guard use the same real sewing area, including small rectangular hoops. */
internal fun letteringOutsideHoop(pattern: EmbPattern, hoop: HoopSpec): Boolean {
    val bounds = pattern.bounds ?: return false
    return !io.github.linker2k.myembro.engine.sewout.SewoutLimits.placedFits(hoop,bounds)
}

internal data class LetteringViewport(val scale: Float, val centerX: Double, val centerY: Double)

/** Fit the physical aspect ratio; text zoom follows placed text rather than the hoop origin. */
internal fun letteringViewport(availablePx: Float, pattern: EmbPattern?, hoop: HoopSpec, fitText: Boolean): LetteringViewport {
    val bounds = pattern?.bounds?.takeIf { fitText }
    val width = bounds?.let { (it.width * 1.4f).coerceAtLeast(200f) } ?: hoop.width.toFloat()
    val height = bounds?.let { (it.height * 1.8f).coerceAtLeast(200f) } ?: hoop.height.toFloat()
    return LetteringViewport(minOf(availablePx / width, availablePx / height),
        bounds?.let { (it.minX.toDouble() + it.maxX) / 2 } ?: 0.0,
        bounds?.let { (it.minY.toDouble() + it.maxY) / 2 } ?: 0.0)
}

internal fun normalizedLetteringText(text: String): String =
    io.github.linker2k.myembro.engine.lettering.normalizedLetteringText(text)

/** Keep colors on unchanged prefix/suffix characters when inserting/deleting text (code points, not UTF-16). */
internal fun remapCharacterColors(old: String, new: String, colors: Map<Int, Int>): HashMap<Int, Int> {
    val a = normalizedLetteringText(old).codePoints().toArray()
    val b = normalizedLetteringText(new).codePoints().toArray()
    var prefix = 0
    while (prefix < minOf(a.size, b.size) && a[prefix] == b[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(a.size, b.size) - prefix && a[a.lastIndex - suffix] == b[b.lastIndex - suffix]) suffix++
    return HashMap<Int, Int>().apply {
        colors.forEach { (index, color) ->
            when {
                index < prefix -> put(index, color)
                index >= a.size - suffix && index < a.size -> put(index + b.size - a.size, color)
            }
        }
    }
}

internal typealias LayoutGlyph = io.github.linker2k.myembro.engine.lettering.LayoutGlyph
internal typealias LetteringLayout = io.github.linker2k.myembro.engine.lettering.LetteringLayout

/** App settings adapter; all source-unit layout is shared with JVM callers. */
internal fun layoutGlyphs(lines: List<List<LayoutGlyph>>, reference: Double, settings: LetteringSettings): LetteringLayout =
    io.github.linker2k.myembro.engine.lettering.layoutGlyphs(lines, reference, settings.heightMm,
        settings.spacingMm, settings.alignment, settings.lineSpacingMm)

/** UI projection only: editing retains the editor object's transform; new text starts centered. */
internal fun letteringPreviewObject(generated: TextObject, original: TextObject?): TextObject =
    generated.copy(transform = original?.transform ?: Transform())

internal data class LetteringDraftSnapshot(val text: String, val fontId: String, val height: String, val color: Int, val singleLineBold: Boolean = false)
