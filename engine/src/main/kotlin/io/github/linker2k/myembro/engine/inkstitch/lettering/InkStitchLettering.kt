// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/lettering/font.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Parser/layout reused from canvas a8acced; satin parameters and S1 groups remain lossless.
package io.github.linker2k.myembro.engine.inkstitch.lettering
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinColumn
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParametersCodec
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.InkRunning
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as SvgPoint
import io.github.linker2k.myembro.engine.lettering.TextAlignment
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** Human-digitized glyphs only. No outline-to-satin inference or per-element native rounding. */
object InkStitchLettering {
    data class Placement(val glyph:InkStitchGlyph,val dx:Double,val dy:Double,val scale:Double) {
        fun mm(q:Point)=Point(q.x*scale+dx,q.y*scale+dy)
    }
    fun render(font:InkStitchFont,text:String,heightMm:Double,thread:EmbThread?=null,
        letterSpacingMm:Double=0.0,lineHeightMm:Double?=null,
        alignment:TextAlignment=TextAlignment.LEFT,backAndForth:Boolean=true,
        forceBoundaryLocks:Boolean=true):EmbPattern {
        val placements=layout(font,text,heightMm,letterSpacingMm,lineHeightMm,alignment,backAndForth)
        val groups=arrayListOf<StitchGroup>()
        for(p in placements) for(element in p.glyph.elements) {
            val params=element.params
            val boundary=groups.isEmpty() || groups.last().trimAfter || groups.last().stopAfter || groups.last().color!= (thread ?: EmbThread(element.color))
            // Product policy uses the upstream force flag only at a real thread boundary.
            // As in element.py, this flag alone does not override ties=3 for lock_start.
            val attrs=params.names.associateWith { params[it]!! }.let {
                if(forceBoundaryLocks && boundary) it + ("force_lock_stitches" to "true") else it
            }
            val effective=InkStitchParams(attrs)
            val locks=LockStitches.fromParameters(LockStitchParameters.fromAttributes(attrs))
            val color=thread ?: EmbThread(element.color)
            fun px(q:Point)=p.mm(q).let { SvgPoint(Units.mmToPixels(it.x),Units.mmToPixels(it.y)) }
            val previous=groups.lastOrNull()?.stitches?.lastOrNull()
            val made=when(element) {
                is InkSatin -> SatinColumn.fromSubpaths(element.subpaths.map { it.map(::px) },
                    SatinParametersCodec.decode(attrs),element.nodePaths.map { it.map(::px) },color=color,
                    trim_after=params.bool("trim_after",false),stop_after=params.bool("stop_after",false),
                    lockStart=locks.start,lockEnd=locks.end,
                    force_lock_stitches=effective.bool("force_lock_stitches",false)).toStitchGroups(previous)
                is InkRun -> element.paths.map { path ->
                    require((params.number("repeats") ?: 1.0)==1.0 && params.values("bean_stitch_repeats").all { it==0.0 }) { "Repeats/bean lettering is deferred" }
                    val base=InkRunning.even(path.points.map(::px).map { Point(it.x,it.y) },
                        Units.mmToPixels(params.number("running_stitch_length_mm") ?: 2.5),
                        Units.mmToPixels(params.number("running_stitch_tolerance_mm") ?: .2))
                    StitchGroup(color,base.map { Stitch(SvgPoint(it.x,it.y)) },
                        trimAfter=params.bool("trim_after",false),stopAfter=params.bool("stop_after",false),
                        lockStart=locks.start,lockEnd=locks.end,
                        minStitchLengthMm=params.number("min_stitch_length_mm"),
                        minJumpLengthMm=params.number("min_jump_stitch_length_mm"),
                        forceLockStitches=effective.bool("force_lock_stitches",false))
                }
                is InkFill -> error("Fill lettering is unavailable before S4")
            }
            groups+=made
        }
        val pattern=GeneratedPatternProcessor.fromGroups(groups,collapse_len=3.0,min_stitch_len=.1)
        return EmbPattern(pattern.stitches,pattern.threads,pattern.metadata+mapOf("name" to text,"font" to font.info.name),pattern.warnings)
    }
    fun layout(
        font: InkStitchFont, text: String, heightMm: Double, letterSpacingMm: Double = 0.0,
        lineHeightMm: Double? = null, alignment: TextAlignment = TextAlignment.LEFT, backAndForth: Boolean = true,
    ): List<Placement> {
        require(heightMm.isFinite() && heightMm > 0) { "Height must be positive" }
        require(letterSpacingMm.isFinite() && (lineHeightMm == null || lineHeightMm.isFinite() && lineHeightMm > 0))
        val reference = font.referenceHeightMm ?: return emptyList()
        val mmPerUnit = InkStitchFont.MM_PER_PX * heightMm / reference
        val info = font.info
        val letterSpacing = letterSpacingMm / mmPerUnit
        val out = ArrayList<Placement>()
        var y = 0.0
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        for ((index, rawLine) in normalized.lines().withIndex()) {
            val line = rawLine.trim()
            val placed = ArrayList<Placement>()
            var x = 0.0
            var left = Double.POSITIVE_INFINITY
            var right = Double.NEGATIVE_INFINITY
            for (rawWord in line.split(' ')) {
                val word = when (info.letterCase) { "upper" -> rawWord.uppercase(); "lower" -> rawWord.lowercase(); else -> rawWord }
                var last: String? = null
                var i = 0
                while (i < word.length) {
                    var (glyph, length) = font.glyphAt(word, i)
                    if (glyph == null && info.defaultGlyph != " ") glyph = info.defaultGlyph?.let { font.glyphs[it] }
                    i += length
                    if (glyph == null) { x += info.wordSpacing; last = null; continue }
                    if (last != null) {
                        val kern = info.kerning["$last ${glyph.name}"] ?: info.kerning[last + glyph.name] ?: 0.0
                        x += glyph.minX - kern + letterSpacing
                    }
                    placed += Placement(glyph, (x - glyph.minX) * mmPerUnit, (y - font.baseline) * mmPerUnit, mmPerUnit)
                    left = min(left, x); right = max(right, x + glyph.maxX - glyph.minX)
                    x += (info.advances[glyph.name] ?: info.defaultAdvance ?: glyph.maxX) - glyph.minX
                    last = glyph.name
                }
                x += info.wordSpacing
            }
            val width = if (right > left) (right - left) * mmPerUnit else 0.0
            val shift = when (alignment) {
                TextAlignment.LEFT -> 0.0
                TextAlignment.CENTER -> -width / 2
                TextAlignment.RIGHT -> -width
            }
            val shifted = placed.map { it.copy(dx = it.dx + shift) }
            out += if (backAndForth && info.reversible && index % 2 == 1) shifted.asReversed() else shifted
            y += lineHeightMm?.let { it / mmPerUnit } ?: info.leading
        }
        return out
    }

}

object InkStitchFontRegistry {
    @Volatile var loader: ((String) -> InkStitchFont?)? = null
    private val fonts = java.util.concurrent.ConcurrentHashMap<String, InkStitchFont>()

    operator fun get(id: String): InkStitchFont? = fonts[id] ?: loader?.invoke(id)?.also { fonts[id] = it }
    fun put(id: String, font: InkStitchFont) { fonts[id] = font }
    fun invalidate(id: String) { fonts.remove(id) }
}
