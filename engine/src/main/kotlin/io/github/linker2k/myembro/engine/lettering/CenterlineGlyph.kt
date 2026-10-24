// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.*
import java.util.Locale

/** Top-down rows, 0=ink/255=background. The caller owns rasterization, with no platform types here. */
data class BinaryGlyphRaster(val width:Int,val height:Int,val pixels:ByteArray,val transform:RasterToSource) {
    init {
        require(width in 1..4096 && height in 1..4096 && width.toLong()*height<=1_048_576) { "Glyph raster exceeds AutoTrace budget" }
        require(pixels.size==width*height)
        require(pixels.all { it==0.toByte() || it==255.toByte() })
    }
}

/** Preserve subtract/add/multiply/divide order of the Android adapter, including floating rounding.
 * Defaults are the existing 128px sampling, 8px padding and 1000px outline coordinate contract.
 */
data class RasterToSource(val left:Int,val top:Int,val paddingX:Int=8,val paddingY:Int=8,
    val sourceSize:Int=1000,val rasterSize:Int=128) {
    init { require(sourceSize>0 && rasterSize>0) }
    fun toSource(x:Double,y:Double):Pair<Double,Double> =
        (x-paddingX+left)*sourceSize/rasterSize to (y-paddingY+top)*sourceSize/rasterSize
}

/** Existing label/thin/trace/fit/order/flatten chain moved unchanged from AndroidCenterlineGlyph.
 * The underlying AutoTrace ports keep their own pinned upstream provenance and defaults.
 */
object CenterlineGlyph {
    data class Result(val paths:List<List<Pair<Double,Double>>>,val components:List<Int>)
    fun extract(raster:BinaryGlyphRaster):Result {
        val width=raster.width;val height=raster.height;val pixels=raster.pixels
        val labeling=HangulComponentOrder.label(width,height,pixels,listOf(HangulComponentOrder.GlyphBounds(0,0,0,width,height)))
        val paths=AutoTraceCenterline.trace(width,height,AutoTraceThinning.thin(width,height,pixels))
        val fitted=AutoTraceFit.fit(paths);val output=mutableListOf<List<Pair<Double,Double>>>();val components=mutableListOf<Int>()
        val owners=paths.map { labeling.component(it).id }
        for((order,component) in labeling.ordered.withIndex()) {
            val indices=paths.indices.filter { owners[it]==component.id }
            require(indices.isNotEmpty()) { "Ink component has no centerline" }
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
        return Result(output,components)
    }
}
