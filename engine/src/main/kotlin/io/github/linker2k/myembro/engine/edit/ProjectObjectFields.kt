// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import kotlinx.serialization.json.*

/** Common editable state shared by stitch and lettering codecs; coordinates use 0.1 mm. */
data class ProjectObjectFields(
    val id: String,
    val name: String,
    val transform: Transform = Transform(),
    val visible: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("name", name); put("visible", visible)
        put("transform", buildJsonObject {
            put("x", transform.x); put("y", transform.y); put("rotationDeg", transform.rotationDeg)
            put("scaleX", transform.scaleX); put("scaleY", transform.scaleY)
        })
    }

    companion object {
        fun from(obj: DesignObject): ProjectObjectFields =
            ProjectObjectFields(obj.id, obj.name, obj.transform, obj.visible)

        fun fromJson(data: JsonObject): ProjectObjectFields {
            val t = data.getValue("transform").jsonObject
            return ProjectObjectFields(
                data.getValue("id").jsonPrimitive.content, data.getValue("name").jsonPrimitive.content,
                Transform(t.number("x"), t.number("y"), t.number("rotationDeg"), t.number("scaleX"), t.number("scaleY")),
                data.getValue("visible").jsonPrimitive.boolean,
            )
        }

        private fun JsonObject.number(key: String): Double = getValue(key).jsonPrimitive.double
    }
}

/**
 * Implement only object-specific fields (text/font/height/spacing/arc/thread for lettering).
 * The adapter owns id/name/transform/visible; payload fields cannot overwrite them.
 * Use a stable, nonblank type other than the reserved "stitch", then register once with
 * ProjectJson.registerCodec(codec). No ProjectJson edits or global plugin discovery required.
 */
abstract class TypedProjectObjectCodec<T : DesignObject>(
    final override val type: String,
    private val objectClass: Class<T>,
) : ProjectObjectCodec {
    final override fun canEncode(obj: DesignObject): Boolean = objectClass.isInstance(obj)
    final override fun encode(obj: DesignObject): JsonObject =
        JsonObject(encodePayload(objectClass.cast(obj)) + ProjectObjectFields.from(obj).toJson())
    final override fun decode(data: JsonObject): DesignObject = decodeObject(ProjectObjectFields.fromJson(data), data)

    protected abstract fun encodePayload(obj: T): JsonObject
    protected abstract fun decodeObject(fields: ProjectObjectFields, data: JsonObject): T
}
