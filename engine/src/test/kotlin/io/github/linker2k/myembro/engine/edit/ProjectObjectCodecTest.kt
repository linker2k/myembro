// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ProjectObjectCodecTest {
    // StitchObject stands in for the concurrently developed TextObject; the same typed adapter
    // and registry dispatch are used without requiring the lettering agent's source files.
    private class ExampleCodec(type: String = "example") : TypedProjectObjectCodec<StitchObject>(type, StitchObject::class.java) {
        override fun encodePayload(obj: StitchObject): JsonObject = buildJsonObject {
            put("payloadName", obj.name)
            put("thread", ProjectJson.encodeThread(obj.source.threads.single()))
            put("id", "payload-cannot-overwrite-id")
            put("type", "payload-cannot-overwrite-type")
        }
        override fun decodeObject(fields: ProjectObjectFields, data: JsonObject): StitchObject = StitchObject(
            fields.id, data.getValue("payloadName").jsonPrimitive.content,
            EmbPattern(listOf(Stitch(0, 0, Command.STITCH)), listOf(ProjectJson.decodeThread(data.getValue("thread").jsonObject))),
            fields.transform, fields.visible,
        )
    }

    @Test fun sharedFieldsRoundTripWithoutPayloadOverwrites() {
        val obj = rectangle().copy(name = "한글", transform = Transform(1.5, -3.5, 90.0, -1.2, 0.8), visible = false)
        val fields = ProjectObjectFields.from(obj)
        assertEquals(fields, ProjectObjectFields.fromJson(fields.toJson()))
        val codec = ExampleCodec()
        assertTrue(codec.canEncode(obj))
        val encoded = codec.encode(obj)
        assertEquals(obj.id, encoded.getValue("id").jsonPrimitive.content)
        val back = codec.decode(encoded)
        assertEquals(fields, ProjectObjectFields.from(back))
        assertEquals(listOf(blue), back.localPattern().threads)
    }

    @Test fun perCallCodecDispatchWorksBothWaysAndDoesNotRegisterGlobally() {
        val p = Project(objects = listOf(rectangle()))
        val codec = ExampleCodec()
        val json = p.toJson(listOf(codec))
        val data = Json.parseToJsonElement(json).jsonObject.getValue("objects").jsonArray.single().jsonObject
        assertEquals("example", data.getValue("type").jsonPrimitive.content)
        assertEquals("a", data.getValue("id").jsonPrimitive.content)
        val back = ProjectJson.decode(json, listOf(codec)).objects.single()
        assertEquals(ProjectObjectFields.from(p.objects.single()), ProjectObjectFields.from(back))
        assertFailsWith<IllegalStateException> { ProjectJson.decode(json) }
        assertTrue(p.toJson().contains("\"stitch\""))
    }

    @Test fun oneRegistrationEnablesExistingSaveAndLoadCalls() {
        val codec = ExampleCodec("registered-example")
        ProjectJson.registerCodec(codec)
        try {
            ProjectJson.registerCodec(codec) // idempotent
            val p = Project(objects = listOf(rectangle()))
            val encoded = p.toJson()
            assertTrue(encoded.contains("registered-example"))
            assertEquals(p.objects.single().id, ProjectJson.decode(encoded).objects.single().id)
            assertEquals(encoded, p.toJson(listOf(codec))) // one instance can also be passed explicitly
            ProjectJson.unregisterCodec(ExampleCodec("registered-example")) // different identity cannot remove it
            assertEquals(encoded, p.toJson())
            assertFailsWith<IllegalArgumentException> { ProjectJson.registerCodec(ExampleCodec("registered-example")) }
        } finally {
            ProjectJson.unregisterCodec(codec)
        }
        assertTrue(Project(objects = listOf(rectangle())).toJson().contains("\"stitch\""))
    }

    @Test fun conflictingAndReservedTypesFailEarly() {
        for (type in listOf("", " ", "stitch")) {
            val codec = ExampleCodec(type)
            assertFailsWith<IllegalArgumentException> { ProjectJson.registerCodec(codec) }
            assertFailsWith<IllegalArgumentException> { Project().toJson(listOf(codec)) }
            assertFailsWith<IllegalArgumentException> { ProjectJson.decode(Project().toJson(), listOf(codec)) }
        }
        assertFailsWith<IllegalArgumentException> { Project().toJson(listOf(ExampleCodec(), ExampleCodec())) }
    }

    @Test fun threadHelpersPreserveNullableFieldsAlphaAndIdentity() {
        for (thread in listOf(blue, EmbThread(0x33123456), blue.copy(catalogNumber = "different"))) {
            assertEquals(thread, ProjectJson.decodeThread(ProjectJson.encodeThread(thread)))
        }
    }
}
