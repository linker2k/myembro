// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.elements.Stroke
import io.github.linker2k.myembro.engine.inkstitch.elements.StrokeParameters
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin
import io.github.linker2k.myembro.engine.inkstitch.stitches.AutoSatin
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.*

/** Mark widths indexed by path, in the same source units as centerlines; absent entries are body paths. */
data class KanaSatinData(val centerlines:CenterlineSatinData, val markWidths:Map<Int,Double>) {
    init {
        require(centerlines.paths.isNotEmpty()) { "Empty kana centerlines" }
        require(markWidths.all { (index,width)->index in centerlines.paths.indices && width.isFinite() && width>0 })
        for(indices in centerlines.paths.indices.groupBy { centerlines.characters[it] to centerlines.components[it] }.values) {
            require(indices.map { markWidths[it] }.distinct().size==1) { "A component needs one consistent mark width" }
        }
    }
}

/** Kana-only orchestration of the existing AutoTrace/InkStitch ports. No changes to HangulSatin.
 * Policy (user decision 2026-10-31): voiced marks last; below 1.0mm use triple running.
 * Ink/Stitch lib/elements/stroke.py@b0edd96311ece82ee48816dc93466274b12c2a9c
 * documents bean_stitch_repeats=1 as forward/back/forward (3 passes), not repeats=3 (7).
 */
object KanaSatin {
    const val MIN_MARK_SATIN_MM=1.0
    const val BACKEND="autotrace-kana-marks-v1"
    fun isKana(codePoint:Int)=codePoint in 0x3041..0x3096 || codePoint in 0x30a1..0x30fa
    fun applies(text:String):Boolean {
        val codes=text.codePoints().toArray().filterNot(Character::isWhitespace)
        return codes.isNotEmpty() && codes.all(::isKana)
    }
    fun generate(source:KanaSatinData,heightMm:Double,sourceHeight:Double,thread:EmbThread,
        characterThreads:Map<Int,EmbThread> = emptyMap(),parameters:SatinParameters=SatinParameters()):EmbPattern {
        require(heightMm.isFinite() && heightMm>0 && sourceHeight.isFinite() && sourceHeight>0)
        val data=source.centerlines;val scale=heightMm/sourceHeight*PIXELS_PER_MM
        val groups=mutableListOf<StitchGroup>();var beans=0;var satinMarks=0
        for(character in data.characters.distinct().sorted()) {
            val components=data.paths.indices.filter { data.characters[it]==character }.groupBy { data.components[it] }.toSortedMap()
            for(indices in components.values) {
                val markWidth=source.markWidths[indices.first()]
                val color=characterThreads[character] ?: thread
                val paths=indices.map { index->data.paths[index].map { (x,y)->Point(x*scale,y*scale) } }
                val componentGroups=if(markWidth!=null && markWidth*heightMm/sourceHeight<MIN_MARK_SATIN_MM) {
                    beans++
                    Stroke(paths,color,StrokeParameters(bean_stitch_repeats=listOf(1),force_lock_stitches=true))
                        .toStitchGroups(groups.lastOrNull()?.stitches?.lastOrNull())
                } else {
                    if(markWidth!=null)satinMarks++
                    val width=(markWidth ?: data.widths.getValue(character))*scale
                    val sections=paths.map { path->
                        val started=if(path.first()==path.last())StrokeToSatin.setFirstNode(path,width)else path
                        StrokeToSatin.filteredRungs(requireNotNull(StrokeToSatin.convertPathToSatin(started,width)) { "Cannot convert kana centerline to satin" })
                    }
                    val routed=AutoSatin.route(sections,color,io.github.linker2k.myembro.engine.inkstitch.elements.SatinUnderlayPolicy.withDefault(parameters,width/PIXELS_PER_MM))
                    require(routed.discardedSatins==0 && routed.groups.isNotEmpty()) { "Incomplete kana centerline satin" }
                    routed.groups
                }
                require(componentGroups.isNotEmpty()) { "Empty kana component" }
                if(groups.isNotEmpty())groups[groups.lastIndex]=groups.last().copy(trimAfter=true)
                groups+=componentGroups
            }
        }
        val pattern=GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true).centeredGenerated()
        return EmbPattern(pattern.stitches,pattern.threads,pattern.metadata+mapOf("myembro.lettering.backend" to BACKEND,
            "myembro.kana.bean_marks" to beans.toString(),"myembro.kana.satin_marks" to satinMarks.toString()),pattern.warnings)
    }
}
