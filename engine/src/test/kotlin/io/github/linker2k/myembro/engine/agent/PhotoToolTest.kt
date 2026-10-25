// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.EditSession
import io.github.linker2k.myembro.engine.geometry.PICTURE_MIN_WIDTH_MM
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoPreparationPolicy
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.*
import kotlin.test.*

class PhotoToolTest {
    private val source by lazy { ImageIO.read(File("../docs/images/launcher-icon-artwork.png")) }
    private var reads = 0
    private val attachments = AttachmentStore { id, request ->
        if (id != "app-picked-artwork") null else {
            reads++
            val image = BufferedImage(request.width,request.height,BufferedImage.TYPE_INT_ARGB)
            val graphics=image.createGraphics()
            if(request.compositeWhite) { graphics.color=Color.WHITE;graphics.fillRect(0,0,image.width,image.height) }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            // Source and default hoop are square: the existing centered zoom=1 crop is the full image.
            graphics.drawImage(source,0,0,image.width,image.height,null);graphics.dispose()
            PhotoRaster(image.width,image.height,image.getRGB(0,0,image.width,image.height,null,0,image.width))
        }
    }
    private fun args(text: String)=Json.parseToJsonElement(text).jsonObject
    private fun request(mode: String,width: Double=40.0,colors: Int=4,id: String="app-picked-artwork") =
        args("""{"attachmentId":"$id","mode":"$mode","colors":$colors,"widthMm":$width}""")
    private fun roundTrip(mode: String) {
        val kernel=ToolDispatcher(attachmentStore=attachments)
        val prepared=kernel.dispatch(Project(),"0","prepare_photo",request(mode))
        assertTrue(prepared.ok,prepared.output.toString())
        assertNull(prepared.pesBytes);assertEquals("READY",prepared.output.string("status"))
        assertEquals(setOf("ok","resultId","status","downloadRequired","widthMm","heightMm","minWidthMm","maxWidthMm","verdict"),prepared.output.keys)
        val added=kernel.dispatch(Project(),"0","add_generated_art",args("""{"transactionId":"t","resultId":"${prepared.output.string("resultId")}","objectId":"photo","name":"Artwork"}"""))
        assertTrue(added.ok,added.output.toString())
        assertNull(assertIs<StitchObject>(added.project.find("photo")).sourceBytes)
        val pes=kernel.dispatch(added.project,"0","prepare_pes",args("""{"projectRevision":"0","machineId":"se700","frameId":"H100","pesVersion":"V6"}"""))
        assertTrue(pes.ok,pes.output.toString());assertNotNull(pes.pesBytes)
        assertEquals("PASS",pes.output.getValue("checks").jsonObject.getValue("report").jsonObject.string("verdict"))
        println("PHOTO $mode prepare -> add -> prepare_pes PASS; bytes=${pes.pesBytes!!.size}")
    }
    @Test fun silhouette() = roundTrip("silhouette")
    @Test fun outline() = roundTrip("outline")
    @Test fun color() = roundTrip("color")
    @Test fun bw() = roundTrip("bw")

    @Test fun `existing size boundaries reject outside before reading and accept inside`() {
        val min=PICTURE_MIN_WIDTH_MM;val max=PhotoPreparationPolicy.photoMaxWidthMm(HoopSpec.H100)
        val kernel=ToolDispatcher(attachmentStore=attachments)
        for(width in listOf(min-0.1,max+0.1)) {
            assertEquals("WIDTH_OUT_OF_RANGE",kernel.dispatch(Project(),"0","prepare_photo",request("outline",width)).code)
        }
        assertEquals(0,reads)
        for(width in listOf(min,min+0.1,max-0.1,max)) {
            val result=kernel.dispatch(Project(),"0","prepare_photo",request("outline",width))
            assertTrue(result.ok,"$width: ${result.output}")
            assertEquals(min,result.output.getValue("minWidthMm").jsonPrimitive.double)
            assertEquals(max,result.output.getValue("maxWidthMm").jsonPrimitive.double)
        }
    }
    @Test fun `unknown attachments invalid colors and arbitrary image sources never resolve`() {
        val kernel=ToolDispatcher(attachmentStore=attachments);val project=Project()
        for(id in listOf("missing","../docs/images/launcher-icon-artwork.png","https://example.com/photo.png")) {
            val result=kernel.dispatch(project,"0","prepare_photo",request("color",id=id))
            assertEquals("ATTACHMENT_NOT_FOUND",result.code);assertSame(project,result.project)
            assertNull(result.pesBytes);assertNull(result.output["resultId"])
        }
        for(colors in listOf(1,7)) assertEquals("INVALID_ARGUMENTS",kernel.dispatch(project,"0","prepare_photo",request("color",colors=colors)).code)
        for(key in listOf("bytes","pixels","url","path")) {
            assertEquals("INVALID_ARGUMENTS",kernel.dispatch(project,"0","prepare_photo",JsonObject(request("color")+(key to JsonPrimitive("not-allowed")))).code)
        }
        assertEquals(0,reads)
    }
    @Test fun `preparation is available outside a turn and does not change session history`() {
        val session=EditSession();val before=session.snapshot()
        val result=ToolDispatcher(attachmentStore=attachments).bind(session).dispatch("prepare_photo",request("outline"))
        assertTrue(result.ok,result.output.toString());assertEquals(before,session.snapshot())
    }
}
