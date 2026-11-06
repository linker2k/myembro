// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.render.realistic

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Explicit local diagnostic, not a CI test or production backend. Uses only generated public fixtures. */
object RealisticCoverageProbe {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args[0]);val out=File(args[1]);val test=RealisticThreadRendererTest()
        for(case in listOf("horizontal","vertical","tatami-patch"))for(dpi in listOf(300,600)) {
            val edge=kotlin.math.floor(16/25.4*dpi+.5).toInt()
            val scale=edge/(16*ThreadShape.SVG_PIXELS_PER_MM)
            val segments=test.segments(case)
            var index=0
            val rasterizer=ThreadAlphaRasterizer { _,width,height ->
                val region=ThreadFilterRegion.bounds(ThreadShape.path(segments[index],scale))
                require(width==region.width&&height==region.height)
                val input=ImageIO.read(File(root,"$case/$dpi/${index.toString().padStart(3,'0')}.png"))
                index++
                IntArray(width*height) { i ->
                    val x=region.left+i%width;val y=region.top+i/width
                    if(x in 0 until edge&&y in 0 until edge)input.getRGB(x,y) ushr 24 else 0
                }
            }
            val pixels=RealisticThreadRenderer.render(edge,edge,dpi,segments,rasterizer,pixelsPerSvgUnit=scale)
            require(index==segments.size)
            val image=BufferedImage(edge,edge,BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0,0,edge,edge,pixels,0,edge)
            val file=File(out,"png/$dpi/$case.png").apply { parentFile.mkdirs() }
            check(ImageIO.write(image,"png",file))
            println("COVERAGE_PROBE $case/$dpi paths=$index")
        }
    }
}
