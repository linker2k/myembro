// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import kotlinx.serialization.json.*
import io.github.linker2k.myembro.engine.lettering.TextAlignment

/** Editable lettering, including cached font outlines for offline reconstruction without Android. */
object TextObjectCodec : TypedProjectObjectCodec<TextObject>("text", TextObject::class.java) {
    override fun encodePayload(obj: TextObject): JsonObject = buildJsonObject {
        put("text", obj.text)
        put("fontId", obj.fontId)
        put("heightMm", obj.heightMm)
        put("spacing", obj.spacing)
        put("arcDeg", obj.arcDeg)
        put("thread", ProjectJson.encodeThread(obj.thread))
        put("fontKind", obj.fontKind.name)
        put("contours", JsonArray(obj.contours.map { contour ->
            JsonArray(contour.map { (x, y) -> buildJsonArray { add(x); add(y) } })
        }))
        put("lineSpacing", obj.lineSpacing)
        put("alignment", obj.alignment.name)
        put("arcRadiusMm", obj.arcRadiusMm?.let(::JsonPrimitive) ?: JsonNull)
        put("characterThreads", buildJsonObject { obj.characterThreads.toSortedMap().forEach { (index, thread) -> put(index.toString(), ProjectJson.encodeThread(thread)) } })
        put("contourCharacters", JsonArray(obj.contourCharacters.map(::JsonPrimitive)))
        put("contourBaselines", JsonArray(obj.contourBaselines.map(::JsonPrimitive)))
        put("contourBaseline", obj.contourBaseline)
        put("contourSourceHeight", obj.contourSourceHeight?.let(::JsonPrimitive) ?: JsonNull)
    }

    override fun decodeObject(fields: ProjectObjectFields, data: JsonObject): TextObject = TextObject(
        id = fields.id,
        name = fields.name,
        text = data.getValue("text").jsonPrimitive.content,
        fontId = data.getValue("fontId").jsonPrimitive.content,
        heightMm = data.getValue("heightMm").jsonPrimitive.double,
        spacing = data.getValue("spacing").jsonPrimitive.double,
        arcDeg = data.getValue("arcDeg").jsonPrimitive.double,
        thread = ProjectJson.decodeThread(data.getValue("thread").jsonObject),
        transform = fields.transform,
        visible = fields.visible,
        fontKind = data["fontKind"]?.jsonPrimitive?.content?.let(FontKind::valueOf) ?: FontKind.HERSHEY,
        contours = data["contours"]?.jsonArray?.map { contour ->
            contour.jsonArray.map { point ->
                val pair = point.jsonArray
                require(pair.size == 2) { "A contour point must contain exactly x and y" }
                pair[0].jsonPrimitive.double to pair[1].jsonPrimitive.double
            }
        } ?: emptyList(),
        lineSpacing = data["lineSpacing"]?.jsonPrimitive?.double ?: 1.5,
        alignment = data["alignment"]?.jsonPrimitive?.content?.let(TextAlignment::valueOf) ?: TextAlignment.CENTER,
        arcRadiusMm = data["arcRadiusMm"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.double,
        characterThreads = data["characterThreads"]?.jsonObject?.map { (key, value) -> key.toInt() to ProjectJson.decodeThread(value.jsonObject) }?.toMap() ?: emptyMap(),
        contourCharacters = data["contourCharacters"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
        contourBaselines = data["contourBaselines"]?.jsonArray?.map { it.jsonPrimitive.double } ?: emptyList(),
        contourBaseline = data["contourBaseline"]?.jsonPrimitive?.double ?: 0.0,
        contourSourceHeight = data["contourSourceHeight"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.double,
    )
}
