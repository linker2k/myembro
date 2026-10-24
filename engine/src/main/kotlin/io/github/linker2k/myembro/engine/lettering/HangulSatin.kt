// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin
import io.github.linker2k.myembro.engine.inkstitch.stitches.AutoSatin
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as SvgPoint
import io.github.linker2k.myembro.engine.model.*

/** Offline, editable source-unit centerlines. Component numbers are already in bbox-row order per glyph. */
data class CenterlineSatinData(val paths:List<List<Pair<Double,Double>>>,val characters:List<Int>,
    val components:List<Int>,val widths:Map<Int,Double>) {
    init {
        require(paths.size==characters.size && paths.size==components.size)
        require(paths.all { it.size>=2 && it.all { (x,y)->x.isFinite() && y.isFinite() } })
        require(characters.all { it>=0 } && components.all { it>=0 })
        require(characters.all { widths[it]?.let { w->w.isFinite() && w>0 }==true })
    }
}
object HangulSatin {
    const val BACKEND="autotrace-component-satin-v1"
    fun applies(text:String):Boolean {
        val codes=text.codePoints().toArray().filterNot(Character::isWhitespace)
        return codes.isNotEmpty() && codes.all { it in 0xAC00..0xD7A3 }
    }
    /** Existing model median measurement at the same 15mm/.1mm sampling reference as the public sweep. */
    fun measuredWidthSource(contours:List<List<Pair<Double,Double>>>,sourceHeight:Double):Double {
        require(sourceHeight.isFinite() && sourceHeight>0)
        val scale=15.0/sourceHeight
        val region=PolygonRegion.fromPaths(contours.map { path -> Polyline(path.map { (x,y)->Point(x*scale,y*scale) },true) },FillRule.EVEN_ODD)
        return StrokeWidthMeasurement.measure(region.geometry).medianMm/scale
    }
    fun generate(data:CenterlineSatinData,heightMm:Double,sourceHeight:Double,thread:EmbThread,
        characterThreads:Map<Int,EmbThread> = emptyMap(),parameters:SatinParameters=SatinParameters()):EmbPattern {
        require(heightMm.isFinite() && heightMm>0 && sourceHeight.isFinite() && sourceHeight>0)
        val scale=heightMm/sourceHeight*PIXELS_PER_MM
        val groups=mutableListOf<StitchGroup>()
        for(character in data.characters.distinct().sorted())for(component in data.paths.indices.filter { data.characters[it]==character }.map { data.components[it] }.distinct().sorted()) {
            val width=data.widths.getValue(character)*scale
            val sections=data.paths.indices.filter { data.characters[it]==character && data.components[it]==component }.map { index ->
                val path=data.paths[index].map { (x,y)->SvgPoint(x*scale,y*scale) }
                val started=if(path.first()==path.last())StrokeToSatin.setFirstNode(path,width)else path
                StrokeToSatin.filteredRungs(requireNotNull(StrokeToSatin.convertPathToSatin(started,width)) { "Cannot convert centerline to satin" })
            }
            val routed=AutoSatin.route(sections,characterThreads[character] ?: thread,parameters)
            require(routed.discardedSatins==0 && routed.groups.isNotEmpty()) { "Incomplete centerline satin" }
            if(groups.isNotEmpty())groups[groups.lastIndex]=groups.last().copy(trimAfter=true)
            groups+=routed.groups
        }
        val pattern=GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true).centeredGenerated()
        return EmbPattern(pattern.stitches,pattern.threads,pattern.metadata+("myembro.lettering.backend" to BACKEND),pattern.warnings)
    }
}
