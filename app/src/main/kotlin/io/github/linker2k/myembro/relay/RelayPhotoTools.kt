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
    /** Where pictures an AI sends with import_picture are kept (app cache), null where there is none. */
    private val pictures: java.io.File?,
) : AttachmentStore {
    private val sources = linkedMapOf<String, String>()

    constructor(context: Context) : this(
        { uri, request -> AndroidAttachmentStore(context.applicationContext) { uri }.raster("picked", request) },
        { uri, edge -> encodePreview(context.applicationContext, uri, edge) },
        java.io.File(context.applicationContext.cacheDir, "ai_picture_imports"),
    )

    /** Trusted app UI only. A picked URI alone is insufficient: false issues no capability. */
    @Synchronized fun handoff(uri: String, consentGiven: Boolean): String? {
        if (!consentGiven) return null
        require(Uri.parse(uri).scheme == "content") { "Use the app photo picker" }
        return UUID.randomUUID().toString().also { sources[it] = uri }
    }
    /** A PNG/JPEG the connected AI sent with import_picture: saved in the app cache and listed like a handed-over photo. */
    @Synchronized internal fun importPicture(bytes: ByteArray, extension: String): String {
        val folder = checkNotNull(pictures) { "No picture folder" }.apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val file = java.io.File(folder, "$id.$extension").apply { writeBytes(bytes) }
        sources[id] = Uri.fromFile(file).toString()
        return id
    }
    @Synchronized fun revoke(id: String) { sources.remove(id)?.let(::deleteImported) }
    @Synchronized fun clear() { sources.values.forEach(::deleteImported); sources.clear() }
    private fun deleteImported(source: String) {
        val folder = pictures ?: return
        Uri.parse(source).takeIf { it.scheme == "file" }?.path?.let { java.io.File(it) }?.takeIf { it.parentFile == folder }?.delete()
    }
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
    /** Tells the user on screen that an AI picture came in (the editor's snackbar); the object's name. */
    private val announce: (String) -> Unit = {},
    /** Any other change this group made (add_generated_art). */
    private val changed: () -> Unit = {},
    /** "AI picture n" in the app's language (the editor's string resource). */
    private val numbered: (Int) -> String = { "AI picture $it" },
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
                IMPORT -> importPicture(args)
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
                    if (result.ok) changed()
                    // The same answer as every relay edit (RelayEdits): the full check details
                    // ran to ~187 KB for one photo, past the chatbot's tool-result limit (5558, 2026-10-26).
                    text(RelayEdits.result(result, controller.session.revision, controller.session.project), !result.ok)
                }
            }
        }
    }

    /**
     * import_picture: the picture is checked ([RelayPicture]), prepared off the turn like prepare_photo (PNG/JPEG:
     * saved as an attachment, the photo screen's color mode with N colors at its default width; SVG: the photo
     * screen's SVG path), then added by add_generated_art as one turn: one undo step, sewout-checked.
     */
    private suspend fun importPicture(args: JsonObject): RelayResult {
        if (!canEdit()) return error("OPEN_EDITOR", "Open the editor in myembro to add a picture.")
        val picture = when (val checked = RelayPicture.check(args)) {
            is RelayPicture.Checked.Rejected -> return error(checked.code, checked.message)
            is RelayPicture.Checked.Accepted -> checked
        }
        val title = RelayPicture.name(args["title"]?.jsonPrimitive?.content.orEmpty(),
            kotlin.coroutines.coroutineContext[RelayCaller]?.name, controller.session.project.objects.map { it.name }, numbered)
        val prepared = if (picture.format == "svg") controller.prepareSvgPicture(picture.bytes, RelayPicture.SVG_WIDTH_MM, title) else {
            val id = photos.importPicture(picture.bytes, picture.format)
            controller.dispatch("prepare_photo", buildJsonObject {
                put("attachmentId", id); put("mode", "color"); put("colors", picture.colors)
                put("widthMm", io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoPreparationPolicy.photoMaxWidthMm(controller.session.project.hoop))
                put("filter", "original")
            })
        }
        if (!prepared.ok) return text(prepared.output, true)
        val resultId = prepared.output.getValue("resultId").jsonPrimitive.content
        val result = publish {
            if (!canEdit()) ToolDispatcher.Result(controller.session.project, buildJsonObject {
                put("ok", false); put("code", "OPEN_EDITOR"); put("message", "Open the editor in myembro to add a picture.")
            }) else {
                val transactionId = "relay-${UUID.randomUUID()}"
                val objectId = RelayCatalog.newId(controller.session.project, "picture")
                controller.executeScript(transactionId, controller.session.revision, listOf(ToolTurnController.Call("add_generated_art",
                    buildJsonObject { put("transactionId", transactionId); put("resultId", resultId); put("objectId", objectId); put("name", title) })),
                    exportSettings())
            }
        }
        if (result.ok) announce(title)
        val answer = RelayEdits.result(result, controller.session.revision, controller.session.project)
        return text(JsonObject(answer + ("picture" to buildJsonObject {
            put("format", picture.format); put("bytes", picture.bytes.size)
            if (picture.format != "svg") put("colors", picture.colors)
            put("widthMm", prepared.output.getValue("widthMm")); put("heightMm", prepared.output.getValue("heightMm"))
        })), !result.ok)
    }

    companion object {
        const val IMPORT = "import_picture"
        val NAMES = setOf("list_attachments", "view_attachment", "prepare_photo", "add_generated_art", IMPORT)
        /** Registry hook can append these MCP definitions without changing the engine catalog. */
        val definitions: List<JsonObject> by lazy {
            val catalog = checkNotNull(ToolSchema::class.java.getResourceAsStream("/agent/tools.json"))
                .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("tools").jsonArray }
            val local = Json.parseToJsonElement("""[
              {"name":"list_attachments","description":"List only photos explicitly shared with AI in this app session.","inputSchema":{"type":"object","properties":{},"required":[],"additionalProperties":false},"annotations":{"readOnly":true,"needsUserConfirmation":false}},
              {"name":"view_attachment","description":"View a consented photo as PNG, preserving aspect ratio, without enlargement; at most 768 pixels on its long edge.","inputSchema":{"type":"object","properties":{"attachmentId":{"type":"string"},"maxEdgePx":{"type":"integer","minimum":1,"maximum":768}},"required":["attachmentId","maxEdgePx"],"additionalProperties":false},"annotations":{"readOnly":true,"needsUserConfirmation":false}}
            ]""").jsonArray
            (local + catalog.filter { it.jsonObject["name"]?.jsonPrimitive?.content in setOf("prepare_photo", "add_generated_art") })
                .map { it.jsonObject } + RelayPicture.DEFINITION
        }
        private fun text(json: JsonObject, failed: Boolean = false) = RelayResult.text(json.toString(), failed)
        private fun error(code: String, message: String = code) = text(buildJsonObject {
            put("ok", false); put("code", code); put("message", message)
        }, true)
    }
}
