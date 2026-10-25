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
    @Test fun applyCannotUseOneBooleanAsConfirmationForAProtectedScriptTool() {
        val base=f.project();val session=EditorSession(base);val store=RecordingStore();val kernel=ToolDispatcher(documentStore=store)
        val r=session.runCommandScript(kernel,"attack",0,listOf(ToolTurnController.Call("remove_objects",f.json("""{"transactionId":"attack","objectIds":["victim"]}"""))),f.export,confirmed=true)
        f.report("phone-blanket-confirm",listOf(f.row("remove_objects/confirmed=true/no-token",r.output,store.calls.size,session.project!=base,r.pesBytes?.size ?: 0)))
        assertSame(base,session.project,"Apply-wide confirmed=true executed a protected tool without a per-action token")
        assertFalse(r.ok);assertNull(r.pesBytes)
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
