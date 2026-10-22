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
 * Codecs may be passed per call or registered once at startup; registration applies to both
 * encode/decode and Project.toJson(). Explicit/registered codecs precede the built-in fallback.
 * StitchObject and TextObject work without registration. Use TypedProjectObjectCodec to share
 * placement/id handling for additional types. Custom codecs take precedence over built-ins.
 */
object ProjectJson {
    const val VERSION = 1
    const val EXTENSION = "myembro"
    private val json = Json { prettyPrint = true }
    private val builtInCodecs: List<ProjectObjectCodec> = listOf(TextObjectCodec)
    @Volatile private var registeredCodecs: List<ProjectObjectCodec> = emptyList()

    /** Register once at application startup. Re-registering the same instance is harmless. */
    @Synchronized
    fun registerCodec(codec: ProjectObjectCodec) {
        if (registeredCodecs.any { it === codec }) return
        registeredCodecs = validateCodecs(registeredCodecs + codec)
    }

    /** Removes only this exact instance, for scoped registrations and test isolation. */
    @Synchronized
    fun unregisterCodec(codec: ProjectObjectCodec) {
        registeredCodecs = registeredCodecs.filterNot { it === codec }
    }

    private fun validateCodecs(codecs: List<ProjectObjectCodec>): List<ProjectObjectCodec> {
        require(codecs.all { it.type.isNotBlank() && it.type != "stitch" }) { "Codec type must be nonblank and not reserved 'stitch'" }
        require(codecs.map { it.type }.distinct().size == codecs.size) { "Duplicate project codec types" }
        return codecs.toList()
    }

    private fun effectiveCodecs(codecs: List<ProjectObjectCodec>): List<ProjectObjectCodec> {
        val combined = registeredCodecs.toMutableList()
        codecs.forEach { codec -> if (combined.none { it === codec }) combined += codec }
        val custom = validateCodecs(combined)
        return custom + builtInCodecs.filter { builtIn -> custom.none { it.type == builtIn.type } }
    }

    fun encode(project: Project, codecs: List<ProjectObjectCodec> = emptyList()): String {
        val activeCodecs = effectiveCodecs(codecs)
        return buildJsonObject {
            put("format", "myembro")
            put("version", VERSION)
            put("hoop", buildJsonObject {
                put("name", project.hoop.name); put("width", project.hoop.width); put("height", project.hoop.height)
            })
            put("objects", JsonArray(project.objects.map { obj ->
                val codec = activeCodecs.firstOrNull { it.canEncode(obj) }
                when {
                    codec != null -> JsonObject(codec.encode(obj) + ("type" to JsonPrimitive(codec.type)))
                    obj is StitchObject -> encodeStitch(obj)
                    else -> error("Unsupported project object: ${obj::class.simpleName}; register a ProjectObjectCodec")
                }
            }))
        }.let { json.encodeToString(JsonObject.serializer(), it) }
    }

    fun decode(text: String, codecs: List<ProjectObjectCodec> = emptyList()): Project {
        val activeCodecs = effectiveCodecs(codecs)
        val root = json.parseToJsonElement(text).jsonObject
        require(root.string("format") == "myembro") { "Not a .myembro project" }
        require(root.int("version") == VERSION) { "Unsupported .myembro version" }
        val hoop = root.getValue("hoop").jsonObject
        val objects = root.getValue("objects").jsonArray.map { element ->
            val obj = element.jsonObject
            when (val type = obj.string("type")) {
                "stitch" -> decodeStitch(obj)
                else -> (activeCodecs.firstOrNull { it.type == type }
                    ?: error("Unsupported project object type: $type")).decode(obj)
            }
        }
        require(objects.map { it.id }.distinct().size == objects.size) { "Duplicate object ids" }
        return Project(HoopSpec(hoop.string("name"), hoop.int("width"), hoop.int("height")), objects)
    }

    private fun encodeStitch(obj: StitchObject): JsonObject = buildJsonObject {
        put("type", "stitch")
        ProjectObjectFields.from(obj).toJson().forEach { (key, value) -> put(key, value) }
        obj.sourceBytes?.let { put("sourceBytes", Base64.getEncoder().encodeToString(it)) }
        obj.sourceFileName?.let { put("sourceFileName", it) }
        // Store the parsed source as well: formats like PES v1 cannot preserve custom threads.
        put("sourcePattern", encodePattern(obj.source))
    }

    private fun decodeStitch(obj: JsonObject): StitchObject {
        val fields = ProjectObjectFields.fromJson(obj)
        return StitchObject(
            id = fields.id, name = fields.name,
            source = decodePattern(obj.getValue("sourcePattern").jsonObject),
            transform = fields.transform,
            visible = fields.visible,
            sourceBytes = obj["sourceBytes"]?.jsonPrimitive?.content?.let { Base64.getDecoder().decode(it) },
            sourceFileName = obj["sourceFileName"]?.jsonPrimitive?.content,
        )
    }

    private fun encodePattern(pattern: EmbPattern): JsonObject = buildJsonObject {
        put("stitches", JsonArray(pattern.stitches.map { s -> buildJsonArray {
            add(s.x); add(s.y); add(s.command.name)
        } }))
        put("threads", JsonArray(pattern.threads.map(::encodeThread)))
        put("metadata", JsonObject(pattern.metadata.mapValues { JsonPrimitive(it.value) }))
        put("warnings", JsonArray(pattern.warnings.map(::JsonPrimitive)))
    }

    private fun decodePattern(obj: JsonObject): EmbPattern = EmbPattern(
        stitches = obj.getValue("stitches").jsonArray.map { element ->
            val s = element.jsonArray
            require(s.size == 3) { "Invalid stitch" }
            Stitch(s[0].jsonPrimitive.int, s[1].jsonPrimitive.int, Command.valueOf(s[2].jsonPrimitive.content))
        },
        threads = obj.getValue("threads").jsonArray.map { decodeThread(it.jsonObject) },
        metadata = obj.getValue("metadata").jsonObject.mapValues { it.value.jsonPrimitive.content },
        warnings = obj.getValue("warnings").jsonArray.map { it.jsonPrimitive.content },
    )

    /** Shared thread representation for stitch and lettering payloads, preserving identity. */
    fun encodeThread(thread: EmbThread): JsonObject = buildJsonObject {
        put("color", thread.color)
        thread.description?.let { put("description", it) }
        thread.catalogNumber?.let { put("catalogNumber", it) }
        thread.brand?.let { put("brand", it) }
        thread.chart?.let { put("chart", it) }
    }

    fun decodeThread(data: JsonObject): EmbThread = EmbThread(
        data.int("color"), data.optional("description"), data.optional("catalogNumber"), data.optional("brand"), data.optional("chart"),
    )

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.optional(key: String) = get(key)?.jsonPrimitive?.contentOrNull
}

fun Project.toJson(codecs: List<ProjectObjectCodec> = emptyList()): String = ProjectJson.encode(this, codecs)
