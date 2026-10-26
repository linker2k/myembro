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
import kotlinx.serialization.json.*

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
        val unowned=ColorDrawingPipeline.generate(ColorDrawingPipeline.trace(edge,edge,photoToolRaster(raster,"color").pixels,width,colors),HoopSpec.H100,false)
        assertPatternEquals(unowned.pattern,legacy.pattern)
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
            assertEquals(obj.regionOrder,shape.regionOrder)
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
    @Test fun photoRegionsSupportOverridesOrderAndPersistenceWithoutReroutingOtherRegions() {
        val image=ImageIO.read(ByteArrayInputStream(png(3)))
        val store=AttachmentStore { _,req -> PhotoRaster(req.width,req.height,
            IntArray(req.width*req.height) { p -> image.getRGB((p%req.width)*64/req.width,(p/req.width)*64/req.height) }) }
        val original=assertIs<ShapeObject>(PhotoPreparation(store).prepare("synthetic","color",3,50.0,HoopSpec.H100).objectFor("photo","Photo"))
        assertEquals(2,original.regions.size)
        val originalPattern=original.localPattern()
        val needle=io.github.linker2k.myembro.engine.model.Command.STITCH
        assertEquals(originalPattern.stitches.filter { it.command==needle },
            original.regionOrder.flatMap { original.regionPattern(it).stitches.filter { s -> s.command==needle } })
        val p=Project(objects=listOf(original))
        val target=original.regionOrder.first();val other=original.regionOrder.last()
        val changes=listOf(
            BlockStitchPatch(angle=135.0),
            BlockStitchPatch(row_spacing_mm=0.4),
            BlockStitchPatch(fill_method="contour_fill",row_spacing_mm=0.4))
        val candidates=mutableListOf(p)
        for(patch in changes) {
            val applied=assertIs<io.github.linker2k.myembro.engine.edit.commands.CommandResult.Applied>(
                io.github.linker2k.myembro.engine.edit.commands.apply(p,
                    io.github.linker2k.myembro.engine.edit.commands.ProjectCommand.SetBlockStitch("photo",setOf(target),patch)))
            assertTrue(applied.changed)
            val obj=assertIs<ShapeObject>(applied.project.find("photo"))
            assertNotEquals(original.regionPattern(target).stitches,obj.regionPattern(target).stitches)
            assertEquals(original.regionPattern(other).stitches,obj.regionPattern(other).stitches)
            assertSafeRoundTrip(applied.project)
            val reset=obj.withOverrides(emptyMap())
            assertPatternEquals(originalPattern,reset.localPattern())
            candidates+=applied.project
        }
        for(candidate in candidates) {
            val before=candidate.find("photo") as ShapeObject
            val moved=assertIs<io.github.linker2k.myembro.engine.edit.commands.CommandResult.Applied>(
                io.github.linker2k.myembro.engine.edit.commands.apply(candidate,
                    io.github.linker2k.myembro.engine.edit.commands.ProjectCommand.SetBlockOrder("photo",setOf(target),
                        BlockOrderMove.LAST)))
            val obj=assertIs<ShapeObject>(moved.project.find("photo"))
            assertEquals(before.regionOrder.reversed(),obj.regionOrder)
            for(id in before.regionOrder) assertPatternEquals(before.regionPattern(id),obj.regionPattern(id))
            assertEquals(obj.regionOrder.flatMap { before.regionPattern(it).stitches.filter { s -> s.command==needle } },
                obj.localPattern().stitches.filter { it.command==needle })
            assertSafeRoundTrip(moved.project)
            val renamed=ShapeObjectFactory.renamed(obj,"renamed","Renamed")
            assertPatternEquals(obj.localPattern(),renamed.localPattern())
            println("PHOTO_REGION_EDIT override=${obj.overrides.values.singleOrNull()?.fill?.fill_method ?: "none"} order=${obj.regionOrder} sewout=PASS other_region=IDENTICAL codec_PES=IDENTICAL")
        }
        // Old photo payloads remain viewable; missing ownership is never guessed from needles.
        val encoded=ProjectJson.encode(p)
        val root=kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject
        val objects=root.getValue("objects").jsonArray
        val oldObject=objects.single().jsonObject
        val photo=oldObject.getValue("photo").jsonObject
        val oldPhoto=kotlinx.serialization.json.JsonObject(photo.filterKeys { it!="fragments" } + ("version" to kotlinx.serialization.json.JsonPrimitive(1)))
        val legacy=kotlinx.serialization.json.JsonObject(root + ("objects" to kotlinx.serialization.json.JsonArray(listOf(
            kotlinx.serialization.json.JsonObject(oldObject + ("photo" to oldPhoto))))))
        val restored=ProjectJson.decode(legacy.toString()).objects.single() as ShapeObject
        assertPatternEquals(originalPattern,restored.localPattern())
        assertFailsWith<IllegalArgumentException> { restored.withRegionOrder(restored.regionOrder.reversed()) }
    }
    private fun assertPatternEquals(expected:io.github.linker2k.myembro.engine.model.EmbPattern,actual:io.github.linker2k.myembro.engine.model.EmbPattern) {
        assertEquals(expected.stitches,actual.stitches)
        assertEquals(expected.threads,actual.threads)
        assertEquals(expected.metadata,actual.metadata)
        assertEquals(expected.warnings,actual.warnings)
    }
    private fun assertSafeRoundTrip(project:Project) {
        val bytes=PesWriter.writeV1(project.flatten())
        val check=io.github.linker2k.myembro.engine.sewout.SewoutCheck.checkPes(bytes,
            options=io.github.linker2k.myembro.engine.sewout.SewoutOptions(hoop=project.hoop,
                origin=io.github.linker2k.myembro.engine.sewout.DesignOrigin.APP_GENERATED))
        assertEquals(io.github.linker2k.myembro.engine.sewout.SewoutVerdict.PASS,check.verdict,check.findings.toString())
        assertTrue(io.github.linker2k.myembro.engine.sewout.PecViewportCheck.check(bytes,project.hoop).valid)
        val restored=ProjectJson.decode(ProjectJson.encode(project))
        assertEquals(project.flatten().stitches,restored.flatten().stitches)
        assertContentEquals(bytes,PesWriter.writeV1(restored.flatten()))
    }
    @Test fun twoColorPngWithHolePreservesExactStitches() = parity(2)
    @Test fun threeColorPngPreservesExactStitches() = parity(3)
}
