// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import java.util.Base64
import kotlinx.serialization.json.*

/** Extension point for editable object types (for example lettering). Unknown types fail explicitly. */
interface ProjectObjectCodec {
    val type: String
    fun canEncode(obj: DesignObject): Boolean
    fun encode(obj: DesignObject): JsonObject
    fun decode(data: JsonObject): DesignObject
}

/**
 * Versioned .myembro JSON. Stitch geometry, commands, thread identity and metadata are lossless;
 * PES is only used at machine export time. Optional original import bytes are kept as Base64.
 * Additional object codecs must encode/decode their complete object, including placement and id.
 */
object ProjectJson {
    const val VERSION = 1
    const val EXTENSION = "myembro"
    private val json = Json { prettyPrint = true }

    fun encode(project: Project, codecs: List<ProjectObjectCodec> = emptyList()): String = buildJsonObject {
        put("format", "myembro")
        put("version", VERSION)
        put("hoop", buildJsonObject {
            put("name", project.hoop.name); put("width", project.hoop.width); put("height", project.hoop.height)
        })
        put("objects", JsonArray(project.objects.map { obj ->
            if (obj is StitchObject) encodeStitch(obj) else {
                val codec = codecs.firstOrNull { it.canEncode(obj) }
                    ?: error("Unsupported project object: ${obj::class.simpleName}")
                JsonObject(codec.encode(obj) + ("type" to JsonPrimitive(codec.type)))
            }
        }))
    }.let { json.encodeToString(JsonObject.serializer(), it) }

    fun decode(text: String, codecs: List<ProjectObjectCodec> = emptyList()): Project {
        val root = json.parseToJsonElement(text).jsonObject
        require(root.string("format") == "myembro") { "Not a .myembro project" }
        require(root.int("version") == VERSION) { "Unsupported .myembro version" }
        val hoop = root.getValue("hoop").jsonObject
        val objects = root.getValue("objects").jsonArray.map { element ->
            val obj = element.jsonObject
            when (val type = obj.string("type")) {
                "stitch" -> decodeStitch(obj)
                else -> (codecs.firstOrNull { it.type == type }
                    ?: error("Unsupported project object type: $type")).decode(obj)
            }
        }
        require(objects.map { it.id }.distinct().size == objects.size) { "Duplicate object ids" }
        return Project(HoopSpec(hoop.string("name"), hoop.int("width"), hoop.int("height")), objects)
    }

    private fun encodeStitch(obj: StitchObject): JsonObject = buildJsonObject {
        put("type", "stitch"); put("id", obj.id); put("name", obj.name); put("visible", obj.visible)
        put("transform", buildJsonObject {
            val t = obj.transform
            put("x", t.x); put("y", t.y); put("rotationDeg", t.rotationDeg)
            put("scaleX", t.scaleX); put("scaleY", t.scaleY)
        })
        obj.sourceBytes?.let { put("sourceBytes", Base64.getEncoder().encodeToString(it)) }
        obj.sourceFileName?.let { put("sourceFileName", it) }
        // Store the parsed source as well: formats like PES v1 cannot preserve custom threads.
        put("sourcePattern", encodePattern(obj.source))
    }

    private fun decodeStitch(obj: JsonObject): StitchObject {
        val t = obj.getValue("transform").jsonObject
        return StitchObject(
            id = obj.string("id"), name = obj.string("name"),
            source = decodePattern(obj.getValue("sourcePattern").jsonObject),
            transform = Transform(t.double("x"), t.double("y"), t.double("rotationDeg"), t.double("scaleX"), t.double("scaleY")),
            visible = obj.getValue("visible").jsonPrimitive.boolean,
            sourceBytes = obj["sourceBytes"]?.jsonPrimitive?.content?.let { Base64.getDecoder().decode(it) },
            sourceFileName = obj["sourceFileName"]?.jsonPrimitive?.content,
        )
    }

    private fun encodePattern(pattern: EmbPattern): JsonObject = buildJsonObject {
        put("stitches", JsonArray(pattern.stitches.map { s -> buildJsonArray {
            add(s.x); add(s.y); add(s.command.name)
        } }))
        put("threads", JsonArray(pattern.threads.map { thread -> buildJsonObject {
            put("color", thread.color)
            thread.description?.let { put("description", it) }
            thread.catalogNumber?.let { put("catalogNumber", it) }
            thread.brand?.let { put("brand", it) }
            thread.chart?.let { put("chart", it) }
        } }))
        put("metadata", JsonObject(pattern.metadata.mapValues { JsonPrimitive(it.value) }))
        put("warnings", JsonArray(pattern.warnings.map(::JsonPrimitive)))
    }

    private fun decodePattern(obj: JsonObject): EmbPattern = EmbPattern(
        stitches = obj.getValue("stitches").jsonArray.map { element ->
            val s = element.jsonArray
            require(s.size == 3) { "Invalid stitch" }
            Stitch(s[0].jsonPrimitive.int, s[1].jsonPrimitive.int, Command.valueOf(s[2].jsonPrimitive.content))
        },
        threads = obj.getValue("threads").jsonArray.map { element ->
            val t = element.jsonObject
            EmbThread(t.int("color"), t.optional("description"), t.optional("catalogNumber"), t.optional("brand"), t.optional("chart"))
        },
        metadata = obj.getValue("metadata").jsonObject.mapValues { it.value.jsonPrimitive.content },
        warnings = obj.getValue("warnings").jsonArray.map { it.jsonPrimitive.content },
    )

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.double(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonObject.optional(key: String) = get(key)?.jsonPrimitive?.contentOrNull
}

fun Project.toJson(codecs: List<ProjectObjectCodec> = emptyList()): String = ProjectJson.encode(this, codecs)
