// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.inkstitch.svg.SvgPictureInput
import kotlinx.serialization.json.*

/**
 * import_picture's input boundary: format, size and content are checked before anything is decoded or saved.
 * SVG keeps the photo screen's own limits (SvgPictureInput, docs/agent/svg-picture.md); PNG/JPEG must start
 * with their own signature. Limits and their basis: docs/agent/relay-picture.md.
 */
object RelayPicture {
    /**
     * Base64 characters of a PNG/JPEG. The whole relay→app call message must stay within 1 MiB (1,048,576
     * bytes): Cloudflare documents 32 MiB only for messages a Worker/Durable Object receives, nothing for
     * the ones it sends, and 1 MiB was the documented WebSocket limit before 2025-10-31. 48,576 bytes are
     * left for the JSON envelope (id, tool, title). relay/src/broker.ts checks the same message limit.
     */
    const val MAX_BASE64_CHARS = 1_000_000
    /** Thread colors for PNG/JPEG when colors is left out: the photo screen's own default (PhotoViewModel.begin). */
    const val DEFAULT_COLORS = 4
    /** The photo screen's first SVG width (EmojiViewModel.WIDTH_MM), clamped to the hoop by the engine. */
    const val SVG_WIDTH_MM = 50.0
    /** Left out: the layer is called "AI picture n" ([name]). */
    const val DEFAULT_TITLE = ""
    const val MAX_TITLE = 60
    const val TOO_LARGE = "PICTURE_TOO_LARGE"
    /** Shown to the chatbot when a picture cannot come over the connection. */
    const val SHARE_INSTEAD = "Too large for the AI connection. Ask the user to save the picture and share it to myembro " +
        "(Share → myembro) instead; in Korean: '그림을 저장한 뒤 공유 → myembro로 보내 주세요'."

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    val DESCRIPTION = "Open an AI-made picture in myembro's picture input screen. The user must confirm there before a ShapeObject layer is added; this call never changes the design. SVG is preferred: send SVG text in data (flat solid fills, no embedded text/images). Raster PNG, JPEG, BMP, WebP, GIF, HEIF or AVIF must be small base64 (at most $MAX_BASE64_CHARS characters). colors 2-6 (default $DEFAULT_COLORS) is the initial color-mode choice. Only the latest pending picture is kept in memory. If too large, ask the user to share the picture to myembro."

    fun opened(format:String,title:String)=buildJsonObject {
        put("ok",true);put("status","awaiting_user_confirmation");put("added",false)
        put("message","Opened in picture input; added only after user confirmation.")
        put("format",format);put("title",title)
    }

    /** The strict definition (RelayPhotoTools validates calls against it); the relay offers it with [DESCRIPTION],
     * colors and title optional (RelayCatalog fills [DEFAULT_COLORS] and [DEFAULT_TITLE]). */
    val DEFINITION: JsonObject = Json.parseToJsonElement("""
        {"name":"import_picture","description":"Open a picture in the picture input screen; add only after user confirmation.",
         "inputSchema":{"type":"object","properties":{"format":{"type":"string","enum":["svg","png","jpeg","bmp","webp","gif","heif","avif"]},"data":{"type":"string"},
           "colors":{"type":"integer","minimum":2,"maximum":6},"title":{"type":"string"}},
           "required":["format","data","colors","title"],"additionalProperties":false},
         "annotations":{"readOnly":false,"needsUserConfirmation":false}}""").jsonObject

    /**
     * The layer name: [title], or the first "AI picture n" ([numbered]) no layer uses yet, then " · " and the calling AI's
     * name when the relay sent one ([client]).
     */
    fun name(title: String, client: String?, names: Collection<String>, numbered: (Int) -> String): String {
        val base = title.trim().take(MAX_TITLE).ifEmpty {
            generateSequence(1) { it + 1 }.map(numbered).first { n -> names.none { it == n || it.startsWith("$n · ") } }
        }
        return if (client == null) base else "$base · $client"
    }

    sealed interface Checked {
        class Accepted(val format: String, val bytes: ByteArray, val colors: Int) : Checked
        class Rejected(val code: String, val message: String) : Checked
    }

    /** [args] already passed the strict schema. Nothing is decoded beyond base64 here. */
    fun check(args: JsonObject): Checked {
        val format = args.getValue("format").jsonPrimitive.content
        val data = args.getValue("data").jsonPrimitive.content
        val colors = args["colors"]?.jsonPrimitive?.int ?: DEFAULT_COLORS
        if (format == "svg") {
            val bytes = data.toByteArray(Charsets.UTF_8)
            if (bytes.size > SvgPictureInput.MAX_BYTES) return Checked.Rejected(TOO_LARGE,
                "The SVG is ${bytes.size} bytes; at most ${SvgPictureInput.MAX_BYTES} are accepted. Simplify it (fewer shapes and points) or send a small PNG.")
            if (!data.trimStart().let { it.startsWith("<svg") || it.startsWith("<?xml") }) return Checked.Rejected("INVALID_PICTURE",
                "data must be the SVG text itself, starting with <svg.")
            return Checked.Accepted(format, bytes, colors)
        }
        if (data.length > MAX_BASE64_CHARS) return Checked.Rejected(TOO_LARGE,
            "The picture is ${data.length} base64 characters; at most $MAX_BASE64_CHARS fit. Send a smaller PNG or an SVG. $SHARE_INSTEAD")
        val bytes = try { java.util.Base64.getDecoder().decode(data.substringAfter("base64,")) }
            catch (_: IllegalArgumentException) { return Checked.Rejected("INVALID_PICTURE", "data is not valid base64.") }
        val kind=io.github.linker2k.myembro.ui.photo.PictureIntake.sniff(bytes)
        if(kind.name.lowercase(java.util.Locale.ROOT)!=format)
            return Checked.Rejected("INVALID_PICTURE", "data is not a $format file (its first bytes do not match).")
        return Checked.Accepted(format, bytes, colors)
    }
}
