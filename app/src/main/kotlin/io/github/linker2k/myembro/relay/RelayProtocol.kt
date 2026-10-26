// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.agent.ToolSchema
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder

/**
 * App side of the relay spike contract v0 (manager, 2026-10-26): the app connects out to
 * `<relay>/app?key=<APP_KEY>`, says hello with its tool definitions, answers each call with one result.
 * Only the read-only spike tools are offered or run; every other tool name is refused with isError.
 */
object RelayProtocol {
    const val GET_PROJECT = "get_project"
    const val RENDER_PREVIEW = "render_preview"
    /** The contract's spike tool set (read-only only). */
    val SPIKE_TOOLS = listOf(GET_PROJECT, RENDER_PREVIEW)
    /** Contract: ping/pong every 30 s, the relay drops the app after 90 s of silence. */
    const val PING_MS = 30_000L
    const val SILENCE_MS = 90_000L
    val PING = buildJsonObject { put("type", "ping") }.toString()
    val PONG = buildJsonObject { put("type", "pong") }.toString()

    /** get_project exactly as the bundled docs/agent/tools.json defines it. */
    val getProjectTool: JsonObject by lazy {
        val text = requireNotNull(ToolSchema::class.java.getResourceAsStream("/agent/tools.json")) { "Missing bundled tools.json" }
            .bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("tools").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == GET_PROJECT }
    }

    /** Defined by the contract; the tool itself is still being built (embro-editor), so the app answers it with a stub. */
    val renderPreviewTool: JsonObject = buildJsonObject {
        put("name", RENDER_PREVIEW)
        put("description", "Render the current project with its hoop as a PNG image.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("widthPx") { put("type", "integer"); put("minimum", 256); put("maximum", 1024) }
            }
            putJsonArray("required") { add("widthPx") }
            put("additionalProperties", false)
        }
        putJsonObject("annotations") { put("readOnly", true); put("needsUserConfirmation", false) }
    }

    fun hello(appVersion: String): String = buildJsonObject {
        put("type", "hello"); put("appVersion", appVersion)
        put("tools", JsonArray(listOf(getProjectTool, renderPreviewTool)))
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
                result(id, when {
                    tool !in SPIKE_TOOLS -> RelayResult.error("Tool $tool is not available over the myembro AI connection.")
                    args !is JsonObject -> RelayResult.error("Arguments must be a JSON object.")
                    else -> runCatching { tools.call(tool, args) }.getOrElse { RelayResult.error("myembro could not run $tool.") }
                })
            }
            else -> null
        }
    }

    /**
     * The WebSocket URL for a relay address the user typed (`wss://host`, `https://host`, or with `/app`).
     * Plain ws:// is only for the emulator's host (10.0.2.2) and this device, so the key never travels in clear.
     */
    fun appUrl(address: String, key: String): String? {
        if (key.isBlank()) return null
        val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return null
        val scheme = when (uri.scheme?.lowercase()) { "wss", "https" -> "wss"; "ws", "http" -> "ws"; else -> return null }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (scheme == "ws" && host !in CLEARTEXT_HOSTS) return null
        val path = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/app")
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://$host$port$path/app?key=${URLEncoder.encode(key.trim(), Charsets.UTF_8.name())}"
    }
    /** Must match res/xml/network_security_config.xml. */
    val CLEARTEXT_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")
}

data class RelayResult(val isError: Boolean, val content: List<JsonObject>) {
    companion object {
        fun text(text: String, isError: Boolean = false) = RelayResult(isError, listOf(buildJsonObject { put("type", "text"); put("text", text) }))
        fun error(text: String) = text(text, isError = true)
    }
}

/** Runs one allowed spike tool; [RelayProtocol.respond] refuses everything else before it gets here. */
fun interface RelayTools {
    suspend fun call(tool: String, args: JsonObject): RelayResult
}
