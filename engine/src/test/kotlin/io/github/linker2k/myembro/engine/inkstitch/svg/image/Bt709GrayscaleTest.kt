// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.*
import javax.imageio.ImageIO
import kotlin.test.*

class Bt709GrayscaleTest {
    @Test fun bt709ReferencePrimariesAndEveryGrayBytePreserveAlpha() {
        assertEquals(54,Bt709Grayscale.argb(0x80ff0000.toInt()) and 255)
        assertEquals(182,Bt709Grayscale.argb(0xff00ff00.toInt()) and 255)
        assertEquals(18,Bt709Grayscale.argb(0xff0000ff.toInt()) and 255)
        for(y in 0..255) {
            val pixel=0x80000000.toInt() or (y shl 16) or (y shl 8) or y
            assertEquals(pixel,Bt709Grayscale.argb(pixel))
            val selected=Bt709Grayscale.nearestThread(pixel)
            assertEquals(Bt709Grayscale.threads.minOf { PecThreads.distance(pixel,it.color) },PecThreads.distance(pixel,selected.color))
        }
    }
    @Test fun everyWuBudgetIsGrayAndSyntheticPesUsesOnlyNeutralThreads() {
        val source=ImageIO.read(javaClass.getResourceAsStream("/image-to-design/face.png"))
        val argb=source.getRGB(0,0,source.width,source.height,null,0,source.width)
        for(n in 2..6) {
            val preview=ColorDrawingPipeline.trace(source.width,source.height,argb,30.0,n,grayscale=true)
            assertTrue(preview.palette.size<=n)
            assertTrue(preview.palette.all { ((it ushr 16) and 255)==((it ushr 8) and 255) && ((it ushr 8) and 255)==(it and 255) })
            assertTrue(preview.layers.all { it.thread in Bt709Grayscale.threads })
        }
        val result=ColorDrawingPipeline.generate(ColorDrawingPipeline.trace(source.width,source.height,argb,30.0,3,grayscale=true))
        assertTrue(result.pattern.stitchCount>0)
        assertEquals(0,result.excludedCount)
        val pes=PesWriter.write(result.pattern)
        assertFalse(SewoutCheck.checkPes(pes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED)).hasBlockingIssues)
        System.getenv("GRAYSCALE_OUT")?.let { path -> java.io.File(path).apply { mkdirs() }.resolve("grayscale-face.pes").writeBytes(pes) }
    }
}
