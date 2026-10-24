// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.lettering.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.io.PesWriter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*

/** Original LoadedFont/prepareObject arithmetic frozen from a852926, without Android extraction.
 * TTF inputs are fixed extracted snapshots; this does not claim independent TTF extractor parity.
 */
class TextPreparationTest {
    private val thread = EmbThread(0xffb5492a.toInt(), "Craft", "40", "test")
    private fun nativeFont(): InkStitchFont {
        fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/inkstitch/font-equivalence/$name"))
        return InkStitchFont.parse(resource("font.svg"), resource("font.json").bufferedReader().use { it.readText() })
    }
    private fun fonts(): List<ResolvedTextFont> {
        val svg = SvgFont.parse("""<svg><defs><font horiz-adv-x="14"><font-face units-per-em="21"/>
            <missing-glyph d="M0,0 L8,20"/><glyph unicode="A" d="M0,0 L5,20 L10,0"/>
            <glyph unicode="B" d="M0,0 L0,20 L10,10 Z"/><glyph unicode="정" d="M0,0 L10,20"/>
            <glyph unicode="민" d="M0,20 L10,0"/><glyph unicode=" " horiz-adv-x="7"/></font></defs></svg>""")
        val contour = listOf(0.0 to -1000.0, 500.0 to -1000.0, 500.0 to 0.0, 0.0 to 0.0, 0.0 to -1000.0)
        val glyphs = listOf("A", "B", "정", "민", "😀").associateWith { PreparedGlyph(650.0, listOf(contour)) } +
            (" " to PreparedGlyph(300.0, emptyList()))
        return listOf(ResolvedTextFont.InkStitch(nativeFont()), ResolvedTextFont.Hershey(HersheyFont.DEFAULT),
            ResolvedTextFont.SingleLineSvg(svg), ResolvedTextFont.OutlineSnapshot(1000.0, glyphs,
                glyphs.keys.map { it.codePointAt(0) }.toSet()))
    }

    @Test fun preparedObjectsAndStitchesAreByteIdenticalToOriginalAssembly() {
        var cases = 0
        for (font in fonts()) for (text in listOf("AB", " A  B ", "A 정\r\nB 민", "정\n\nA\t민"))
            for (height in listOf(7.0, 12.0)) for (alignment in LetteringAlignment.entries) {
                val request = TextPreparationRequest(text, "fixed-font", height, 0.75, alignment, 3.0, thread,
                    characterColors = mapOf(0 to thread.color, 3 to 0xff7f9a76.toInt()), stitchStyle = StitchStyle.RUNNING)
                if (font is ResolvedTextFont.InkStitch) InkStitchFontRegistry.put(request.fontId, font.font)
                val expected = original(request, font)
                val result = prepareText(request, font, TextPreparationPolicy())
                assertSameBytes(expected, result)
                cases++
            }
        assertEquals(96, cases)
        println("TEXT_PREPARATION PASS: $cases objects (UTF-8 project JSON), ordered stitch bytes and PES bytes identical")
        InkStitchFontRegistry.invalidate("fixed-font")
    }

    @Test fun outlineFillAndCurvedSingleLineOptionsRemainIdentical() {
        for (font in fonts().filterNot { it is ResolvedTextFont.InkStitch }) {
            val outline = font is ResolvedTextFont.OutlineSnapshot
            val request = TextPreparationRequest("AB", "options", 10.0, 1.0, LetteringAlignment.RIGHT, 5.0, thread,
                arcDeg = if (outline) 0.0 else -35.0, arcRadiusMm = if (outline) null else 45.0,
                stitchStyle = if (outline) StitchStyle.FILL else StitchStyle.BEAN,
                fillOptions = FillOptions(underlay = false), threadWeight = ThreadWeight.WT50)
            assertSameBytes(original(request, font), prepareText(request, font))
        }
    }

    @Test fun directNativeRenderingRequiresNoHostRegistry() {
        val font = ResolvedTextFont.InkStitch(nativeFont())
        val request = TextPreparationRequest("AB", "never-registered", 10.0, 0.75,
            LetteringAlignment.CENTER, 3.0, thread)
        assertNull(InkStitchFontRegistry[request.fontId])
        val prepared = prepareText(request, font)
        assertEquals(InkStitchLettering.render(font.font, "AB", 10.0, thread,
            letterSpacingMm = 0.75, lineHeightMm = prepared.textObject.lineSpacing * 10.0, alignment = TextAlignment.CENTER).stitches,
            prepared.localPattern().stitches)
        assertNull(InkStitchFontRegistry[request.fontId])
    }

    private fun assertSameBytes(expected: TextObject, actual: PreparedText) {
        assertEquals(expected.copy(resolvedInkFont = actual.textObject.resolvedInkFont), actual.textObject)
        assertContentEquals(Project(objects = listOf(expected)).toJson().encodeToByteArray(),
            Project(objects = listOf(actual.textObject)).toJson().encodeToByteArray())
        val before = expected.localPattern()
        val after = actual.localPattern()
        assertContentEquals(patternBytes(before), patternBytes(after))
        // Export uses the same writer and defaults; comparing bytes also checks command conversion.
        assertContentEquals(PesWriter.write(before), PesWriter.write(after))
    }
    private fun patternBytes(pattern: EmbPattern): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(pattern.stitches.size)
            pattern.stitches.forEach { out.writeInt(it.x); out.writeInt(it.y); out.writeUTF(it.command.name) }
            out.writeUTF(Project(objects = listOf(StitchObject("stitches", "stitches", pattern))).toJson())
            out.writeUTF(pattern.metadata.toString()); out.writeUTF(pattern.warnings.toString())
        }
    }.toByteArray()

    private fun advance(font: ResolvedTextFont, character: String): Double = when (font) {
        is ResolvedTextFont.SingleLineSvg -> font.font.advance(character)
        is ResolvedTextFont.Hershey -> (character.singleOrNull()?.let { font.font[it] } ?: font.font['?'])!!.advance.toDouble()
        is ResolvedTextFont.OutlineSnapshot -> font.glyphs.getValue(character).advance
        else -> error("Not used for Ink/Stitch")
    }
    private fun glyph(font: ResolvedTextFont, character: String): List<List<Pair<Double, Double>>> = when (font) {
        is ResolvedTextFont.SingleLineSvg -> font.font.layout(character).map { path -> path.map { it.x to -it.y } }
        is ResolvedTextFont.Hershey -> {
            val g = character.singleOrNull()?.let { font.font[it] } ?: checkNotNull(font.font['?'])
            g.strokes.map { path -> path.map { (it.x - g.left).toDouble() to (it.y - font.font.baseline).toDouble() } }
        }
        is ResolvedTextFont.OutlineSnapshot -> font.glyphs.getValue(character).paths
        else -> error("Not used for Ink/Stitch")
    }
    private fun original(r: TextPreparationRequest, font: ResolvedTextFont): TextObject {
        val text = java.text.Normalizer.normalize(r.text, java.text.Normalizer.Form.NFC)
            .replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
        if (font is ResolvedTextFont.InkStitch) return TextObject(
            id = "lettering-preview", name = text.take(32), text = text, fontId = r.fontId,
            heightMm = r.heightMm, spacing = r.spacingMm, thread = r.thread, fontKind = FontKind.INKSTITCH,
            lineSpacing = 1 + r.lineSpacingMm / r.heightMm,
            alignment = TextAlignment.valueOf(r.alignment.name), singleLine = false, threadWeight = r.threadWeight)
        val reference = when (font) {
            is ResolvedTextFont.OutlineSnapshot -> font.reference
            is ResolvedTextFont.SingleLineSvg -> font.font.unitsPerEm.coerceAtLeast(1.0)
            else -> 21.0
        }
        val lines = arrayListOf(mutableListOf<LayoutGlyph>())
        text.codePoints().toArray().forEachIndexed { index, code ->
            if (code == '\n'.code) lines += mutableListOf<LayoutGlyph>()
            else String(Character.toChars(code)).let { lines.last() += LayoutGlyph(index, advance(font, it), glyph(font, it)) }
        }
        val layout = layoutGlyphs(lines, reference, r.heightMm, r.spacingMm, r.alignment, r.lineSpacingMm)
        return TextObject(id = "lettering-preview", name = text.take(32), text = text, fontId = r.fontId,
            heightMm = r.heightMm, spacing = r.spacingMm, arcDeg = r.arcDeg, thread = r.thread,
            fontKind = FontKind.OUTLINE, contours = layout.contours, contourSourceHeight = reference,
            lineSpacing = 1 + r.lineSpacingMm / r.heightMm, alignment = TextAlignment.valueOf(r.alignment.name),
            arcRadiusMm = r.arcRadiusMm,
            characterThreads = r.characterColors.mapValues { (_, color) -> if (color == r.thread.color) r.thread else EmbThread(color) },
            contourCharacters = layout.characters, contourBaselines = layout.baselines,
            singleLine = font !is ResolvedTextFont.OutlineSnapshot, centerlineSatin = r.centerlineSatin,
            stitchStyle = r.stitchStyle ?: StitchStyle.RUNNING,
            fillOptions = r.fillOptions, satinOptions = r.satinOptions, threadWeight = r.threadWeight)
    }
}
