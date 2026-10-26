// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*

/** Cached photo defaults are part of the source payload: decoding must never route them through VectorArt. */
internal object PhotoShapeCodec {
    fun encode(obj: ShapeObject) = buildJsonObject {
        put("version",2)
        put("regions",JsonArray(obj.regions.map { r -> buildJsonObject {
            put("id",r.id);put("sourceShape",r.sourceShape);put("thread",ProjectJson.encodeThread(r.color))
            put("paths",JsonArray(r.paths.map { p -> buildJsonObject {
                put("closed",p.closed);put("points",JsonArray(p.points.map { q -> buildJsonArray { add(q.x);add(q.y) } }))
            } }))
        } }))
        val source=requireNotNull(obj.photoSource)
        put("pattern",encodePattern(source.pattern))
        put("fragments",JsonObject(source.fragments.mapValues { encodePattern(it.value) }))
    }
    private fun encodePattern(p: EmbPattern)=buildJsonObject {
        put("stitches",JsonArray(p.stitches.map { s -> buildJsonArray { add(s.x);add(s.y);add(s.command.name) } }))
        put("threads",JsonArray(p.threads.map(ProjectJson::encodeThread)))
        put("metadata",JsonObject(p.metadata.mapValues { JsonPrimitive(it.value) }))
        put("warnings",JsonArray(p.warnings.map(::JsonPrimitive)))
    }
    private fun decodePattern(p: JsonObject)=EmbPattern(p.getValue("stitches").jsonArray.map { value ->
        val s=value.jsonArray;require(s.size==3);Stitch(s[0].jsonPrimitive.int,s[1].jsonPrimitive.int,Command.valueOf(s[2].jsonPrimitive.content))
    },p.getValue("threads").jsonArray.map { ProjectJson.decodeThread(it.jsonObject) },
        p.getValue("metadata").jsonObject.mapValues { it.value.jsonPrimitive.content },p.getValue("warnings").jsonArray.map { it.jsonPrimitive.content })

    fun decode(fields: ProjectObjectFields, art: VectorArt.Art, widthMm: Double, data: JsonObject): ShapeObject {
        val version=data.getValue("version").jsonPrimitive.int
        require(version in 1..2)
        val regions=data.getValue("regions").jsonArray.map { value ->
            val r=value.jsonObject
            val paths=r.getValue("paths").jsonArray.map { path ->
                val p=path.jsonObject
                Polyline(p.getValue("points").jsonArray.map { q -> val xy=q.jsonArray;require(xy.size==2);Point(xy[0].jsonPrimitive.double,xy[1].jsonPrimitive.double) },
                    p.getValue("closed").jsonPrimitive.boolean)
            }
            ShapeRegion(r.getValue("id").jsonPrimitive.content,r.getValue("sourceShape").jsonPrimitive.int,
                ShapeObjectFactory.PHOTO_FILL,paths,ProjectJson.decodeThread(r.getValue("thread").jsonObject),BlockStitch())
        }
        val pattern=decodePattern(data.getValue("pattern").jsonObject)
        val fragments=if(version==2) data.getValue("fragments").jsonObject.mapValues { decodePattern(it.value.jsonObject) } else emptyMap()
        require(fragments.isEmpty() || fragments.keys==regions.map { it.id }.toSet()) { "INVALID_PHOTO_PROVENANCE" }
        return ShapeObjectFactory.retained(fields.id,fields.name,art,widthMm,regions,pattern,fragments)
            .withTransform(fields.transform).withVisible(fields.visible)
    }
}
