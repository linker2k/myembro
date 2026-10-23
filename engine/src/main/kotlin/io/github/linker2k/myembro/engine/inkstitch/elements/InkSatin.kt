// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/satin_column.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.lettering.SatinOptions
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as SvgPoint

/** Existing lettering API adapter. Core SVG defaults remain in SatinParameters. */
object InkSatin {
    private fun Point.svg()=SvgPoint(Units.mmToPixels(x),Units.mmToPixels(y))
    private fun SvgPoint.mm()=Point(Units.pixelsToMm(x),Units.pixelsToMm(y))
    data class Rung(val a:Point,val b:Point)
    fun paths(left:List<Point>,right:List<Point>,options:SatinOptions,rungs:List<Rung> = emptyList()):List<List<Point>> {
        val p = options.sourceParameters()
        return SatinColumn.generateLayers(left.map { it.svg() },right.map { it.svg() },p,rungs.map { SatinColumn.Rung(it.a.svg(),it.b.svg()) }).map { layer->layer.points.map { it.mm() } }
    }
    /** Shared source element, preserving tags, per-element minima and source lock defaults. */
    fun element(left:List<Point>,right:List<Point>,options:SatinOptions,rungs:List<Rung> = emptyList(),
        color:io.github.linker2k.myembro.engine.model.EmbThread? = null):SatinColumn = SatinColumn(
            left.map { it.svg() }, right.map { it.svg() }, options.sourceParameters(),
            rungs.map { SatinColumn.Rung(it.a.svg(),it.b.svg()) }, color=color)
    internal fun pairs(left:List<Point>,right:List<Point>,rungs:List<Rung>,spacing:Double,offsetMm:Double=0.0):List<Pair<Point,Point>> =
        SatinColumn.plotPointsOnRails(left.map { it.svg() },right.map { it.svg() },SatinParameters(zigzag_spacing_mm=spacing,pull_compensation_mm=offsetMm to offsetMm),rungs.map { SatinColumn.Rung(it.a.svg(),it.b.svg()) }).map { it.first.mm() to it.second.mm() }
    internal fun shorten(pairs:List<Pair<Point,Point>>,distanceMm:Double=0.3,insetFraction:Double=0.15):List<Pair<Point,Point>> {
        var a:Point?=null;var b:Point?=null
        return pairs.map { (x,y)->
            val inset=minOf(insetFraction*x.distance(y),7.0/3)
            val dx=if(a==null || x.distance(a!!)>=distanceMm){a=x;0.0}else -inset
            val dy=if(b==null || y.distance(b!!)>=distanceMm){b=y;0.0}else -inset
            SatinColumn.offsetPoints(x.svg(),y.svg(),Units.mmToPixels(dx) to Units.mmToPixels(dy)).let { it.first.mm() to it.second.mm() }
        }
    }
}
