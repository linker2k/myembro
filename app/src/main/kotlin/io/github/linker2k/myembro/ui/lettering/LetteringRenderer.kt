// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.inkstitch.lettering.*
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.FontKind
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.fonts.CatalogFont
import io.github.linker2k.myembro.fonts.FontCmap
import io.github.linker2k.myembro.fonts.FontRepository
import io.github.linker2k.myembro.fonts.LetterFont
import io.github.linker2k.myembro.lettering.AndroidGlyphOutlines
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import io.github.linker2k.myembro.ui.settings.withSewingDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LetteringFont(val id: String, val name: String, val resource: Int = 0)
internal val bundledLetteringFonts = listOf(
    LetteringFont("gowun-batang", "고운바탕", R.font.gowun_batang),
    LetteringFont("jua", "둥근고딕", R.font.jua_regular),
    LetteringFont("noto-sans-kr", "담백한 고딕", R.font.noto_sans_kr),
)
internal data class LetteringResult(
    val textObject: TextObject, val pattern: EmbPattern, val quality: FontQualityReport?,
    val unsupported: Boolean, val outsideHoop: Boolean,
    val singleLine: Boolean, val fontName: String,
    /** Ink/Stitch font: stitch types and densities come from the font, so style controls do not apply. */
    val predigitized: Boolean = false,
    /** The digitizer's tested height range for [predigitized] fonts. */
    val testedRangeMm: ClosedFloatingPointRange<Double>? = null,
    /** Distinct characters of the text the font has no glyph for, in text order. */
    val missing: String = "",
)

/** Catalog-card bridge: call after download, on a worker via this suspending API. No download UI here. */
object LetteringFontQuality {
    suspend fun analyze(
        context: Context, fontId: String, text: String = "자수봄날Aa012", heightMm: Double = 18.0,
        threadWeight: ThreadWeight = ThreadWeight.WT40,
    ): FontQualityReport = withContext(Dispatchers.Default) {
        val source = loadFont(context.applicationContext, fontId)
        qualityFor(source, fontId, text, heightMm, threadWeight)
    }
    fun invalidate(fontId: String) = FontQualityCache.Shared.invalidate(fontId)
}

internal data class AutomaticLetteringState(val fontName:String,val style:StitchStyle?,val heightRanges:List<ValidatedHeightRange>)

/** No implicit satin conversion, quality-derived bounds, or fallback to fill before S4. */
internal suspend fun automaticLetteringState(context:Context,fontId:String,text:String,heightMm:Double=18.0):AutomaticLetteringState = withContext(Dispatchers.Default) {
    val source=loadFont(context.applicationContext,fontId)
    // US-3: an Ink/Stitch font is sewn as digitized (satin from the font), within its recorded heights.
    source.ink?.let { _ -> source.testedRangeMm?.takeIf { it.start > 0 && it.endInclusive.isFinite() }?.let { r ->
        return@withContext AutomaticLetteringState(source.name, StitchStyle.SATIN, listOf(ValidatedHeightRange(r.start, r.endInclusive)))
    } }
    val geometry=if(source.face!=null) LetteringGeometry.OUTLINE else LetteringGeometry.SINGLE_LINE
    val policy=AutomaticLetteringPolicy.Shared
    val candidates=policy.candidates(fontId,source.revision,geometry,normalizedLetteringText(text))
    AutomaticLetteringState(source.name,policy.method(candidates,heightMm),candidates.map { it.heights }.distinct())
}

private class LoadedFont(
    val face: Typeface?, val svg: SvgFont?, val hershey: HersheyFont?, val revision: String, val name: String,
    /** Outline file's own character map; [Paint.hasGlyph] would also count system fallback fonts. */
    val cmap: FontCmap? = null,
    val ink: InkStitchFont? = null,
    val testedRangeMm: ClosedFloatingPointRange<Double>? = null,
) {
    val paint = face?.let { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = it; textSize = 1000f } }
    fun advance(character: String): Double = when {
        paint != null -> paint.measureText(character).toDouble()
        svg != null -> svg.advance(character)
        else -> (character.singleOrNull()?.let { hershey?.get(it) } ?: hershey?.get('?'))!!.advance.toDouble()
    }
    fun glyph(character: String): List<List<Pair<Double, Double>>> = when {
        face != null -> AndroidGlyphOutlines.extract(character, face).map { path -> path.map { (x, y) -> x + advance(character) / 2 to y } }
        svg != null -> svg.layout(character).map { path -> path.map { it.x to -it.y } }
        else -> {
            val font = checkNotNull(hershey)
            val g = character.singleOrNull()?.let { font[it] } ?: checkNotNull(font['?'])
            g.strokes.map { path -> path.map { (it.x - g.left).toDouble() to (it.y - font.baseline).toDouble() } }
        }
    }
    val reference: Double by lazy {
        when {
            face != null -> glyph(if (supports("자".codePointAt(0))) "자" else "H").flatten().let { points ->
                if (points.isEmpty()) 1000.0 else (points.maxOf { it.second } - points.minOf { it.second }).coerceAtLeast(1.0)
            }
            svg != null -> svg.unitsPerEm.coerceAtLeast(1.0)
            else -> 21.0
        }
    }
    fun supports(code: Int): Boolean = when {
        // Ink/Stitch sews forced-case fonts in that case (font.json letter_case).
        ink != null -> String(Character.toChars(code)).let { c ->
            when (ink.info.letterCase) { "upper" -> c.uppercase(); "lower" -> c.lowercase(); else -> c }
        } in ink.glyphs
        paint != null -> cmap?.let { code in it } ?: paint.hasGlyph(String(Character.toChars(code)))
        svg != null -> String(Character.toChars(code)) in svg.glyphs
        else -> code <= Char.MAX_VALUE.code && hershey?.supports(code.toChar()) == true
    }
}

private fun loadFont(context: Context, id: String): LoadedFont {
    val bundled = bundledLetteringFonts.firstOrNull { it.id == id }
    val repo = FontRepository(context)
    val installed = if (bundled == null) repo.find(id) else null
    val user = if (bundled == null && installed == null) repo.userFont(id) else null
    val face = when {
        bundled != null -> context.resources.getFont(bundled.resource)
        installed?.kind == CatalogFont.Kind.OUTLINE_TTF -> repo.typeface(installed)
        user?.kind == LetterFont.Kind.OUTLINE -> repo.userTypeface(user)
        else -> null
    }
    val svg = when {
        installed?.kind == CatalogFont.Kind.SINGLE_LINE_SVG -> repo.svgFont(installed)
        user?.kind == LetterFont.Kind.SINGLE_LINE_SVG -> repo.userSvgFont(user)
        else -> null
    }
    val ink = if (installed?.kind == CatalogFont.Kind.INKSTITCH_SVG) repo.inkStitchFont(installed) else null
    val hershey = if (id == HersheyFont.DEFAULT_ID) HersheyFont.DEFAULT else null
    check(face != null || svg != null || hershey != null || ink != null) { "선택한 폰트를 불러올 수 없습니다." }
    val cmap = when {
        installed?.kind == CatalogFont.Kind.OUTLINE_TTF -> FontCmap.read(repo.fontFile(installed))
        user?.kind == LetterFont.Kind.OUTLINE -> user.file?.let(FontCmap::read)
        else -> null
    }
    val digest=java.security.MessageDigest.getInstance("SHA-256")
    fun hash(stream:java.io.InputStream):String=stream.use { input ->
        val buffer=ByteArray(8192)
        while(true) { val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    val revision=when {
        bundled!=null -> hash(context.resources.openRawResource(bundled.resource))
        installed!=null -> hash(repo.fontFile(installed).inputStream())
        user?.file!=null -> hash(user.file.inputStream())
        else -> "bundled-hershey-v1"
    }
    return LoadedFont(face, svg, hershey, revision + ":glyph-extractor-v3",
        bundled?.name ?: installed?.nameKo ?: installed?.name ?: user?.name ?: if (hershey != null) "Hershey Futural" else id, cmap,
        ink, installed?.takeIf { ink != null }?.let { f ->
            (f.recommendedMinHeightMm ?: 0.0)..(f.recommendedMaxHeightMm ?: Double.POSITIVE_INFINITY)
        })
}

private fun qualityFor(source: LoadedFont, id: String, text: String, heightMm: Double, threadWeight: ThreadWeight): FontQualityReport {
    if (source.ink != null) return testedRangeQuality(source.testedRangeMm, heightMm)
    val codes = normalizedLetteringText(text).codePoints().toArray().distinct().filter { !Character.isWhitespace(it) }.sorted()
    val sample = codes.joinToString("") { String(Character.toChars(it)) }
    return FontQualityCache.Shared.getOrAnalyze(FontQualityKey(id, source.revision, sample, heightMm, source.face != null, threadWeight = threadWeight),
        FontQuality.detailFloorMm(sample)) {
        codes.map { code -> source.glyph(String(Character.toChars(code))).map { path ->
            path.map { (x, y) -> x * heightMm / source.reference to y * heightMm / source.reference }
        } }
    }
}

/**
 * Ink/Stitch fonts are judged by the range their digitizer tested (font.json min/max scale on the 'H'
 * basis): below it satins get too narrow and dense, above it they grow too long. Contour analysis
 * would measure outlines the font does not sew.
 */
internal fun testedRangeQuality(range: ClosedFloatingPointRange<Double>?, heightMm: Double): FontQualityReport {
    val min = range?.start ?: 0.0
    val (grade, reasons) = when {
        range == null -> Grade.GOOD to emptyList()
        heightMm < range.start -> Grade.UNSUITABLE to listOf("디지타이저가 시험한 높이(%.1f–%.1f mm)보다 작습니다.".format(range.start, range.endInclusive))
        heightMm > range.endInclusive -> Grade.CAUTION to listOf("디지타이저가 시험한 높이(%.1f–%.1f mm)보다 커서 새틴이 길어질 수 있습니다.".format(range.start, range.endInclusive))
        else -> Grade.GOOD to emptyList()
    }
    return FontQualityReport(Double.POSITIVE_INFINITY, min, 0, 0, 0, grade, reasons)
}

/** Font/catalog cards can request the same real stitches without running quality analysis again. */
object LetteringFontPreview {
    suspend fun pattern(
        context: Context, fontId: String, text: String, heightMm: Double, thread: EmbThread,
        stitchStyle: StitchStyle? = null,
        fillOptions: FillOptions = FillOptions(),
        threadWeight: ThreadWeight = ThreadWeight.WT40,
    ): EmbPattern = withContext(Dispatchers.Default) {
        val source = loadFont(context.applicationContext, fontId)
        val sample = normalizedLetteringText(text).lineSequence().first().ifBlank { "Abc" }
            .codePoints().toArray().take(4).joinToString("") { String(Character.toChars(it)) }
        val settings = LetteringSettings(sample, fontId, heightMm, 1.0, LetteringAlignment.CENTER,
            false, LetteringCurve.STRAIGHT, 0.0, stitchStyle = stitchStyle, fillOptions = fillOptions, threadWeight = threadWeight)
        prepareObject(source, settings, thread).localPattern().withSewingDefaults(SettingsPreferences(context).read().sewing)
    }
}

/** Keep geometry construction shared by main preview, cards, and the added object. */
private fun prepareObject(source: LoadedFont, settings: LetteringSettings, thread: EmbThread): TextObject {
    val text = normalizedLetteringText(settings.text)
    if (source.ink != null) return TextObject(
        id = "lettering-preview", name = text.take(32), text = text, fontId = settings.fontId,
        heightMm = settings.heightMm, spacing = settings.spacingMm, thread = thread, fontKind = FontKind.INKSTITCH,
        lineSpacing = 1 + settings.lineSpacingMm / settings.heightMm,
        alignment = TextAlignment.valueOf(settings.alignment.name), singleLine = false, threadWeight = settings.threadWeight,
    )
    val lines = arrayListOf(mutableListOf<LayoutGlyph>())
    text.codePoints().toArray().forEachIndexed { index, code ->
        if (code == '\n'.code) lines += mutableListOf<LayoutGlyph>()
        else {
            val character = String(Character.toChars(code))
            lines.last() += LayoutGlyph(index, source.advance(character), source.glyph(character))
        }
    }
    val layout = layoutGlyphs(lines, source.reference, settings)
    return TextObject(
        id = "lettering-preview", name = text.take(32), text = text, fontId = settings.fontId,
        heightMm = settings.heightMm, spacing = settings.spacingMm, arcDeg = settings.arcDeg,
        thread = thread, fontKind = FontKind.OUTLINE, contours = layout.contours, contourSourceHeight = source.reference,
        lineSpacing = 1 + settings.lineSpacingMm / settings.heightMm,
        alignment = TextAlignment.valueOf(settings.alignment.name),
        arcRadiusMm = if (settings.curved && settings.curve != LetteringCurve.STRAIGHT) settings.radiusMm else null,
        characterThreads = settings.characterColors.mapValues { (_, color) -> if (color == thread.color) thread else EmbThread(color) },
        contourCharacters = layout.characters, contourBaselines = layout.baselines,
        singleLine = source.face == null,
        stitchStyle = settings.stitchStyle ?: StitchStyle.RUNNING,
        fillOptions = settings.fillOptions, satinOptions = settings.satinOptions, threadWeight = settings.threadWeight,
    )
}

/** Glyph extraction/analysis runs on a worker. Each contour retains its code-point owner and line baseline. */
internal fun renderLettering(context: Context, settings: LetteringSettings, thread: EmbThread, hoop: HoopSpec,
    sewingOptions: SewingOptions = SettingsPreferences(context).read().sewing): LetteringResult {
    val text = normalizedLetteringText(settings.text)
    val source = loadFont(context, settings.fontId)
    val geometry=if(source.face!=null) LetteringGeometry.OUTLINE else LetteringGeometry.SINGLE_LINE
    val policy=AutomaticLetteringPolicy.Shared
    val method=policy.method(policy.candidates(settings.fontId,source.revision,geometry,text),settings.heightMm)
    require((settings.stitchStyle ?: StitchStyle.RUNNING)==method) { "Satin validation range is unconfirmed; use running stitch" }
    val placed = placeLettering(prepareObject(source, settings, thread), hoop, settings.alignment)
    val transformed = placed.placedPattern().withSewingDefaults(sewingOptions)
    val pattern = EmbPattern(transformed.stitches, transformed.threads, transformed.metadata, placed.localPattern().warnings)
    val quality: FontQualityReport? = null // Heuristic quality remains a separate catalog API, never an approval.
    val lacking = text.codePoints().toArray().filter { !Character.isWhitespace(it) && !source.supports(it) }.distinct()
    val outside = letteringOutsideHoop(pattern, hoop)
    return LetteringResult(placed, pattern, quality, lacking.isNotEmpty(), outside, source.face == null && source.ink == null, source.name,
        predigitized = source.ink != null, testedRangeMm = source.testedRangeMm,
        missing = lacking.joinToString("") { String(Character.toChars(it)) })
}
