// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.Transform

internal enum class LetteringAlignment { LEFT, CENTER, RIGHT }
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
) {
    val arcDeg: Double get() = when {
        !curved || curve == LetteringCurve.STRAIGHT -> 0.0
        curve == LetteringCurve.CIRCLE -> 180.0 + curvature.coerceIn(0.0, 100.0) * 1.8
        curve == LetteringCurve.BELOW -> -curvature.coerceIn(0.0, 100.0) * 1.8
        else -> curvature.coerceIn(0.0, 100.0) * 1.8
    }
}

/** Extra offset for lines shorter than the longest one; inputs share the same font units. */
internal fun lineOffset(width: Double, maximum: Double, alignment: LetteringAlignment): Double = when (alignment) {
    LetteringAlignment.LEFT -> -(maximum - width) / 2
    LetteringAlignment.CENTER -> 0.0
    LetteringAlignment.RIGHT -> (maximum - width) / 2
}

/** Align the final centered local pattern inside the hoop, keeping a 5 mm margin. */
internal fun placeLettering(text: TextObject, hoop: HoopSpec, alignment: LetteringAlignment): TextObject {
    val bounds = text.localPattern().bounds ?: return text
    val room = ((hoop.width - bounds.width) / 2.0 - 50.0).coerceAtLeast(0.0)
    val x = when (alignment) {
        LetteringAlignment.LEFT -> -room
        LetteringAlignment.CENTER -> 0.0
        LetteringAlignment.RIGHT -> room
    }
    return text.copy(transform = Transform(x = x))
}

internal fun normalizedLetteringText(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
    .replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")

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

internal data class LayoutGlyph(val index: Int, val advance: Double, val paths: List<List<Pair<Double, Double>>>)
internal data class LetteringLayout(
    val contours: List<List<Pair<Double, Double>>>,
    val characters: List<Int>,
    val baselines: List<Double>,
)

/** Source-unit layout shared by outline and single-line fonts, preserving character ownership. */
internal fun layoutGlyphs(lines: List<List<LayoutGlyph>>, reference: Double, settings: LetteringSettings): LetteringLayout {
    val gap = settings.spacingMm / settings.heightMm * reference
    val widths = lines.map { line -> line.sumOf { it.advance } + (line.size - 1).coerceAtLeast(0) * gap }
    val maximum = widths.maxOrNull() ?: 0.0
    val contours = arrayListOf<List<Pair<Double, Double>>>()
    val characters = arrayListOf<Int>()
    val baselines = arrayListOf<Double>()
    lines.forEachIndexed { lineIndex, line ->
        var cursor = -widths[lineIndex] / 2 + lineOffset(widths[lineIndex], maximum, settings.alignment)
        val baseline = lineIndex * reference * (1 + settings.lineSpacingMm / settings.heightMm)
        line.forEach { glyph ->
            glyph.paths.forEach { path ->
                contours += path.map { (x, y) -> x + cursor to y + baseline }
                characters += glyph.index
                baselines += baseline
            }
            cursor += glyph.advance + gap
        }
    }
    return LetteringLayout(contours, characters, baselines)
}
