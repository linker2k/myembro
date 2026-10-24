// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.agent.*
import kotlinx.serialization.json.*
import kotlin.test.*

class EditorCommandScriptTest {
    private fun json(s:String)=Json.parseToJsonElement(s).jsonObject
    private val export=json("""{"machineId":"se700","frameId":"H100","pesVersion":"V6"}""")
    private fun calls(bad:Boolean=false)=listOf(
        ToolTurnController.Call("transform_objects",json("""{"transactionId":"phone","objectIds":["logo"],"deltaXMm":1,"deltaYMm":2,"scaleFactor":1,"rotationDeg":0}""")),
        ToolTurnController.Call("transform_objects",json("""{"transactionId":"phone","objectIds":["logo"],"deltaXMm":0,"deltaYMm":0,"scaleFactor":1,"rotationDeg":90}""")),
        ToolTurnController.Call("recolor_object",json("""{"transactionId":"phone","objectId":"logo","threadIndex":${if(bad)99 else 0},"threadColor":"#112233"}"""))
    )
    @Test fun phonePreviewAndApplyShareToolbarHistoryWithoutPartialEdits() {
        val base=AgentScenario.builtin("myembro_logo.pes","logo","H100");val session=EditorSession(base);val kernel=ToolDispatcher()
        val before=session.history
        val preview=session.runCommandScript(kernel,"phone",0,calls(),export,dryRun=true)
        assertTrue(preview.ok,preview.output.toString());assertNull(preview.pesBytes);assertSame(base,session.project);assertEquals(before,session.history)
        val done=session.runCommandScript(kernel,"phone",0,calls(),export)
        assertTrue(done.ok,done.output.toString());assertEquals(1,session.history.undoCount);assertEquals(1,session.history.revision)
        assertEquals(90.0,session.project.find("logo")!!.transform.rotationDeg)
        assertTrue(session.stepHistory(redo=false));assertSame(base,session.project);assertEquals(0,session.history.undoCount)
        println("PHONE_SCRIPT PASS: dry-run unchanged; apply -> toolbar undo restores entire script")
    }
    @Test fun thirdPhoneCommandFailureAndActivePointerGesturePreserveState() {
        val base=AgentScenario.builtin("myembro_logo.pes","logo","H100");val session=EditorSession(base);val kernel=ToolDispatcher()
        assertFalse(session.runCommandScript(kernel,"phone",0,calls(bad=true),export).ok)
        assertSame(base,session.project);assertEquals(0,session.history.revision);assertEquals(0,session.history.undoCount)
        session.beginGesture()
        assertEquals("GESTURE_IN_PROGRESS",session.runCommandScript(kernel,"phone2",0,calls(),export).code)
        assertTrue(session.isGesturing);assertSame(base,session.project)
    }
}
