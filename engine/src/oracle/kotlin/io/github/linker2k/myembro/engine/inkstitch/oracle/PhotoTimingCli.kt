// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import io.github.linker2k.myembro.engine.inkstitch.svg.image.RedworkPipeline
import io.github.linker2k.myembro.engine.model.EmbThread

/** Local opt-in timing; defaults to generated images; an explicit local-only input is never copied or rendered. */
object PhotoTimingCli {
    @JvmStatic fun main(args: Array<String>) {
        val out=File("/tmp/myembro-photo-performance").apply { mkdirs() }
        val input=args.getOrNull(3)?.let(::File) ?: File(out,"synthetic-12mp.jpg")
        if(args.getOrNull(3)==null && !input.exists()) {
            val image=BufferedImage(4000,3000,BufferedImage.TYPE_INT_RGB)
            for(y in 0 until 3000) for(x in 0 until 4000) {
                // Textured synthetic landscape: broad sky, roof, circular foliage and fine ripples.
                val v=when {
                    y<900 -> 220+(x*13+y*7)%30
                    x in 1200..2700 && y in 1300..2600 -> if((x/85+y/75)%2==0) 35 else 115
                    (x-700L)*(x-700)+(y-1600L)*(y-1600)<450L*450 -> 35+(x*17+y*23)%85
                    else -> 140+(x*7+y*11)%100
                }
                image.setRGB(x,y,(v shl 16) or (v shl 8) or v)
            }
            ImageIO.write(image,"jpg",input)
        }
        val start=System.nanoTime();val image=ImageIO.read(input)
        println("decode_ms="+(System.nanoTime()-start)/1e6+" input=${image.width}x${image.height}")
        val edge=args.firstOrNull()?.toInt()?:192
        val scaled=BufferedImage(edge,edge,BufferedImage.TYPE_INT_RGB)
        val side=minOf(image.width,image.height);val left=(image.width-side)/2;val top=(image.height-side)/2
        scaled.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image,0,0,edge,edge,left,top,left+side,top+side,null); dispose()
        }
        val rgb=scaled.getRGB(0,0,edge,edge,null,0,edge).map { it and 0xFFFFFF }.toIntArray()
        var stage="start";var tick=System.nanoTime()
        val progress: (String)->Unit={ next -> if(next!=stage) {
            println("${stage}_ms="+(System.nanoTime()-tick)/1e6);System.out.flush();stage=next;tick=System.nanoTime()
        } }
        val filtered=args.getOrNull(1)=="filtered"
        val filteredRgb=if(filtered) io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoRasterFilters.median3(edge,edge,rgb) else rgb
        val outline=RedworkPipeline.trace(edge,edge,filteredRgb,99.9,0,EmbThread(0xFF000000.toInt()),
            if(filtered) .5 else 0.0, if(filtered) 128 else Int.MAX_VALUE,checkpoint=progress)
        println("contours=${outline.contourCount} foreground_area_mm2=${outline.foregroundAreaMm2}")
        if(args.getOrNull(2)!="preview") {
            val result=RedworkPipeline.generate(outline,EmbThread(0xFF000000.toInt()),photoMode=filtered,checkpoint=progress)
            println("${stage}_ms="+(System.nanoTime()-tick)/1e6+" stitches=${result.pattern.stitchCount} backend=${result.pattern.metadata["myembro.fill.backend"]}")
            val report=io.github.linker2k.myembro.engine.sewout.SewoutCheck.checkPes(result.copyPesBytes(),
                options=io.github.linker2k.myembro.engine.sewout.SewoutOptions(origin=io.github.linker2k.myembro.engine.sewout.DesignOrigin.APP_GENERATED))
            println("blockers="+report.findings.count { it.severity==io.github.linker2k.myembro.engine.sewout.Severity.BLOCKER })
        }
    }
}
