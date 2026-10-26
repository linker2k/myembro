// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import kotlinx.serialization.json.*

/** Preserve original vector geometry on autosave, never flatten a shape into machine stitches. */
object ShapeObjectCodec : TypedProjectObjectCodec<ShapeObject>("shape", ShapeObject::class.java) {
    override fun encodePayload(obj: ShapeObject) = buildJsonObject {
        put("shapeVersion", 1)
        put("blocks", buildJsonObject {})
        put("widthMm", obj.widthMm)
        put("viewBox", JsonArray(obj.sourceArt.viewBox.map(::JsonPrimitive)))
        put("shapes", JsonArray(obj.sourceArt.shapes.map { s -> buildJsonObject {
            put("d", s.d); put("fill", s.fill?.let(::JsonPrimitive) ?: JsonNull)
            put("stroke", s.stroke?.let(::JsonPrimitive) ?: JsonNull); put("strokeWidth", s.strokeWidth)
            put("lineCap", s.lineCap?.let(::JsonPrimitive) ?: JsonNull); put("lineJoin", s.lineJoin?.let(::JsonPrimitive) ?: JsonNull)
            put("fillRule", s.fillRule.name)
            put("matrix", buildJsonArray { with(s.transform) { add(a);add(b);add(c);add(d);add(e);add(f) } })
        } }))
    }
    override fun decodeObject(fields: ProjectObjectFields, data: JsonObject): ShapeObject {
        require(data.getValue("shapeVersion").jsonPrimitive.int == 1)
        require(data.getValue("blocks").jsonObject.isEmpty()) { "Block overrides require the model settings codec" }
        val art = VectorArt.Art(data.getValue("viewBox").jsonArray.map { it.jsonPrimitive.double },
            data.getValue("shapes").jsonArray.map { element ->
                val s = element.jsonObject; val m = s.getValue("matrix").jsonArray.map { it.jsonPrimitive.double }
                require(m.size == 6)
                VectorArt.Shape(s.getValue("d").jsonPrimitive.content, s["fill"]?.jsonPrimitive?.intOrNull,
                    s["stroke"]?.jsonPrimitive?.intOrNull, s.getValue("strokeWidth").jsonPrimitive.double,
                    s["lineCap"]?.jsonPrimitive?.contentOrNull, s["lineJoin"]?.jsonPrimitive?.contentOrNull,
                    Affine2D(m[0],m[1],m[2],m[3],m[4],m[5]), FillRule.valueOf(s.getValue("fillRule").jsonPrimitive.content))
            })
        return ShapeObject.fromArt(fields.id, fields.name, art, data.getValue("widthMm").jsonPrimitive.double)
            .withTransform(fields.transform).withVisible(fields.visible)
    }
}
