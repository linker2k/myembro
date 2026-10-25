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
        assertEquals(JsonPrimitive(true),h.call("get_settings",args("{}"))["ok"])
        assertEquals("INVALID_ARGUMENTS",h.call("get_settings",args("""{"unknown":true}""")).getValue("code").jsonPrimitive.content)
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
        assertEquals(JsonPrimitive(true),h.call("save_pes",save)["ok"])
        val original=Files.readAllBytes(h.output.resolve("test.pes"))
        val conflict=h.call("save_pes",save)
        assertEquals("CONFIRMATION_REQUIRED",conflict.getValue("code").jsonPrimitive.content)
        assertContentEquals(original,Files.readAllBytes(h.output.resolve("test.pes")))
        val token=conflict.getValue("confirmToken").jsonPrimitive.content
        assertEquals(JsonPrimitive(true),h.call("save_pes",conflict.getValue("retryArguments").jsonObject,token)["ok"])
        assertEquals("INVALID_CONFIRMATION",h.call("save_pes",conflict.getValue("retryArguments").jsonObject,token).getValue("code").jsonPrimitive.content)
        val escape=JsonObject(save+("name" to JsonPrimitive("../escaped.pes")))
        assertEquals("DOCUMENT_SAVE_FAILED",h.call("save_pes",escape).getValue("code").jsonPrimitive.content)

    }
    @Test fun logoTextHeartCompositionAndExistingFileConfirmationUseCommonServices() {
        val h=host()
        fun ok(name:String,body:String):JsonObject {
            val r=h.call(name,args(body));assertEquals(JsonPrimitive(true),r["ok"],"$name $r");return r
        }
        val seed=checkNotNull(javaClass.getResourceAsStream("/agent/builtins/myembro_logo.pes")).use { it.readBytes() }
        val target=h.output.resolve("composition.pes");Files.write(target,seed)
        val designs=ok("list_builtin_designs","{}").getValue("designs").jsonArray
        assertTrue(designs.any { it.jsonObject.getValue("assetId").jsonPrimitive.content=="builtin:myembro-logo" })
        val fonts=ok("list_fonts","""{"text":"안녕","filters":["KOREAN"],"installedOnly":true}""").getValue("fonts").jsonArray
        assertTrue(fonts.any { it.jsonObject.getValue("fontId").jsonPrimitive.content=="jua" })
        val emoji=ok("prepare_emoji","""{"emojiId":"red-heart","frameId":"H100","widthMm":20}""")
        ok("begin_edit_turn","""{"transactionId":"t","expectedRevision":"0"}""")
        ok("add_design","""{"transactionId":"t","assetId":"builtin:myembro-logo","objectId":"logo","name":"Logo"}""")
        ok("add_text","""{"transactionId":"t","objectId":"text","text":"안녕","fontId":"jua","fontRevision":"${h.fonts.revision("jua")}","heightMm":15,"threadColor":"#000000","singleLineBold":false}""")
        ok("add_generated_art","""{"transactionId":"t","resultId":"${emoji.getValue("resultId").jsonPrimitive.content}","objectId":"heart","name":"Heart"}""")
        for((id,x,y) in listOf(Triple("logo",-12,-10),Triple("text",-12,35),Triple("heart",35,-35))) {
            ok("center_objects","""{"transactionId":"t","objectIds":["$id"]}""")
            ok("transform_objects","""{"transactionId":"t","objectIds":["$id"],"deltaXMm":$x,"deltaYMm":$y,"scaleFactor":1,"rotationDeg":0}""")
        }
        ok("commit_edit_turn","""{"transactionId":"t","expectedRevision":"0"}""")
        val project=ok("get_project","{}").getValue("project").jsonObject
        val objects=project.getValue("objects").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals(setOf("logo","text","heart"),objects.keys);assertEquals(1,h.session.snapshot().undoCount)
        fun bounds(id:String)=objects.getValue(id).getValue("placedBoundsMm").jsonObject
        fun n(id:String,key:String)=bounds(id).getValue(key).jsonPrimitive.double
        assertTrue(n("text","minYMm")>n("logo","maxYMm"))
        assertTrue(n("heart","minXMm")>n("logo","maxXMm"));assertTrue(n("heart","maxYMm")<n("text","minYMm"))
        assertEquals(15.0,objects.getValue("text").getValue("heightMm").jsonPrimitive.double)
        val pes=ok("prepare_pes","""{"projectRevision":"1","machineId":"se400","frameId":"H100","pesVersion":"V6"}""")
        val save=args("""{"artifactId":"${pes.getValue("artifactId").jsonPrimitive.content}","destinationId":"output","name":"composition.pes","overwrite":false}""")
        val conflict=h.call("save_pes",save);assertEquals("CONFIRMATION_REQUIRED",conflict.getValue("code").jsonPrimitive.content)
        assertContentEquals(seed,Files.readAllBytes(target))
        val saved=h.call("save_pes",conflict.getValue("retryArguments").jsonObject,conflict.getValue("confirmToken").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true),saved["ok"],saved.toString());assertEquals(JsonPrimitive(true),saved["overwritten"])
        assertFalse(seed.contentEquals(Files.readAllBytes(target)))
        println("COMPOSITION PASS: full-size logo + 안녕 15mm below + heart 20mm upper-right; overwrite handshake")
    }
    @Test fun invalidMcpArgumentsAbortTheSharedPreparedTurn() {
        val h=host()
        assertEquals(JsonPrimitive(true),h.call("begin_edit_turn",args("""{"transactionId":"t","expectedRevision":"0"}"""))["ok"])
        assertEquals(JsonPrimitive(true),h.call("add_text",args("""{"transactionId":"t","objectId":"text","text":"A","fontId":"hershey-futural","fontRevision":"${h.fonts.revision("hershey-futural")}","heightMm":15,"threadColor":"#000000","singleLineBold":false}"""))["ok"])
        val bad=h.call("get_settings",args("""{"unknown":true}"""))
        assertEquals("INVALID_ARGUMENTS",bad.getValue("code").jsonPrimitive.content)
        val commit=h.call("commit_edit_turn",args("""{"transactionId":"t","expectedRevision":"0"}"""))
        assertEquals(JsonPrimitive(false),commit["ok"]);assertEquals(0,h.session.revision)
        assertTrue(h.session.project.objects.isEmpty());assertEquals(0,h.session.snapshot().undoCount)
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
