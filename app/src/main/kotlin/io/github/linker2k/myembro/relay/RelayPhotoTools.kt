// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import io.github.linker2k.myembro.engine.agent.*
import io.github.linker2k.myembro.ui.photo.AndroidAttachmentStore
import io.github.linker2k.myembro.ui.photo.decodeUprightPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.roundToInt

/** Session-local, explicit photo capabilities. Never enumerate the gallery, resolve remote IDs as
 * paths/URIs, or reuse Ask AI's old pending photos. Only the trusted picker may grant consent.
 * Pass this SAME AttachmentStore to the editor's existing dispatcher when constructing its kernel. */
class RelayPhotoAttachments private constructor(
    private val rasterize: (String, PhotoRasterRequest) -> PhotoRaster?,
    private val preview: (String, Int) -> JsonObject,
) : AttachmentStore {
    private val sources = linkedMapOf<String, String>()

    constructor(context: Context) : this(
        { uri, request -> AndroidAttachmentStore(context.applicationContext) { uri }.raster("picked", request) },
        { uri, edge -> encodePreview(context.applicationContext, uri, edge) },
    )

    /** Trusted app UI only. A picked URI alone is insufficient: false issues no capability. */
    @Synchronized fun handoff(uri: String, consentGiven: Boolean): String? {
        if (!consentGiven) return null
        require(Uri.parse(uri).scheme == "content") { "Use the app photo picker" }
        return UUID.randomUUID().toString().also { sources[it] = uri }
    }
    @Synchronized fun revoke(id: String) { sources.remove(id) }
    @Synchronized fun clear() { sources.clear() }
    @Synchronized fun ids(): List<String> = sources.keys.toList()
    @Synchronized fun contains(id: String) = id in sources
    @Synchronized override fun raster(attachmentId: String, request: PhotoRasterRequest): PhotoRaster? =
        sources[attachmentId]?.let { rasterize(it, request) }

    @Synchronized internal fun view(id: String, edge: Int): JsonObject? {
        require(edge in 1..768) { "maxEdgePx must be 1..768" }
        return sources[id]?.let { preview(it, edge) }
    }

    companion object {
        private fun encodePreview(context: Context, source: String, edge: Int): JsonObject {
            // Existing bounded, EXIF-aware photo-screen decoder; no crop or enlargement.
            val original = decodeUprightPhoto(context.contentResolver, Uri.parse(source))
            var scaled: Bitmap? = null
            try {
                val ratio = minOf(1.0, edge.toDouble() / maxOf(original.width, original.height))
                val image = if (ratio == 1.0) original else Bitmap.createScaledBitmap(original,
                    (original.width * ratio).roundToInt().coerceAtLeast(1),
                    (original.height * ratio).roundToInt().coerceAtLeast(1), true).also { scaled = it }
                val bytes = ByteArrayOutputStream().use { stream ->
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray()
                }
                return buildJsonObject {
                    put("type", "image"); put("mimeType", "image/png")
                    put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            } finally { scaled?.recycle(); original.recycle() }
        }
    }
}

/** Independent registry extension: not advertised or connected to RelayProtocol until integration.
 * Supply the SAME controller/session/kernel used by Ask AI, with [photos] as its AttachmentStore.
 * [publish] must use the editor's existing editing lock and refresh path, exactly like Ask AI Apply.
 * No second session, stitch generator, transaction logic or file export is introduced here. */
class RelayPhotoTools(
    private val photos: RelayPhotoAttachments,
    private val controller: ToolTurnController,
    private val exportSettings: () -> JsonObject,
    private val publish: suspend (suspend () -> ToolDispatcher.Result) -> ToolDispatcher.Result,
    private val canEdit: () -> Boolean,
) : RelayTools {
    override val definitions: List<JsonObject> get() = Companion.definitions
    private val mutex = Mutex()
    private val prepared = mutableMapOf<String, String>()
    private val schema = ToolSchema(buildJsonObject { put("tools", JsonArray(definitions)) }.toString())

    override suspend fun call(tool: String, args: JsonObject): RelayResult = mutex.withLock {
        if (tool !in NAMES) return@withLock error("UNKNOWN_TOOL")
        schema.validate(tool, args)?.let { return@withLock error("INVALID_ARGUMENTS", it) }
        withContext(Dispatchers.Default) {
            when (tool) {
                "list_attachments" -> text(buildJsonObject {
                    put("ok", true); put("attachments", JsonArray(photos.ids().map { id ->
                        buildJsonObject { put("attachmentId", id) }
                    }))
                })
                "view_attachment" -> {
                    val id = args.getValue("attachmentId").jsonPrimitive.content
                    val edge = args.getValue("maxEdgePx").jsonPrimitive.double.toInt()
                    try { photos.view(id, edge)?.let { RelayResult(false, listOf(it)) } ?: error("ATTACHMENT_NOT_FOUND") }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error("ATTACHMENT_READ_FAILED") }
                }
                "prepare_photo" -> {
                    if (!canEdit()) return@withContext error("OPEN_EDITOR", "Open the editor to prepare a photo.")
                    val id = args.getValue("attachmentId").jsonPrimitive.content
                    if (!photos.contains(id)) return@withContext error("ATTACHMENT_NOT_FOUND")
                    val result = controller.dispatch(tool, args)
                    if (result.ok) {
                        if (!photos.contains(id)) return@withContext error("ATTACHMENT_NOT_FOUND")
                        prepared[result.output.getValue("resultId").jsonPrimitive.content] = id
                    }
                    text(result.output, !result.ok)
                }
                else -> {
                    if (!canEdit()) return@withContext error("OPEN_EDITOR", "Open the editor to add a design.")
                    val resultId = args.getValue("resultId").jsonPrimitive.content
                    val attachmentId = prepared[resultId] ?: return@withContext error("RESULT_NOT_FOUND")
                    if (!photos.contains(attachmentId)) return@withContext error("ATTACHMENT_NOT_FOUND")
                    val result = publish {
                        // Check again inside the editor lock, including revocation during preparation.
                        if (!canEdit() || !photos.contains(attachmentId)) {
                            ToolDispatcher.Result(controller.session.project, buildJsonObject {
                                put("ok", false); put("code", "ATTACHMENT_NOT_FOUND")
                            })
                        } else controller.executeScript(args.getValue("transactionId").jsonPrimitive.content,
                            controller.session.revision, listOf(ToolTurnController.Call(tool, args)), exportSettings())
                    }
                    val check = result.output["sewout"] as? JsonObject
                    text(buildJsonObject {
                        result.output.forEach { (k, v) -> put(k, v) }
                        put("revision", controller.session.revision.toString())
                        put("project", ToolDispatcher.summary(controller.session.project, controller.session.revision.toString()))
                        put("sewout", buildJsonObject {
                            put("verdict", if (check?.get("ok") == JsonPrimitive(true)) "PASS" else "BLOCK")
                            put("findings", JsonArray(collectFindings(check)))
                            put("details", check ?: JsonNull)
                        })
                    }, !result.ok)
                }
            }
        }
    }

    companion object {
        val NAMES = setOf("list_attachments", "view_attachment", "prepare_photo", "add_generated_art")
        /** Registry hook can append these MCP definitions without changing the engine catalog. */
        val definitions: List<JsonObject> by lazy {
            val catalog = checkNotNull(ToolSchema::class.java.getResourceAsStream("/agent/tools.json"))
                .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("tools").jsonArray }
            val local = Json.parseToJsonElement("""[
              {"name":"list_attachments","description":"List only photos explicitly shared with AI in this app session.","inputSchema":{"type":"object","properties":{},"required":[],"additionalProperties":false},"annotations":{"readOnly":true,"needsUserConfirmation":false}},
              {"name":"view_attachment","description":"View a consented photo as PNG, preserving aspect ratio, without enlargement; at most 768 pixels on its long edge.","inputSchema":{"type":"object","properties":{"attachmentId":{"type":"string"},"maxEdgePx":{"type":"integer","minimum":1,"maximum":768}},"required":["attachmentId","maxEdgePx"],"additionalProperties":false},"annotations":{"readOnly":true,"needsUserConfirmation":false}}
            ]""").jsonArray
            (local + catalog.filter { it.jsonObject["name"]?.jsonPrimitive?.content in setOf("prepare_photo", "add_generated_art") })
                .map { it.jsonObject }
        }
        private fun text(json: JsonObject, failed: Boolean = false) = RelayResult.text(json.toString(), failed)
        private fun error(code: String, message: String = code) = text(buildJsonObject {
            put("ok", false); put("code", code); put("message", message)
        }, true)
        private fun collectFindings(value: JsonElement?): List<JsonElement> = when (value) {
            is JsonObject -> value.flatMap { (key, child) ->
                if (key == "findings" && child is JsonArray) child.toList() else collectFindings(child)
            }.distinct()
            is JsonArray -> value.flatMap(::collectFindings).distinct()
            else -> emptyList()
        }
    }
}
