// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.agent.ToolTurnController
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.placedBounds
import kotlinx.serialization.json.*
import kotlin.math.abs

/**
 * The relay's view of the strict tools.json tools: short descriptions, and arguments a chatbot may leave
 * out. [complete] fills them with defaults or from the open project, so the same strict dispatcher runs the
 * same calls Ask AI would; Ask AI and docs/agent/tools.json keep their strict contract.
 */
object RelayCatalog {
    /** One or two sentences each; units and coordinates are explained once, in get_project. */
    val DESCRIPTIONS = mapOf(
        "get_project" to "Read the open design: hoop, objects, fonts for add_text, free space around objects and the last " +
            "sewing check. Lengths are mm; the hoop center is (0,0), x grows to the right and y grows downward.",
        "render_preview" to "Picture of the design in its hoop as a PNG (widthPx 256-1024, default 512).",
        "list_fonts" to "Installed fonts that can write a text (all arguments optional), with allowed heights; get_project already lists them.",
        "list_builtin_designs" to "Built-in designs add_design can place, with their sizes.",
        "get_settings" to "The chosen machine and the frames set_project_frame accepts.",
        "add_design" to "Add a built-in design by its assetId from list_builtin_designs.",
        "add_text" to "Add embroidered text at heightMm; leave out fontId to use the last font used (lastFontKo for " +
            "Hangul, lastFontEn otherwise). Give xMm/yMm to place its center in the same step.",
        "replace_text" to "Change an existing text object's text; font, height and color stay unless given.",
        "set_object_transforms" to "Move objects so their center is at xMm/yMm; rotation and scale stay unless given.",
        "transform_objects" to "Move, scale or rotate several objects together around their shared center.",
        "mirror_objects" to "Mirror objects left to right.",
        "center_objects" to "Center the objects as a group in the hoop.",
        "align_objects" to "Align objects by their placed bounds.",
        "distribute_objects" to "Space three or more objects evenly.",
        "duplicate_object" to "Copy an object to a new position.",
        "reorder_object" to "Set an object's place in the sewing order (0 = sewn first).",
        "set_object_visibility" to "Show or hide an object.",
        "recolor_object" to "Change one thread color of an object (threadIndex 0 = first color).",
        "remove_objects" to "Remove objects.",
        "set_project_frame" to "Change the hoop to a frame id from get_settings.",
        "list_attachments" to "Photos the user handed to AI in myembro.",
        "view_attachment" to "Look at a handed-over photo (at most 768 px).",
        "prepare_photo" to "Turn a handed-over photo into a stitch design at widthMm; add it with add_generated_art.",
        "add_generated_art" to "Add a prepared photo result to the design.",
    )

    /** Arguments that may be left out, with their default; null = taken from the open project or font. */
    private val OPTIONAL: Map<String, Map<String, JsonElement?>> = mapOf(
        "render_preview" to mapOf("widthPx" to JsonPrimitive(512)),
        "list_fonts" to mapOf("text" to JsonPrimitive(""), "filters" to JsonArray(emptyList()), "installedOnly" to JsonPrimitive(true)),
        "add_design" to mapOf("objectId" to null, "name" to JsonPrimitive("")),
        "add_text" to mapOf("objectId" to null, "fontId" to null, "fontRevision" to null, "threadColor" to JsonPrimitive("#000000"),
            "singleLineBold" to JsonPrimitive(false)),
        "replace_text" to mapOf("fontId" to null, "fontRevision" to null, "heightMm" to null, "threadColor" to null,
            "singleLineBold" to JsonPrimitive(false)),
        "transform_objects" to mapOf("deltaXMm" to JsonPrimitive(0), "deltaYMm" to JsonPrimitive(0),
            "scaleFactor" to JsonPrimitive(1), "rotationDeg" to JsonPrimitive(0)),
        "duplicate_object" to mapOf("newObjectId" to null),
        "recolor_object" to mapOf("threadIndex" to JsonPrimitive(0)),
    )
    /** Inside set_object_transforms rows and duplicate_object's transform: kept from the object. */
    private val POSE = listOf("rotationDeg", "scaleXPercent", "scaleYPercent")
    private val PLACE = mapOf(
        "xMm" to "Where the text's center goes, mm from the hoop center (right is positive).",
        "yMm" to "Where the text's center goes, mm from the hoop center (down is positive).",
    )

    /** The strict tools.json definition as the relay offers it. */
    fun project(tool: JsonObject): JsonObject {
        val name = tool.getValue("name").jsonPrimitive.content
        val schema = tool.getValue("inputSchema").jsonObject
        val optional = OPTIONAL[name].orEmpty()
        var properties = JsonObject(schema["properties"]?.jsonObject.orEmpty() - "transactionId")
        properties = JsonObject(properties.mapValues { (key, value) ->
            val default = optional[key]
            when {
                default != null -> JsonObject(value.jsonObject + ("default" to default))
                key == "transforms" || key == "transform" -> poseOptional(value.jsonObject)
                else -> value
            }
        })
        if (name == "add_text") properties = JsonObject(properties + PLACE.mapValues { (_, d) ->
            buildJsonObject { put("type", "number"); put("minimum", -10000); put("maximum", 10000); put("description", d) } })
        val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.filter { it != "transactionId" && it !in optional }
        return JsonObject(tool + ("description" to JsonPrimitive(DESCRIPTIONS[name] ?: tool.getValue("description").jsonPrimitive.content)) +
            ("inputSchema" to JsonObject(schema + ("properties" to properties) + ("required" to JsonArray(required.map(::JsonPrimitive))))))
    }

    /** An object schema (or an array of them) whose rotation and scale may be left out. */
    private fun poseOptional(value: JsonObject): JsonObject {
        value["items"]?.let { return JsonObject(value + ("items" to poseOptional(it.jsonObject))) }
        val required = value["required"]?.jsonArray.orEmpty().filter { it.jsonPrimitive.content !in POSE }
        return JsonObject(value + ("required" to JsonArray(required)))
    }

    /**
     * The strict dispatcher calls for one relay call, or an error message. Left-out arguments get their
     * default or the open object's own value; add_text with xMm/yMm becomes add_text then
     * set_object_transforms, run as one turn by the caller.
     */
    fun complete(tool: String, args: JsonObject, project: Project, fontRevision: (String) -> String?): Result {
        val a = args.toMutableMap()
        OPTIONAL[tool].orEmpty().forEach { (key, default) -> if (key !in a && default != null) a[key] = default }
        fun fail(message: String) = Result(emptyList(), message)
        when (tool) {
            "add_design", "add_text" -> if ("objectId" !in a) a["objectId"] = JsonPrimitive(newId(project, if (tool == "add_text") "text" else "design"))
            "duplicate_object" -> if ("newObjectId" !in a) a["newObjectId"] = JsonPrimitive(newId(project, "copy"))
        }
        if (tool == "replace_text") {
            val id = a["objectId"]?.jsonPrimitive?.contentOrNull ?: return fail("objectId is required")
            val text = project.find(id) as? TextObject ?: return fail("Object $id is not a text object")
            a.putIfAbsent("fontId", JsonPrimitive(text.fontId))
            a.putIfAbsent("heightMm", JsonPrimitive(text.heightMm))
            a.putIfAbsent("threadColor", JsonPrimitive("#%06X".format(java.util.Locale.ROOT, text.thread.color and 0xFFFFFF)))
        }
        if (tool == "add_text" || tool == "replace_text") {
            val font = a["fontId"]?.jsonPrimitive?.contentOrNull ?: return fail("fontId is required")
            if ("fontRevision" !in a) a["fontRevision"] = JsonPrimitive(fontRevision(font) ?: return fail("Font $font is not installed"))
        }
        if (tool == "set_object_transforms") a["transforms"]?.let { rows ->
            a["transforms"] = JsonArray((rows as? JsonArray ?: return@let).map { row -> withPose(row, project) })
        }
        if (tool == "duplicate_object") a["transform"]?.let { t ->
            a["transform"] = withPose(t, project, a["sourceId"]?.jsonPrimitive?.contentOrNull)
        }
        val place = listOf("xMm", "yMm").mapNotNull { key -> a.remove(key)?.let { key to it } }.toMap()
        if (tool != "add_text" || place.isEmpty()) {
            if (place.isNotEmpty()) return fail("xMm/yMm belong to add_text only")
            return Result(listOf(ToolTurnController.Call(tool, JsonObject(a))), null)
        }
        val id = a.getValue("objectId")
        val row = buildJsonObject {
            put("objectId", id); put("xMm", place["xMm"] ?: JsonPrimitive(0)); put("yMm", place["yMm"] ?: JsonPrimitive(0))
            put("rotationDeg", 0); put("scaleXPercent", 100); put("scaleYPercent", 100)
        }
        return Result(listOf(ToolTurnController.Call(tool, JsonObject(a)),
            ToolTurnController.Call("set_object_transforms", buildJsonObject { put("transforms", JsonArray(listOf(row))) })), null)
    }

    data class Result(val calls: List<ToolTurnController.Call>, val error: String?)

    /** A transform row with rotation and scale filled from the object ([source] for a duplicate). */
    private fun withPose(row: JsonElement, project: Project, source: String? = null): JsonElement {
        val r = row as? JsonObject ?: return row
        val id = source ?: r["objectId"]?.jsonPrimitive?.contentOrNull ?: return row
        val t = project.find(id)?.transform ?: return row
        val pose = mapOf("rotationDeg" to t.rotationDeg, "scaleXPercent" to abs(t.scaleX) * 100, "scaleYPercent" to abs(t.scaleY) * 100)
        return JsonObject(r + pose.filterKeys { it !in r }.mapValues { JsonPrimitive(it.value) })
    }

    private fun newId(project: Project, prefix: String) =
        generateSequence(1) { it + 1 }.map { "$prefix-$it" }.first { project.find(it) == null }

    /**
     * Empty margin (mm) on each side of every visible object and of all of them together, inside the hoop
     * (center 0,0, y down): where something new fits without overlapping their bounds.
     */
    fun freeSpace(project: Project): JsonObject {
        val halfW = project.hoop.width / 20.0; val halfH = project.hoop.height / 20.0
        fun margins(minX: Double, minY: Double, maxX: Double, maxY: Double) = buildJsonObject {
            put("leftMm", r1(minX + halfW)); put("rightMm", r1(halfW - maxX)); put("topMm", r1(minY + halfH)); put("bottomMm", r1(halfH - maxY))
        }
        val placed = project.objects.filter { it.visible }.mapNotNull { o -> o.placedBounds()?.let { o to it } }
        return buildJsonObject {
            put("objects", JsonArray(placed.map { (o, b) -> JsonObject(margins(b.minX / 10.0, b.minY / 10.0, b.maxX / 10.0, b.maxY / 10.0) +
                ("id" to JsonPrimitive(o.id))) }))
            if (placed.isNotEmpty()) put("all", margins(placed.minOf { it.second.minX } / 10.0, placed.minOf { it.second.minY } / 10.0,
                placed.maxOf { it.second.maxX } / 10.0, placed.maxOf { it.second.maxY } / 10.0))
        }
    }

    /**
     * One font as the relay lists it: id, name, scripts or whether it writes [supportsText], and the allowed
     * height range (the same pinned ranges the Ask AI prompt uses). No glyph or range lists.
     */
    fun fontRow(id: String, name: String, hangul: Boolean, latin: Boolean, minMm: Double?, maxMm: Double, supportsText: Boolean? = null) = buildJsonObject {
        put("fontId", id); put("name", name)
        if (supportsText == null) { put("hangul", hangul); put("latin", latin) } else put("supportsText", supportsText)
        put("minHeightMm", minMm?.let { JsonPrimitive(r1(it)) } ?: JsonNull); put("maxHeightMm", r1(maxMm))
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
}
