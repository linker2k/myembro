// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ProjectJsonTest {
    @Test fun losslessRoundTripPreservesOriginalBytesAndEditorState() {
        val bytes = requireNotNull(javaClass.getResource("/fixtures/flower.v6.pes")).readBytes()
        val imported = StitchObject.fromBytes("imported", "한글 \"꽃\"\n🌷", bytes, "꽃.pes")
            .copy(transform = Transform(10.5, -30.0, 27.0, -1.5, 0.7), visible = false)
        val custom = EmbPattern(
            Command.entries.mapIndexed { i, command -> Stitch(i * 3, -i, command) },
            listOf(blue, red.copy(color = 0x33123456, description = null)),
            mapOf("name" to "한글", "comments" to "a\tb\n\"c\""), listOf("import warning"),
        )
        val p = Project(HoopSpec("custom", 1234, 5678), listOf(imported, StitchObject("custom", "custom", custom)))
        val text = p.toJson()
        assertEquals(ProjectJson.VERSION, Json.parseToJsonElement(text).jsonObject.getValue("version").jsonPrimitive.int)
        assertTrue(text.contains("sourceBytes"))
        val back = ProjectJson.decode(text)
        assertEquals(p.hoop, back.hoop)
        assertEquals(p.objects.map { it.id }, back.objects.map { it.id })
        p.objects.zip(back.objects).forEach { (before, after) ->
            before as StitchObject; after as StitchObject
            assertEquals(before.name, after.name)
            assertEquals(before.transform, after.transform)
            assertEquals(before.visible, after.visible)
            assertEquals(before.sourceFileName, after.sourceFileName)
            assertContentEquals(before.sourceBytes, after.sourceBytes)
            assertEquals(before.source.stitches, after.source.stitches)
            assertEquals(before.source.threads, after.source.threads)
            assertEquals(before.source.metadata, after.source.metadata)
            assertEquals(before.source.warnings, after.source.warnings)
            assertEquals(before.placedPattern().stitches, after.placedPattern().stitches)
        }
        assertEquals(text, back.toJson())
        assertContentEquals(bytes, (back.find("imported") as StitchObject).sourceBytes)
    }

    @Test fun projectsSavedWithAnOlderReaderGetTheReadersCurrentColors() {
        val bytes = requireNotNull(javaClass.getResource("/fixtures/embroidepy/random1-brother-v6.pes")).readBytes()
        val imported = StitchObject.fromBytes("imported", "random", bytes, "random1.pes")
        // As an older reader might have stored it: right stitches, wrong thread colors.
        val garbled = imported.source.let { EmbPattern(it.stitches, it.threads.map { t -> t.copy(color = 0xFF6C0079.toInt()) }, it.metadata) }
        val back = ProjectJson.decode(Project(objects = listOf(imported.copy(source = garbled))).toJson()).objects.single() as StitchObject
        assertEquals(imported.source.threads, back.source.threads)
        // Recolored objects drop their source bytes, and their colors stay as the user chose.
        val recolored = imported.copy(source = garbled, sourceBytes = null)
        val kept = ProjectJson.decode(Project(objects = listOf(recolored)).toJson()).objects.single() as StitchObject
        assertEquals(garbled.threads, kept.source.threads)
    }

    @Test fun emptyProjectAndMissingImportBytesRoundTrip() {
        assertEquals(Project(), ProjectJson.decode(Project().toJson()))
        val obj = rectangle()
        val back = ProjectJson.decode(Project(objects = listOf(obj)).toJson()).objects.single() as StitchObject
        assertNull(back.sourceBytes)
        assertEquals(obj.source.stitches, back.source.stitches)
        assertEquals(obj.source.threads, back.source.threads)
    }

    @Test fun rejectFutureVersionsUnknownTypesAndDuplicateIds() {
        val root = Json.parseToJsonElement(Project(objects = listOf(rectangle())).toJson()).jsonObject
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(JsonObject(root + ("version" to JsonPrimitive(999))).toString()) }
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(JsonObject(root + ("format" to JsonPrimitive("other"))).toString()) }
        val objectJson = root.getValue("objects").jsonArray.single().jsonObject
        val unknown = JsonObject(objectJson + ("type" to JsonPrimitive("future")))
        assertFailsWith<IllegalStateException> { ProjectJson.decode(JsonObject(root + ("objects" to JsonArray(listOf(unknown)))).toString()) }
        assertFailsWith<IllegalArgumentException> {
            ProjectJson.decode(JsonObject(root + ("objects" to JsonArray(listOf(objectJson, objectJson)))).toString())
        }
    }

    @Test fun originalBytesAreCopiedOnImport() {
        val bytes = io.github.linker2k.myembro.engine.PolicyFixtureWriter.write(rectangle().source)
        val original = bytes.copyOf()
        val obj = StitchObject.fromBytes("a", "a", bytes, "a.pes")
        bytes[0] = 0
        assertContentEquals(original, obj.sourceBytes)
    }
}
