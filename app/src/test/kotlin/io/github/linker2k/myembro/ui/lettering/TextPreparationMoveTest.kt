// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import kotlin.test.Test
import kotlin.test.assertEquals

/** Frozen pre-extraction implementation from main 7fad04a, compared exactly (no numeric tolerance). */
class TextPreparationMoveTest {
    @Test fun layoutAndNormalizationRemainIdenticalAcrossSourceScalesAndText() {
        var cases = 0
        for (raw in listOf("AB", " A  B ", "정민 Ab", "정\r\n A\t민\r\n\n", "", "😀 A\n민 ")) {
            val expectedText = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
            assertEquals(expectedText, normalizedLetteringText(raw))
            for (reference in listOf(10.0, 21.0, 128.0, 256.0, 512.0, 750.0, 1000.0, 1024.0, 2048.0)) {
                // Different font-unit metrics; blank glyphs advance without owning a contour.
                var index = 0
                val lines = expectedText.split('\n').map { line ->
                    val glyphs = line.codePoints().toArray().map { code ->
                        val owner = index++
                        val advance = reference * if (code > 127) 1.0 else 0.6
                        val paths = if (Character.isWhitespace(code)) emptyList() else listOf(
                            listOf(0.0 to -reference, advance to 0.0),
                            listOf(advance / 3 to -reference / 2, advance / 2 to 0.0))
                        LayoutGlyph(owner, advance, paths)
                    }
                    index++ // Newline code-point slot, matching the preparation caller.
                    glyphs
                }
                for (height in listOf(7.0, 15.0, 30.0)) for (spacing in listOf(-0.25, 0.0, 1.5))
                    for (lineSpacing in listOf(0.0, 9.0)) for (alignment in LetteringAlignment.entries) {
                        val settings = LetteringSettings(expectedText, "fixed-metrics", height, spacing,
                            alignment, false, LetteringCurve.STRAIGHT, 0.0, lineSpacingMm = lineSpacing)
                        val expected = originalLayout(lines, reference, settings)
                        assertEquals(expected, layoutGlyphs(lines, reference, settings), "case $cases")
                        assertEquals(expected, io.github.linker2k.myembro.engine.lettering.layoutGlyphs(
                            lines, reference, height, spacing, alignment, lineSpacing), "JVM case $cases")
                        cases++
                    }
            }
        }
        assertEquals(2916, cases)
        println("TEXT_PREP_MOVE PASS: $cases exact layout comparisons; NFC/CRLF/tab/mixed-script inputs")
    }

    /** Source-unit layout shared by outline and single-line fonts, preserving character ownership. */
    private fun originalLayout(lines: List<List<LayoutGlyph>>, reference: Double, settings: LetteringSettings): LetteringLayout {
        val gap = settings.spacingMm / settings.heightMm * reference
        val widths = lines.map { line -> line.sumOf { it.advance } + (line.size - 1).coerceAtLeast(0) * gap }
        val maximum = widths.maxOrNull() ?: 0.0
        val contours = arrayListOf<List<Pair<Double, Double>>>()
        val characters = arrayListOf<Int>()
        val baselines = arrayListOf<Double>()
        lines.forEachIndexed { lineIndex, line ->
            var cursor = -widths[lineIndex] / 2 + when (settings.alignment) {
                LetteringAlignment.LEFT -> -(maximum - widths[lineIndex]) / 2
                LetteringAlignment.CENTER -> 0.0
                LetteringAlignment.RIGHT -> (maximum - widths[lineIndex]) / 2
            }
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

}
