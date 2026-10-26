// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.io.PesWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class PhotoShapeParityTest {
    /** Synthetic flat 2/3-color PNG, including white; 2-color case has a real hole. */
    private fun png(colors: Int): ByteArray {
        val im=BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
        for(y in 0..63) for(x in 0..63) {
            val rgb=if(colors==2) {
                if(x in 8..55 && y in 8..55 && !(x in 24..39 && y in 24..39)) 0x224488 else 0xffffff
            } else when {
                x in 4..27 && y in 8..55 -> 0x224488
                x in 36..59 && y in 8..55 -> 0xcc4422
                else -> 0xffffff
            }
            im.setRGB(x,y,rgb)
        }
        return ByteArrayOutputStream().also { assertTrue(ImageIO.write(im,"png",it)) }.toByteArray()
    }
    private fun parity(colors: Int) {
        val bytes=png(colors);val image=ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(colors,image.getRGB(0,0,64,64,null,0,64).toSet().size)
        val store=AttachmentStore { id,req ->
            if(id!="synthetic") null else {
                val pixels=IntArray(req.width*req.height) { p -> image.getRGB((p%req.width)*64/req.width,(p/req.width)*64/req.height) }
                PhotoRaster(req.width,req.height,pixels)
            }
        }
        val width=50.0;val edge=PhotoPreparationPolicy.photoRasterSize(1,width,width)
        val raster=store.raster("synthetic",PhotoRasterRequest(edge,edge,HoopSpec.H100,false))!!
        val legacy=ColorDrawingPipeline.generate(ColorDrawingPipeline.trace(edge,edge,photoToolRaster(raster,"color").pixels,width,colors),HoopSpec.H100)
        val direct=assertNotNull(ShapeObjectFactory.fromPhoto("direct","Photo",legacy,width))
        val prepared=PhotoPreparation(store).prepare("synthetic","color",colors,width,HoopSpec.H100)
        val actual=assertIs<ShapeObject>(prepared.objectFor("picture","AI PNG"))
        assertTrue(ShapeObjectFactory.isPhoto(actual))
        assertEquals(legacy.pattern.stitches,direct.localPattern().stitches)
        assertEquals(legacy.pattern.stitches,actual.localPattern().stitches)
        assertEquals(legacy.pattern.threads,actual.localPattern().threads)
        assertEquals(legacy.pattern.metadata,actual.localPattern().metadata)
        assertEquals(legacy.pattern.warnings,actual.localPattern().warnings)
        assertEquals(legacy.regions.filter { !it.excluded && it.groups>0 }.map { it.paths },actual.regions.map { it.paths })
        if(colors==2) assertTrue(actual.regions.any { it.paths.size>1 },"POTRACE hole must survive")
        val old=Project(objects=listOf(StitchObject("picture","AI PNG",legacy.pattern)))
        val current=Project(objects=listOf(actual))
        assertEquals(old.flatten().stitches,current.flatten().stitches)
        assertContentEquals(PesWriter.writeV1(old.flatten()),PesWriter.writeV1(current.flatten()))
        for(obj in listOf(actual,actual.withTransform(actual.transform.copy(x=actual.transform.x+17,y=actual.transform.y-23,rotationDeg=90.0)).withVisible(false))) {
            val p=Project(objects=listOf(obj));val restored=ProjectJson.decode(ProjectJson.encode(p))
            val shape=assertIs<ShapeObject>(restored.objects.single())
            assertTrue(ShapeObjectFactory.isPhoto(shape));assertEquals(obj.sourceArt,shape.sourceArt)
            assertEquals(obj.transform,shape.transform);assertEquals(obj.visible,shape.visible)
            assertEquals(obj.localPattern().stitches,shape.localPattern().stitches)
            assertEquals(obj.localPattern().threads,shape.localPattern().threads)
            assertEquals(obj.localPattern().metadata,shape.localPattern().metadata)
            assertEquals(obj.localPattern().warnings,shape.localPattern().warnings)
            assertEquals(obj.regions.map { it.paths },shape.regions.map { it.paths })
            assertEquals(p.flatten().stitches,restored.flatten().stitches)
        }
        assertContentEquals(PesWriter.writeV1(current.flatten()),PesWriter.writeV1(ProjectJson.decode(ProjectJson.encode(current)).flatten()))
        println("PHOTO_SHAPE_PARITY colors=$colors shape=true stitches=${legacy.pattern.stitchCount} coordinates=IDENTICAL pes=IDENTICAL codec=IDENTICAL regions=${actual.regions.size}")
        assertIs<StitchObject>(PhotoPreparation(store).prepare("synthetic","outline",2,width,HoopSpec.H100).objectFor("outline","Outline"))
    }
    @Test fun twoColorPngWithHolePreservesExactStitches() = parity(2)
    @Test fun threeColorPngPreservesExactStitches() = parity(3)
}
