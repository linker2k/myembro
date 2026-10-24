// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import java.awt.image.BufferedImage
import java.awt.RenderingHints
import javax.imageio.ImageIO
import java.io.File
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.*
import kotlin.test.*

/** Ten fixed cases; own original drawings only, no photos. */
class ColorCleanupSweep {
    @Test fun smallestTestedAreaRemovingSpecksRetainsThreeDrawingThreads() {
        val rows=mutableListOf<String>();val counts=mutableMapOf<Double,MutableList<Int>>()
        val out=System.getenv("COLOR_CLEANUP_OUT")?.let { File(it).apply { mkdirs() } }
        for(name in listOf("face","robot")) {
            val source=ImageIO.read(javaClass.getResourceAsStream("/image-to-design/$name.png"))
            val image=BufferedImage(192,192,BufferedImage.TYPE_INT_ARGB)
            val graphics=image.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(source,0,0,192,192,null);graphics.dispose()
            // Four-by-four bright speck strictly inside the main colored body: 0.390625mm² at 30mm.
            for(y in 105..108) for(x in 92..95) image.setRGB(x,y,0xfffefefe.toInt())
            for(area in listOf(0.25,0.5,1.0,2.0,3.0)) {
                val start=System.nanoTime()
                val preview=ColorDrawingPipeline.trace(192,192,image.getRGB(0,0,192,192,null,0,192),30.0,4,minAreaMm2=area)
                val result=ColorDrawingPipeline.generate(preview)
                val pes=PesWriter.write(result.pattern)
                val report=SewoutCheck.checkPes(pes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
                val threads=result.pattern.threads.distinctBy { it.color }
                val count=threads.size
                val expected=if(name=="face") setOf("23","12","49") else setOf("23","32","49")
                counts.getOrPut(area) { mutableListOf() }+=if(threads.map { it.catalogNumber }.toSet()==expected && result.excludedCount==0) count else -1
                rows+="$name\t$area\t3\t$count\t${result.pattern.stitchCount}\t${report.verdict}\t${(System.nanoTime()-start)/1e6}\t${threads.map { it.catalogNumber to it.description }}"
                out?.resolve("$name-$area.pes")?.writeBytes(pes)
                assertFalse(report.hasBlockingIssues,rows.last())
            }
        }
        val header="input\tmin_area_mm2\texpected_threads\tactual_threads\tstitches\tgate\ttotal_ms\tthreads"
        println(header);rows.forEach(::println)
        out?.resolve("sweep.tsv")?.writeText((listOf(header)+rows).joinToString("\n"))
        assertEquals(ColorDrawingPipeline.MINIMUM_REGION_AREA_MM2,counts.entries.first { (_,values) -> values.all { it==3 } }.key,rows.joinToString("\n"))
    }
}
