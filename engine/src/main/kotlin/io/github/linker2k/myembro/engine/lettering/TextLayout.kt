// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

/** Alignment of source-unit glyph lines before object placement. */
enum class LetteringAlignment { LEFT, CENTER, RIGHT }

fun normalizedLetteringText(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
    .replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")

/** Extra offset for lines shorter than the longest one; inputs share the same font units. */
fun lineOffset(width: Double, maximum: Double, alignment: LetteringAlignment): Double = when (alignment) {
    LetteringAlignment.LEFT -> -(maximum - width) / 2
    LetteringAlignment.CENTER -> 0.0
    LetteringAlignment.RIGHT -> (maximum - width) / 2
}

data class LayoutGlyph(val index: Int, val advance: Double, val paths: List<List<Pair<Double, Double>>>)
data class LetteringLayout(
    val contours: List<List<Pair<Double, Double>>>,
    val characters: List<Int>,
    val baselines: List<Double>,
)

/** Source-unit layout shared by outline and single-line fonts, preserving character ownership. */
fun layoutGlyphs(lines: List<List<LayoutGlyph>>, reference: Double, heightMm: Double, spacingMm: Double,
    alignment: LetteringAlignment, lineSpacingMm: Double): LetteringLayout {
    val gap = spacingMm / heightMm * reference
    val widths = lines.map { line -> line.sumOf { it.advance } + (line.size - 1).coerceAtLeast(0) * gap }
    val maximum = widths.maxOrNull() ?: 0.0
    val contours = arrayListOf<List<Pair<Double, Double>>>()
    val characters = arrayListOf<Int>()
    val baselines = arrayListOf<Double>()
    lines.forEachIndexed { lineIndex, line ->
        var cursor = -widths[lineIndex] / 2 + lineOffset(widths[lineIndex], maximum, alignment)
        val baseline = lineIndex * reference * (1 + lineSpacingMm / heightMm)
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
