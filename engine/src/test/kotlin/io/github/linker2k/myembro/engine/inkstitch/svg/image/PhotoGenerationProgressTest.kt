// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.io.PesWriter
import javax.imageio.ImageIO
import kotlin.test.*

class PhotoGenerationProgressTest {
    @Test fun countersComeFromExistingRegionsAndDoNotChangePes() {
        val image=ImageIO.read(javaClass.getResourceAsStream("/image-to-design/face.png"))
        val pixels=image.getRGB(0,0,image.width,image.height,null,0,image.width)
        val preview=ColorDrawingPipeline.trace(image.width,image.height,pixels,30.0,3)
        val events=mutableListOf<String>()
        val result=ColorDrawingPipeline.generate(preview) { events+=it }
        val counts=events.filter { it.startsWith("regions=") }.map { it.substringAfter('=').substringBefore(';').split('/').map(String::toInt) }
        assertEquals(0,counts.first()[0])
        assertEquals(result.regions.size,counts.last()[1])
        assertEquals((0..result.regions.size).toList(),counts.map { it[0] })
        assertContentEquals(PesWriter.write(result.pattern),PesWriter.write(ColorDrawingPipeline.generate(preview).pattern))
        val thread=EmbThread(0xffb5492a.toInt())
        val outline=RedworkPipeline.trace(image.width,image.height,pixels.map { it and 0xffffff }.toIntArray(),30.0,0,thread)
        events.clear()
        val redwork=RedworkPipeline.generate(outline,thread,photoMode=true) { events+=it }
        val last=events.last { it.startsWith("regions=") }.substringAfter('=').substringBefore(';').split('/')
        assertEquals(last[0],last[1])
        assertTrue(events.contains("stitches_ready=${redwork.pattern.stitchCount}"))
        assertContentEquals(redwork.copyPesBytes(),RedworkPipeline.generate(outline,thread,photoMode=true).copyPesBytes())
    }
}
