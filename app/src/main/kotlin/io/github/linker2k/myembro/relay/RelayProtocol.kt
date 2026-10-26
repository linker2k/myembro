// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.agent.ToolSchema
import kotlinx.serialization.json.*
import java.net.URI

/**
 * App side of the relay contract (v0 2026-10-26, edit phase /tmp/myembro-relay-edit-plan.md): the app
 * connects out to `<relay>/app` with `Authorization: Bearer <APP_KEY>` (contract v1), says hello with its tool definitions, answers each call
 * with one result. Only tools the hello offered are run; every other tool name is refused with isError.
 */
object RelayProtocol {
    const val GET_PROJECT = "get_project"
    const val RENDER_PREVIEW = "render_preview"
    /** Reads that need the editor's own kernel (its fonts, designs and frames). */
    val EDITOR_READS = listOf("list_fonts", "list_builtin_designs", "get_settings")
    /** Relay answers stay small: the chatbot app fails or times out on long tool results (291,209 chars seen). */
    const val MAX_RESULT_CHARS = 8_000
    const val MAX_FONT_LIST_CHARS = 2_000
    /**
     * Every Ask AI script tool except the photo pair, which RelayPhotoTools.kt registers. Each call is one
     * undoable turn; save, share, USB, file deletion, restore and settings are never offered.
     */
    val EDITS = io.github.linker2k.myembro.ui.agent.AgentTools.NAMES.toList() - "add_generated_art"
    const val REMOVE = "remove_objects"
    /** Added to remove_objects' description so the chatbot tells the user before it waits. */
    const val REMOVE_ON_PHONE = "The phone asks the user first (up to 90 s); tell them: ${RelayConfirmation.ON_PHONE}"
    /** Contract: ping/pong every 30 s, the relay drops the app after 90 s of silence. */
    const val PING_MS = 30_000L
    const val SILENCE_MS = 90_000L
    /** The relay answers ping with pong at once; no answer in 10 s (the reconnect target) means a dead socket. */
    const val PONG_TIMEOUT_MS = 10_000L
    val PING = buildJsonObject { put("type", "ping") }.toString()
    val PONG = buildJsonObject { put("type", "pong") }.toString()

    private val catalog: List<JsonObject> by lazy {
        val text = requireNotNull(ToolSchema::class.java.getResourceAsStream("/agent/tools.json")) { "Missing bundled tools.json" }
            .bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("tools").jsonArray.map { it.jsonObject }
    }

    /**
     * [names] as the relay offers them: the bundled docs/agent/tools.json tools projected by [RelayCatalog.project]
     * (short description, optional arguments, no transactionId), with annotations in MCP's standard names.
     */
    fun definitions(names: List<String>): List<JsonObject> =
        offer(names.map { name -> catalog.single { it.getValue("name").jsonPrimitive.content == name } })

    /** Any tools.json-shaped definitions (also RelayPhotoTools') projected for the relay. */
    fun offer(tools: List<JsonObject>): List<JsonObject> = tools.map { RelayCatalog.project(it) }.map { tool ->
        val description = tool.getValue("description").jsonPrimitive.content +
            (if (tool.getValue("name").jsonPrimitive.content == REMOVE) " $REMOVE_ON_PHONE" else "")
        JsonObject(tool + ("description" to JsonPrimitive(description)) +
            ("annotations" to mcpAnnotations(tool.getValue("annotations").jsonObject)))
    }

    /**
     * MCP ToolAnnotations from the catalog's own: readOnly → readOnlyHint; a write is destructive when it
     * needs the user's confirmation; reads are idempotent; no tool reaches outside the open project, so
     * openWorldHint is false. needsUserConfirmation and mapsTo have no MCP counterpart and are dropped.
     */
    fun mcpAnnotations(catalog: JsonObject): JsonObject {
        val readOnly = catalog["readOnly"]?.jsonPrimitive?.booleanOrNull == true
        return buildJsonObject {
            put("readOnlyHint", readOnly)
            if (!readOnly) put("destructiveHint", catalog["needsUserConfirmation"]?.jsonPrimitive?.booleanOrNull == true)
            put("idempotentHint", readOnly)
            put("openWorldHint", false)
        }
    }

    fun hello(appVersion: String, tools: List<JsonObject>): String = buildJsonObject {
        put("type", "hello"); put("appVersion", appVersion)
        put("tools", JsonArray(tools))
    }.toString()

    fun result(id: String, result: RelayResult): String = buildJsonObject {
        put("type", "result"); put("id", id); put("isError", result.isError); put("content", JsonArray(result.content))
    }.toString()

    /** The reply to one relay message, or null when none is due (pong, unknown or malformed messages). */
    suspend fun respond(message: String, tools: RelayTools): String? {
        val m = runCatching { Json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
        return when (m["type"]?.jsonPrimitive?.contentOrNull) {
            "ping" -> PONG
            "call" -> {
                val id = m["id"]?.jsonPrimitive?.contentOrNull ?: return null
                val tool = m["tool"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val args = m["args"] ?: JsonObject(emptyMap())
                val caller = RelayCaller.of(m["client"]?.jsonPrimitive?.contentOrNull)
                result(id, when {
                    tool !in tools.names -> RelayResult.error("Tool $tool is not available over the myembro AI connection.")
                    args !is JsonObject -> RelayResult.error("Arguments must be a JSON object.")
                    else -> runCatching { kotlinx.coroutines.withContext(caller) { tools.call(tool, args) } }
                        .getOrElse { RelayResult.error("myembro could not run $tool.") }
                })
            }
            else -> null
        }
    }

    /**
     * The WebSocket URL for a relay address the user typed (`wss://host`, `https://host`, or with `/app`),
     * or null when the address or the key cannot be used. The key goes in the Authorization header
     * ([authorization]), never in the URL. Plain ws:// is only for the emulator's host (10.0.2.2) and this
     * device, so the key never travels in clear.
     */
    fun appUrl(address: String, key: String): String? {
        if (key.isBlank()) return null
        val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return null
        val scheme = when (uri.scheme?.lowercase()) { "wss", "https" -> "wss"; "ws", "http" -> "ws"; else -> return null }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (scheme == "ws" && host !in CLEARTEXT_HOSTS) return null
        val path = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/app")
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://$host$port$path/app"
    }
    fun authorization(key: String) = "Bearer ${key.trim()}"
    /** Must match res/xml/network_security_config.xml. */
    val CLEARTEXT_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")
}

data class RelayResult(val isError: Boolean, val content: List<JsonObject>) {
    companion object {
        fun text(text: String, isError: Boolean = false) = RelayResult(isError, listOf(buildJsonObject { put("type", "text"); put("text", text) }))
        fun error(text: String) = text(text, isError = true)
    }
}

/** The tools the hello offers and runs; [RelayProtocol.respond] refuses every other name before [call]. */
/** The calling AI's name (MCP clientInfo.name) the relay sent with a call, for naming what that call adds. */
class RelayCaller private constructor(val name: String?) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RelayCaller> {
        /** One line of at most 40 characters, as the relay sends it; null when unknown. */
        fun of(name: String?) = RelayCaller(name?.replace(Regex("\\p{Cntrl}"), " ")?.trim()?.take(40)?.trim()?.ifEmpty { null })
    }
}

interface RelayTools {
    val definitions: List<JsonObject>
    val names: Set<String> get() = definitions.mapTo(mutableSetOf()) { it.getValue("name").jsonPrimitive.content }
    suspend fun call(tool: String, args: JsonObject): RelayResult
}
