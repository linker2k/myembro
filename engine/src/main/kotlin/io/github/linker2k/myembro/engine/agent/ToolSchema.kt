// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import kotlinx.serialization.json.*

/** Validator for the deliberately closed vocabulary used by the checked-in tools.json.
 * Fails closed if that vocabulary changes; no remote schemas, coercion or provider SDK needed. */
class ToolSchema(catalog: String) {
    private val tools = Json.parseToJsonElement(catalog).jsonObject.getValue("tools").jsonArray
        .map { it.jsonObject }.also { rows -> require(rows.map { it.getValue("name").jsonPrimitive.content }.distinct().size == rows.size) }
        .associateBy { it.getValue("name").jsonPrimitive.content }
    init { tools.values.forEach { checkSchema(it.getValue("inputSchema").jsonObject) } }
    fun contains(name: String) = name in tools
    fun validate(name: String, arguments: JsonElement): String? {
        val tool = tools[name] ?: return "Unknown tool: $name"
        return validateValue(tool.getValue("inputSchema").jsonObject, arguments, "$")
    }
    fun confirmation(name: String) = tools.getValue(name).getValue("annotations").jsonObject.getValue("needsUserConfirmation").jsonPrimitive.boolean

    private fun checkSchema(s: JsonObject) {
        require(s.keys.all { it in setOf("type","properties","required","additionalProperties","items","enum","minimum","maximum","pattern") })
        when(s.getValue("type").jsonPrimitive.content) {
            "object" -> {
                require(s.getValue("additionalProperties") == JsonPrimitive(false))
                require(s.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet() == s.getValue("properties").jsonObject.keys)
                s.getValue("properties").jsonObject.values.forEach { checkSchema(it.jsonObject) }
            }
            "array" -> checkSchema(s.getValue("items").jsonObject)
            "string", "number", "integer", "boolean" -> Unit
            else -> error("Unsupported schema type")
        }
    }
    private fun validateValue(s: JsonObject, v: JsonElement, path: String): String? {
        val primitive = v as? JsonPrimitive
        when (s.getValue("type").jsonPrimitive.content) {
            "object" -> {
                val o = v as? JsonObject ?: return "$path: expected object"
                val properties = s.getValue("properties").jsonObject
                if (o.keys != properties.keys) return "$path: missing or unknown fields: ${(o.keys - properties.keys) + (properties.keys - o.keys)}"
                for ((k, child) in properties) validateValue(child.jsonObject, o.getValue(k), "$path.$k")?.let { return it }
            }
            "array" -> {
                val a = v as? JsonArray ?: return "$path: expected array"
                a.forEachIndexed { i, child -> validateValue(s.getValue("items").jsonObject, child, "$path[$i]")?.let { return it } }
            }
            "string" -> {
                if (primitive == null || !primitive.isString) return "$path: expected string"
                s["pattern"]?.jsonPrimitive?.content?.let { if (!Regex(it).containsMatchIn(primitive.content)) return "$path: pattern mismatch" }
            }
            "boolean" -> if (primitive == null || primitive.isString || primitive.booleanOrNull == null) return "$path: expected boolean"
            "number", "integer" -> {
                val n = primitive?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: return "$path: expected finite number"
                if (s.getValue("type").jsonPrimitive.content == "integer" && n % 1.0 != 0.0) return "$path: expected integer"
                if (s["minimum"]?.jsonPrimitive?.double?.let { n < it } == true || s["maximum"]?.jsonPrimitive?.double?.let { n > it } == true) return "$path: out of range"
            }
        }
        s["enum"]?.jsonArray?.let { choices ->
            // JSON Schema treats 1 and 1.0 as the same numeric value.
            if (choices.none { it == v || it is JsonPrimitive && primitive != null && !it.isString && !primitive.isString && it.doubleOrNull != null && it.doubleOrNull == primitive.doubleOrNull }) return "$path: invalid enum"
        }
        return null
    }
    companion object {
        fun bundled() = ToolSchema(requireNotNull(ToolSchema::class.java.getResourceAsStream("/agent/tools.json")) { "Missing bundled tools.json" }.bufferedReader().use { it.readText() })
    }
}
