// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import io.github.linker2k.myembro.engine.edit.DesignObject
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.model.Bounds
import java.util.Locale

/**
 * The 16 edit tools of docs/agent/tools.json (agent/tools 688ffd8) in the compact one-line form of
 * docs/agent/mobile-standalone.md: inputSchema without transactionId. The app owns the transaction.
 */
internal object AgentTools {
    val SIGNATURES = listOf(
        "add_design(assetId:str, objectId:str, name:str) - Add an approved design asset to the staged project.",
        "add_text(objectId:str, text:str, fontId:str, fontRevision:str, heightMm:num[..60], threadColor:str ^#[0-9A-Fa-f]{6}\$, singleLineBold:bool) - Prepare and add text with automatic stitch selection.",
        "replace_text(objectId:str, text:str, fontId:str, fontRevision:str, heightMm:num[..60], threadColor:str ^#[0-9A-Fa-f]{6}\$, singleLineBold:bool) - Regenerate an existing text object while retaining its identity and placement.",
        "set_object_transforms(transforms:[{objectId:str, xMm:num[-10000..10000], yMm:num[-10000..10000], rotationDeg:num, scaleXPercent:num[5..2000], scaleYPercent:num[5..2000]}]) - Set explicit transforms for staged objects.",
        "transform_objects(objectIds:[str], deltaXMm:num, deltaYMm:num, scaleFactor:num, rotationDeg:num) - Move, scale, and rotate a selection about its shared pivot.",
        "mirror_objects(objectIds:[str]) - Mirror objects horizontally using the existing editor behavior.",
        "center_objects(objectIds:[str]) - Center the selected objects as a group in the hoop.",
        "align_objects(objectIds:[str], alignment:LEFT|CENTER_X|RIGHT|TOP|CENTER_Y|BOTTOM) - Align selected objects by their actual placed bounds.",
        "distribute_objects(objectIds:[str], axis:HORIZONTAL|VERTICAL) - Distribute gaps between selected object bounds.",
        "duplicate_object(sourceId:str, newObjectId:str, transform:{xMm:num[-10000..10000], yMm:num[-10000..10000], rotationDeg:num, scaleXPercent:num[5..2000], scaleYPercent:num[5..2000]}) - Copy an object with an explicit new identity and final transform.",
        "reorder_object(objectId:str, index:int) - Set an object’s sewing-order index.",
        "set_object_visibility(objectId:str, visible:bool) - Show or hide an object in the staged project.",
        "recolor_object(objectId:str, threadIndex:int, threadColor:str ^#[0-9A-Fa-f]{6}\$) - Change one object thread without changing its stitches.",
        "remove_objects(objectIds:[str]) - Remove explicit objects from the staged project.",
        "set_project_frame(frameId:str) - Set the staged project’s configured hoop or frame.",
        "add_generated_art(resultId:str, objectId:str, name:str) - Add an approved photo or emoji result to the staged project.",
    )
    val NAMES: Set<String> = SIGNATURES.mapTo(linkedSetOf()) { it.substringBefore('(') }
    private val schema by lazy { io.github.linker2k.myembro.engine.agent.ToolSchema.bundled() }
    /** Settings changes are not undoable project edits either, though tools.json does not mark them. */
    private val SETTINGS = setOf("set_settings")

    /**
     * Tools a script may name but Apply never runs: file deletion, transfer, overwrite, restore and
     * settings changes (every tools.json needsUserConfirmation tool except undoable remove_objects).
     * They are shown as "do this separately in the app" and make the whole script unappliable.
     */
    fun separate(tool: String): Boolean =
        tool in SETTINGS || (tool != "remove_objects" && schema.contains(tool) && schema.confirmation(tool))

    /** prepare_photo of docs/agent/tools.json, listed only when a photo is attached. Not an edit tool: no transaction. */
    const val PHOTO_SIGNATURE = "prepare_photo(attachmentId:str, frameId:str, style:SILHOUETTE|OUTLINE|COLOR, detail:0|1|2, " +
        "colorCount:int[2..6], grayscale:bool, widthMm:num, crop:{zoom:num[1..4], panX:num[-1..1], panY:num[-1..1]}) - " +
        "Prepare an app-selected photo using only its attachmentId; add the result with add_generated_art."
}

/**
 * A photo the user picked in the app. Only [id] and the pixel size ever leave the app; the image and
 * its location stay on the phone (docs/agent/mobile-standalone.md).
 */
internal data class AgentAttachment(val id: String, val widthPx: Int, val heightPx: Int)

/** Short names the chatbot uses instead of UUIDs, font ids and asset ids; the app expands them back. */
internal data class AgentHandles(val objects: Map<String, String>, val fonts: Map<String, String>,
    val designs: Map<String, String> = emptyMap(),
    /** Attached photo ids (a1, a2, …) to the app's own attachment ids; the same today. */
    val attachments: Map<String, String> = emptyMap())

/** A font the lettering screen can use now; [minMm]/[maxMm] come from font metadata or pinned sweeps, capped at 60mm. */
internal data class AgentFontInfo(val id: String, val name: String, val hangul: Boolean, val latin: Boolean,
    val minMm: Double?, val maxMm: Double)

/** A built-in design (assets/samples/manifest.json). */
internal data class AgentDesignInfo(val id: String, val name: String, val widthMm: Double, val heightMm: Double)

internal data class AgentObjectSummary(
    val handle: String, val kind: String, val name: String, val text: String?, val font: String?, val textHeightMm: Double?,
    val bounds: Bounds?, val rotationDeg: Double, val scaleXPercent: Double, val scaleYPercent: Double,
    val colors: List<String>, val visible: Boolean,
)

internal data class AgentProjectSummary(
    val hoopName: String, val hoopWidthMm: Double, val hoopHeightMm: Double,
    val objects: List<AgentObjectSummary>, val handles: AgentHandles,
    /** By handle, in handle order. */
    val fonts: Map<String, AgentFontInfo> = emptyMap(),
    val designs: Map<String, AgentDesignInfo> = emptyMap(),
) {
    companion object {
        /**
         * [bounds] is the placed bounds in 0.1mm (the caller computes it off the main thread). Fonts get
         * handles in [fonts] order; a font used in the design but not listed is added after them by id.
         */
        fun of(project: Project, fonts: List<AgentFontInfo> = emptyList(), designs: List<AgentDesignInfo> = emptyList(),
            bounds: (DesignObject) -> Bounds?): AgentProjectSummary {
            val fontInfo = linkedMapOf<String, AgentFontInfo>()
            val fontIds = linkedMapOf<String, String>()
            // A blank display name would leave the chatbot only an empty string; the stable id is never blank.
            fonts.distinctBy { it.id }.forEach { f -> "f${fontIds.size + 1}".let { fontIds[it] = f.id
                fontInfo[it] = if (f.name.isBlank()) f.copy(name = f.id) else f } }
            val objects = linkedMapOf<String, String>()
            val summaries = project.objects.mapIndexed { i, obj ->
                val handle = "o${i + 1}"
                objects[handle] = obj.id
                val text = obj as? TextObject
                val font = text?.let { t -> fontIds.entries.firstOrNull { it.value == t.fontId }?.key
                    ?: "f${fontIds.size + 1}".also { fontIds[it] = t.fontId } }
                val colors = when (obj) {
                    is TextObject -> listOf(obj.thread.color)
                    is StitchObject -> obj.source.threads.map { it.color }
                    else -> emptyList()
                }.map { "#%06X".format(Locale.ROOT, it and 0xFFFFFF) }
                AgentObjectSummary(handle, if (text != null) "text" else "design", obj.name, text?.text, font, text?.heightMm,
                    runCatching { bounds(obj) }.getOrNull(), obj.transform.rotationDeg,
                    obj.transform.scaleX * 100, obj.transform.scaleY * 100, colors, obj.visible)
            }
            val designHandles = designs.distinctBy { it.id }.mapIndexed { i, d -> "d${i + 1}" to d }.toMap()
            return AgentProjectSummary(project.hoop.name, project.hoop.width / 10.0, project.hoop.height / 10.0,
                summaries, AgentHandles(objects, fontIds, designHandles.mapValues { it.value.id }), fontInfo, designHandles)
        }
    }
}

/** [level] is the shortening step used (0 = full); [overBudget] when even the last step did not fit. */
internal data class AgentPrompt(val promptId: String, val text: String, val handles: AgentHandles,
    val level: Int = 0, val overBudget: Boolean = false, val attachments: List<AgentAttachment> = emptyList())

/** What the share sheet gets: plain text only, never an image stream (no EXTRA_STREAM, no image type). */
internal object AgentShare {
    const val MIME_TYPE = "text/plain"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"
    fun extras(prompt: AgentPrompt): Map<String, String> = mapOf(EXTRA_TEXT to prompt.text)
}

/**
 * Builds the text shared to any chatbot app. Deterministic for a given summary, request and id.
 * With a [budgetChars], shortens in the order of docs/agent/mobile-standalone.md "Length" until it fits:
 * 1. fonts → only those covering the scripts of the design and request; built-in designs left out,
 * 2. objects → those named in the request plus a count of the rest, 3. tool descriptions → signatures only.
 * Instructions, format, tool signatures and the request are never cut. No budget is set by default:
 * the chatbot apps' limits are not measured yet (unverified), so no number is used.
 */
internal object AgentPromptBuilder {
    const val LEVELS = 3

    fun build(summary: AgentProjectSummary, request: String, promptId: String, budgetChars: Int? = null,
        attachments: List<AgentAttachment> = emptyList()): AgentPrompt {
        for (level in 0..LEVELS) {
            val text = text(summary, request, promptId, level, attachments)
            if (budgetChars == null || text.length <= budgetChars || level == LEVELS)
                return AgentPrompt(promptId, text, summary.handles.copy(attachments = attachments.associate { it.id to it.id }),
                    level, budgetChars != null && text.length > budgetChars, attachments)
        }
        error("unreachable")
    }

    private val HANGUL = Regex("[\\uAC00-\\uD7A3\\u3131-\\u318E]")
    private val LATIN = Regex("[A-Za-z]")

    private fun text(summary: AgentProjectSummary, request: String, promptId: String, level: Int,
        attachments: List<AgentAttachment>) = buildString {
        appendLine("You are helping edit an embroidery design in the Android app myembro.")
        appendLine("Answer with ONLY the JSON command list: one ```json fenced block in the format below and nothing else.")
        appendLine("If the request is unclear, ask a short question instead of guessing.")
        appendLine("Use only the tools and handles listed here. Never invent ids: existing objects are o1, o2, …; fonts are f1, f2, …;")
        appendLine("built-in designs are d1, d2, … (use as assetId). Name new objects new:<word>. For fonts put the handle in both fontId and fontRevision.")
        appendLine("Units: millimeters (center of the hoop is 0,0; y grows downward), degrees, percent.")
        appendLine("Deleting, saving, sharing and USB transfer are confirmed by the user in the app.")
        appendLine()
        appendLine("Format:")
        appendLine("```json")
        appendLine("{\"myembroScript\":1,\"promptId\":\"$promptId\",\"summary\":\"<one line>\",\"commands\":[{\"tool\":\"<name>\",\"args\":{}}]}")
        appendLine("```")
        appendLine()
        appendLine("Tools (args are required exactly as listed):")
        val signatures = AgentTools.SIGNATURES + listOfNotNull(AgentTools.PHOTO_SIGNATURE.takeIf { attachments.isNotEmpty() })
        signatures.forEach { appendLine("- " + if (level >= 3) it.substringBefore(" - ") else it) }
        appendLine()
        appendLine("Design:")
        appendLine("- hoop: ${summary.hoopName} ${mm(summary.hoopWidthMm)} x ${mm(summary.hoopHeightMm)} mm")
        if (summary.objects.isEmpty()) appendLine("- no objects yet")
        val shown = if (level < 2) summary.objects else summary.objects.filter { o ->
            listOfNotNull(o.handle, o.name, o.text).any { it.isNotBlank() && request.contains(it, ignoreCase = true) }
        }
        summary.objects.forEachIndexed { order, o ->
            if (o !in shown) return@forEachIndexed
            append("- ${o.handle} ${o.kind} \"${o.name}\"")
            o.text?.let { append(" text=\"$it\"") }
            o.font?.let { append(" font=$it") }
            o.textHeightMm?.let { append(" textHeight=${mm(it)}mm") }
            o.bounds?.let { b ->
                append(" center=(${mm((b.minX + b.maxX) / 20.0)},${mm((b.minY + b.maxY) / 20.0)})mm")
                append(" size=${mm(b.width / 10.0)}x${mm(b.height / 10.0)}mm")
            }
            append(" rotation=${mm(o.rotationDeg)}deg scale=${mm(o.scaleXPercent)}%x${mm(o.scaleYPercent)}%")
            if (o.colors.isNotEmpty()) append(" colors=${o.colors.joinToString(",")}")
            append(" order=$order")
            if (!o.visible) append(" hidden")
            appendLine()
        }
        (summary.objects.size - shown.size).takeIf { it > 0 }?.let { appendLine("- (list shortened) $it more objects: o1…o${summary.objects.size}") }
        val scripts = (summary.objects.mapNotNull { it.text } + request).joinToString(" ")
        val needHangul = HANGUL.containsMatchIn(scripts); val needLatin = LATIN.containsMatchIn(scripts)
        val fonts = summary.handles.fonts.filter { (handle, _) ->
            val f = summary.fonts[handle] ?: return@filter true // fonts used by the design always stay
            level < 1 || ((!needHangul || f.hangul) && (!needLatin || f.latin))
        }
        if (fonts.isNotEmpty()) {
            appendLine()
            appendLine("Fonts (name in the user's language, stable id; ko = has Korean, la = has Latin; h = text height):")
            fonts.forEach { (handle, id) ->
                val f = summary.fonts[handle]
                if (f == null) appendLine("- $handle = $id") else {
                    val langs = listOfNotNull("ko".takeIf { f.hangul }, "la".takeIf { f.latin }).joinToString("+")
                    appendLine("- $handle \"${f.name}\" id=${f.id} $langs h${f.minMm?.let(::mm) ?: ""}-${mm(f.maxMm)}mm")
                }
            }
            if (fonts.size < summary.handles.fonts.size) appendLine("- (list shortened to fonts for this text)")
        }
        if (summary.designs.isNotEmpty()) {
            appendLine()
            appendLine("Built-in designs:")
            if (level >= 1) appendLine("- (list left out to save space)")
            else summary.designs.forEach { (handle, d) -> appendLine("- $handle \"${d.name}\" ${mm(d.widthMm)}x${mm(d.heightMm)}mm") }
        }
        if (attachments.isNotEmpty()) {
            appendLine()
            appendLine("Photos (only the id and size are sent, not the image; use the id as attachmentId):")
            attachments.forEach { appendLine("- photo ${it.id} attached (${it.widthPx}x${it.heightPx} px)") }
        }
        appendLine()
        appendLine("Request:")
        append("\"\"\"\n${request.trim()}\n\"\"\"")
    }

    /** A short follow-up for the same chat thread, listing what the app could not use. */
    fun repair(promptId: String, problems: List<String>): String = buildString {
        appendLine("myembro could not use your last reply (prompt $promptId):")
        problems.forEach { appendLine("- $it") }
        append("Reply again with ONLY the corrected ```json block in the same format.")
    }

    private fun mm(value: Double) = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
}
