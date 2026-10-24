// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.lettering

import android.graphics.*
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.*
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** Android Paint raster -> existing exact AutoTrace fit -> component order. Call off main thread. */
object AndroidCenterlineGlyph {
    data class Result(val paths:List<List<Pair<Double,Double>>>,val components:List<Int>)
    fun extract(character:String,typeface:Typeface):Result {
        require(character.codePointCount(0,character.length)==1)
        // Same 128px sampling and 8px pad as KRSAT3; returned coordinates use the 1000px outline contract.
        val paint=Paint().apply { this.typeface=typeface;textSize=128f;isAntiAlias=false;color=Color.BLACK }
        val outline=Path();paint.getTextPath(character,0,character.length,0f,0f,outline)
        val bounds=RectF();outline.computeBounds(bounds,true)
        val left=floor(bounds.left.toDouble()).toInt();val top=floor(bounds.top.toDouble()).toInt()
        val width=ceil(bounds.right.toDouble()).toInt()-left+16;val height=ceil(bounds.bottom.toDouble()).toInt()-top+16
        require(width in 1..4096 && height in 1..4096 && width.toLong()*height<=1_048_576) { "Glyph raster exceeds AutoTrace budget" }
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);canvas.drawText(character,(8-left).toFloat(),(8-top).toFloat(),paint)
        val rgba=IntArray(width*height);bitmap.getPixels(rgba,0,width,0,0,width,height);bitmap.recycle()
        val pixels=ByteArray(rgba.size) { if(rgba[it] and 0xffffff==0)0 else 255.toByte() }
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
                output+=points.map { (it.x-8+left)*1000/128 to (it.y-8+top)*1000/128 }
                components+=order
            }
        }
        return Result(output,components)
    }
}
