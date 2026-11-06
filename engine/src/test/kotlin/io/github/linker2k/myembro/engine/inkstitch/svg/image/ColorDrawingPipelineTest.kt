// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.sewout.*
import javax.imageio.ImageIO
import kotlin.test.*

class ColorDrawingPipelineTest {
    /** Wu route side limit = the 1024px resolution maximum (1024² = Wu's 1,048,576-pixel limit). */
    @Test fun wuRouteSideLimitIs1024Inclusive() {
        fun stripes(w: Int, h: Int) = IntArray(w * h) { i -> val x = i % w; val y = i / w
            if (x in w / 4 until w * 3 / 4 && y in h / 4 until h * 3 / 4) 0xff2233cc.toInt() else 0xffffffff.toInt() }
        for (edge in listOf(1023, 1024)) {
            ColorDrawingPipeline.trace(edge, 16, stripes(edge, 16), 99.9, 2)
            ColorDrawingPipeline.trace(16, edge, stripes(16, edge), 99.9, 2)
        }
        assertFailsWith<IllegalArgumentException> { ColorDrawingPipeline.trace(1025, 16, stripes(1025, 16), 99.9, 2) }
        assertFailsWith<IllegalArgumentException> { ColorDrawingPipeline.trace(16, 1025, stripes(16, 1025), 99.9, 2) }
    }

    @Test fun borderBackgroundPartialAlphaAndCropEdgesHaveExplicitContracts() {
        val pixels=IntArray(32*32) { p -> if(p%32 in 4..27 && p/32 in 4..27) 0xffff0000.toInt() else 0xffffffff.toInt() }
        val opaque=ColorDrawingPipeline.trace(32,32,pixels,30.0,2)
        assertEquals(1,opaque.layers.size)
        assertEquals(0,opaque.previewPixels()[0])
        val edge=IntArray(32*32) { p -> if(p%32<16) 0x80ff0000.toInt() else 0 }
        val transparent=ColorDrawingPipeline.trace(32,32,edge,30.0,2)
        assertTrue(transparent.layers.isNotEmpty())
        assertEquals(0,transparent.previewPixels()[0])
        assertTrue(transparent.palette.single() and 0xffffff == 0xff0000)
        assertFailsWith<IllegalArgumentException> { ColorDrawingPipeline.trace(32,32,pixels,30.0,7) }
    }
    @Test fun originalEmojiMapsToBrotherThreadsAndCancelsBetweenStages() {
        val image=ImageIO.read(javaClass.getResourceAsStream("/image-to-design/face.png"))
        val pixels=image.getRGB(0,0,image.width,image.height,null,0,image.width)
        val preview=ColorDrawingPipeline.trace(image.width,image.height,pixels,30.0,4)
        // User-approved batch assignment replaces independent red-mean matching.
        val labels=preview.indices()
        val expected=io.github.linker2k.myembro.engine.color.ThreadAssignment.assign(
            preview.palette.mapIndexedNotNull { i,rgb ->
                labels.count { it==i }.takeIf { it>0 }?.let { io.github.linker2k.myembro.engine.color.DesignColor(rgb,it.toDouble()) }
            })
        preview.layers.forEach { layer -> assertEquals(expected.threadFor(preview.palette[layer.paletteIndex]),layer.thread) }
        assertEquals(preview.layers.size,preview.layers.map { it.thread.color }.toSet().size)
        assertFailsWith<InterruptedException> { ColorDrawingPipeline.generate(preview) { throw InterruptedException() } }
        val pes=PesWriter.write(ColorDrawingPipeline.generate(preview).pattern)
        assertFalse(SewoutCheck.checkPes(pes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED)).hasBlockingIssues)
    }
    @Test fun borderWhiteBackgroundDoesNotConsumeDrawingColorBudgetEvenWithDirtyTopLeft() {
        val source=ImageIO.read(javaClass.getResourceAsStream("/image-to-design/face.png"))
        val image=java.awt.image.BufferedImage(128,128,java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g=image.createGraphics();g.color=java.awt.Color.WHITE;g.fillRect(0,0,128,128);g.drawImage(source,0,0,null);g.dispose()
        image.setRGB(0,0,0xff252525.toInt())
        val preview=ColorDrawingPipeline.trace(128,128,image.getRGB(0,0,128,128,null,0,128),30.0,3)
        val result=ColorDrawingPipeline.generate(preview)
        assertEquals(setOf("23","12","5"),result.pattern.threads.map { it.catalogNumber }.toSet())
        assertTrue(preview.layers.none { it.thread.catalogNumber=="29" })
    }

}
