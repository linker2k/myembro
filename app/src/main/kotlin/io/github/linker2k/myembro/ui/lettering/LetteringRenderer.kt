// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.inkstitch.lettering.*
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import io.github.linker2k.myembro.R
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

internal data class LetteringFont(
    val id: String, val name: String = "", val resource: Int = 0,
    @androidx.annotation.StringRes val nameResource: Int = 0,
) {
    fun displayName(context: Context): String = if (nameResource != 0) context.getString(nameResource) else name
}
internal val bundledLetteringFonts = listOf(
    LetteringFont("gowun-batang", resource = R.font.gowun_batang, nameResource = R.string.lettering_font_gowun_batang),
    LetteringFont("jua", resource = R.font.jua_regular, nameResource = R.string.lettering_font_jua),
    LetteringFont("noto-sans-kr", resource = R.font.noto_sans_kr, nameResource = R.string.lettering_font_noto_sans_kr),
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

/** Font metadata or pinned TTF sweep bounds, always capped by the app height limit. */
internal suspend fun automaticLetteringState(context:Context,fontId:String,text:String,heightMm:Double=18.0):AutomaticLetteringState = withContext(Dispatchers.Default) {
    automaticState(context,loadFont(context.applicationContext,fontId),fontId,text,heightMm)
}

/** The one automatic decision used by both the screen and [renderLettering], so they cannot disagree. */
private fun automaticState(context:Context,source:LoadedFont,fontId:String,text:String,heightMm:Double):AutomaticLetteringState {
    val geometry=if(source.face!=null) LetteringGeometry.OUTLINE else LetteringGeometry.SINGLE_LINE
    val limits=LetteringHeightLimits.decode(context.assets.open(LetteringHeightLimits.ASSET).bufferedReader().use { it.readText() })
    return automaticLetteringState(source.name,fontId,source.revision,normalizedLetteringText(text),heightMm,geometry,limits,
        source.testedRangeMm.takeIf { source.ink!=null }).copy(singleLineWeightEnabled = singleLineWeightEnabled(source.face == null, source.ink != null, fontId))
}

private class LoadedFont(
    val face: Typeface?, val svg: SvgFont?, val hershey: HersheyFont?, val revision: String, val name: String,
    /** Outline file's own character map; [Paint.hasGlyph] would also count system fallback fonts. */
    val cmap: FontCmap? = null,
    val ink: InkStitchFont? = null,
    val testedRangeMm: ClosedFloatingPointRange<Double>? = null,
    val centerlineFace: Typeface? = face,
) {
    val paint = face?.let { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = it; textSize = 1000f } }
    val resolved: ResolvedTextFont by lazy {
        when {
            ink != null -> ResolvedTextFont.InkStitch(ink)
            svg != null -> ResolvedTextFont.SingleLineSvg(svg)
            else -> ResolvedTextFont.Hershey(checkNotNull(hershey))
        }
    }
    fun advance(character: String): Double = paint?.measureText(character)?.toDouble() ?: resolved.advance(character)
    fun glyph(character: String): List<List<Pair<Double, Double>>> = if (face != null)
        AndroidGlyphOutlines.extract(character, face).map { path -> path.map { (x, y) -> x + advance(character) / 2 to y } }
        else resolved.glyph(character)
    val reference: Double by lazy {
        if (face != null) glyph(if (supports("자".codePointAt(0))) "자" else "H").flatten().let { points ->
            if (points.isEmpty()) 1000.0 else (points.maxOf { it.second } - points.minOf { it.second }).coerceAtLeast(1.0)
        } else resolved.reference
    }
    fun supports(code: Int): Boolean = if (paint != null)
        cmap?.let { code in it } ?: paint.hasGlyph(String(Character.toChars(code)))
        else resolved.supports(code)

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
        bundled?.displayName(context) ?: installed?.nameKo ?: installed?.name ?: user?.name ?: if (hershey != null) "Hershey Futural" else id, cmap,
        ink, installed?.takeIf { ink != null }?.let { f ->
            (f.recommendedMinHeightMm ?: 0.0)..(f.recommendedMaxHeightMm ?: Double.POSITIVE_INFINITY)
        }, centerlineFace = if (id == "noto-sans-kr") context.resources.getFont(R.font.myembro_sans) else face)
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
        val previewStyle = when {
            source.face != null -> if (HangulSatin.applies(sample)) StitchStyle.SATIN else StitchStyle.RUNNING
            singleLineWeightEnabled(true, source.ink != null, fontId) -> stitchStyle
            else -> null
        }
        val settings = LetteringSettings(sample, fontId, heightMm, 1.0, LetteringAlignment.CENTER,
            false, LetteringCurve.STRAIGHT, 0.0, stitchStyle = previewStyle, fillOptions = fillOptions, threadWeight = threadWeight)
        prepareObject(source, settings, thread).localPattern().withSewingDefaults(SettingsPreferences(context).read().sewing)
    }
}

/** Keep geometry construction shared by main preview, cards, and the added object. */
private fun prepareObject(loaded: LoadedFont, settings: LetteringSettings, thread: EmbThread): TextObject {
    val text = normalizedLetteringText(settings.text)
    // The bundled variable file defaults to Thin. Only the new Hangul path requests the existing
    // explicit 400 XML family, as KRSAT3 did; legacy outline/fill extraction remains unchanged.
    val source = if(settings.stitchStyle==StitchStyle.SATIN && HangulSatin.applies(text) && loaded.centerlineFace!=loaded.face)
        LoadedFont(loaded.centerlineFace,loaded.svg,loaded.hershey,loaded.revision+":satin400",loaded.name,loaded.cmap,loaded.ink,loaded.testedRangeMm)
        else loaded
    val request = TextPreparationRequest(
        text = text, fontId = settings.fontId, heightMm = settings.heightMm, spacingMm = settings.spacingMm,
        alignment = settings.alignment, lineSpacingMm = settings.lineSpacingMm, thread = thread,
        arcDeg = settings.arcDeg,
        arcRadiusMm = if (settings.curved && settings.curve != LetteringCurve.STRAIGHT) settings.radiusMm else null,
        characterColors = settings.characterColors, stitchStyle = settings.stitchStyle,
        fillOptions = settings.fillOptions, satinOptions = settings.satinOptions, threadWeight = settings.threadWeight,
    )
    if (source.face == null) return prepareText(request, source.resolved).textObject
    val codes = text.codePoints().toArray()
    val glyphs = codes.filter { it != '\n'.code }.distinct().associate { code ->
        val character = String(Character.toChars(code))
        character to PreparedGlyph(source.advance(character), source.glyph(character))
    }
    val snapshot = ResolvedTextFont.OutlineSnapshot(source.reference, glyphs, codes.filter(source::supports).toSet())
    // The Android centerline extraction/assembly stays here until the next extraction step.
    val lines = arrayListOf(mutableListOf<LayoutGlyph>())
    codes.forEachIndexed { index, code ->
        if (code == '\n'.code) lines += mutableListOf<LayoutGlyph>()
        else glyphs.getValue(String(Character.toChars(code))).let { glyph ->
            lines.last() += LayoutGlyph(index, glyph.advance, glyph.paths)
        }
    }
    val centerline=source.face?.takeIf { HangulSatin.applies(text) && settings.stitchStyle==StitchStyle.SATIN }?.let { face ->
        val componentIds=mutableListOf<Int>();val widths=mutableMapOf<Int,Double>()
        val tracedLines=lines.map { line -> line.map { glyph ->
            val code=text.codePoints().toArray()[glyph.index]
            if(Character.isWhitespace(code)) LayoutGlyph(glyph.index,glyph.advance,emptyList()) else {
                val traced=io.github.linker2k.myembro.lettering.AndroidCenterlineGlyph.extract(String(Character.toChars(code)),face)
                componentIds+=traced.components
                widths[glyph.index]=HangulSatin.measuredWidthSource(glyph.paths,source.reference)
                LayoutGlyph(glyph.index,glyph.advance,traced.paths)
            }
        } }
        val traced=layoutGlyphs(tracedLines,source.reference,settings)
        CenterlineSatinData(traced.contours,traced.characters,componentIds,widths)
    }
    return prepareText(request.copy(centerlineSatin = centerline), snapshot).textObject
}

/** Glyph extraction/analysis runs on a worker. Each contour retains its code-point owner and line baseline. */
internal fun renderLettering(context: Context, settings: LetteringSettings, thread: EmbThread, hoop: HoopSpec,
    sewingOptions: SewingOptions = SettingsPreferences(context).read().sewing): LetteringResult {
    val text = normalizedLetteringText(settings.text)
    val source = loadFont(context, settings.fontId)
    val method=letteringStyleForWeight(automaticState(context,source,settings.fontId,text,settings.heightMm), settings.stitchStyle == StitchStyle.BEAN)
    require((settings.stitchStyle ?: StitchStyle.RUNNING)==method) { "Lettering method is outside the supported automatic range" }
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

/** Agent adapter shares the screen's exact installed-font loading and outline extraction. No download. */
class AndroidTextFontResolver(context: Context) : io.github.linker2k.myembro.engine.agent.FontResolver {
    private val context=context.applicationContext
    private val sources=mutableMapOf<String,LoadedFont>()
    private fun source(id:String)=sources[id] ?: runCatching { loadFont(context,id) }.getOrNull()?.also { sources[id]=it }
    override fun revision(fontId:String)=source(fontId)?.revision
    override fun resolve(fontId:String):ResolvedTextFont? = resolveText(fontId, "")
    override fun resolveText(fontId:String,text:String):ResolvedTextFont? {
        val s=source(fontId) ?: return null
        if(s.face==null) return s.resolved
        val codes=normalizedLetteringText(text).codePoints().toArray().filter { it!='\n'.code }.distinct()
        return ResolvedTextFont.OutlineSnapshot(s.reference,codes.associate { code ->
            val c=String(Character.toChars(code)); c to PreparedGlyph(s.advance(c),s.glyph(c))
        },codes.filter(s::supports).toSet())
    }
    override fun prepare(request:TextPreparationRequest,font:ResolvedTextFont,policy:TextPreparationPolicy):TextObject {
        val source=source(request.fontId) ?: error("Font is not installed")
        val settings=LetteringSettings(request.text,request.fontId,request.heightMm,request.spacingMm,
            request.alignment,false,LetteringCurve.STRAIGHT,0.0,lineSpacingMm=request.lineSpacingMm,
            stitchStyle=request.stitchStyle,threadWeight=request.threadWeight)
        return prepareObject(source,settings,request.thread).withId(policy.objectId)
    }
    override fun inkRange(fontId:String,font:ResolvedTextFont)=source(fontId)?.testedRangeMm
}
