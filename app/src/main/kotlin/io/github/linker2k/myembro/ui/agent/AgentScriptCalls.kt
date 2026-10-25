// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import io.github.linker2k.myembro.engine.agent.ToolTurnController
import io.github.linker2k.myembro.engine.edit.Project
import kotlinx.serialization.json.*

/**
 * A script turned into dispatcher calls: handles expanded to real ids, the app's transaction id added,
 * and follow-up tools split off (they never enter the edit turn). [indices] maps each call back to its
 * command position in the script.
 */
internal data class AgentScriptCalls(
    val transactionId: String,
    val calls: List<ToolTurnController.Call>,
    val indices: List<Int>,
    /** Tools that must be done separately in the app; any of them blocks Apply ([AgentTools.separate]). */
    val separate: List<String>,
    val problems: List<AgentProblem>,
) {
    /** Change list rows from the expanded calls; ids become object names from [before] (or the new id's word). */
    /** Object name in [before], or the script's own word for an object the script creates (`new:<word>`). */
    private fun name(before: Project, id: String) = before.find(id)?.name ?: id.substringAfter("$NEW_PREFIX$transactionId-")

    fun changes(before: Project): List<AgentChange> {
        fun name(id: String) = name(before, id)
        return calls.map { call ->
            val a = call.arguments as JsonObject
            fun ids(key: String) = a[key]?.jsonArray?.map { name(it.jsonPrimitive.content) }.orEmpty()
            fun id(key: String) = listOfNotNull(a[key]?.jsonPrimitive?.content?.let(::name))
            fun value(key: String) = a[key]?.jsonPrimitive?.content.orEmpty()
            fun num(key: String) = a[key]?.jsonPrimitive?.doubleOrNull?.let(::fmt).orEmpty()
            when (call.name) {
                "set_object_transforms" -> {
                    val rows = a.getValue("transforms").jsonArray.map { it.jsonObject }
                    AgentChange(AgentChange.Kind.CHANGE, call.name, rows.map { name(it.getValue("objectId").jsonPrimitive.content) },
                        rows.firstOrNull()?.let { r -> listOf("xMm", "yMm", "rotationDeg", "scaleXPercent", "scaleYPercent")
                            .map { fmt(r.getValue(it).jsonPrimitive.double) } }.orEmpty())
                }
                "transform_objects" -> AgentChange(AgentChange.Kind.CHANGE, call.name, ids("objectIds"),
                    listOf(num("deltaXMm"), num("deltaYMm"), num("scaleFactor"), num("rotationDeg")))
                "align_objects" -> AgentChange(AgentChange.Kind.CHANGE, call.name, ids("objectIds"), listOf(value("alignment")))
                "distribute_objects" -> AgentChange(AgentChange.Kind.CHANGE, call.name, ids("objectIds"), listOf(value("axis")))
                "mirror_objects", "center_objects" -> AgentChange(AgentChange.Kind.CHANGE, call.name, ids("objectIds"))
                "remove_objects" -> AgentChange(AgentChange.Kind.REMOVE, call.name, ids("objectIds"))
                "duplicate_object" -> AgentChange(AgentChange.Kind.ADD, call.name, id("sourceId"), id("newObjectId"))
                "reorder_object" -> AgentChange(AgentChange.Kind.CHANGE, call.name, id("objectId"),
                    listOf(((a["index"]?.jsonPrimitive?.intOrNull ?: 0) + 1).toString()))
                "set_object_visibility" -> AgentChange(AgentChange.Kind.CHANGE, call.name, id("objectId"), listOf(value("visible")))
                "recolor_object" -> AgentChange(AgentChange.Kind.CHANGE, call.name, id("objectId"),
                    listOf(((a["threadIndex"]?.jsonPrimitive?.intOrNull ?: 0) + 1).toString(), value("threadColor").uppercase()))
                "set_project_frame" -> AgentChange(AgentChange.Kind.CHANGE, call.name, emptyList(), listOf(value("frameId")))
                "add_text" -> AgentChange(AgentChange.Kind.ADD, call.name, id("objectId"), listOf(value("text"), num("heightMm")))
                "replace_text" -> AgentChange(AgentChange.Kind.CHANGE, call.name, id("objectId"), listOf(value("text"), num("heightMm")))
                "add_design", "add_generated_art" -> AgentChange(AgentChange.Kind.ADD, call.name, id("objectId"), listOf(value("name")))
                else -> AgentChange(AgentChange.Kind.CHANGE, call.name, id("objectId") + ids("objectIds"))
            }
        }
    }

    /** One row per remove_objects call: its call index (what a ScriptApproval binds) and object names. */
    fun removals(before: Project): List<AgentRemoval> = calls.withIndex().filter { it.value.name == "remove_objects" }.map { (i, call) ->
        AgentRemoval(i, (call.arguments as JsonObject).getValue("objectIds").jsonArray.map { id -> name(before, id.jsonPrimitive.content) })
    }

    companion object {
        const val NEW_PREFIX = "agent-"
        private val OBJECT_KEYS = setOf("objectId", "sourceId", "newObjectId", "assetId", "attachmentId")
        private val FONT_KEYS = setOf("fontId", "fontRevision")
        private val OBJECT_HANDLE = Regex("o\\d+")
        private val FONT_HANDLE = Regex("f\\d+")
        private val DESIGN_HANDLE = Regex("d\\d+")

        /**
         * Deterministic for a given script and [transactionId]: the preview and the apply produce the same
         * calls, and new ids (`new:<word>` → `agent-<transaction>-<word>`) are the same both times.
         */
        fun of(script: AgentScript, handles: AgentHandles, transactionId: String,
            fontRevision: (String) -> String? = { null }, isSeparate: (String) -> Boolean = AgentTools::separate): AgentScriptCalls {
            val calls = mutableListOf<ToolTurnController.Call>()
            val indices = mutableListOf<Int>()
            val separate = linkedSetOf<String>()
            val problems = mutableListOf<AgentProblem>()
            script.commands.forEachIndexed { index, command ->
                if (isSeparate(command.tool)) { separate += command.tool; return@forEachIndexed }
                fun id(key: String?, value: String, font: Boolean): String = when {
                    !font && value.startsWith("new:") && value.length > 4 -> "$NEW_PREFIX$transactionId-${value.removePrefix("new:")}"
                    !font && OBJECT_HANDLE.matches(value) -> handles.objects[value]
                        ?: value.also { problems += AgentProblem("UNKNOWN_HANDLE", index, command.tool, value) }
                    // fontRevision gets the resolver's exact revision; the chatbot only knows the handle.
                    font && FONT_HANDLE.matches(value) -> handles.fonts[value]?.let { id -> if (key == "fontRevision") fontRevision(id) ?: id else id }
                        ?: value.also { problems += AgentProblem("UNKNOWN_HANDLE", index, command.tool, value) }
                    !font && key == "assetId" && DESIGN_HANDLE.matches(value) -> handles.designs[value]
                        ?: value.also { problems += AgentProblem("UNKNOWN_HANDLE", index, command.tool, value) }
                    // Only a photo attached to this prompt; never a path, URL or another prompt's photo.
                    !font && key == "attachmentId" -> handles.attachments[value]
                        ?: value.also { problems += AgentProblem("UNKNOWN_HANDLE", index, command.tool, value) }
                    else -> value
                }
                fun expand(key: String?, element: JsonElement): JsonElement = when (element) {
                    is JsonObject -> JsonObject(element.mapValues { (k, v) -> expand(k, v) })
                    is JsonArray -> JsonArray(element.map { expand(if (key == "objectIds") "objectId" else key, it) })
                    is JsonPrimitive -> if (element.isString && (key in OBJECT_KEYS || key in FONT_KEYS))
                        JsonPrimitive(id(key, element.content, key in FONT_KEYS)) else element
                    JsonNull -> element
                }
                // The app owns the turn: a script cannot name its own transaction. Other tools keep their
                // arguments untouched so the dispatcher reports them as they are (unknown, not implemented…).
                val expanded = expand(null, command.args).jsonObject
                val args = if (command.tool in AgentTools.NAMES) JsonObject(expanded + ("transactionId" to JsonPrimitive(transactionId))) else expanded
                calls += ToolTurnController.Call(command.tool, args)
                indices += index
            }
            return AgentScriptCalls(transactionId, calls, indices, separate.toList(), problems)
        }

        private fun fmt(value: Double) = String.format(java.util.Locale.ROOT, "%.1f", value).removeSuffix(".0")
    }
}
