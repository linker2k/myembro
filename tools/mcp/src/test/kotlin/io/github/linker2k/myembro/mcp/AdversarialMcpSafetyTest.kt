// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.mcp

import io.github.linker2k.myembro.safety.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AdversarialMcpSafetyTest {
    private val f=SafetyFixtures
    private fun host(store:RecordingStore):McpHost {
        val h=McpHost(Files.createTempDirectory("mcp-safety-"));h.session.commitSnapshot(f.project(),0)
        // Tests only: host has no storage injection seam. Replace its captured backend, not behavior.
        fun field(o:Any,n:String):Any=o.javaClass.getDeclaredField(n).apply { isAccessible=true }.get(o)
        val docs=field(field(h,"controller"),"documents")
        docs.javaClass.getDeclaredField("store").apply { isAccessible=true }.set(docs,store)
        return h
    }
    private fun arguments(tool:JsonObject,artifact:String):JsonObject {
        val a=f.arguments(tool,artifact)
        return JsonObject(a.mapValues { (k,v)->if(k in setOf("projectRevision","expectedRevision"))JsonPrimitive("1") else v })
    }
    private fun prepare(h:McpHost)=h.call("prepare_pes",f.json("""{"projectRevision":"1","machineId":"se700","frameId":"H100","pesVersion":"V6"}""")).getValue("artifactId").jsonPrimitive.content
    @Test fun all21ProtectedScopesWithoutConsentHaveZeroSideEffects() {
        val failures=mutableListOf<String>();val rows=mutableListOf<JsonObject>();assertEquals(21,f.confirmation.size)
        for(tool in f.confirmation) {
            val name=tool.getValue("name").jsonPrimitive.content;val store=RecordingStore();val h=host(store);val artifact=prepare(h)
            if(name=="remove_objects")h.call("begin_edit_turn",f.json("""{"transactionId":"attack","expectedRevision":"1"}"""))
            if(name in setOf("save_project","save_pes"))store.files["unapproved.${if(name=="save_project")"myembro" else "pes"}"]="seed".toByteArray()
            val before=h.session.snapshot();val r=h.call(name,arguments(tool,artifact))
            val changed=h.session.project!=before.project
            rows+=f.row(name,r,store.calls.size,changed)
            if(store.calls.isNotEmpty() || changed)failures+="$name writes=${store.calls.size} changed=$changed"
        }
        f.report("mcp-no-token",rows);assertTrue(failures.isEmpty(),failures.joinToString("\n"))
    }
    @Test fun freshSaveNamesNeedNoToken() {
        for(name in listOf("save_project","save_pes")) {
            val store=RecordingStore();val h=host(store);val artifact=prepare(h)
            val tool=f.confirmation.single { it.getValue("name")==JsonPrimitive(name) }
            assertEquals(JsonPrimitive(true),h.call(name,arguments(tool,artifact))["ok"]);assertEquals(1,store.calls.size)
        }
    }
    @Test fun all21WrongOtherArgumentsAndUsedTokensAreRejected() {
        val rows=mutableListOf<JsonObject>()
        for(tool in f.confirmation) {
            val name=tool.getValue("name").jsonPrimitive.content;val store=RecordingStore();val h=host(store);val artifact=prepare(h);val a=arguments(tool,artifact)
            if(name in setOf("save_project","save_pes"))store.files["unapproved.${if(name=="save_project")"myembro" else "pes"}"]="seed".toByteArray()
            if(name=="remove_objects")h.call("begin_edit_turn",f.json("""{"transactionId":"attack","expectedRevision":"1"}"""))
            val before=h.session.snapshot()
            val wrong=h.call(name,a,"forged");assertEquals(JsonPrimitive(false),wrong["ok"]);assertTrue(store.calls.isEmpty());assertEquals(before,h.session.snapshot())
            val first=h.call(name,a);val token=first.getValue("confirmToken").jsonPrimitive.content
            val retry=(first["retryArguments"] as? JsonObject) ?: a
            // Change a schema-valid string value where possible; empty-argument tools use a token for another name.
            val key=retry.entries.firstOrNull { it.value is JsonPrimitive && (it.value as JsonPrimitive).isString }?.key
            val altered=if(key!=null) JsonObject(retry+(key to JsonPrimitive("different"))) else retry
            val changed=if(key!=null)h.call(name,altered,token) else h.call("delete_font",f.json("""{"fontId":"different"}"""),token)
            assertEquals(JsonPrimitive(false),changed["ok"]);assertTrue(store.calls.isEmpty());assertEquals(before,h.session.snapshot())
            val next=h.call(name,a);val nextToken=next.getValue("confirmToken").jsonPrimitive.content
            val approved=h.call(name,(next["retryArguments"] as? JsonObject) ?: a,nextToken)
            val writes=store.calls.size;val afterApproved=h.session.snapshot()
            val replay=h.call(name,(next["retryArguments"] as? JsonObject) ?: a,nextToken)
            assertEquals(JsonPrimitive(false),replay["ok"]);assertEquals(writes,store.calls.size);assertEquals(afterApproved,h.session.snapshot())
            rows+=f.row(name,replay,writes)
        }
        f.report("mcp-token-negative",rows)
    }
    @Test fun malformedAndBlockedInputsNeverWrite() {
        val failures=mutableListOf<String>();val rows=mutableListOf<JsonObject>()
        for(a in f.malformed()) {
            val store=RecordingStore();val h=host(store);h.call("begin_edit_turn",f.json("""{"transactionId":"attack","expectedRevision":"1"}"""))
            val r=h.call(a.tool,a.args);rows+=f.row(a.label,r,store.calls.size)
            if((r["ok"]==JsonPrimitive(true)) == a.mustReject)failures+=a.label+" accepted="+r["ok"]
            if(!a.mustReject) {
                assertTrue(h.call("commit_edit_turn",f.json("""{"transactionId":"attack","expectedRevision":"1"}"""))["ok"]==JsonPrimitive(true))
                val blocked=h.call("prepare_pes",f.json("""{"projectRevision":"2","machineId":"se700","frameId":"H100","pesVersion":"V6"}"""))
                assertEquals("SEWOUT_BLOCKED",blocked.getValue("code").jsonPrimitive.content);assertNull(blocked["artifactId"])
                val save=h.call("save_pes",f.json("""{"artifactId":"missing","destinationId":"output","name":"blocked","overwrite":false}"""))
                assertEquals(JsonPrimitive(false),save["ok"])
                val usbArgs=f.arguments(f.confirmation.single { it.getValue("name")==JsonPrimitive("send_usb") })
                val challenge=h.call("send_usb",usbArgs)
                assertEquals("CONFIRMATION_REQUIRED",challenge.getValue("code").jsonPrimitive.content)
                val send=h.call("send_usb",usbArgs,challenge.getValue("confirmToken").jsonPrimitive.content)
                assertEquals("NOT_IMPLEMENTED",send.getValue("code").jsonPrimitive.content);assertNull(send["artifactId"])
            }
            assertTrue(store.calls.isEmpty())
        }
        val store=RecordingStore();val h=host(store);h.session.commitSnapshot(f.blockedProject(),1)
        val r=h.call("prepare_pes",f.json("""{"projectRevision":"2","machineId":"se700","frameId":"H100","pesVersion":"V6"}"""))
        assertEquals(JsonPrimitive(false),r["ok"]);assertNull(r["artifactId"]);assertTrue(store.calls.isEmpty())
        val schemaRows=mutableListOf<JsonObject>()
        for(raw in f.catalog.getValue("tools").jsonArray) {
            val tool=raw.jsonObject;val name=tool.getValue("name").jsonPrimitive.content
            val checked=h.call(name,JsonObject(arguments(tool,"missing")+("__attack_extra" to JsonPrimitive(true))))
            assertEquals("INVALID_ARGUMENTS",checked.getValue("code").jsonPrimitive.content,name);assertTrue(store.calls.isEmpty())
            schemaRows+=f.row(name,checked,store.calls.size)
        }
        assertEquals(70,schemaRows.size);f.report("mcp-extra-fields",schemaRows)
        f.report("mcp-malformed",rows);assertTrue(failures.isEmpty(),failures.joinToString("\n"))
    }
}
