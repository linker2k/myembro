// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.mcp

import io.github.linker2k.myembro.engine.agent.*
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.EditSession
import io.github.linker2k.myembro.engine.lettering.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.UUID

/** One isolated in-memory document per stdio connection. All mutations use the shared dispatcher. */
class McpHost(val output:Path) {
    val snapshots=SnapshotFontResolver()
    val fonts=BundledFontResolver(snapshots)
    val session=EditSession(Project())
    private val kernel=ToolDispatcher(fontResolver=fonts)
    private val controller=kernel.bind(session)
    private val catalog=checkNotNull(javaClass.getResourceAsStream("/agent/tools.json")).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private val schema=ToolSchema(catalog.toString())
    private data class Consent(val name:String,val args:JsonObject,val revision:Long)
    private val consents=mutableMapOf<String,Consent>()
    private val artifacts=mutableMapOf<String,ByteArray>()
    fun listTools()=catalog
    fun instructions():String = "myembro: get_project then begin_edit_turn with its revision; stage edits with the same transactionId, then commit_edit_turn using the original revision. Coordinates are mm, centered hoop coordinates. Font resolver revisions: "+
        (snapshots.ids+BundledFontResolver.IDS+HersheyFont.DEFAULT_ID).joinToString { "$it=${fonts.revision(it)}" }+
        ". TTF snapshots cover 자수봄날정민안녕, A-Z/a-z/0-9/space only. Mixed Korean/Latin uses existing automatic fill; try jua heightMm=8. No invented methods. center_objects centers selected IDs. prepare_pes(machineId=se400,frameId=H100,pesVersion=V6,projectRevision=current) returns artifactId only on complete PASS. save_pes uses destinationId=output, a plain .pes filename and overwrite=false. Confirmation-required tools first return a token; a trusted host must resend the identical call with params._meta.myembro.confirmToken. Do not claim user approval merely because you can read a token. Unsupported tools return NOT_IMPLEMENTED."
    fun call(name:String,args:JsonObject,token:String?=null):JsonObject {
        if(!schema.contains(name))return error("UNKNOWN_TOOL",name)
        schema.validate(name,args)?.let { return error("INVALID_ARGUMENTS",it) }
        val needs=schema.confirmation(name)
        if(needs) {
            if(token==null) {
                val id=UUID.randomUUID().toString();consents[id]=Consent(name,args,session.revision)
                return buildJsonObject { put("ok",false);put("code","CONFIRMATION_REQUIRED");put("confirmToken",id);put("revision",session.revision.toString());put("arguments",args);put("message","Obtain user confirmation, then resend identical arguments and the token in params._meta.myembro.confirmToken") }
            }
            val consent=consents.remove(token) ?: return error("INVALID_CONFIRMATION","Unknown or consumed token")
            if(consent!=Consent(name,args,session.revision))return error("INVALID_CONFIRMATION","Action, arguments or revision changed")
        } else if(token!=null) return error("INVALID_CONFIRMATION","This action does not use confirmation")
        if(name=="list_fonts") {
            val text=args.getValue("text").jsonPrimitive.content
            val filters=args.getValue("filters").jsonArray.map { it.jsonPrimitive.content }.toSet()
            if("SCRIPT" in filters)return error("NOT_IMPLEMENTED","Script style tagging is not bound")
            val rows=(snapshots.ids+BundledFontResolver.IDS+HersheyFont.DEFAULT_ID).mapNotNull { id ->
                val f=fonts.resolve(id) ?: return@mapNotNull null
                if(text.codePoints().toArray().any { !Character.isWhitespace(it) && !f.supports(it) })return@mapNotNull null
                val outline=f is ResolvedTextFont.OutlineSnapshot;val single=f !is ResolvedTextFont.InkStitch && !outline
                if("LATIN" in filters && !f.supports('A'.code) || "OUTLINE" in filters && !outline || "SINGLE_LINE" in filters && !single || "KOREAN" in filters && !f.supports('안'.code))return@mapNotNull null
                buildJsonObject { put("fontId",id);put("fontRevision",fonts.revision(id));put("geometry",if(outline)"OUTLINE" else if(single)"SINGLE_LINE" else "INKSTITCH");fonts.inkRange(id,f)?.let { put("minHeightMm",it.start);put("maxHeightMm",it.endInclusive) } }
            }
            return buildJsonObject { put("ok",true);put("fonts",JsonArray(rows)) }
        }
        if(name=="save_pes") return save(args)
        val result=controller.dispatch(name,args,confirmed=needs)
        return if(result.pesBytes!=null) {
            val id=UUID.randomUUID().toString();artifacts[id]=result.pesBytes!!.copyOf()
            JsonObject(result.output+("artifactId" to JsonPrimitive(id)))
        } else result.output
    }
    private fun save(a:JsonObject):JsonObject {
        if(a.getValue("destinationId").jsonPrimitive.content!="output")return error("INVALID_DESTINATION","Only the host-approved output directory is available")
        val name=a.getValue("name").jsonPrimitive.content
        if(name.isBlank() || Path.of(name).fileName.toString()!=name || !name.endsWith(".pes") || name.contains('/') || name.contains('\\'))return error("INVALID_FILENAME","Use a plain .pes filename")
        val bytes=artifacts[a.getValue("artifactId").jsonPrimitive.content] ?: return error("UNKNOWN_ARTIFACT","Prepare a checked PES first")
        val target=output.resolve(name)
        if(Files.isSymbolicLink(target))return error("INVALID_DESTINATION","Symlink targets are forbidden")
        val overwrite=a.getValue("overwrite").jsonPrimitive.boolean
        if(!overwrite && Files.exists(target))return error("ALREADY_EXISTS","Explicit overwrite and new confirmation are required")
        val temp=Files.createTempFile(output,".pes-",".tmp")
        try {
            Files.write(temp,bytes)
            if(overwrite) Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            else {
                // CREATE_NEW is enforced by a hard link: no check-then-move overwrite race.
                Files.createLink(target,temp);Files.delete(temp)
            }
        } finally { Files.deleteIfExists(temp) }
        return buildJsonObject { put("ok",true);put("path",target.toString());put("byteCount",bytes.size) }
    }
    private fun error(code:String,message:String)=buildJsonObject { put("ok",false);put("code",code);put("message",message) }
    fun rpc(request:JsonObject):JsonObject? {
        val method=request["method"] as? JsonPrimitive
        if(request["jsonrpc"]!=JsonPrimitive("2.0") || method==null || !method.isString)
            return protocolError(JsonNull,-32600,"Invalid Request")
        val id=request["id"] ?: return null
        if(id !is JsonPrimitive || (id!=JsonNull && !id.isString && id.doubleOrNull?.isFinite()!=true))
            return protocolError(JsonNull,-32600,"Invalid request id")
        fun response(result:JsonObject)=buildJsonObject { put("jsonrpc","2.0");put("id",id);put("result",result) }
        return try {
            when(request.getValue("method").jsonPrimitive.content) {
                "initialize" -> response(buildJsonObject { put("protocolVersion","2025-06-18");put("capabilities",buildJsonObject { put("tools",buildJsonObject { put("listChanged",false) }) });put("serverInfo",buildJsonObject { put("name","myembro");put("version","0.1.0") });put("instructions",instructions()) })
                "ping" -> response(buildJsonObject {})
                "tools/list" -> response(listTools())
                "tools/call" -> {
                    val params=request.getValue("params").jsonObject
                    val result=call(params.getValue("name").jsonPrimitive.content,params["arguments"]?.jsonObject ?: buildJsonObject {},params["_meta"]?.jsonObject?.get("myembro.confirmToken")?.jsonPrimitive?.content)
                    response(buildJsonObject { put("content",buildJsonArray { add(buildJsonObject { put("type","text");put("text",result.toString()) }) });put("isError",result["ok"]!=JsonPrimitive(true)) })
                }
                else -> buildJsonObject { put("jsonrpc","2.0");put("id",id);put("error",buildJsonObject { put("code",-32601);put("message","Method not found") }) }
            }
        } catch(e:Exception) { response(buildJsonObject { put("isError",true);put("content",buildJsonArray { add(buildJsonObject { put("type","text");put("text",error("REQUEST_FAILED",e.message ?: "Request failed").toString()) }) }) }) }
    }
}
internal fun protocolError(id:JsonElement,code:Int,message:String)=buildJsonObject {
    put("jsonrpc","2.0");put("id",id);put("error",buildJsonObject { put("code",code);put("message",message) })
}
fun main(args:Array<String>) {
    require(args.size==1) { "Usage: myembro-mcp APPROVED_OUTPUT_DIRECTORY" }
    val dir=Path.of(args.single()).toAbsolutePath().normalize();Files.createDirectories(dir)
    val host=McpHost(dir.toRealPath())
    System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        try { host.rpc(Json.parseToJsonElement(line).jsonObject)?.let { println(it);System.out.flush() } }
        catch(e:Exception) { println(protocolError(JsonNull,-32700,"Parse error"));System.out.flush() }
    }
}
