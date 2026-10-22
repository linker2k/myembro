// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.FontKind
import io.github.linker2k.myembro.engine.edit.contains
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.FontQuality
import io.github.linker2k.myembro.engine.lettering.FontQualityReport
import io.github.linker2k.myembro.engine.lettering.HersheyFont
import io.github.linker2k.myembro.engine.lettering.SvgFont
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.fonts.CatalogFont
import io.github.linker2k.myembro.fonts.FontRepository
import io.github.linker2k.myembro.lettering.AndroidGlyphOutlines
import java.text.Normalizer

internal data class LetteringFont(val id: String, val name: String, val resource: Int = 0)

internal val bundledLetteringFonts = listOf(
    LetteringFont("gowun-batang", "고운바탕", R.font.gowun_batang),
    LetteringFont("jua", "둥근고딕", R.font.jua_regular),
    LetteringFont("noto-sans-kr", "담백한 고딕", R.font.noto_sans_kr),
)

internal data class LetteringResult(
    val textObject: TextObject,
    val pattern: EmbPattern,
    val quality: FontQualityReport,
    val unsupported: Boolean,
    val outsideHoop: Boolean,
)

/** Called on Dispatchers.Default; no font parsing or quality scanning on the Compose UI thread. */
internal fun renderLettering(
    context: Context, settings: LetteringSettings, thread: EmbThread, hoop: HoopSpec,
    initialObject: TextObject? = null,
): LetteringResult {
    // Reuse saved contours for size/curve/color edits, even when the source font is offline.
    val saved = initialObject?.let { reuseSavedLettering(it, settings, thread) }
    if (saved != null) {
        val adjusted = saved
        val pattern = adjusted.placedPattern()
        val paths = listOf(listOf(pattern.stitches.filter { it.command == Command.STITCH }.map { it.x / 10.0 to it.y / 10.0 }))
        val quality = FontQuality.analyze(paths, settings.heightMm, outline = adjusted.fontKind == FontKind.OUTLINE)
        return LetteringResult(adjusted, pattern, quality, false, pattern.bounds?.let { !hoop.contains(it) } ?: false)
    }
    val text = Normalizer.normalize(settings.text, Normalizer.Form.NFC).replace("\r\n", "\n")
        .replace('\r', '\n').replace("\t", "    ")
    val repo = FontRepository(context)
    val bundled = bundledLetteringFonts.firstOrNull { it.id == settings.fontId }
    val installed = if (bundled == null) repo.find(settings.fontId) else null
    val face: Typeface? = when {
        bundled != null -> context.resources.getFont(bundled.resource)
        installed?.kind == CatalogFont.Kind.OUTLINE_TTF -> repo.typeface(installed)
        else -> null
    }
    val svg: SvgFont? = if (installed?.kind == CatalogFont.Kind.SINGLE_LINE_SVG) repo.svgFont(installed) else null
    val hershey = if (settings.fontId == HersheyFont.DEFAULT_ID) HersheyFont.DEFAULT else null
    check(face != null || svg != null || hershey != null) { "선택한 폰트를 불러올 수 없습니다. 폰트 보관함을 확인해 주세요." }
    val paint = face?.let { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = it; textSize = 1000f } }
    fun glyph(character: String): List<List<Pair<Double, Double>>> = when {
        face != null -> AndroidGlyphOutlines.extract(character, face)
        svg != null -> svg.layout(character).map { path -> path.map { it.x to -it.y } }
        else -> {
            val font = checkNotNull(hershey)
            val g = character.singleOrNull()?.let { font[it] } ?: checkNotNull(font['?'])
            g.strokes.map { path -> path.map { (it.x - g.left).toDouble() to (it.y - font.baseline).toDouble() } }
        }
    }
    val reference = when {
        face != null -> glyph(if (paint?.hasGlyph("자") == true) "자" else "H").flatten().let { points ->
            if (points.isEmpty()) 1000.0 else (points.maxOf { it.second } - points.minOf { it.second }).coerceAtLeast(1.0)
        }
        svg != null -> svg.unitsPerEm.coerceAtLeast(1.0)
        else -> 21.0
    }
    val extra = settings.spacingMm / settings.heightMm * reference
    paint?.letterSpacing = (extra / 1000).toFloat()
    fun characters(line: String) = line.codePoints().toArray().map { String(Character.toChars(it)) }
    fun advance(character: String): Double = when {
        paint != null -> paint.measureText(character).toDouble()
        svg != null -> svg.advance(character)
        else -> (character.singleOrNull()?.let { hershey?.get(it) } ?: hershey?.get('?'))!!.advance.toDouble()
    }
    val lines = text.split('\n')
    fun width(line: String): Double = if (paint != null) paint.measureText(line).toDouble() else
        characters(line).sumOf(::advance) + (characters(line).size - 1).coerceAtLeast(0) * extra
    val widths = lines.map(::width)
    val maximum = widths.maxOrNull() ?: 0.0
    val contours = arrayListOf<List<Pair<Double, Double>>>()
    lines.forEachIndexed { index, line ->
        val offset = lineOffset(widths[index], maximum, settings.alignment)
        val paths = when {
            face != null -> AndroidGlyphOutlines.extract(line, face, spacing = extra.toFloat())
            svg != null -> svg.layout(line, extra).map { path -> path.map { (it.x - widths[index] / 2) to -it.y } }
            else -> {
                var cursor = -widths[index] / 2
                characters(line).flatMap { character ->
                    val paths = glyph(character).map { path -> path.map { (x, y) -> x + cursor to y } }
                    cursor += advance(character) + extra
                    paths
                }
            }
        }
        contours += paths.map { path -> path.map { (x, y) -> x + offset to y + index * reference * 1.5 } }
    }
    val objectData = TextObject(
        id = "lettering-preview", name = text.take(32), text = text, fontId = settings.fontId,
        heightMm = settings.heightMm, spacing = settings.spacingMm, arcDeg = settings.arcDeg,
        thread = thread, fontKind = FontKind.OUTLINE, contours = contours, contourSourceHeight = reference,
    )
    val placed = if (initialObject == null) placeLettering(objectData, hoop, settings.alignment)
        else objectData.copy(id = initialObject.id, transform = initialObject.transform, visible = initialObject.visible)
    val pattern = placed.placedPattern()
    val unique = text.codePoints().toArray().distinct().filter { !Character.isWhitespace(it) }
    val normalizedGlyphs = unique.map { code ->
        glyph(String(Character.toChars(code))).map { path ->
            path.map { (x, y) -> x * settings.heightMm / reference to y * settings.heightMm / reference }
        }
    }
    val quality = FontQuality.analyze(normalizedGlyphs, settings.heightMm, outline = face != null)
    val unsupported = unique.any { code ->
        val character = String(Character.toChars(code))
        when {
            paint != null -> !paint.hasGlyph(character)
            svg != null -> character !in svg.glyphs
            else -> code > Char.MAX_VALUE.code || hershey?.supports(code.toChar()) != true
        }
    }
    val b = pattern.bounds
    val outside = b != null && !hoop.contains(b)
    return LetteringResult(placed, pattern, quality, unsupported, outside)
}
