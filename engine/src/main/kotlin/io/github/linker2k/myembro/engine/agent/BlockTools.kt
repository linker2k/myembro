// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.ProjectCommand
import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.MeanderFill
import kotlinx.serialization.json.*

/** Shared headless/relay adapters; the reducer owns parameter ranges, generation and safety gates. */
object BlockTools {
    val names = setOf("list_blocks", "set_block_stitch")
    private val fields = setOf("type", "fill_method", "automatic_angle", "angle", "row_spacing_mm",
        "meander_pattern", "meander_scale_percent", "zigzag_spacing_mm")

    fun validate(name: String, arguments: JsonElement): String? = runCatching {
        val a = arguments.jsonObject
        val required = if (name == "list_blocks") setOf("objectId") else setOf("objectId", "regionIds", "change", "transactionId")
        require(a.keys == required) { "Missing or unknown fields" }
        fun text(v: JsonElement) { require(v is JsonPrimitive && v.isString && v.content.isNotBlank()) { "Expected nonblank string" } }
        text(a.getValue("objectId"))
        if (name == "set_block_stitch") {
            text(a.getValue("transactionId"))
            val ids = a.getValue("regionIds").jsonArray
            require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "regionIds must be nonempty and unique" }
            ids.forEach(::text)
            val c = a.getValue("change").jsonObject
            require(c.keys.all { it in fields }) { "Unknown block setting" }
            c.forEach { (k, v) -> when (k) {
                "type", "fill_method", "meander_pattern" -> text(v)
                "automatic_angle" -> require(v is JsonPrimitive && !v.isString && v.booleanOrNull != null) { "Expected boolean" }
                "meander_scale_percent" -> {
                    val scale = v.jsonArray; require(scale.size in 1..2) { "Expected one or two scale percentages" }
                    scale.forEach { require(it is JsonPrimitive && !it.isString && it.doubleOrNull?.isFinite() == true) { "Expected finite number" } }
                }
                else -> require(v is JsonPrimitive && !v.isString && v.doubleOrNull?.isFinite() == true) { "Expected finite number" }
            } }
        }
    }.exceptionOrNull()?.let { it.message ?: "Invalid block arguments" }

    fun command(a: JsonObject): ProjectCommand.SetBlockStitch {
        val c = a.getValue("change").jsonObject
        fun number(k: String) = c[k]?.jsonPrimitive?.double
        return ProjectCommand.SetBlockStitch(a.getValue("objectId").jsonPrimitive.content,
            a.getValue("regionIds").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            BlockStitchPatch(type = c["type"]?.jsonPrimitive?.content?.let(BlockStitch.Type::valueOf),
                fill_method = c["fill_method"]?.jsonPrimitive?.content,
                automatic_angle = c["automatic_angle"]?.jsonPrimitive?.boolean, angle = number("angle"),
                row_spacing_mm = number("row_spacing_mm"), meander_pattern = c["meander_pattern"]?.jsonPrimitive?.content,
                meander_scale_percent = c["meander_scale_percent"]?.jsonArray?.map { it.jsonPrimitive.double },
                zigzag_spacing_mm = number("zigzag_spacing_mm")))
    }

    fun list(project: Project, revision: String, id: String): JsonObject {
        val obj = project.find(id)
        if (obj !is ShapeObject) return buildJsonObject {
            put("ok", false); put("code", if (obj == null) "MISSING_OBJECT" else "INVALID_OBJECT_TYPE")
            put("message", "Object $id must be an editable shape object")
        }
        return buildJsonObject {
            put("ok", true); put("revision", revision); put("objectId", id)
            put("meanderPatterns", JsonArray(MeanderFill.availablePatterns().map(::JsonPrimitive)))
            put("regions", JsonArray(obj.regions.map { region -> buildJsonObject {
                put("id", region.id); put("color", "#%06X".format(java.util.Locale.ROOT, region.color.color and 0xFFFFFF))
                // Geometry is in local mm; holes subtract, transforms do not change this local area.
                put("areaMm2", PolygonRegion.fromPaths(region.paths.filter { it.closed }, FillRule.EVEN_ODD).area)
                put("stitch", ShapeObjectCodec.encodeBlock(region.stitch))
            } }))
        }
    }
}
