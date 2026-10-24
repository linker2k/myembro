// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import kotlin.math.roundToInt
import io.github.linker2k.myembro.engine.lettering.HersheyFont
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFontRegistry
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchLettering
import io.github.linker2k.myembro.engine.lettering.LetteringEngine
import io.github.linker2k.myembro.engine.lettering.TextAlignment
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.engine.lettering.SatinOptions
import io.github.linker2k.myembro.engine.lettering.ThreadWeight
import io.github.linker2k.myembro.engine.lettering.FillOptions
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread

/** INKSTITCH: a pre-digitized Ink/Stitch font resolved by [fontId] through [InkStitchFontRegistry]. */
enum class FontKind { HERSHEY, OUTLINE, INKSTITCH }

/** Editable text. Height and extra character spacing are in mm. */
data class TextObject(
    override val id: String,
    override val name: String = "Text",
    val text: String = "",
    val fontId: String = HersheyFont.DEFAULT_ID,
    val heightMm: Double = 10.0,
    val spacing: Double = 1.0,
    val arcDeg: Double = 0.0,
    val thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
    override val transform: Transform = Transform(),
    override val visible: Boolean = true,
    val fontKind: FontKind = FontKind.HERSHEY,
    /** Precomputed +y-down outlines; Android types never enter the engine. */
    val contours: List<List<Pair<Double, Double>>> = emptyList(),
    val contourBaseline: Double = 0.0,
    val contourSourceHeight: Double? = null,
    val lineSpacing: Double = 1.5,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val arcRadiusMm: Double? = null,
    /** Code-point indices in NFC/normalized text, including spaces and newlines. */
    val characterThreads: Map<Int, EmbThread> = emptyMap(),
    val contourCharacters: List<Int> = emptyList(),
    /** Source-unit baseline for each contour, allowing independent multiline arcs. */
    val contourBaselines: List<Double> = emptyList(),
    /** Also true for imported single-line SVGs stored as precomputed contours. */
    val singleLine: Boolean = fontKind == FontKind.HERSHEY,
    val stitchStyle: StitchStyle = StitchStyle.RUNNING,
    val satinOptions: SatinOptions = SatinOptions(),
    val fillOptions: FillOptions = FillOptions(),
    val threadWeight: ThreadWeight = ThreadWeight.WT40,
    val resolvedInkFont: io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFont? = null,
    val centerlineSatin: io.github.linker2k.myembro.engine.lettering.CenterlineSatinData? = null,
) : DesignObject {
    init {
        require(lineSpacing.isFinite() && lineSpacing >= 1.0)
        require(arcRadiusMm == null || arcRadiusMm.isFinite() && arcRadiusMm > 0)
        require(characterThreads.keys.all { it >= 0 })
        require(contourCharacters.isEmpty() || contourCharacters.size == contours.size)
        require(contourCharacters.all { it >= 0 })
        require(contourBaselines.isEmpty() || contourBaselines.size == contours.size)
        require(contourBaselines.all { it.isFinite() })
        require(fontKind != FontKind.OUTLINE || characterThreads.isEmpty() || contourCharacters.size == contours.size)
        require(stitchStyle != StitchStyle.FILL || !singleLine) { "Fill requires an outline font" }
    }
    /** A new weight restores automatic spacing; subsequent manual density edits remain possible. */
    fun withThreadWeight(weight: ThreadWeight): TextObject = if (weight == threadWeight) this else copy(
        threadWeight = weight, fillOptions = fillOptions.copy(autoDensity = true),
        satinOptions = satinOptions.copy(autoDensity = true),
    )
    private var pattern: Lazy<EmbPattern> = lazy {
        val spacingMm = satinOptions.densityForHeight(heightMm, threadWeight)
        val resolvedSatin = satinOptions.copy(densityMm = spacingMm, autoDensity = false,
            parameters = satinOptions.parameters?.let { if (satinOptions.autoDensity) it.copy(zigzag_spacing_mm = spacingMm) else it })
        val resolvedFill = fillOptions.copy(densityMm = fillOptions.densityForHeight(heightMm, threadWeight), autoDensity = false)
        if(centerlineSatin!=null && stitchStyle==StitchStyle.SATIN) {
            require(arcDeg==0.0 && arcRadiusMm==null) { "Curved centerline lettering is not supported" }
            io.github.linker2k.myembro.engine.lettering.HangulSatin.generate(centerlineSatin,heightMm,requireNotNull(contourSourceHeight),thread,characterThreads,
                satinOptions.parameters ?: io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters(zigzag_spacing_mm=spacingMm))
        } else when (fontKind) {
            FontKind.HERSHEY -> LetteringEngine.generate(text, fontId, heightMm, spacing, arcDeg, thread,
                lineSpacing = lineSpacing, alignment = alignment, arcRadiusMm = arcRadiusMm, characterThreads = characterThreads,
                stitchStyle = stitchStyle, satinOptions = resolvedSatin)
            FontKind.OUTLINE -> LetteringEngine.fromContours(
                contours, heightMm, arcDeg = arcDeg, thread = thread,
                baseline = contourBaseline, sourceHeight = contourSourceHeight, arcRadiusMm = arcRadiusMm,
                simplify = false, mergeToleranceMm = 0.2, // Ink/Stitch stroke getter default, no extra Douglas–Peucker pass.
                contourThreads = if (contourCharacters.isEmpty()) emptyList() else contourCharacters.map { characterThreads[it] ?: thread },
                contourBaselines = contourBaselines, stitchStyle = stitchStyle, satinOptions = resolvedSatin,
                fillOptions = resolvedFill, contourCharacters = contourCharacters, outline = !singleLine,
            )
            // The font carries its own stitch types and parameters; style, weight and density settings do not apply.
            FontKind.INKSTITCH -> (resolvedInkFont ?: InkStitchFontRegistry[fontId])?.let { font ->
                InkStitchLettering.render(font, text, heightMm, thread, letterSpacingMm = spacing,
                    lineHeightMm = lineSpacing * heightMm, alignment = alignment)
            } ?: EmbPattern(emptyList(), emptyList(), warnings = listOf("Ink/Stitch font $fontId is not installed"))
        }
    }

    private class ScaleCache {
        private var entry: Pair<Pair<Double,Double>,EmbPattern>? = null
        @Synchronized fun get(key: Pair<Double,Double>, create: () -> EmbPattern): EmbPattern {
            entry?.takeIf { it.first==key }?.let { return it.second }
            return create().also { entry=key to it }
        }
    }
    private var scaleCache = ScaleCache()
    private fun retainGeometry(copy: TextObject): TextObject = copy.also {
        it.pattern=pattern; it.scaleCache=scaleCache
    }
    override fun localPattern(): EmbPattern = pattern.value
    override fun withId(id: String): TextObject = retainGeometry(copy(id = id))
    override fun withTransform(t: Transform): TextObject = retainGeometry(copy(transform = t))
    override fun withVisible(v: Boolean): TextObject = retainGeometry(copy(visible = v))

    /** Only scale changes replan generated stitches. Rigid placement maps the finished coordinates. */
    override fun placedPattern(): EmbPattern {
        val t=transform
        val scaled=if(t.scaleX==1.0 && t.scaleY==1.0) localPattern() else
            scaleCache.get(t.scaleX to t.scaleY) {
                placeDesignPattern(withTransform(Transform(scaleX=t.scaleX,scaleY=t.scaleY)))
            }
        if(t.x==0.0 && t.y==0.0 && t.rotationDeg==0.0) return scaled
        val rigid=Transform(x=t.x,y=t.y,rotationDeg=t.rotationDeg)
        return io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.mapCoordinates(scaled) { x,y ->
            val (px,py)=rigid.apply(x.toDouble(),y.toDouble())
            px.roundToInt() to py.roundToInt()
        }
    }
}
