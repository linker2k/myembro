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
    /** Requested by a script but run only afterwards, each from its own existing confirmation. */
    val FOLLOW_UPS = setOf("save_project", "save_pes", "share_pes", "send_usb")
}

/** Short names the chatbot uses instead of UUIDs and font ids; the app expands them back. */
internal data class AgentHandles(val objects: Map<String, String>, val fonts: Map<String, String>)

internal data class AgentObjectSummary(
    val handle: String, val kind: String, val name: String, val text: String?, val font: String?, val textHeightMm: Double?,
    val bounds: Bounds?, val rotationDeg: Double, val scaleXPercent: Double, val scaleYPercent: Double,
    val colors: List<String>, val visible: Boolean,
)

internal data class AgentProjectSummary(
    val hoopName: String, val hoopWidthMm: Double, val hoopHeightMm: Double,
    val objects: List<AgentObjectSummary>, val handles: AgentHandles,
) {
    companion object {
        /** [bounds] is the placed bounds in 0.1mm (the caller computes it off the main thread). */
        fun of(project: Project, bounds: (DesignObject) -> Bounds?): AgentProjectSummary {
            val fonts = linkedMapOf<String, String>()
            val objects = linkedMapOf<String, String>()
            val summaries = project.objects.mapIndexed { i, obj ->
                val handle = "o${i + 1}"
                objects[handle] = obj.id
                val text = obj as? TextObject
                val font = text?.let { t -> fonts.entries.firstOrNull { it.value == t.fontId }?.key
                    ?: "f${fonts.size + 1}".also { fonts[it] = t.fontId } }
                val colors = when (obj) {
                    is TextObject -> listOf(obj.thread.color)
                    is StitchObject -> obj.source.threads.map { it.color }
                    else -> emptyList()
                }.map { "#%06X".format(Locale.ROOT, it and 0xFFFFFF) }
                AgentObjectSummary(handle, if (text != null) "text" else "design", obj.name, text?.text, font, text?.heightMm,
                    runCatching { bounds(obj) }.getOrNull(), obj.transform.rotationDeg,
                    obj.transform.scaleX * 100, obj.transform.scaleY * 100, colors, obj.visible)
            }
            return AgentProjectSummary(project.hoop.name, project.hoop.width / 10.0, project.hoop.height / 10.0,
                summaries, AgentHandles(objects, fonts))
        }
    }
}

internal data class AgentPrompt(val promptId: String, val text: String, val handles: AgentHandles)

/** Builds the text shared to any chatbot app. Deterministic for a given summary, request and id. */
internal object AgentPromptBuilder {
    fun build(summary: AgentProjectSummary, request: String, promptId: String): AgentPrompt = AgentPrompt(promptId, buildString {
        appendLine("You are helping edit an embroidery design in the Android app myembro.")
        appendLine("Answer with ONLY the JSON command list: one ```json fenced block in the format below and nothing else.")
        appendLine("If the request is unclear, ask a short question instead of guessing.")
        appendLine("Use only the tools and handles listed here. Never invent ids: existing objects are o1, o2, …; fonts are f1, f2, …;")
        appendLine("name new objects new:<word>. For fonts put the handle in both fontId and fontRevision.")
        appendLine("Units: millimeters (center of the hoop is 0,0; y grows downward), degrees, percent.")
        appendLine("Deleting, saving, sharing and USB transfer are confirmed by the user in the app.")
        appendLine()
        appendLine("Format:")
        appendLine("```json")
        appendLine("{\"myembroScript\":1,\"promptId\":\"$promptId\",\"summary\":\"<one line>\",\"commands\":[{\"tool\":\"<name>\",\"args\":{}}]}")
        appendLine("```")
        appendLine()
        appendLine("Tools (args are required exactly as listed):")
        AgentTools.SIGNATURES.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Design:")
        appendLine("- hoop: ${summary.hoopName} ${mm(summary.hoopWidthMm)} x ${mm(summary.hoopHeightMm)} mm")
        if (summary.objects.isEmpty()) appendLine("- no objects yet")
        summary.objects.forEachIndexed { order, o ->
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
        if (summary.handles.fonts.isNotEmpty()) {
            appendLine()
            appendLine("Fonts:")
            summary.handles.fonts.forEach { (handle, id) -> appendLine("- $handle = $id") }
        }
        appendLine()
        appendLine("Request:")
        append("\"\"\"\n${request.trim()}\n\"\"\"")
    }, summary.handles)

    /** A short follow-up for the same chat thread, listing what the app could not use. */
    fun repair(promptId: String, problems: List<String>): String = buildString {
        appendLine("myembro could not use your last reply (prompt $promptId):")
        problems.forEach { appendLine("- $it") }
        append("Reply again with ONLY the corrected ```json block in the same format.")
    }

    private fun mm(value: Double) = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
}
