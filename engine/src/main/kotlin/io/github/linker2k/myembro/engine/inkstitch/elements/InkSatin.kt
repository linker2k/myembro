// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/satin_column.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.lettering.SatinOptions

/** Existing lettering API adapter. Core SVG defaults remain in SatinParameters. */
object InkSatin {
    data class Rung(val a:Point,val b:Point)
    fun paths(left:List<Point>,right:List<Point>,options:SatinOptions,rungs:List<Rung> = emptyList()):List<List<Point>> {
        val paired = if(left.size>=2 && right.size>=2) SatinColumn.plotPointsOnRails(left,right) else emptyList()
        val maximum=minOf(options.maxStitchLengthMm, if(paired.any { it.first.distance(it.second)>7.0 })4.0 else 7.0)
        val p=SatinParameters(zigzag_spacing_mm=options.densityMm,
            pull_compensation_mm=options.pullCompensationMm to options.pullCompensationMm,
            max_stitch_length_mm=maximum, short_stitch_distance_mm=0.3,
            center_walk_underlay=options.centerUnderlay,
            center_walk_underlay_stitch_length_mm=options.underlayStitchLengthMm,
            contour_underlay=options.contourUnderlay,
            contour_underlay_inset_mm=options.underlayInsetMm to options.underlayInsetMm,
            contour_underlay_stitch_length_mm=options.underlayStitchLengthMm,
            zigzag_underlay=options.zigzagUnderlay,
            zigzag_underlay_spacing_mm=options.underlayDensityMm,
            zigzag_underlay_max_stitch_length_mm=options.maxStitchLengthMm)
        return SatinColumn.generateLayers(left,right,p,rungs.map { SatinColumn.Rung(it.a,it.b) }).map { it.points }
    }
    internal fun pairs(left:List<Point>,right:List<Point>,rungs:List<Rung>,spacing:Double,offsetMm:Double=0.0):List<Pair<Point,Point>> =
        SatinColumn.plotPointsOnRails(left,right,SatinParameters(zigzag_spacing_mm=spacing,pull_compensation_mm=offsetMm to offsetMm),rungs.map { SatinColumn.Rung(it.a,it.b) })
    internal fun shorten(pairs:List<Pair<Point,Point>>,distanceMm:Double=0.3,insetFraction:Double=0.15):List<Pair<Point,Point>> {
        var a:Point?=null;var b:Point?=null
        return pairs.map { (x,y)->
            val inset=minOf(insetFraction*x.distance(y),7.0/3)
            val dx=if(a==null || x.distance(a!!)>=distanceMm){a=x;0.0}else -inset
            val dy=if(b==null || y.distance(b!!)>=distanceMm){b=y;0.0}else -inset
            SatinColumn.offsetPoints(x,y,dx to dy)
        }
    }
}
