// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import kotlinx.serialization.json.*

/** Additive v1 object codec; original import/text formats and codec precedence are unchanged. */
object ShapeObjectCodec : TypedProjectObjectCodec<ShapeObject>("shape",ShapeObject::class.java) {
    override fun encodePayload(obj: ShapeObject): JsonObject = buildJsonObject {
        put("shapeVersion",1);put("widthMm",obj.widthMm)
        if (ShapeObjectFactory.isPhoto(obj)) put("photo", PhotoShapeCodec.encode(obj))
        put("viewBox",JsonArray(obj.sourceArt.viewBox.map(::JsonPrimitive)))
        put("shapes",JsonArray(obj.sourceArt.shapes.map { shape -> buildJsonObject {
            put("d",shape.d);put("fill",shape.fill?.let(::JsonPrimitive) ?: JsonNull)
            put("stroke",shape.stroke?.let(::JsonPrimitive) ?: JsonNull);put("strokeWidth",shape.strokeWidth)
            put("lineCap",shape.lineCap?.let(::JsonPrimitive) ?: JsonNull);put("lineJoin",shape.lineJoin?.let(::JsonPrimitive) ?: JsonNull)
            put("fillRule",shape.fillRule.name)
            val t=shape.transform;put("matrix",JsonArray(listOf(t.a,t.b,t.c,t.d,t.e,t.f).map(::JsonPrimitive)))
        } }))
        put("blocks",buildJsonObject { obj.overrides.forEach { (id,block) -> put(id,encodeBlock(block)) } })
    }
    override fun decodeObject(fields: ProjectObjectFields,data: JsonObject): ShapeObject {
        require(data.getValue("shapeVersion").jsonPrimitive.int==1) { "Unsupported shape version" }
        val art=VectorArt.Art(data.getValue("viewBox").jsonArray.map { it.jsonPrimitive.double },
            data.getValue("shapes").jsonArray.map { element ->
                val s=element.jsonObject;val m=s.getValue("matrix").jsonArray.map { it.jsonPrimitive.double };require(m.size==6)
                VectorArt.Shape(s.text("d"),s.getValue("fill").jsonPrimitive.intOrNull,s.getValue("stroke").jsonPrimitive.intOrNull,
                    s.number("strokeWidth"),s.getValue("lineCap").jsonPrimitive.contentOrNull,s.getValue("lineJoin").jsonPrimitive.contentOrNull,
                    Affine2D(m[0],m[1],m[2],m[3],m[4],m[5]),FillRule.valueOf(s.text("fillRule")))
            })
        val base = data["photo"]?.let { PhotoShapeCodec.decode(fields,art,data.number("widthMm"),it.jsonObject) }
            ?: ShapeObject.fromArt(fields.id,fields.name,art,data.number("widthMm"))
        return base
            .withOverrides(data.getValue("blocks").jsonObject.mapValues { decodeBlock(it.value.jsonObject) })
            .withTransform(fields.transform).withVisible(fields.visible)
    }
    /** Current editable block settings, shared by persistence and tool inspection. */
    fun encodeBlock(b: BlockStitch)=buildJsonObject {
        put("type",b.type.name);put("automatic_angle",b.automatic_angle)
        put("fill_method",b.fill.fill_method);put("angle",b.fill.angle);put("row_spacing_mm",b.fill.row_spacing_mm)
        put("meander_pattern",b.meander_pattern);put("meander_scale_percent",JsonArray(b.meander_scale_percent.map(::JsonPrimitive)))
        put("zigzag_spacing_mm",b.satin?.zigzag_spacing_mm?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun decodeBlock(b: JsonObject): BlockStitch {
        require(b.keys==setOf("type","automatic_angle","fill_method","angle","row_spacing_mm","meander_pattern","meander_scale_percent","zigzag_spacing_mm"))
        val satin=b.getValue("zigzag_spacing_mm").jsonPrimitive.doubleOrNull?.let { SatinParameters(zigzag_spacing_mm=it) }
        return BlockStitch(BlockStitch.Type.valueOf(b.text("type")),
            FillParameters(fill_method=b.text("fill_method"),angle=b.number("angle"),row_spacing_mm=b.number("row_spacing_mm")),
            satin,b.getValue("automatic_angle").jsonPrimitive.boolean,b.text("meander_pattern"),
            b.getValue("meander_scale_percent").jsonArray.map { it.jsonPrimitive.double })
    }
    private fun JsonObject.text(k: String)=getValue(k).jsonPrimitive.content
    private fun JsonObject.number(k: String)=getValue(k).jsonPrimitive.double
}
