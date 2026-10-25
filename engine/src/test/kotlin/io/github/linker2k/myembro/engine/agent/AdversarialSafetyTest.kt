// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.safety.*
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.EditSession
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AdversarialSafetyTest {
    private val f=SafetyFixtures
    @Test fun all21ConfirmationToolsHaveNoTokenNoSideEffectsAtKernelAndController() {
        assertEquals(21,f.confirmation.size);val rows=mutableListOf<JsonObject>();val failures=mutableListOf<String>()
        for(tool in f.confirmation) for(route in listOf("kernel","controller")) {
            val name=tool.getValue("name").jsonPrimitive.content;val store=RecordingStore();val base=f.project();val session=EditSession(base)
            val kernel=ToolDispatcher(documentStore=store);val controller=kernel.bind(session)
            val artifact=controller.dispatch("prepare_pes",f.prepare).output.getValue("artifactId").jsonPrimitive.content
            if(name=="remove_objects")controller.dispatch("begin_edit_turn",f.json("""{"transactionId":"attack","expectedRevision":"0"}"""))
            val a=f.arguments(tool,artifact)
            val r=if(route=="kernel")kernel.dispatch(base,"0",name,a) else controller.dispatch(name,a)
            val changed=session.project!=base || r.project!=base
            rows+=f.row("$route/$name",r.output,store.calls.size,changed,r.pesBytes?.size ?: 0)
            if(store.calls.isNotEmpty() || changed)failures+="$route/$name writes=${store.calls.size} projectChanged=$changed"
        }
        f.report("engine-no-token",rows);assertTrue(failures.isEmpty(),failures.joinToString("\n"))
    }
    @Test fun knownStoreTokensRejectWrongReuseAndDifferentArguments() {
        val rows=mutableListOf<JsonObject>()
        for(name in listOf("save_project","save_pes")) {
            val tool=f.confirmation.single { it.getValue("name").jsonPrimitive.content==name };val store=RecordingStore();val c=ToolDispatcher(documentStore=store).bind(EditSession(f.project()))
            val artifact=c.dispatch("prepare_pes",f.prepare).output.getValue("artifactId").jsonPrimitive.content;val a=f.arguments(tool,artifact)
            val ext=if(name=="save_project")"myembro" else "pes";store.files["unapproved.$ext"]="test-owned seed".toByteArray()
            val challenge=c.dispatch(name,a);val retry=challenge.output.getValue("retryArguments").jsonObject
            val token=challenge.output.getValue("confirmToken").jsonPrimitive.content
            val wrong=c.dispatch(name,retry,confirmToken="wrong");assertFalse(wrong.ok);assertTrue(store.calls.isEmpty())
            val altered=c.dispatch(name,JsonObject(retry+("name" to JsonPrimitive("different"))),confirmToken=token);assertFalse(altered.ok);assertTrue(store.calls.isEmpty())
            val fresh=c.dispatch(name,a);val approved=c.dispatch(name,fresh.output.getValue("retryArguments"),confirmToken=fresh.output.getValue("confirmToken").jsonPrimitive.content)
            assertTrue(approved.ok);val n=store.calls.size
            val replay=c.dispatch(name,fresh.output.getValue("retryArguments"),confirmToken=fresh.output.getValue("confirmToken").jsonPrimitive.content)
            assertFalse(replay.ok);assertEquals(n,store.calls.size)
            rows+=f.row(name,replay.output,store.calls.size)
        }
        f.report("engine-token-negative",rows)
    }
    @Test fun malformedArgumentsAreRejectedIncluding9999NativeUnits() {
        val failures=mutableListOf<String>();val rows=mutableListOf<JsonObject>()
        for(a in f.malformed()) {
            val store=RecordingStore();val base=f.project();val r=ToolDispatcher(documentStore=store).dispatch(base,"0",a.tool,a.args)
            rows+=f.row(a.label,r.output,store.calls.size,r.project!=base,r.pesBytes?.size ?: 0)
            if(r.ok || store.calls.isNotEmpty())failures+=a.label+" accepted="+r.ok
        }
        val schemaRows=mutableListOf<JsonObject>();val store=RecordingStore();val kernel=ToolDispatcher(documentStore=store)
        for(raw in f.catalog.getValue("tools").jsonArray) {
            val tool=raw.jsonObject;val name=tool.getValue("name").jsonPrimitive.content
            val r=kernel.dispatch(f.project(),"0",name,JsonObject(f.arguments(tool)+("__attack_extra" to JsonPrimitive(true))))
            assertEquals("INVALID_ARGUMENTS",r.code,name);assertTrue(store.calls.isEmpty())
            schemaRows+=f.row(name,r.output,store.calls.size)
        }
        assertEquals(69,schemaRows.size);f.report("engine-extra-fields",schemaRows)
        f.report("engine-malformed",rows);assertTrue(failures.isEmpty(),failures.joinToString("\n"))
    }
    @Test fun blockedPlacementDensityAndViewportNeverReleaseArtifactOrBytes() {
        val bad=f.densePes();assertTrue(PecViewportCheck.check(bad,HoopSpec.H100).valid)
        assertTrue(SewoutCheck.checkPes(bad,options=SewoutOptions(origin=DesignOrigin.IMPORTED)).hasBlockingIssues)
        val source=checkNotNull(javaClass.getResourceAsStream("/agent/builtins/myembro_logo.pes")).use { it.readBytes() }
        val corrupt=source.copyOf();val pointer=(0..3).sumOf { (corrupt[8+it].toInt() and 255) shl(it*8) };corrupt[pointer+520]=(corrupt[pointer+520].toInt() xor 1).toByte()
        assertFalse(PecViewportCheck.check(corrupt,HoopSpec.H100).valid)
        val readStore=RecordingStore();val read=ToolDispatcher(assets=mapOf("blocked" to bad),documentStore=readStore).dispatch(f.project(),"0","sewout_check",f.json("""{"projectRevision":"","pesAssetId":"blocked","machineId":"se700","frameId":"H100"}"""))
        assertTrue(read.ok);assertEquals("BLOCK",read.output.getValue("report").jsonObject.getValue("verdict").jsonPrimitive.content)
        assertNull(read.pesBytes);assertTrue(readStore.calls.isEmpty())
        val projects=listOf(f.blockedProject(),Project(objects=listOf(StitchObject("dense","dense",PesReader.read(bad),sourceBytes=bad,sourceFileName="dense.pes"))),Project(objects=listOf(StitchObject("viewport","viewport",PesReader.read(corrupt),sourceBytes=corrupt,sourceFileName="viewport.pes"))))
        val rows=mutableListOf<JsonObject>()
        for((i,p) in projects.withIndex()) {
            val store=RecordingStore();val kernel=ToolDispatcher(documentStore=store);val c=kernel.bind(EditSession(p))
            for(name in listOf("prepare_pes","sewout_check")) {
                val a=if(name=="prepare_pes")f.prepare else f.json("""{"projectRevision":"0","pesAssetId":"","machineId":"se700","frameId":"H100"}""")
                val r=c.dispatch(name,a);assertFalse(r.ok,r.output.toString());assertNull(r.pesBytes);assertNull(r.output["artifactId"])
                rows+=f.row("$i/$name",r.output,store.calls.size,false,r.pesBytes?.size ?: 0)
            }
            val save=c.dispatch("save_pes",f.json("""{"artifactId":"fabricated","destinationId":"output","name":"bad","overwrite":false}"""))
            assertFalse(save.ok);assertTrue(store.calls.isEmpty())
        }
        f.report("engine-blocked",rows)
    }
    @Test fun publicPesExportPreparationCannotReturnSewoutBlockedBytes() {
        val bytes=f.densePes();assertTrue(SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.IMPORTED)).hasBlockingIssues)
        val prepared=runCatching { PesExport.prepare(bytes,"dense.pes") }.getOrNull()
        val count=prepared?.bytes?.size ?: 0
        f.report("engine-pes-export",listOf(buildJsonObject { put("blockedInput",true);put("returnedBytes",count) }))
        assertEquals(0,count,"PesExport returned SewoutCheck BLOCK input")
    }
}
