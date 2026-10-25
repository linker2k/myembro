// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesReader
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/** Local trusted scenario configuration, not a model-call input. No arbitrary source resolver. */
object AgentScenario {
    val frames = mapOf("H100" to HoopSpec.H100, "H130x180" to HoopSpec.H130x180, "H160x260" to HoopSpec.H160x260)
    fun builtin(name: String, id: String, frame: String): Project {
        require(name in setOf("sample_heart_running.pes", "myembro_logo.pes")) { "Unknown builtin" }
        require(id.isNotBlank())
        val bytes = requireNotNull(javaClass.getResourceAsStream("/agent/builtins/$name")).use { it.readBytes() }
        return Project(frames.getValue(frame), listOf(StitchObject(id, name, PesReader.read(bytes))))
    }
    data class Outcome(val output: JsonObject, val pesBytes: ByteArray?, val success: Boolean)
    fun run(input: JsonObject, dispatcher: ToolDispatcher = ToolDispatcher(), approvals: List<ScriptApproval> = emptyList(),
        dryRun: Boolean = false): Outcome {
        require(input.keys == setOf("initialProject", "calls", "export") || input.keys == setOf("initialProject", "calls", "export", "transactionId")) { "Expected initialProject, calls, export and optional transactionId" }
        val initial = input.getValue("initialProject").jsonObject
        val project = if ("builtinDesign" in initial) {
            require(initial.keys == setOf("builtinDesign", "objectId", "frameId"))
            builtin(initial.string("builtinDesign"), initial.string("objectId"), initial.string("frameId"))
        } else ProjectJson.decode(initial.toString())
        val session=io.github.linker2k.myembro.engine.edit.commands.EditSession(project)
        val transactionId=input["transactionId"]?.jsonPrimitive?.content ?: "scenario"
        val calls=input.getValue("calls").jsonArray.map { call ->
            val c=call.jsonObject
            require(c.keys == setOf("name", "arguments")) { "Call must have name and arguments only" }
            ToolTurnController.Call(c.string("name"),resolveRevision(c.getValue("arguments"),session.revision.toString()))
        }
        val result=dispatcher.bind(session).executeScript(transactionId,session.revision,calls,input.getValue("export").jsonObject,dryRun,approvals)
        val snapshot=session.snapshot()
        return Outcome(buildJsonObject {
            result.output.forEach { (key,value)->put(key,value) }
            put("finalProject",ToolDispatcher.summary(snapshot.project,snapshot.revision.toString()))
            put("history",buildJsonObject { put("undoCount",snapshot.undoCount);put("redoCount",snapshot.redoCount) })
            put("export",result.output["sewout"] ?: result.output["details"] ?: JsonNull)
            put("success",result.ok)
        },if(result.ok && !dryRun) result.pesBytes else null,result.ok)
    }
    private fun resolveRevision(v: JsonElement, revision: String): JsonElement = when(v) {
        is JsonObject -> JsonObject(v.mapValues { (key, value) ->
            if (key in setOf("projectRevision", "expectedRevision") && value == JsonPrimitive("$" + "revision")) JsonPrimitive(revision) else resolveRevision(value, revision)
        })
        is JsonArray -> JsonArray(v.map { resolveRevision(it, revision) })
        else -> v
    }
}
object AgentScenarioCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..3 && args.all { it.isNotBlank() } && (args.size==2 || args[2]=="--dry-run")) { "Usage: AgentScenarioCli scenario.json NEW-output-directory [--dry-run]" }
        val input = Json.parseToJsonElement(Files.readString(Path.of(args[0]))).jsonObject
        // Fresh directory prevents stale successful PES from masquerading as this run's result.
        val output = Files.createDirectory(Path.of(args[1]))
        val result = AgentScenario.run(input,dryRun=args.size==3)
        Files.writeString(output.resolve("results.json"), Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), result.output), StandardOpenOption.CREATE_NEW)
        result.pesBytes?.let { bytes ->
            val temp=Files.createTempFile(output,".pes-",".tmp")
            try {
                Files.write(temp,bytes)
                Files.move(temp,output.resolve("output.pes"),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(temp) }
        }
        println("AGENT_SCENARIO ${if(result.success) "PASS" else "BLOCK"}; PES=${result.pesBytes != null}; results=${output.resolve("results.json")}")
        if (!result.success) exitProcess(1)
    }
}
