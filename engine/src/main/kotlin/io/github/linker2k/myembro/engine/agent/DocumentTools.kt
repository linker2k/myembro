// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.ProjectJson
import io.github.linker2k.myembro.engine.edit.commands.EditSession
import kotlinx.serialization.json.*
import java.util.UUID

/** Session-owned artifacts and confirmations. No model can register bytes as checked PES. */
internal class DocumentTools(private val session:EditSession,private val store:DocumentStore?) {
    private data class Artifact(val bytes:ByteArray,val revision:Long,val generated:Boolean)
    private data class Consent(val tool:String,val arguments:JsonObject,val revision:Long,val digest:String,val targetVersion:String)
    private val artifacts=mutableMapOf<String,Artifact>()
    private val consents=mutableMapOf<String,Consent>()
    fun publish(result:ToolDispatcher.Result):ToolDispatcher.Result {
        if(!result.ok || result.pesBytes==null)return result
        val id=UUID.randomUUID().toString();artifacts[id]=Artifact(result.pesBytes.copyOf(),session.revision,result.generatedPes)
        return result.copy(output=JsonObject(result.output+("artifactId" to JsonPrimitive(id))))
    }
    fun save(name:String,a:JsonObject,token:String?):ToolDispatcher.Result {
        fun reply(output:JsonObject)=ToolDispatcher.Result(session.project,output)
        fun error(code:String,message:String)=reply(buildJsonObject { put("ok",false);put("code",code);put("message",message) })
        val storage=store ?: return error("DOCUMENT_STORE_UNAVAILABLE","Host has not configured approved storage")
        return try {
            val revision=session.revision
            val kind=if(name=="save_project") DocumentKind.PROJECT else DocumentKind.PES
            var generated=false
            val bytes=if(kind==DocumentKind.PROJECT) {
                if(a.string("projectRevision")!=revision.toString())return error("STALE_REVISION","Read current project revision")
                ProjectJson.encode(session.project).toByteArray(Charsets.UTF_8)
            } else {
                val artifact=artifacts[a.string("artifactId")] ?: return error("INVALID_ARTIFACT","A complete PASS prepare_pes artifact is required")
                if(artifact.revision!=revision)return error("STALE_ARTIFACT","Prepare PES again after edits")
                generated=artifact.generated
                artifact.bytes.copyOf()
            }
            val destination=a.string("destinationId");val leaf=documentFileName(a.string("name"),kind)
            val arguments=JsonObject(a+mapOf("name" to JsonPrimitive(leaf),"overwrite" to JsonPrimitive(true)))
            val existing=storage.inspect(destination,leaf,kind)
            val overwrite=a.getValue("overwrite").jsonPrimitive.boolean
            val digest=documentDigest(bytes)
            if(token!=null) {
                val consent=consents.remove(token) ?: return error("INVALID_CONFIRMATION","Unknown or consumed token")
                if(!overwrite || existing==null || consent!=Consent(name,arguments,revision,digest,existing.version))
                    return error("INVALID_CONFIRMATION","Arguments, project, payload or target changed")
            } else if(existing!=null) {
                val id=UUID.randomUUID().toString();consents[id]=Consent(name,arguments,revision,digest,existing.version)
                return reply(buildJsonObject { put("ok",false);put("code","CONFIRMATION_REQUIRED");put("needsUserConfirmation",true);put("confirmToken",id);put("retryArguments",arguments);put("message","Confirm replacement of the existing document before retrying") })
            }
            val saved=storage.write(destination,leaf,kind,bytes,existing?.version)
            if(generated)storage.recordGeneratedPes(bytes)
            reply(buildJsonObject { put("ok",true);put("name",saved.name);put("location",saved.location);put("version",saved.version);put("byteCount",bytes.size);put("overwritten",existing!=null) })
        } catch(e:DocumentConflict) { error("DOCUMENT_CONFLICT",e.message ?: "Document changed") }
        catch(e:Exception) { error("DOCUMENT_SAVE_FAILED",e.message ?: "Could not save document") }
    }
}
