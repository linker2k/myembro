// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ToolTurnControllerTest {
    private fun json(s: String)=Json.parseToJsonElement(s).jsonObject
    private fun initial()=AgentScenario.builtin("myembro_logo.pes","logo","H100")
    private val export=json("""{"machineId":"se700","frameId":"H100","pesVersion":"V6"}""")
    private fun calls(id: String="t",badThird: Boolean=false)=listOf(
        ToolTurnController.Call("transform_objects",json("""{"transactionId":"$id","objectIds":["logo"],"deltaXMm":1,"deltaYMm":2,"scaleFactor":1,"rotationDeg":0}""")),
        ToolTurnController.Call("transform_objects",json("""{"transactionId":"$id","objectIds":["logo"],"deltaXMm":0,"deltaYMm":0,"scaleFactor":1,"rotationDeg":90}""")),
        if(badThird) ToolTurnController.Call("reorder_object",json("""{"transactionId":"$id","objectId":"logo","index":999}"""))
        else ToolTurnController.Call("recolor_object",json("""{"transactionId":"$id","objectId":"logo","threadIndex":0,"threadColor":"#112233"}"""))
    )
    @Test fun thirdFailurePublishesNothingAndPreservesRevisionAndRedo() {
        val base=initial();val engine=EditSession(base);engine.commitSnapshot(base.rotateBy("logo",1.0),0);engine.undo(1)
        val before=engine.snapshot();val tools=ToolDispatcher().bind(engine)
        val result=tools.executeScript("t",before.revision,calls(badThird=true),export)
        assertFalse(result.ok);assertNull(result.pesBytes);assertEquals(before,engine.snapshot());assertSame(base,engine.project)
        assertEquals(2,result.output.getValue("failedCallIndex").jsonPrimitive.int)
        assertEquals("INVALID_REORDER_INDEX",result.code)
        println("SCRIPT_THIRD_FAILURE PASS: original project/revision/redo retained")
    }
    @Test fun successfulThreeEditsCommitOnceAndOneUndoRestoresWholeScript() {
        val base=initial();val engine=EditSession(base);val tools=ToolDispatcher().bind(engine)
        val result=tools.executeScript("t",0,calls(),export)
        assertTrue(result.ok,result.output.toString());assertNotNull(result.pesBytes)
        assertEquals(1,engine.revision);assertEquals(1,engine.snapshot().undoCount)
        val t=engine.project.find("logo")!!.transform
        assertEquals(90.0,t.rotationDeg)
        // Existing group rotation uses the actual placed-bounds pivot, which may be half a native unit.
        val beforeBounds=base.objects.single().placedBounds()!!
        val afterBounds=engine.project.objects.single().placedBounds()!!
        assertEquals((beforeBounds.minX.toDouble()+beforeBounds.maxX)/2+10,(afterBounds.minX.toDouble()+afterBounds.maxX)/2,0.5)
        assertEquals((beforeBounds.minY.toDouble()+beforeBounds.maxY)/2+20,(afterBounds.minY.toDouble()+afterBounds.maxY)/2,0.5)
        assertEquals(0x112233,engine.project.find("logo")!!.localPattern().threads.first().color and 0xFFFFFF)
        assertTrue(tools.dispatch("undo",json("""{"expectedRevision":"1"}""")).ok)
        assertSame(base,engine.project);assertEquals(2,engine.revision);assertEquals(1,engine.snapshot().redoCount)
        assertTrue(tools.dispatch("redo",json("""{"expectedRevision":"2"}""")).ok);assertEquals(90.0,engine.project.find("logo")!!.transform.rotationDeg)
        println("SCRIPT_COMMIT PASS: three edits = one undo unit")
    }
    @Test fun dryRunReturnsDiffAndCheckButPreservesAllLiveStateAndDoesNotReserveId() {
        val base=initial();val engine=EditSession(base);val tools=ToolDispatcher().bind(engine);val before=engine.snapshot()
        repeat(2) {
            val preview=tools.executeScript("t",0,calls(),export,dryRun=true)
            assertTrue(preview.ok,preview.output.toString());assertNull(preview.pesBytes);assertSame(base,preview.project)
            assertEquals(before,engine.snapshot());assertEquals(listOf("logo"),preview.output.getValue("changedIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("PASS",preview.output.getValue("sewout").jsonObject.getValue("checks").jsonObject.getValue("report").jsonObject.string("verdict"))
        }
        assertTrue(tools.executeScript("t",0,calls(),export).ok);assertEquals(1,engine.revision)
        println("DRY_RUN PASS: revision/history/receipts unchanged; diff and sewing PASS returned")
    }
    @Test fun interactiveBeginStageCommitCancelUndoRedoUseEngineSessionAndAbortFailure() {
        val base=initial();val engine=EditSession(base);val tools=ToolDispatcher().bind(engine)
        assertTrue(tools.dispatch("begin_edit_turn",json("""{"transactionId":"t","expectedRevision":"0"}""")).ok)
        assertTrue(tools.dispatch(calls()[0].name,calls()[0].arguments).ok);assertSame(base,engine.project)
        assertEquals("TURN_IN_PROGRESS",tools.dispatch("undo",json("""{"expectedRevision":"0"}""")).code)
        val state=tools.dispatch("get_project",json("{}"));assertTrue(state.output.getValue("turnInProgress").jsonPrimitive.boolean)
        assertTrue(tools.dispatch("commit_edit_turn",json("""{"transactionId":"t","expectedRevision":"0"}""")).ok)
        assertEquals(1,engine.revision);assertEquals(1,engine.snapshot().undoCount)
        assertTrue(tools.dispatch("commit_edit_turn",json("""{"transactionId":"t","expectedRevision":"0"}""")).ok);assertEquals(1,engine.revision)
        assertTrue(tools.dispatch("begin_edit_turn",json("""{"transactionId":"cancel","expectedRevision":"1"}""")).ok)
        assertEquals("WRONG_TRANSACTION",tools.dispatch("cancel_edit_turn",json("""{"transactionId":"wrong"}""")).code)
        assertTrue(tools.dispatch("cancel_edit_turn",json("""{"transactionId":"cancel"}""")).ok);assertEquals(1,engine.revision)
        assertTrue(tools.dispatch("undo",json("""{"expectedRevision":"1"}""")).ok);assertSame(base,engine.project)
        assertTrue(tools.dispatch("redo",json("""{"expectedRevision":"2"}""")).ok)
        assertTrue(tools.dispatch("begin_edit_turn",json("""{"transactionId":"bad","expectedRevision":"3"}""")).ok)
        val wrong=tools.dispatch("remove_objects",json("""{"transactionId":"bad","objectIds":["logo"]}"""))
        assertEquals("CONFIRMATION_REQUIRED",wrong.code)
        assertEquals("CONFIRMATION_REQUIRED",tools.dispatch("commit_edit_turn",json("""{"transactionId":"bad","expectedRevision":"3"}""")).code)
        assertEquals(3,engine.revision);assertNotNull(engine.project.find("logo"))
        for(name in listOf("begin_edit_turn","commit_edit_turn","cancel_edit_turn","undo","redo")) {
            assertEquals("INVALID_ARGUMENTS",tools.dispatch(name,json("""{"unknown":true}""")).code)
        }
        println("LIFECYCLE_TOOLS PASS: begin/commit/cancel/undo/redo and schema rejection")
    }
    @Test fun staleCommitNeverRestoresOverAnInterveningUiEditAndReplayDoesNotResurrectUndoneState() {
        val base=initial();val engine=EditSession(base);val tools=ToolDispatcher().bind(engine)
        tools.dispatch("begin_edit_turn",json("""{"transactionId":"t","expectedRevision":"0"}"""))
        tools.dispatch(calls()[0].name,calls()[0].arguments)
        val ui=base.rotateBy("logo",45.0);engine.commitSnapshot(ui,0)
        assertEquals("REVISION_MISMATCH",tools.dispatch("commit_edit_turn",json("""{"transactionId":"t","expectedRevision":"0"}""")).code)
        assertSame(ui,engine.project)
        val result=tools.executeScript("next",1,calls("next"),export);assertTrue(result.ok,result.output.toString())
        engine.undo(2);assertSame(ui,engine.project)
        val replay=tools.executeScript("next",1,calls("next"),export)
        assertTrue(replay.ok);assertSame(ui,replay.project);assertNull(replay.pesBytes);assertEquals(3,engine.revision)
        assertEquals("TRANSACTION_ID_REUSED",tools.executeScript("next",1,calls("different"),export).code)
    }
    @Test fun scriptInvalidSchemaUnsupportedToolAndFinalSewingBlockAreAtomic() {
        val engine=EditSession(initial());val tools=ToolDispatcher().bind(engine);val before=engine.snapshot()
        val unsupported=ToolTurnController.Call("list_fonts",json("""{"text":"x","filters":[],"installedOnly":"wrong"}"""))
        assertEquals("INVALID_ARGUMENTS",tools.executeScript("bad-schema",0,listOf(unsupported),export).code)
        assertEquals(before,engine.snapshot())
        val bad=calls("outside").toMutableList();bad[0]=bad[0].copy(arguments=JsonObject(bad[0].arguments.jsonObject+("deltaXMm" to JsonPrimitive(100))))
        val result=tools.executeScript("outside",0,bad,export)
        assertEquals("SEWOUT_BLOCKED",result.code);assertNull(result.pesBytes);assertEquals(before,engine.snapshot())
        assertNotNull(result.output["previewProject"]);assertNotNull(result.output["sewout"])
        val preview=tools.executeScript("outside-preview",0,bad.map { c->c.copy(arguments=JsonObject(c.arguments.jsonObject+("transactionId" to JsonPrimitive("outside-preview")))) },export,dryRun=true)
        assertEquals("SEWOUT_BLOCKED",preview.code);assertEquals(before,engine.snapshot())
    }
}
