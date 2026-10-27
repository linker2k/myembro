// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.ui.editor.EditorAgentExecutor
import io.github.linker2k.myembro.ui.editor.EditorSession
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class RelayBlockToolsTest {
    private val shape=ShapeObject.fromSvg("heart","heart",File("../engine/src/test/resources/emoji/fluent/red_heart_flat.svg").readText(),30.0)
    private val base=Project(objects=listOf(shape))
    private val session=EditorSession(base)
    private val executor=EditorAgentExecutor({session},{EmbroiderySettings(machineModel="se400")},{listOf(base.hoop)},publish={it()})
    private val doc=object : RelayDocument {
        override suspend fun read()=RelayProject(session.project,session.history.revision)
        override val editor=object : RelayEditor {
            override suspend fun query(tool:String,args:JsonObject)=executor.relayQuery(tool,args)
            override suspend fun edit(tool:String,args:JsonObject)=executor.relayEdit(tool,args){error("No confirmation for block edits")}
        }
    }
    private val tools=RelayToolbox({doc})
    private suspend fun call(name:String,args:String): Pair<Boolean,JsonObject> {
        val r=tools.call(name,Json.parseToJsonElement(args).jsonObject)
        val text=r.content.single().getValue("text").jsonPrimitive.content
        return r.isError to runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { buildJsonObject { put("message",text) } }
    }
    @Test fun inspectEditAndUndoUseRealEditorSessionAndReturnSewout()=runBlocking {
        val (error,before)=call("list_blocks","""{"objectId":"heart"}""")
        assertFalse(error);assertEquals(4,before.getValue("regions").jsonArray.size)
        assertTrue(before.toString().length<RelayProtocol.MAX_RESULT_CHARS)
        assertEquals(0,session.history.undoCount)
        val (failed,result)=call("set_block_stitch","""{"objectId":"heart","regionIds":["region-0"],"change":{"angle":135}}""")
        assertFalse(failed,result.toString());assertEquals("1",result.getValue("revision").jsonPrimitive.content)
        assertEquals("PASS",result.getValue("sewout").jsonObject.getValue("verdict").jsonPrimitive.content)
        assertEquals(1,session.history.undoCount)
        val (_,after)=call("list_blocks","""{"objectId":"heart"}""")
        val changed=after.getValue("regions").jsonArray.first().jsonObject.getValue("stitch").jsonObject
        assertEquals(135.0,changed.getValue("angle").jsonPrimitive.double)
        assertFalse(changed.getValue("automatic_angle").jsonPrimitive.boolean)
        assertTrue(session.stepHistory(redo=false));assertEquals(base,session.project)
    }
    @Test fun rejectionKeepsRevisionHistoryAndRedo()=runBlocking {
        for(change in listOf("{\"row_spacing_mm\":0.099}","{\"fill_method\":\"guided_fill\"}","{\"type\":\"WRONG\"}")) {
            val (error,result)=call("set_block_stitch","""{"objectId":"heart","regionIds":["region-0"],"change":$change}""")
            assertTrue(error,result.toString());assertEquals(base,session.project);assertEquals(0,session.history.undoCount)
        }
        assertTrue(call("set_block_stitch","""{"objectId":"missing","regionIds":["region-0"],"change":{}}""").first)
        assertTrue(call("list_blocks","""{"objectId":"missing"}""").first)
        assertEquals(0L,session.history.revision)
    }
    @Test fun noopDoesNotAddUndoAndViewerCannotEdit()=runBlocking {
        val (error,result)=call("set_block_stitch","""{"objectId":"heart","regionIds":["region-0"],"change":{}}""")
        assertFalse(error,result.toString());assertFalse(result.getValue("changed").jsonPrimitive.boolean)
        assertEquals(0,session.history.undoCount)
        val viewer=RelayToolbox({object : RelayDocument {
            override suspend fun read()=RelayProject(base,0)
            override val editor:RelayEditor?=null
        }})
        val list=viewer.call("list_blocks",buildJsonObject {put("objectId","heart")});assertFalse(list.isError)
        assertTrue(viewer.call("set_block_stitch",Json.parseToJsonElement("""{"objectId":"heart","regionIds":["region-0"],"change":{}}""").jsonObject).isError)
        assertTrue(call("set_block_stitch","""{"objectId":"heart","regionIds":[],"change":{}}""").first)
    }
    @Test fun inspectionPublishesPublicCapabilityDecisionsAndGradientIsUnavailable()=runBlocking {
        val (error,result)=call("list_blocks", """{"objectId":"heart"}""")
        assertFalse(error)
        for(row in result.getValue("regions").jsonArray) {
            val r=row.jsonObject
            val region=shape.regions.single { it.id==r.getValue("id").jsonPrimitive.content }
            val caps=BlockStitchCapabilities.forRegion(region,shape.transform)
            val published=r.getValue("capabilities").jsonObject
            for((name,value) in mapOf("SATIN" to caps.satin,"RUNNING" to caps.running,"FILL" to caps.fill)) {
                val option=published.getValue(name).jsonObject
                assertEquals(value.possible,option.getValue("possible").jsonPrimitive.boolean)
                assertEquals(value.reason,option["reason"]?.jsonPrimitive?.content)
                assertEquals(value.maxStitchLengthMm,option["maxStitchLengthMm"]?.jsonPrimitive?.double)
            }
            assertFalse(JsonPrimitive("linear_gradient_fill") in published.getValue("fillMethods").jsonArray)
        }
        assertFalse(tools.definitions.single { it.getValue("name").jsonPrimitive.content=="set_block_stitch" }
            .toString().contains("linear_gradient_fill"))
        val (blocked,failure)=call("set_block_stitch", """{"objectId":"heart","regionIds":["region-0"],"change":{"fill_method":"linear_gradient_fill"}}""")
        assertTrue(blocked)
        assertEquals("INVALID_BLOCK_SETTINGS",failure.getValue("code").jsonPrimitive.content)
        assertContains(failure.getValue("message").jsonPrimitive.content,"LINEAR_GRADIENT_REQUIRES_COLOR_STOPS")
        assertEquals(base,session.project);assertEquals(0,session.history.undoCount)
    }

    @Test fun blockOrderUsesOneCheckedEditorTurnAndInspectionShowsSewingOrder()=runBlocking {
        val originalNeedles=shape.regionOrder.associateWith { shape.regionPattern(it).stitches.filter { s -> s.command==io.github.linker2k.myembro.engine.model.Command.STITCH } }
        val (error,result)=call("set_block_order", """{"objectId":"heart","regionIds":["region-0"],"move":"LAST"}""")
        assertFalse(error,result.toString())
        assertEquals("1",result.getValue("revision").jsonPrimitive.content)
        assertEquals("PASS",result.getValue("sewout").jsonObject.getValue("verdict").jsonPrimitive.content)
        assertEquals(1,session.history.undoCount)
        val next=session.project.find("heart") as ShapeObject
        assertEquals(listOf("region-1","region-2","region-3","region-0"),next.regionOrder)
        assertEquals(next.regionOrder.flatMap { originalNeedles.getValue(it) },next.localPattern().stitches.filter { it.command==io.github.linker2k.myembro.engine.model.Command.STITCH })
        val (_,blocks)=call("list_blocks", """{"objectId":"heart"}""")
        val rows=blocks.getValue("regions").jsonArray.map { it.jsonObject }
        assertEquals(next.regionOrder,rows.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf(0,1,2,3),rows.map { it.getValue("order").jsonPrimitive.int })
        val revision=session.history.revision
        val same=call("set_block_order", """{"objectId":"heart","regionIds":["region-0"],"move":"LAST"}""")
        assertFalse(same.first);assertEquals(revision,session.history.revision)
        for(args in listOf(
            """{"objectId":"heart","regionIds":["missing"],"move":"FIRST"}""",
            """{"objectId":"heart","regionIds":["region-0"],"move":"INVALID"}""")) {
            assertTrue(call("set_block_order",args).first)
            assertEquals(revision,session.history.revision);assertEquals(next,session.project.find("heart"))
        }
        assertTrue(session.stepHistory(redo=false));assertEquals(base,session.project)
    }

    @Test fun generationErrorsExposeKindAndRetainRevisionWithoutFakeSewoutVerdict()=runBlocking {
        for((change,kind) in listOf(
            """{"fill_method":"guided_fill"}""" to "UNSUPPORTED_FILL_BACKEND",
            """{"fill_method":"meander_fill","meander_scale_percent":[100000]}""" to "MEANDER_GRAPH")) {
            val (error,result)=call("set_block_stitch", """{"objectId":"heart","regionIds":["region-0"],"change":$change}""")
            assertTrue(error,result.toString())
            assertEquals("GENERATION_FAILED",result.getValue("code").jsonPrimitive.content)
            assertEquals(kind,result.getValue("kind").jsonPrimitive.content)
            assertEquals("0",result.getValue("revision").jsonPrimitive.content)
            assertNull(result["sewout"]);assertEquals(base,session.project);assertEquals(0,session.history.undoCount)
        }
    }

}
