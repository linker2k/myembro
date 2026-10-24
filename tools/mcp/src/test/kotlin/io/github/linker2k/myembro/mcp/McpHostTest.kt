// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.mcp

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class McpHostTest {
    private fun args(s:String)=Json.parseToJsonElement(s).jsonObject
    private fun host()=McpHost(Files.createTempDirectory("myembro-mcp-test-"))
    @Test fun toolCatalogIsExactAndStrictDispatcherValidationSurvivesTransport() {
        val h=host();val expected=checkNotNull(javaClass.getResourceAsStream("/agent/tools.json")).bufferedReader().use { Json.parseToJsonElement(it.readText()) }
        assertEquals(expected,h.listTools());assertEquals(69,h.listTools().getValue("tools").jsonArray.size)
        assertEquals("INVALID_ARGUMENTS",h.call("get_project",args("""{"unknown":true}""")).getValue("code").jsonPrimitive.content)
        assertEquals("NOT_IMPLEMENTED",h.call("get_settings",args("{}")).getValue("code").jsonPrimitive.content)
    }
    @Test fun confirmationIsRequiredBoundToActionRevisionAndSingleUse() {
        val h=host();val a=args("""{"transactionId":"t","objectIds":["x"]}""")
        val consent=h.call("remove_objects",a);assertEquals("CONFIRMATION_REQUIRED",consent.getValue("code").jsonPrimitive.content)
        assertEquals(0,h.session.revision)
        val token=consent.getValue("confirmToken").jsonPrimitive.content
        assertEquals("INVALID_CONFIRMATION",h.call("remove_objects",args("""{"transactionId":"t","objectIds":["y"]}"""),token).getValue("code").jsonPrimitive.content)
        assertEquals("INVALID_CONFIRMATION",h.call("remove_objects",a,token).getValue("code").jsonPrimitive.content)
        val next=h.call("remove_objects",a).getValue("confirmToken").jsonPrimitive.content
        h.session.commitSnapshot(h.session.project.copy(hoop=io.github.linker2k.myembro.engine.edit.HoopSpec.H130x180),0)
        assertEquals("INVALID_CONFIRMATION",h.call("remove_objects",a,next).getValue("code").jsonPrimitive.content)
    }
    @Test fun actualSnapshotMixedTextAndCheckedSaveUseOneSharedTurn() {
        val h=host();val revision=h.fonts.revision("jua")!!
        assertEquals(JsonPrimitive(true),h.call("begin_edit_turn",args("""{"transactionId":"t","expectedRevision":"0"}"""))["ok"])
        val a=args("""{"transactionId":"t","objectId":"text","text":"안녕 myembro","fontId":"jua","fontRevision":"$revision","heightMm":8,"threadColor":"#000000","singleLineBold":false}""")
        assertEquals(JsonPrimitive(true),h.call("add_text",a)["ok"])
        assertTrue(h.session.project.objects.isEmpty())
        assertEquals(JsonPrimitive(true),h.call("center_objects",args("""{"transactionId":"t","objectIds":["text"]}"""))["ok"])
        assertEquals(JsonPrimitive(true),h.call("commit_edit_turn",args("""{"transactionId":"t","expectedRevision":"0"}"""))["ok"])
        assertEquals(1,h.session.snapshot().undoCount)
        val pes=h.call("prepare_pes",args("""{"projectRevision":"1","machineId":"se400","frameId":"H100","pesVersion":"V6"}"""))
        assertEquals(JsonPrimitive(true),pes["ok"],pes.toString())
        val save=args("""{"artifactId":"${pes.getValue("artifactId").jsonPrimitive.content}","destinationId":"output","name":"test.pes","overwrite":false}""")
        val token=h.call("save_pes",save).getValue("confirmToken").jsonPrimitive.content
        assertFalse(Files.exists(h.output.resolve("test.pes")))
        assertEquals(JsonPrimitive(true),h.call("save_pes",save,token)["ok"])
        assertTrue(Files.size(h.output.resolve("test.pes"))>0)
        val second=h.call("save_pes",save).getValue("confirmToken").jsonPrimitive.content
        assertEquals("ALREADY_EXISTS",h.call("save_pes",save,second).getValue("code").jsonPrimitive.content)
        val escape=JsonObject(save+("name" to JsonPrimitive("../escaped.pes")))
        val third=h.call("save_pes",escape).getValue("confirmToken").jsonPrimitive.content
        assertEquals("INVALID_FILENAME",h.call("save_pes",escape,third).getValue("code").jsonPrimitive.content)
    }
    @Test fun eofNotificationAndMethodErrorsAreProtocolMessagesOnly() {
        val h=host()
        assertEquals(-32600,h.rpc(args("""{"id":1}"""))!!.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        assertEquals(-32600,h.rpc(args("""{"jsonrpc":"2.0","id":true,"method":"ping"}"""))!!.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        assertNull(h.rpc(args("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")))
        val r=h.rpc(args("""{"jsonrpc":"2.0","id":1,"method":"unknown"}"""))!!
        assertEquals(-32601,r.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        val init=h.rpc(args("""{"jsonrpc":"2.0","id":2,"method":"initialize"}"""))!!
        assertTrue(init.getValue("result").jsonObject.getValue("instructions").jsonPrimitive.content.contains("jua="))
    }
}
