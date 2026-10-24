// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.sewout.*
import javax.imageio.ImageIO
import kotlin.test.*

class ColorDrawingPipelineTest {
    @Test fun opaqueBackgroundPartialAlphaAndCropEdgesHaveExplicitContracts() {
        val pixels=IntArray(32*32) { p -> if(p%32 in 4..27 && p/32 in 4..27) 0xffff0000.toInt() else 0xffffffff.toInt() }
        val opaque=ColorDrawingPipeline.trace(32,32,pixels,30.0,2)
        assertEquals(1,opaque.layers.size)
        assertEquals(0,opaque.previewPixels()[0])
        val edge=IntArray(32*32) { p -> if(p%32<16) 0x80ff0000.toInt() else 0 }
        val transparent=ColorDrawingPipeline.trace(32,32,edge,30.0,2)
        assertTrue(transparent.layers.isNotEmpty())
        assertEquals(0,transparent.previewPixels()[0])
        assertTrue(transparent.palette.single() and 0xffffff == 0xff7f7f)
        assertFailsWith<IllegalArgumentException> { ColorDrawingPipeline.trace(32,32,pixels,30.0,7) }
    }
    @Test fun originalEmojiMapsToBrotherThreadsAndCancelsBetweenStages() {
        val image=ImageIO.read(javaClass.getResourceAsStream("/image-to-design/face.png"))
        val pixels=image.getRGB(0,0,image.width,image.height,null,0,image.width)
        val preview=ColorDrawingPipeline.trace(image.width,image.height,pixels,30.0,4)
        preview.layers.forEach { layer ->
            val rgb=preview.palette[layer.paletteIndex]
            assertEquals(PecThreads.SET.filterNotNull().minOf { PecThreads.distance(rgb,it.color) },PecThreads.distance(rgb,layer.thread.color))
        }
        assertFailsWith<InterruptedException> { ColorDrawingPipeline.generate(preview) { throw InterruptedException() } }
        val pes=PesWriter.write(ColorDrawingPipeline.generate(preview).pattern)
        assertFalse(SewoutCheck.checkPes(pes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED)).hasBlockingIssues)
    }
}
