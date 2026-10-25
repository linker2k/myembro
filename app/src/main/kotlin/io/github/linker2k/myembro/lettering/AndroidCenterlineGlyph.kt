// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.lettering

import android.graphics.*
import io.github.linker2k.myembro.engine.lettering.*
import kotlin.math.ceil
import kotlin.math.floor

/** Android Paint raster -> existing exact AutoTrace fit -> component order. Call off main thread. */
object AndroidCenterlineGlyph {
    data class Result(val paths:List<List<Pair<Double,Double>>>,val components:List<Int>)
    fun extract(character:String,typeface:Typeface):Result {
        val result=CenterlineGlyph.extract(rasterize(character,typeface))
        return Result(result.paths,result.components)
    }
    /** Exact existing binary raster, exposed for pinned JVM replay snapshots. */
    fun rasterize(character:String,typeface:Typeface):BinaryGlyphRaster {
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
        return BinaryGlyphRaster(width,height,pixels,RasterToSource(left,top))
    }
}
