// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.agent.*
import io.github.linker2k.myembro.safety.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AdversarialPhoneSafetyTest {
    private val f=SafetyFixtures
    @Test fun all21ConfirmationToolsAreInertInDefaultApplyAndPreview() {
        assertEquals(21,f.confirmation.size);val rows=mutableListOf<JsonObject>()
        for(tool in f.confirmation) for(dry in listOf(false,true)) {
            val name=tool.getValue("name").jsonPrimitive.content;val store=RecordingStore();val kernel=ToolDispatcher(documentStore=store)
            val base=f.project();val session=EditorSession(base);val before=session.history
            val r=session.runCommandScript(kernel,"attack",0,listOf(ToolTurnController.Call(name,f.arguments(tool))),f.export,dryRun=dry)
            rows+=f.row("$name/dry=$dry",r.output,store.calls.size,session.project!=base,r.pesBytes?.size ?: 0)
            assertTrue(store.calls.isEmpty(),"$name wrote storage");assertEquals(before,session.history,"$name changed revision/history");assertSame(base,session.project)
            assertNull(r.pesBytes)
        }
        f.report("phone-no-token",rows)
    }
    @Test fun previewApprovalsAllowOnlySelectedUndoableRemovalsAndBindWholeScript() {
        val base=f.project().let { it.copy(objects=it.objects+it.objects.last().withId("second")) }
        val session=EditorSession(base);val store=RecordingStore();val kernel=ToolDispatcher(documentStore=store)
        val calls=listOf("victim","second").map { id-> ToolTurnController.Call("remove_objects",f.json("""{"transactionId":"attack","objectIds":["$id"]}""")) }
        val preview=session.runCommandScript(kernel,"attack",0,calls,f.export,dryRun=true)
        assertTrue(preview.ok,preview.output.toString());assertSame(base,session.project);assertNull(preview.pesBytes)
        val approvals=calls.indices.map { ScriptApprovals.request("attack",0,calls,it) }
        assertEquals(approvals.map { it.json() },preview.output.getValue("approvalRequests").jsonArray.toList())
        val before=session.history
        val absent=session.runCommandScript(kernel,"attack",0,calls,f.export)
        assertEquals("CONFIRMATION_REQUIRED",absent.code)
        val partial=session.runCommandScript(kernel,"attack",0,calls,f.export,approvals=approvals.take(1))
        assertEquals("CONFIRMATION_REQUIRED",partial.code);assertEquals(1,partial.output.getValue("failedCallIndex").jsonPrimitive.int)
        assertEquals(before,session.history);assertSame(base,session.project);assertNull(partial.pesBytes)
        val badApprovals=listOf(
            approvals[0].copy(callIndex=-1), approvals[0].copy(callIndex=99), approvals[0].copy(callIndex=1), approvals[0].copy(targetIds=listOf("logo")), approvals[0].copy(argumentsHash="forged"),
            ScriptApprovals.request("other",0,calls,0), ScriptApprovals.request("attack",1,calls,0)
        )
        for(bad in badApprovals) {
            val r=session.runCommandScript(kernel,"attack",0,calls,f.export,approvals=listOf(bad))
            assertEquals("INVALID_APPROVAL",r.code);assertSame(base,session.project);assertNull(r.pesBytes)
        }
        val duplicate=session.runCommandScript(kernel,"attack",0,calls,f.export,approvals=listOf(approvals[0],approvals[0]))
        assertEquals("INVALID_APPROVAL",duplicate.code)
        val edited=calls.toMutableList().apply { this[1]=this[1].copy(arguments=f.json("""{"transactionId":"attack","objectIds":["logo"]}""")) }
        assertEquals("INVALID_APPROVAL",session.runCommandScript(kernel,"attack",0,edited,f.export,approvals=approvals).code)
        val applied=session.runCommandScript(kernel,"attack",0,calls,f.export,approvals=approvals)
        assertTrue(applied.ok,applied.output.toString());assertEquals(listOf("logo"),session.project.objects.map { it.id });assertNotNull(applied.pesBytes)
        assertEquals(1,session.history.undoCount);session.undo();assertSame(base,session.project);assertTrue(store.calls.isEmpty())
        f.report("phone-item-approvals",listOf(f.row("selected-removals",applied.output,store.calls.size)))
    }
    @Test fun externalSideEffectToolsCannotRunInsideScriptsEvenWithAnApproval() {
        for(tool in f.confirmation.filter { it.getValue("name") != JsonPrimitive("remove_objects") }) {
            val name=tool.getValue("name").jsonPrimitive.content
            for(dry in listOf(false,true)) for(forged in listOf(false,true)) {
                val base=f.project();val session=EditorSession(base);val store=RecordingStore()
                val approvals=if(forged)listOf(ScriptApproval(0,listOf("victim"),"forged")) else emptyList()
                val r=session.runCommandScript(ToolDispatcher(documentStore=store),"attack",0,listOf(ToolTurnController.Call(name,f.arguments(tool))),f.export,dryRun=dry,approvals=approvals)
                assertFalse(r.ok,name);assertSame(base,session.project);assertTrue(store.calls.isEmpty());assertNull(r.pesBytes)
                assertEquals(if(forged)"INVALID_APPROVAL" else "SCRIPT_TOOL_FORBIDDEN",r.code,name)
            }
        }
    }
    @Test fun malformedArgumentsAndOffHoopScriptNeverApplyOrEmitBytes() {
        val rows=mutableListOf<JsonObject>()
        for(a in f.malformed()) {
            val base=f.project();val session=EditorSession(base);val store=RecordingStore()
            val r=session.runCommandScript(ToolDispatcher(documentStore=store),"attack",0,listOf(ToolTurnController.Call(a.tool,a.args)),f.export)
            rows+=f.row(a.label,r.output,store.calls.size,session.project!=base,r.pesBytes?.size ?: 0)
            assertFalse(r.ok,a.label);assertSame(base,session.project);assertTrue(store.calls.isEmpty());assertNull(r.pesBytes)
        }
        val extraRows=mutableListOf<JsonObject>()
        for(raw in f.catalog.getValue("tools").jsonArray) {
            val tool=raw.jsonObject;val name=tool.getValue("name").jsonPrimitive.content
            val base=f.project();val session=EditorSession(base);val store=RecordingStore()
            val args=JsonObject(f.arguments(tool)+("__attack_extra" to JsonPrimitive(true)))
            val r=session.runCommandScript(ToolDispatcher(documentStore=store),"attack",0,listOf(ToolTurnController.Call(name,args)),f.export)
            assertFalse(r.ok,name);assertTrue(store.calls.isEmpty());assertSame(base,session.project);assertNull(r.pesBytes)
            extraRows+=f.row(name,r.output,store.calls.size)
        }
        assertEquals(69,extraRows.size);f.report("phone-extra-fields",extraRows)
        f.report("phone-malformed",rows)
    }
}
