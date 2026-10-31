// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.*
import java.util.Locale

/** Kana-only adapter around the existing AutoTrace chain; Hangul extraction is unchanged.
 * Voicing marks are excluded from the body row DAG and emitted last (user decision 2026-10-31).
 * Geometry and width measurement evidence: docs/research/kana-satin-dakuten.md.
 */
object KanaCenterlineGlyph {
    data class Result(val paths:List<List<Pair<Double,Double>>>,val components:List<Int>, val marks:Map<Int,Double>)
    fun extract(raster:BinaryGlyphRaster, codePoint:Int, sourceHeight:Double, contours:List<List<Pair<Double,Double>>>):Result {
        require(KanaSatin.isKana(codePoint))
        val width=raster.width;val height=raster.height;val pixels=raster.pixels
        val labeling=HangulComponentOrder.label(width,height,pixels,listOf(HangulComponentOrder.GlyphBounds(0,0,0,width,height)))
        val paths=AutoTraceCenterline.trace(width,height,AutoTraceThinning.thin(width,height,pixels))
        val fitted=AutoTraceFit.fit(paths);val output=mutableListOf<List<Pair<Double,Double>>>();val components=mutableListOf<Int>()
        val owners=paths.map { labeling.component(it).id }
        val markIds=markComponents(codePoint,labeling.components).map { it.id }.toSet()
        val ordered=orderComponents(codePoint,labeling.components)
        val markWidths=mutableMapOf<Int,Double>()
        for((order,component) in ordered.withIndex()) {
            val indices=paths.indices.filter { owners[it]==component.id }
            require(indices.isNotEmpty()) { "Ink component has no centerline" }
            if(component.id in markIds) markWidths[order]=measureMark(raster, component, contours, sourceHeight)
            for(index in indices) {
                fun v(segment:AutoTraceFit.Segment,i:Int,axis:Int):String {
                    val p=segment.v[i];val number=if(axis==0)p.x else height.toFloat()-p.y
                    return String.format(Locale.ROOT,"%.6g",number)
                }
                val d=buildString {
                    val segments=fitted[index].segments;require(segments.isNotEmpty())
                    append("M ${v(segments.first(),0,0)} ${v(segments.first(),0,1)} ")
                    for(s in segments) if(s.degree==1)append("L ${v(s,3,0)} ${v(s,3,1)} ")
                    else append("C ${v(s,1,0)} ${v(s,1,1)} ${v(s,2,0)} ${v(s,2,1)} ${v(s,3,0)} ${v(s,3,1)} ")
                }
                val points=SvgPath.flatten(d,Affine2D.IDENTITY,tolerance=0.1).single().points
                output+=points.map { raster.transform.toSource(it.x,it.y) }
                components+=order
            }
        }
        return Result(output,components,markWidths)
    }

    internal fun orderComponents(codePoint:Int,components:List<HangulComponentOrder.Component>):List<HangulComponentOrder.Component> {
        val markIds=markComponents(codePoint,components).map { it.id }.toSet()
        return HangulComponentOrder.order(components.filterNot { it.id in markIds }) +
            components.filter { it.id in markIds }.sortedWith(compareBy({it.left},{it.top}))
    }
    /** NFC decomposition restricts geometric detection to actual voiced/semi-voiced kana. */
    fun markComponents(codePoint:Int, components:List<HangulComponentOrder.Component>):List<HangulComponentOrder.Component> {
        val nfd=java.text.Normalizer.normalize(String(Character.toChars(codePoint)),java.text.Normalizer.Form.NFD)
        val mark=nfd.lastOrNull()
        if(mark!='\u3099' && mark!='\u309a') return emptyList()
        val body=components.maxByOrNull { it.pixelCount } ?: error("Empty kana glyph")
        val candidates=components.filter { it.id!=body.id && it.pixelCount<body.pixelCount &&
            2*it.left>=body.left+body.right && 2*it.bottom<=body.top+body.bottom }
        require(candidates.size==if(mark=='\u309a')1 else 2) { "Ambiguous kana voicing-mark geometry" }
        return candidates
    }
    /** Measure original vector rings (including holes), never the stair-stepped raster boundary.
     * One raster-pixel envelope accounts only for Paint pixel quantization at the component edge.
     * Contours use an uncentered x origin, matching rasterize/getTextPath at baseline (0,0).
     */
    internal fun measureMark(raster:BinaryGlyphRaster,component:HangulComponentOrder.Component,
        contours:List<List<Pair<Double,Double>>>,sourceHeight:Double):Double {
        val leftTop=raster.transform.toSource((component.left-1).toDouble(),(component.top-1).toDouble())
        val rightBottom=raster.transform.toSource((component.right+1).toDouble(),(component.bottom+1).toDouble())
        // Select complete ink polygons, never isolated contour rings: accepting an inner ring
        // without its outer ring would measure the counter's diameter as stroke thickness.
        val glyph=io.github.linker2k.myembro.engine.geometry.PolygonRegion.fromPaths(contours.map { path ->
            io.github.linker2k.myembro.engine.geometry.Polyline(path.map { (x,y)->io.github.linker2k.myembro.engine.geometry.Point(x,y) },true)
        },io.github.linker2k.myembro.engine.geometry.FillRule.EVEN_ODD).geometry
        val polygons=(0 until glyph.numGeometries).map { glyph.getGeometryN(it) }.filterIsInstance<org.locationtech.jts.geom.Polygon>()
            .filter { polygon -> polygon.envelopeInternal.let { box ->
                box.minX>=leftTop.first && box.maxX<=rightBottom.first && box.minY>=leftTop.second && box.maxY<=rightBottom.second
            } }
        require(polygons.size==1) { "Cannot isolate kana mark outline" }
        val polygon=polygons.single()
        val rings=(listOf(polygon.exteriorRing)+(0 until polygon.numInteriorRing).map(polygon::getInteriorRingN))
            .map { ring->ring.coordinates.map { it.x to it.y } }
        return HangulSatin.measuredWidthSource(rings,sourceHeight)
    }
}
