// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.render.realistic

import kotlin.math.*

/** Resolution policy only; the e441a132 stitch shape/filter/composite equations are unchanged. */
object FixedRealisticPreview {
    // 사용자 결정 2026-11-06: docs/realistic-speed-experiment.md result B (16px/mm, serial).
    const val PIXELS_PER_MM = 16.0
    val svgScale get()=PIXELS_PER_MM/ThreadShape.SVG_PIXELS_PER_MM
    val dpi get()=(PIXELS_PER_MM*25.4).roundToInt()

    /** >=native density returns the original object: never call the area-average backend. */
    fun <T> output(outputPixelsPerMm:Double,original:T,areaAverage:()->T):T {
        require(outputPixelsPerMm>0&&outputPixelsPerMm.isFinite())
        return if(outputPixelsPerMm<PIXELS_PER_MM)areaAverage() else original
    }

    /** Exact rectangular area weights in native-pixel coordinates, averaging opaque sRGB bytes.
     * left/top locate the viewport in the fixed native-density original. Out-of-source area is background.
     * row buffers are caller-owned/reused. backgroundRow optionally supplies the already-painted
     * output backdrop, preserving hoop fabric outside a bounded native source. No Android scaling, gamma change or renderer equation change.
     */
    fun areaAverage(sourceWidth:Int,sourceHeight:Int,readRow:(Int,IntArray)->Unit,
                    left:Double,top:Double,step:Double,width:Int,height:Int,background:Int=-1,
                    isCancelled:()->Boolean={false},backgroundRow:((Int,IntArray)->Unit)?=null,onRow:(Int,IntArray)->Unit) {
        require(sourceWidth>0&&sourceHeight>0&&width>0&&height>0)
        require(left.isFinite()&&top.isFinite()&&step.isFinite()&&step>=1.0)
        val src=IntArray(sourceWidth);val out=IntArray(width);val backdrop=IntArray(width){background}
        val red=DoubleArray(width);val green=DoubleArray(width);val blue=DoubleArray(width)
        fun cancelled(){if(isCancelled())throw java.util.concurrent.CancellationException("Preview cancelled")}
        for(y in 0 until height) {
            cancelled();backgroundRow?.invoke(y,backdrop);red.fill(0.0);green.fill(0.0);blue.fill(0.0)
            val y0=top+y*step;val y1=top+(y+1)*step
            for(sy in floor(y0).toInt() until ceil(y1).toInt()) {
                cancelled();val insideY=sy in 0 until sourceHeight
                if(insideY)readRow(sy,src)
                val wy=min(y1,sy+1.0)-max(y0,sy.toDouble())
                for(x in 0 until width) {
                    val x0=left+x*step;val x1=left+(x+1)*step
                    for(sx in floor(x0).toInt() until ceil(x1).toInt()) {
                        val weight=wy*(min(x1,sx+1.0)-max(x0,sx.toDouble()))
                        val c=if(insideY&&sx in 0 until sourceWidth)src[sx] else backdrop[x]
                        red[x]+=weight*(c ushr 16 and 255);green[x]+=weight*(c ushr 8 and 255);blue[x]+=weight*(c and 255)
                    }
                }
            }
            val area=step*step
            for(x in 0 until width)out[x]=-0x1000000 or ((red[x]/area).roundToInt().coerceIn(0,255) shl 16) or
                ((green[x]/area).roundToInt().coerceIn(0,255) shl 8) or (blue[x]/area).roundToInt().coerceIn(0,255)
            cancelled();onRow(y,out)
        }
    }
    fun areaAverage(pixels:IntArray,sourceWidth:Int,sourceHeight:Int,left:Double,top:Double,
                    step:Double,width:Int,height:Int,background:Int=-1):IntArray {
        require(pixels.size==Math.multiplyExact(sourceWidth,sourceHeight))
        val output=IntArray(Math.multiplyExact(width,height))
        areaAverage(sourceWidth,sourceHeight,{y,row->pixels.copyInto(row,0,y*sourceWidth,(y+1)*sourceWidth)},
            left,top,step,width,height,background,onRow={y,row->row.copyInto(output,y*width)})
        return output
    }
}
