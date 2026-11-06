// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.render.realistic

import io.github.linker2k.myembro.engine.io.*
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.test.*

internal object FixedPreviewFixture {
    private val rasterizer=RealisticThreadRendererTest().rasterizer // construct before timed scene rendering
    const val widthMm=44.0;const val heightMm=32.0
    fun patch():List<RenderThreadSegment> {
        val rgb=PatternThreadSegments.displayRgb(PecThreads.forIndex(35).color)
        fun point(x:Double,y:Double)=RenderPoint(x*ThreadShape.SVG_PIXELS_PER_MM,y*ThreadShape.SVG_PIXELS_PER_MM)
        fun stitch(x:Double,y:Double,xx:Double,yy:Double)=RenderThreadSegment(point(x,y),point(xx,yy),rgb)
        val underlay=(0..48).flatMap { col->val ys=(0..6).map { 4.0+it*4 }.let { if(col%2==0)it else it.reversed() }
            ys.zipWithNext().map { (a,b)->stitch(4+col*.75,a,4+col*.75,b) } }
        val lengths=listOf(5.0,7.0,3.0,6.0,4.0,7.0,4.0)
        return underlay+(0..96).flatMap { row->val xs=mutableListOf(4.0);val k=row%lengths.size
            for(d in lengths.drop(k)+lengths.take(k))xs+=xs.last()+d
            (if(row%2==0)xs else xs.reversed()).zipWithNext().map { (a,b)->stitch(a,4+row*.25,b,4+row*.25) } }
    }
    fun mean(pixels:IntArray,width:Int,ppm:Double,box:DoubleArray):Pair<Double,Double> {
        var sum=0.0;var mean=0.0;var m2=0.0
        for(y in floor(box[2]*ppm).toInt() until ceil(box[3]*ppm).toInt())for(x in floor(box[0]*ppm).toInt() until ceil(box[1]*ppm).toInt()) {
            val weight=(min(x+1.0,box[1]*ppm)-max(x.toDouble(),box[0]*ppm))*(min(y+1.0,box[3]*ppm)-max(y.toDouble(),box[2]*ppm))
            if(weight<=0)continue
            val c=pixels[y*width+x];val v=.2126*(c ushr 16 and 255)+.7152*(c ushr 8 and 255)+.0722*(c and 255)
            val delta=v-mean;sum+=weight;mean+=weight/sum*delta;m2+=weight*delta*(v-mean)
        }
        return mean to sqrt(max(0.0,m2/sum))
    }
    fun native(segments:List<RenderThreadSegment>,widthMm:Double,heightMm:Double):Triple<IntArray,Int,Int> {
        val w=ceil(widthMm*FixedRealisticPreview.PIXELS_PER_MM).toInt();val h=ceil(heightMm*FixedRealisticPreview.PIXELS_PER_MM).toInt()
        val a=RealisticThreadRenderer.render(w,h,FixedRealisticPreview.dpi,segments,rasterizer,
            pixelsPerSvgUnit=FixedRealisticPreview.svgScale)
        return Triple(a,w,h)
    }
}

class FixedRealisticPreviewTest {
    @Test fun integerAndFractionalAreasAndNativeCrop() {
        val pixels=intArrayOf(0xff000000.toInt(),0xff787878.toInt(),0xfff0f0f0.toInt(),0xff000000.toInt(),0xff787878.toInt(),0xfff0f0f0.toInt())
        assertContentEquals(intArrayOf(0xff282828.toInt(),0xffc8c8c8.toInt()),
            FixedRealisticPreview.areaAverage(pixels,3,2,0.0,0.0,1.5,2,1))
        assertContentEquals(intArrayOf(pixels[1],pixels[2]),FixedRealisticPreview.areaAverage(pixels,3,2,1.0,0.0,1.0,2,1))
    }
    @Test fun backdropRowsPreserveFabricOutsideTheBoundedSource() {
        val rows=mutableListOf<IntArray>()
        FixedRealisticPreview.areaAverage(1,1,{_,row->row[0]=0xff000000.toInt()},
            -1.0,0.0,1.0,3,1,backgroundRow={_,row->
                row[0]=0xff112233.toInt();row[1]=0xff445566.toInt();row[2]=0xff778899.toInt()
            },onRow={_,row->rows+=row.copyOf()})
        assertContentEquals(intArrayOf(0xff112233.toInt(),0xff000000.toInt(),0xff778899.toInt()),rows.single())
    }
    @Test fun atOrAboveNativeDensityNeverCallsDownsampler() {
        assertEquals(16.0,FixedRealisticPreview.PIXELS_PER_MM)
        val source=intArrayOf(1,2,3);var calls=0
        for(ppm in listOf(FixedRealisticPreview.PIXELS_PER_MM,FixedRealisticPreview.PIXELS_PER_MM*2)) {
            assertSame(source,FixedRealisticPreview.output(ppm,source){calls++;error("must not downsample")})
        }
        assertEquals(0,calls)
        val reduced=intArrayOf(4)
        assertSame(reduced,FixedRealisticPreview.output(FixedRealisticPreview.PIXELS_PER_MM/2,source){calls++;reduced})
        assertEquals(1,calls)
    }
    @Test fun outputResolutionChangesPatchMeanByAtMostOneCodeValue() {
        val (source,sw,sh)=FixedPreviewFixture.native(FixedPreviewFixture.patch(),FixedPreviewFixture.widthMm,FixedPreviewFixture.heightMm)
        val means=listOf(4.43,8.0,FixedRealisticPreview.PIXELS_PER_MM).map { ppm->
            val w=ceil(FixedPreviewFixture.widthMm*ppm).toInt();val h=ceil(FixedPreviewFixture.heightMm*ppm).toInt()
            val a=FixedRealisticPreview.output(ppm,source){FixedRealisticPreview.areaAverage(source,sw,sh,0.0,0.0,FixedRealisticPreview.PIXELS_PER_MM/ppm,w,h)}
            FixedPreviewFixture.mean(a,w,ppm,doubleArrayOf(10.0,34.0,7.0,25.0)).first.also { println("BOX_MEAN ppm=$ppm mean=$it") }
        }
        val delta=means.max()-means.min()
        println("BOX_MEAN maxDelta=$delta limit=1.0")
        assertTrue(delta<=1.0,"mean Y' spread=$delta") // User-approved code-value difference, unchanged.
    }
}

/** Explicit local PNG/timing output. No source PES is a bundled fixture. */
object FixedRealisticMeasurement {
    @JvmStatic fun main(args:Array<String>) {
        val out=File(args[0]).apply { mkdirs() }
        val p=PesReader.read(File(args[1]).readBytes());val bounds=p.bounds!!
        val scenes=listOf(Triple("patch",FixedPreviewFixture.patch(),FixedPreviewFixture.widthMm to FixedPreviewFixture.heightMm),
            Triple("skull",PatternThreadSegments.from(p,bounds.minX-40.0,bounds.minY-40.0).toList(),bounds.width/10.0+8 to bounds.height/10.0+8))
        File(out,"measurements.csv").printWriter().use { log->
            log.println("scene,px_per_mm,mean_Y,std_Y,render_ms,resize_ms,downsample_calls")
            for((name,segments,size)in scenes) {
                val started=System.nanoTime();val (source,sw,sh)=FixedPreviewFixture.native(segments,size.first,size.second)
                val renderMs=(System.nanoTime()-started)/1e6
                for(ppm in listOf(4.43,8.0,FixedRealisticPreview.PIXELS_PER_MM)) {
                    val w=ceil(size.first*ppm).toInt();val h=ceil(size.second*ppm).toInt();var calls=0
                    val then=System.nanoTime()
                    val a=FixedRealisticPreview.output(ppm,source){calls++;FixedRealisticPreview.areaAverage(source,sw,sh,0.0,0.0,FixedRealisticPreview.PIXELS_PER_MM/ppm,w,h)}
                    val resizeMs=if(calls==0)0.0 else (System.nanoTime()-then)/1e6
                    val box=if(name=="patch")doubleArrayOf(10.0,34.0,7.0,25.0) else doubleArrayOf(38.0,45.0,33.0,44.5)
                    val (mean,std)=FixedPreviewFixture.mean(a,w,ppm,box)
                    val image=BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,w,h,a,0,w)
                    ImageIO.write(image,"png",File(out,"${name}-${ppm}pxmm.png"))
                    log.println("$name,$ppm,$mean,$std,$renderMs,$resizeMs,$calls");log.flush()
                    println("BOX_OUTPUT $name ppm=$ppm mean=$mean std=$std renderMs=$renderMs resizeMs=$resizeMs calls=$calls")
                }
            }
        }
    }
}
