// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import io.github.linker2k.myembro.engine.edit.FontKind
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.HersheyFont
import io.github.linker2k.myembro.engine.lettering.SvgFont
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.lettering.AndroidGlyphOutlines
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Every font the lettering tool can use: built-in, downloaded from the catalog, or added by the user. */
data class LetterFont(
    val id: String,
    val name: String,
    val kind: Kind,
    /** Font file for downloaded and user fonts; null for built-ins. */
    val file: File?,
    val quality: FontQualitySummary?,
    val userAdded: Boolean = false,
) {
    enum class Kind { HERSHEY, OUTLINE, SINGLE_LINE_SVG }

    val outline: Boolean get() = kind == Kind.OUTLINE
}

/** Result of the 10 mm / 20 mm sample analysis, stored beside each font as `<file>.quality.json`. */
data class FontQualitySummary(
    val grade: QualityGrade,
    val recommendedMinHeightMm: Double,
    val at10: QualityReport?,
    val at20: QualityReport?,
    /** Missing-script keys ([QualityReasons]) followed by the engine's sentences for the 10 mm sample. */
    val reasons: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", ANALYSIS_VERSION)
        put("grade", grade.name)
        putMm("recommendedMinHeightMm", recommendedMinHeightMm)
        at10?.let { put("at10", it.toJson()) }
        at20?.let { put("at20", it.toJson()) }
        put("reasons", JSONArray(reasons))
    }

    companion object {
        const val SAMPLE = "가나다 ABC 자수"
        /** Bump when the analysis changes; older stored reports are recomputed. */
        const val ANALYSIS_VERSION = 5

        fun fromJson(o: JSONObject) = FontQualitySummary(
            grade = QualityGrade.valueOf(o.getString("grade")),
            recommendedMinHeightMm = o.mm("recommendedMinHeightMm"),
            at10 = o.optJSONObject("at10")?.let(::reportFromJson),
            at20 = o.optJSONObject("at20")?.let(::reportFromJson),
            reasons = o.optJSONArray("reasons")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )

        /** Badge: good if fine at 10 mm, caution if usable at 20 mm, otherwise unsuitable. */
        fun of(at10: QualityReport, at20: QualityReport, missing: List<String>): FontQualitySummary {
            var grade = when {
                at10.grade == QualityGrade.GOOD -> QualityGrade.GOOD
                at20.grade != QualityGrade.UNSUITABLE -> QualityGrade.CAUTION
                else -> QualityGrade.UNSUITABLE
            }
            if (missing.isNotEmpty() && grade == QualityGrade.GOOD) grade = QualityGrade.CAUTION
            return FontQualitySummary(grade, at10.recommendedMinHeightMm, at10, at20, missing + at10.reasons)
        }

        private fun QualityReport.toJson() = JSONObject().apply {
            putMm("minStrokeMm", minStrokeMm)
            putMm("recommendedMinHeightMm", recommendedMinHeightMm)
            put("stitchCount", stitchCount)
            put("jumpCount", jumpCount)
            put("tightSpots", tightSpots)
            put("grade", grade.name)
            put("reasons", JSONArray(reasons))
        }

        private fun reportFromJson(o: JSONObject) = QualityReport(
            minStrokeMm = o.mm("minStrokeMm"),
            recommendedMinHeightMm = o.mm("recommendedMinHeightMm"),
            stitchCount = o.getInt("stitchCount"),
            jumpCount = o.getInt("jumpCount"),
            tightSpots = o.getInt("tightSpots"),
            grade = QualityGrade.valueOf(o.getString("grade")),
            reasons = o.optJSONArray("reasons")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )

        // The engine reports "no limit" / "not fixable by scaling" as +Infinity, which JSON cannot hold.
        private fun JSONObject.putMm(key: String, value: Double) = put(key, if (value.isFinite()) value else JSONObject.NULL)

        private fun JSONObject.mm(key: String) = if (isNull(key)) Double.POSITIVE_INFINITY else getDouble(key)

        fun read(fontFile: File): FontQualitySummary? =
            qualityFile(fontFile).takeIf { it.isFile }?.let { f ->
                runCatching { JSONObject(f.readText()) }.getOrNull()
                    ?.takeIf { it.optInt("version") == ANALYSIS_VERSION }
                    ?.let { runCatching { fromJson(it) }.getOrNull() }
            }

        fun write(fontFile: File, summary: FontQualitySummary) = qualityFile(fontFile).writeText(summary.toJson().toString(2))

        fun qualityFile(fontFile: File) = File(fontFile.parentFile, fontFile.name + ".quality.json")
    }
}

/**
 * Turns a [LetterFont] into glyph geometry: per-glyph mm contours for quality analysis and
 * ready-to-add [TextObject]s. Outline fonts go through [AndroidGlyphOutlines]; SVG single-line
 * fonts through [SvgFont]. All output is +y down like the rest of the lettering pipeline.
 */
class LetterFontGeometry(context: Context) {
    private val appContext = context.applicationContext
    private val typefaces = ConcurrentHashMap<String, Typeface>()
    private val svgFonts = ConcurrentHashMap<String, SvgFont>()
    private val cmaps = ConcurrentHashMap<String, FontCmap>()

    fun typeface(font: LetterFont): Typeface = typefaces.getOrPut(font.id) {
        if (font.file == null) AndroidGlyphOutlines.jua(appContext)
        else Typeface.Builder(font.file).build() ?: throw IllegalArgumentException("Unreadable font file")
    }

    fun svgFont(font: LetterFont): SvgFont = svgFonts.getOrPut(font.id) {
        SvgFont.parse(requireNotNull(font.file).readText())
    }

    /** The font file's own character map; null for built-ins, which fall back to [Paint.hasGlyph]. */
    private fun cmap(font: LetterFont): FontCmap? = font.file?.let { f -> cmaps[font.id] ?: FontCmap.read(f)?.also { cmaps[font.id] = it } }

    /** Whether the outline font itself (not a system fallback) has a glyph for [s]. */
    private fun hasOutlineGlyph(font: LetterFont, s: String): Boolean =
        cmap(font)?.has(s) ?: Paint().apply { typeface = typeface(font) }.hasGlyph(s)

    /** Characters of [text] (ignoring whitespace) this font cannot draw. */
    fun missing(font: LetterFont, text: String): Set<Char> = text.filterNot(Char::isWhitespace).toSet().filterTo(LinkedHashSet()) { c ->
        when (font.kind) {
            LetterFont.Kind.HERSHEY -> c.code !in 0x20..0x7E
            LetterFont.Kind.OUTLINE -> !hasOutlineGlyph(font, c.toString())
            LetterFont.Kind.SINGLE_LINE_SVG -> !svgFont(font).supports(c)
        }
    }

    /** Analyzes [FontQualitySummary.SAMPLE] at 10 mm and 20 mm. */
    fun analyze(font: LetterFont): FontQualitySummary {
        val sample = FontQualitySummary.SAMPLE
        val lacking = missing(font, sample)
        val reasons = buildList {
            if (lacking.any { it in '가'..'힣' }) add(QualityReasons.MISSING_HANGUL)
            if (lacking.any { it in 'A'..'Z' }) add(QualityReasons.MISSING_LATIN)
        }
        val chars = sample.filter { !it.isWhitespace() && it !in lacking }
        val at10 = FontQualityGateway.analyze(glyphContours(font, chars, 10.0), 10.0, font.outline)
        val at20 = FontQualityGateway.analyze(glyphContours(font, chars, 20.0), 20.0, font.outline)
        return FontQualitySummary.of(at10, at20, reasons)
    }

    /** One entry per character: its contours in mm, scaled so the reference glyph is [heightMm] tall. */
    fun glyphContours(font: LetterFont, chars: String, heightMm: Double): List<List<List<Pair<Double, Double>>>> =
        when (font.kind) {
            LetterFont.Kind.OUTLINE -> {
                val face = typeface(font)
                val scale = heightMm / outlineReference(font)
                chars.map { c -> AndroidGlyphOutlines.extract(c.toString(), face).map { it.map { (x, y) -> x * scale to y * scale } } }
            }
            LetterFont.Kind.SINGLE_LINE_SVG -> {
                val svg = svgFont(font)
                val scale = heightMm / svgReference(svg)
                chars.map { c -> svg.glyph(c)?.strokes.orEmpty().map { s -> s.map { it.x * scale to -it.y * scale } } }
            }
            LetterFont.Kind.HERSHEY -> {
                val hershey = HersheyFont.load(font.id)
                val scale = heightMm / hershey.capHeight
                chars.map { c ->
                    hershey[c]?.strokes.orEmpty().map { s -> s.map { it.x * scale to it.y * scale } }
                }
            }
        }

    /** One line of [text] as polylines (+y down, the font's own units) for small font-card previews. */
    fun previewStrokes(font: LetterFont, text: String): List<List<Pair<Double, Double>>> {
        val line = text.lineSequence().firstOrNull().orEmpty()
        return when (font.kind) {
            LetterFont.Kind.OUTLINE -> AndroidGlyphOutlines.extract(line, typeface(font))
            LetterFont.Kind.SINGLE_LINE_SVG -> svgFont(font).layout(line).map { s -> s.map { it.x to -it.y } }
            LetterFont.Kind.HERSHEY -> {
                val hershey = HersheyFont.load(font.id)
                var cursor = 0.0
                line.flatMap { c ->
                    val g = hershey[c] ?: hershey['?'] ?: return@flatMap emptyList()
                    val x0 = cursor - g.left
                    cursor += g.advance
                    g.strokes.map { s -> s.map { (x0 + it.x) to it.y.toDouble() } }
                }
            }
        }
    }

    fun textObject(
        font: LetterFont, id: String, text: String, heightMm: Double, spacing: Double, arcDeg: Double,
        thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
    ): TextObject {
        val name = text.take(32).ifBlank { "Text" }
        return when (font.kind) {
            LetterFont.Kind.HERSHEY -> TextObject(
                id = id, name = name, text = text, fontId = font.id, heightMm = heightMm,
                spacing = spacing, arcDeg = arcDeg, thread = thread,
            )
            LetterFont.Kind.OUTLINE -> {
                val face = typeface(font)
                val reference = outlineReference(font)
                TextObject(
                    id = id, name = name, text = text, fontId = font.id, heightMm = heightMm, spacing = spacing,
                    arcDeg = arcDeg, thread = thread, fontKind = FontKind.OUTLINE,
                    contours = AndroidGlyphOutlines.extract(text, face, spacing = (spacing / heightMm * reference).toFloat()),
                    contourSourceHeight = reference,
                )
            }
            LetterFont.Kind.SINGLE_LINE_SVG -> {
                val svg = svgFont(font)
                val reference = svgReference(svg)
                val gap = spacing / heightMm * reference
                val lines = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')
                val contours = lines.flatMapIndexed { index, line ->
                    val offset = -svg.advance(line, gap) / 2
                    val baseline = index * reference * 1.5
                    // Font units are y-up; the lettering pipeline is y-down.
                    svg.layout(line, gap).map { stroke -> stroke.map { (it.x + offset) to (baseline - it.y) } }
                }
                TextObject(
                    id = id, name = name, text = text, fontId = font.id, heightMm = heightMm, spacing = spacing,
                    arcDeg = arcDeg, thread = thread, fontKind = FontKind.OUTLINE,
                    contours = contours, contourSourceHeight = reference,
                )
            }
        }
    }

    /** Ink height of a capital-size reference glyph, in the extractor's source pixels. */
    private fun outlineReference(font: LetterFont): Double {
        val ref = listOf("자", "H").firstOrNull { hasOutlineGlyph(font, it) } ?: "H"
        val points = AndroidGlyphOutlines.extract(ref, typeface(font)).flatten()
        return if (points.isEmpty()) 1000.0 else (points.maxOf { it.second } - points.minOf { it.second }).coerceAtLeast(1.0)
    }

    private fun svgReference(svg: SvgFont): Double {
        val points = (svg.glyph('H') ?: svg.glyph('가') ?: svg.glyphs.values.firstOrNull { it.strokes.isNotEmpty() })
            ?.strokes?.flatten().orEmpty()
        return if (points.isEmpty()) svg.unitsPerEm * 0.7 else (points.maxOf { it.y } - points.minOf { it.y }).coerceAtLeast(1.0)
    }
}
