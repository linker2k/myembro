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
    fun run(input: JsonObject, dispatcher: ToolDispatcher = ToolDispatcher(), allowRemoval: Boolean = false): Outcome {
        require(input.keys == setOf("initialProject", "calls", "export")) { "Expected initialProject, calls, export" }
        val initial = input.getValue("initialProject").jsonObject
        var project = if ("builtinDesign" in initial) {
            require(initial.keys == setOf("builtinDesign", "objectId", "frameId"))
            builtin(initial.string("builtinDesign"), initial.string("objectId"), initial.string("frameId"))
        } else ProjectJson.decode(initial.toString())
        var revision = 0L
        var successful = true
        val results = mutableListOf<JsonObject>()
        input.getValue("calls").jsonArray.forEach { call ->
            val c = call.jsonObject
            require(c.keys == setOf("name", "arguments")) { "Call must have name and arguments only" }
            val a = resolveRevision(c.getValue("arguments"), revision.toString())
            val result = dispatcher.dispatch(project, revision.toString(), c.string("name"), a,
                confirmed = allowRemoval && c.string("name") == "remove_objects")
            results += result.output
            successful = successful && result.ok
            if (result.ok && result.project != project) { project = result.project; revision++ }
        }
        val settings = input.getValue("export").jsonObject
        require(settings.keys == setOf("machineId", "frameId", "pesVersion"))
        val final = dispatcher.dispatch(project, revision.toString(), "prepare_pes", buildJsonObject {
            put("projectRevision", revision.toString()); settings.forEach { (k, v) -> put(k, v) }
        })
        successful = successful && final.ok && final.pesBytes != null
        return Outcome(buildJsonObject {
            put("calls", JsonArray(results)); put("finalProject", ToolDispatcher.summary(project, revision.toString()))
            put("export", final.output); put("success", successful)
        }, if (successful) final.pesBytes else null, successful)
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
        require(args.size == 2 && args.all { it.isNotBlank() }) { "Usage: AgentScenarioCli scenario.json NEW-output-directory" }
        val input = Json.parseToJsonElement(Files.readString(Path.of(args[0]))).jsonObject
        // Fresh directory prevents stale successful PES from masquerading as this run's result.
        val output = Files.createDirectory(Path.of(args[1]))
        val result = AgentScenario.run(input)
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
