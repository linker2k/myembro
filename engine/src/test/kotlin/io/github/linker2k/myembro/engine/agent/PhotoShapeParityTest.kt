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
    @Test fun thinPhotoRegionsDefaultToFillAndAllowTypeOverride() {
        // Synthetic three-color PNG: a sub-mm line, a satin band and a wide fill block.
        val im=BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)
        for(y in 0..63) for(x in 0..63) im.setRGB(x,y,when {
            y in 8..55 && x==8 -> 0x224488
            y in 8..55 && x in 20..22 -> 0xcc4422
            y in 8..55 && x in 36..49 -> 0x224488
            else -> 0xffffff
        })
        val png=ByteArrayOutputStream().also { assertTrue(ImageIO.write(im,"png",it)) }.toByteArray()
        val image=ImageIO.read(ByteArrayInputStream(png))
        val edge=PhotoPreparationPolicy.photoRasterSize(1,50.0,50.0)
        val pixels=IntArray(edge*edge) { p -> image.getRGB(p%edge*64/edge,p/edge*64/edge) }
        fun generate(capture:Boolean)=ColorDrawingPipeline.generate(
            ColorDrawingPipeline.trace(edge,edge,pixels,50.0,3),HoopSpec.H100,capture)
        val source=generate(true)
        assertPatternEquals(generate(false).pattern,source.pattern)
        assertNull(ShapeObjectFactory.photoUnsupportedReason(source))
        val empty=source.copy(regions=emptyList())
        assertEquals("NO_SEWN_REGIONS",ShapeObjectFactory.photoUnsupportedReason(empty))
        assertNull(ShapeObjectFactory.fromPhoto("empty","Empty",empty,50.0))
        val missing=source.copy(regions=source.regions.map { it.copy(paths=emptyList()) })
        assertEquals("MISSING_REGION_PATHS",ShapeObjectFactory.photoUnsupportedReason(missing))
        assertNull(ShapeObjectFactory.fromPhoto("missing","Missing",missing,50.0))
        val obj=assertNotNull(ShapeObjectFactory.fromPhoto("thin","Thin photo",source,50.0))
        val expected=source.regions.filter { !it.excluded && it.groups>0 }.map { when(it.method) {
            io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule.Type.TATAMI -> BlockStitch.Type.FILL
            io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule.Type.SATIN -> BlockStitch.Type.SATIN
            io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule.Type.RUNNING -> BlockStitch.Type.RUNNING
        } }
        println("THIN_PHOTO types=$expected")
        assertFalse(expected.contains(BlockStitch.Type.SATIN),"Photo defaults must not auto-convert polygons to constant-width satin")
        assertTrue(expected.contains(BlockStitch.Type.FILL))
        assertEquals(expected,obj.regions.map { it.stitch.type })
        assertPatternEquals(source.pattern,obj.localPattern())
        val original=Project(objects=listOf(obj))
        assertContentEquals(PesWriter.writeV1(Project(objects=listOf(StitchObject("thin","Thin photo",source.pattern))).flatten()),
            PesWriter.writeV1(original.flatten()))
        val restored=ProjectJson.decode(ProjectJson.encode(original)).objects.single() as ShapeObject
        assertEquals(obj.regions.map { it.defaultStitch },restored.regions.map { it.defaultStitch })
        assertSafeRoundTrip(original)
        val sewn=source.regions.filter { !it.excluded && it.groups>0 }
        val target=obj.regions[sewn.indexOfFirst { it.widths.maxMm in 1.0..6.0 }]
        val applied=assertIs<io.github.linker2k.myembro.engine.edit.commands.CommandResult.Applied>(
            io.github.linker2k.myembro.engine.edit.commands.apply(original,
                io.github.linker2k.myembro.engine.edit.commands.ProjectCommand.SetBlockStitch("thin",setOf(target.id),
                    BlockStitchPatch(type=BlockStitch.Type.RUNNING))))
        val satin=assertIs<io.github.linker2k.myembro.engine.edit.commands.CommandResult.Applied>(
            io.github.linker2k.myembro.engine.edit.commands.apply(original,
                io.github.linker2k.myembro.engine.edit.commands.ProjectCommand.SetBlockStitch("thin",setOf(target.id),
                    BlockStitchPatch(type=BlockStitch.Type.SATIN))))
        assertSafeRoundTrip(satin.project)
        val changed=applied.project.objects.single() as ShapeObject
        assertEquals(BlockStitch.Type.RUNNING,changed.regions.first { it.id==target.id }.stitch.type)
        assertSafeRoundTrip(applied.project)
        assertPatternEquals(source.pattern,changed.withOverrides(emptyMap()).localPattern())
        val reloaded=ProjectJson.decode(ProjectJson.encode(applied.project)).objects.single() as ShapeObject
        assertPatternEquals(source.pattern,reloaded.withOverrides(emptyMap()).localPattern())
        println("THIN_PHOTO shape=true native_PES=IDENTICAL codec=IDENTICAL override=RUNNING sewout=PASS")
    }
    /** Saved, reopened and then edited must equal the same edit on the never-saved project. */
    @Test fun reopenedPhotoProjectKeepsBlocksAndEditsLikeTheUnsavedOne() {
        val image=ImageIO.read(ByteArrayInputStream(png(3)))
        val store=AttachmentStore { _,req -> PhotoRaster(req.width,req.height,
            IntArray(req.width*req.height) { p -> image.getRGB((p%req.width)*64/req.width,(p/req.width)*64/req.height) }) }
        val original=assertIs<ShapeObject>(PhotoPreparation(store).prepare("synthetic","color",3,50.0,HoopSpec.H100).objectFor("photo","Photo"))
        val target=original.regionOrder.first();val other=original.regionOrder.last()
        fun applied(p: Project,c: io.github.linker2k.myembro.engine.edit.commands.ProjectCommand)=assertIs<io.github.linker2k.myembro.engine.edit.commands.CommandResult.Applied>(io.github.linker2k.myembro.engine.edit.commands.apply(p,c)).project
        val edited=applied(applied(Project(objects=listOf(original)),
            io.github.linker2k.myembro.engine.edit.commands.ProjectCommand.SetBlockStitch("photo",setOf(target),BlockStitchPatch(angle=135.0))),
            io.github.linker2k.myembro.engine.edit.commands.ProjectCommand.SetBlockOrder("photo",setOf(target),BlockOrderMove.LAST))
        val reopened=ProjectJson.decode(ProjectJson.encode(edited))
        val before=edited.find("photo") as ShapeObject
        val shape=assertIs<ShapeObject>(reopened.find("photo"))
        assertTrue(ShapeObjectFactory.isPhoto(shape))
        assertEquals(before.overrides,shape.overrides);assertEquals(before.regionOrder,shape.regionOrder)
        assertEquals(before.regions.map { Triple(it.id,it.stitch,it.defaultStitch) },shape.regions.map { Triple(it.id,it.stitch,it.defaultStitch) })
        val next=io.github.linker2k.myembro.engine.edit.commands.ProjectCommand.SetBlockStitch("photo",setOf(other),BlockStitchPatch(row_spacing_mm=0.4))
        val live=applied(edited,next);val again=applied(reopened,next)
        assertEquals(live.flatten().stitches,again.flatten().stitches)
        assertContentEquals(PesWriter.writeV1(live.flatten()),PesWriter.writeV1(again.flatten()))
        assertSafeRoundTrip(again)
        println("PHOTO_REOPEN_EDIT overrides=${shape.overrides.size} order=${shape.regionOrder} next_edit_PES=IDENTICAL sewout=PASS")
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
