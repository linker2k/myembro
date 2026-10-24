// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.FontKind
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFont
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchLettering
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread

/** Resolved local inputs only: preparation never downloads fonts or invokes a platform extractor. */
sealed interface ResolvedTextFont {
    data class InkStitch(val font: InkStitchFont) : ResolvedTextFont
    data class Hershey(val font: HersheyFont) : ResolvedTextFont
    data class SingleLineSvg(val font: SvgFont) : ResolvedTextFont
    /** +y down, baseline zero; outlines already include the adapter's half-advance correction. */
    data class OutlineSnapshot(val reference: Double, val glyphs: Map<String, PreparedGlyph>,
        val supportedCodes: Set<Int>) : ResolvedTextFont
}

data class PreparedGlyph(val advance: Double, val paths: List<List<Pair<Double, Double>>>)

val ResolvedTextFont.reference: Double get() = when (this) {
    is ResolvedTextFont.OutlineSnapshot -> reference
    is ResolvedTextFont.SingleLineSvg -> font.unitsPerEm.coerceAtLeast(1.0)
    else -> 21.0 // Existing app source-unit reference, including custom Hershey/JHF.
}

fun ResolvedTextFont.advance(character: String): Double = when (this) {
    is ResolvedTextFont.OutlineSnapshot -> glyphs.getValue(character).advance
    is ResolvedTextFont.SingleLineSvg -> font.advance(character)
    is ResolvedTextFont.Hershey -> (character.singleOrNull()?.let { font[it] } ?: font['?'])!!.advance.toDouble()
    is ResolvedTextFont.InkStitch -> error("Predigitized fonts use Ink/Stitch layout")
}

fun ResolvedTextFont.glyph(character: String): List<List<Pair<Double, Double>>> = when (this) {
    is ResolvedTextFont.OutlineSnapshot -> glyphs.getValue(character).paths
    is ResolvedTextFont.SingleLineSvg -> font.layout(character).map { path -> path.map { it.x to -it.y } }
    is ResolvedTextFont.Hershey -> {
        val g = character.singleOrNull()?.let { font[it] } ?: checkNotNull(font['?'])
        g.strokes.map { path -> path.map { (it.x - g.left).toDouble() to (it.y - font.baseline).toDouble() } }
    }
    is ResolvedTextFont.InkStitch -> error("Predigitized fonts use Ink/Stitch layout")
}

fun ResolvedTextFont.supports(code: Int): Boolean = when (this) {
    is ResolvedTextFont.OutlineSnapshot -> code in supportedCodes
    is ResolvedTextFont.SingleLineSvg -> String(Character.toChars(code)) in font.glyphs
    is ResolvedTextFont.Hershey -> code <= Char.MAX_VALUE.code && font.supports(code.toChar())
    is ResolvedTextFont.InkStitch -> String(Character.toChars(code)).let { c ->
        when (font.info.letterCase) { "upper" -> c.uppercase(); "lower" -> c.lowercase(); else -> c }
    } in font.glyphs
}

data class TextPreparationRequest(
    val text: String, val fontId: String, val heightMm: Double, val spacingMm: Double,
    val alignment: LetteringAlignment, val lineSpacingMm: Double, val thread: EmbThread,
    val arcDeg: Double = 0.0, val arcRadiusMm: Double? = null,
    val characterColors: Map<Int, Int> = emptyMap(), val stitchStyle: StitchStyle? = null,
    val fillOptions: FillOptions = FillOptions(), val satinOptions: SatinOptions = SatinOptions(),
    val threadWeight: ThreadWeight = ThreadWeight.WT40,
    /** Already prepared by the host; extracting/tracing centerlines is outside this API's current scope. */
    val centerlineSatin: CenterlineSatinData? = null,
)

/** Host selects/validates the method before preparing, as before; no new automatic rules. */
data class TextPreparationPolicy(val objectId: String = "lettering-preview")

class PreparedText internal constructor(val textObject: TextObject, private val font: ResolvedTextFont) {
    /** Direct headless rendering binds parsed Ink/Stitch data without a global registry mutation.
     * Editable Ink/Stitch objects still require their font ID registered when replayed independently.
     */
    fun localPattern(): EmbPattern = when (font) {
        is ResolvedTextFont.InkStitch -> with(textObject) {
            InkStitchLettering.render(font.font, text, heightMm, thread, letterSpacingMm = spacing,
                lineHeightMm = lineSpacing * heightMm, alignment = alignment)
        }
        else -> textObject.localPattern()
    }
}

fun prepareText(request: TextPreparationRequest, font: ResolvedTextFont,
    policy: TextPreparationPolicy = TextPreparationPolicy()): PreparedText {
    val text = normalizedLetteringText(request.text)
    if (font is ResolvedTextFont.InkStitch) return PreparedText(TextObject(
        id = policy.objectId, name = text.take(32), text = text, fontId = request.fontId,
        heightMm = request.heightMm, spacing = request.spacingMm, thread = request.thread, fontKind = FontKind.INKSTITCH,
        resolvedInkFont = font.font,
        lineSpacing = 1 + request.lineSpacingMm / request.heightMm,
        alignment = TextAlignment.valueOf(request.alignment.name), singleLine = false, threadWeight = request.threadWeight,
    ), font)
    val lines = arrayListOf(mutableListOf<LayoutGlyph>())
    text.codePoints().toArray().forEachIndexed { index, code ->
        if (code == '\n'.code) lines += mutableListOf<LayoutGlyph>()
        else {
            val character = String(Character.toChars(code))
            lines.last() += LayoutGlyph(index, font.advance(character), font.glyph(character))
        }
    }
    val layout = layoutGlyphs(lines, font.reference, request.heightMm, request.spacingMm,
        request.alignment, request.lineSpacingMm)
    return PreparedText(TextObject(
        id = policy.objectId, name = text.take(32), text = text, fontId = request.fontId,
        heightMm = request.heightMm, spacing = request.spacingMm, arcDeg = request.arcDeg,
        thread = request.thread, fontKind = FontKind.OUTLINE, contours = layout.contours, contourSourceHeight = font.reference,
        lineSpacing = 1 + request.lineSpacingMm / request.heightMm,
        alignment = TextAlignment.valueOf(request.alignment.name), arcRadiusMm = request.arcRadiusMm,
        characterThreads = request.characterColors.mapValues { (_, color) -> if (color == request.thread.color) request.thread else EmbThread(color) },
        contourCharacters = layout.characters, contourBaselines = layout.baselines,
        singleLine = font !is ResolvedTextFont.OutlineSnapshot, centerlineSatin = request.centerlineSatin,
        stitchStyle = request.stitchStyle ?: StitchStyle.RUNNING,
        fillOptions = request.fillOptions, satinOptions = request.satinOptions, threadWeight = request.threadWeight,
    ), font)
}
