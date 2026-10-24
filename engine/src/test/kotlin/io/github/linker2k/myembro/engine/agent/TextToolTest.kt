// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.lettering.*
import kotlinx.serialization.json.*
import kotlin.test.*

class TextToolTest {
    private val fonts=BundledFontResolver()
    private val tools=ToolDispatcher(fontResolver=fonts)
    private val ink="inkstitch-medium-font"
    private fun args(fontId:String=ink,text:String="A",height:Double=15.0,id:String="text")=buildJsonObject {
        put("transactionId","t");put("objectId",id);put("fontId",fontId);put("fontRevision",fonts.revision(fontId) ?: "missing")
        put("text",text);put("heightMm",height);put("threadColor","#B5492A");put("singleLineBold",false)
    }
    private val export=Json.parseToJsonElement("""{"machineId":"se700","frameId":"H100","pesVersion":"V6"}""").jsonObject
    @Test fun inkAndHersheyAddTextProduceCheckedPesAndAtomicUndo() {
        for(id in listOf(ink,HersheyFont.DEFAULT_ID)) {
            val session=EditSession(Project(HoopSpec.H100));val controller=tools.bind(session)
            val outcome=controller.executeScript("t",0,listOf(ToolTurnController.Call("add_text",args(id))),export)
            assertTrue(outcome.ok,"$id: ${outcome.output}");assertNotNull(outcome.pesBytes)
            assertEquals(1,session.project.objects.size);assertTrue(session.project.objects[0] is TextObject)
            assertTrue(controller.dispatch("undo",Json.parseToJsonElement("""{"expectedRevision":"1"}""")).ok)
            assertTrue(session.project.objects.isEmpty())
            println("TEXT_PES $id PASS: bytes=${outcome.pesBytes!!.size}, one undo")
        }
    }
    @Test fun unsupportedMissingRevisionAndSchemaRejectWithoutChanges() {
        val base=Project()
        fun reject(a:JsonObject,code:String) {
            val result=tools.dispatch(base,"0","add_text",a)
            assertEquals(code,result.code,result.output.toString());assertSame(base,result.project);assertNull(result.pesBytes)
        }
        reject(args(HersheyFont.DEFAULT_ID,"뷁"),"UNSUPPORTED_CHARACTERS")
        reject(args(ink,"뷁"),"UNSUPPORTED_CHARACTERS")
        reject(args("does-not-exist"),"FONT_NOT_FOUND")
        reject(JsonObject(args()+("fontRevision" to JsonPrimitive("stale"))),"FONT_REVISION_MISMATCH")
        reject(args(text="  \n "),"EMPTY_TEXT")
        reject(JsonObject(args()+("unknown" to JsonPrimitive(true))),"INVALID_ARGUMENTS")
        reject(args(height=0.0),"HEIGHT_OUT_OF_RANGE")
    }
    @Test fun policyHeightFourBoundaryCases() {
        val f=fonts.resolve(ink)!!;val range=fonts.inkRange(ink,f)!!
        val max=minOf(range.endInclusive,LetteringHeightLimits.MAX_MM)
        for((h,ok) in listOf(range.start-0.1 to false,range.start to true,max to true,max+0.1 to false)) {
            val r=tools.dispatch(Project(),"0","add_text",args(height=h))
            assertEquals(ok,r.ok,"height=$h ${r.output}")
            if(!ok) assertTrue(r.code in setOf("HEIGHT_OUT_OF_RANGE","INVALID_ARGUMENTS"))
            println("TEXT_HEIGHT $h ${if(r.ok) "PASS" else "REJECT"}")
        }
        // Hershey has no evidenced minimum in the existing running baseline: do not invent one.
        assertTrue(tools.dispatch(Project(),"0","add_text",args(HersheyFont.DEFAULT_ID,height=LetteringHeightLimits.MAX_MM)).ok)
        assertFalse(tools.dispatch(Project(),"0","add_text",args(HersheyFont.DEFAULT_ID,height=LetteringHeightLimits.MAX_MM+0.1)).ok)
    }
    @Test fun replacePreservesPositionOrderIdentityAndVisibility() {
        val first=tools.dispatch(Project(),"0","add_text",args()).project.objects.single() as TextObject
        val placed=first.copy(transform=Transform(37.0,-24.0,rotationDeg=22.0),visible=false)
        val project=Project(objects=listOf(placed.withId("before"),placed,placed.withId("after")))
        val result=tools.dispatch(project,"0","replace_text",args(text="B"))
        assertTrue(result.ok,result.output.toString());assertTrue(result.preparedCommand is ProjectCommand.ReplaceText)
        assertEquals(project.objects.map { it.id },result.project.objects.map { it.id })
        val next=result.project.find("text") as TextObject
        assertEquals(placed.transform,next.transform);assertEquals(placed.visible,next.visible);assertEquals("B",next.text)
        assertFalse(tools.dispatch(Project(),"0","replace_text",args()).ok)
        assertEquals("DUPLICATE_ID",tools.dispatch(project,"0","add_text",args()).code)
    }
    @Test fun snapshotResolverSlotAndResolvedInkFontDoNotNeedGlobalRegistry() {
        val source=fonts.resolve(ink) as ResolvedTextFont.InkStitch
        val obj=prepareText(TextPreparationRequest("A","private-font",15.0,1.0,LetteringAlignment.CENTER,0.0,
            io.github.linker2k.myembro.engine.model.EmbThread(0x112233)),source).textObject
        assertTrue(obj.localPattern().stitches.isNotEmpty());assertSame(source.font,obj.resolvedInkFont)
        val adapter=object:FontResolver {
            override fun resolve(fontId:String)=if(fontId=="snapshot") ResolvedTextFont.OutlineSnapshot(1000.0,
                mapOf("A" to PreparedGlyph(700.0,listOf(listOf(0.0 to 0.0,500.0 to -1000.0,1000.0 to 0.0)))),setOf(65)) else null
            override fun revision(fontId:String)="fixture-revision"
        }
        val resolver=BundledFontResolver(adapter)
        assertNotNull(resolver.resolve("snapshot"));assertEquals("fixture-revision",resolver.revision("snapshot"))
        val input=JsonObject(args("snapshot")+("fontRevision" to JsonPrimitive("fixture-revision")))
        val prepared=ToolDispatcher(fontResolver=resolver).dispatch(Project(),"0","add_text",input)
        assertTrue(prepared.ok,prepared.output.toString())
        assertEquals(StitchStyle.RUNNING,(prepared.project.objects.single() as TextObject).stitchStyle)
    }
}
