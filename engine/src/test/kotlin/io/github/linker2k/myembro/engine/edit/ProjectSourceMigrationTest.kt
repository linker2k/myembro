// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import kotlin.test.*

class ProjectSourceMigrationTest {
    // Entirely synthetic geometry and a PE-Design v8 UTF-16 thread header. No private PES data.
    private val correct = listOf(
        EmbThread(0xFFFFD911.toInt(), "Gold", "206", "EMBROIDERY"),
        EmbThread(0xFFA8A8A8.toInt(), "Silver", "005", "EMBROIDERY"),
        EmbThread(0xFFF993BC.toInt(), "Pink", "085", "EMBROIDERY"),
    )
    private fun v8(): ByteArray {
        val pattern = EmbPattern(correct.indices.flatMap { i -> buildList {
            if (i > 0) add(Stitch(i*40, 0, Command.COLOR_CHANGE))
            add(Stitch(i*40, 0, Command.STITCH)); add(Stitch(i*40+20, 20, Command.STITCH))
        } }, correct)
        val h = ByteArrayOutputStream()
        fun u16(v: Int) { h.write(v); h.write(v ushr 8) }
        fun string(s: String) { h.write(255); u16(0xFFFE); h.write(s.length); h.write(s.toByteArray(Charsets.UTF_16LE)) }
        u16(1); h.write("01".toByteArray()); string("Synthetic")
        repeat(4) { h.write(0) }; h.write(ByteArray(38)); h.write(0); h.write(ByteArray(26))
        repeat(3) { u16(0) }; u16(correct.size)
        for (t in correct) {
            string(t.catalogNumber!!); h.write(t.red); h.write(t.green); h.write(t.blue)
            h.write(byteArrayOf(0, 1, 0, 0, 0))
            string(t.description!!); string(t.brand!!); string("")
        }
        u16(0)
        val offset = 12+h.size()
        return "#PES0080".toByteArray() + ByteArray(4) { (offset ushr (it*8)).toByte() } + h.toByteArray() + PecWriter.write(pattern).drop(8).toByteArray()
    }
    private val bytes get() = v8()
    private fun legacy(embedded: ByteArray? = null): StitchObject {
        val fresh = PatternReader.read(bytes, "source.pes")
        assertEquals(correct, fresh.threads)
        return StitchObject("old", "old project", EmbPattern(fresh.stitches,
            fresh.threads.mapIndexed { i, t -> t.copy(color = listOf(0xFF650073, 0xFF0F03B4, 0xFF02D804)[i].toInt(),
                description = "\uFFFD\uFFFD085\uFFFD", brand = "EMBROIDERY\u0000") }, fresh.metadata),
            transform = Transform(20.0, -30.0, 27.0, -1.2, 0.7), visible = false,
            sourceBytes = embedded, sourceFileName = "source.pes")
    }
    private fun decode(obj: StitchObject, resolver: ProjectSourceResolver? = null): StitchObject =
        ProjectJson.decode(Project(objects = listOf(obj)).toJson(), sourceResolver = resolver).objects.single() as StitchObject

    @Test fun embeddedV8RepairsLegacyColorsAndNamesBeforeConsultingLibrary() {
        val old = legacy(bytes)
        var lookups = 0
        val restored = decode(old) { lookups++; bytes }
        assertEquals(0, lookups)
        assertEquals(correct, restored.source.threads)
        assertEquals(old.source.stitches, restored.source.stitches)
        assertEquals(old.transform, restored.transform)
        assertEquals(old.visible, restored.visible)
        assertEquals(old.name, restored.name)
        assertContentEquals(bytes, restored.sourceBytes)
    }

    @Test fun missingBytesUseResolverAndPersistTheMatchedOriginalForSubsequentLoads() {
        val old = legacy()
        val resolvedBytes = bytes
        val restored = decode(old) { name -> assertEquals("source.pes", name); resolvedBytes }
        assertEquals(correct, restored.source.threads)
        assertEquals(old.placedPattern().stitches, restored.placedPattern().stitches)
        assertContentEquals(bytes, restored.sourceBytes)
        resolvedBytes[0] = 0 // no mutable alias to the app's byte buffer
        assertContentEquals(bytes, restored.sourceBytes)
        assertEquals(correct, decode(restored).source.threads)
    }

    @Test fun changedCoordinatesOrStitchCountDoNotReplaceTheSavedSource() {
        val old = legacy()
        for (stitches in listOf(old.source.stitches.dropLast(1), old.source.stitches.mapIndexed { i, s -> if (i == 0) s.copy(x = s.x+1) else s })) {
            val changed = old.copy(source = EmbPattern(stitches, old.source.threads))
            for (embedded in listOf<ByteArray?>(null, bytes)) {
                val restored = decode(changed.copy(sourceBytes = embedded)) { bytes }
                assertEquals(stitches, restored.source.stitches)
                assertEquals(old.source.threads, restored.source.threads)
                assertContentEquals(embedded, restored.sourceBytes)
            }
        }
    }

    @Test fun missingUnreadableOrInvalidOriginalLeavesStoredGeometryAndColorsAvailable() {
        val old = legacy()
        for (resolver in listOf<ProjectSourceResolver?>(null, ProjectSourceResolver { null },
            ProjectSourceResolver { throw java.io.IOException("missing") }, ProjectSourceResolver { byteArrayOf(1, 2, 3) })) {
            val restored = decode(old, resolver)
            assertEquals(old.source.stitches, restored.source.stitches)
            assertEquals(old.source.threads, restored.source.threads)
            assertNull(restored.sourceBytes)
        }
        assertEquals(correct, decode(legacy(byteArrayOf(1, 2, 3))) { bytes }.source.threads)
    }

    @Test fun explicitRecoloringMarkerPreservesUserThreadsWithEitherKindOfOriginal() {
        for (embedded in listOf<ByteArray?>(null, bytes)) {
            val changed = legacy(embedded).copy(sourceThreadsEdited = true)
            var lookups = 0
            val restored = decode(changed) { lookups++; bytes }
            assertEquals(0, lookups)
            assertEquals(changed.source.threads, restored.source.threads)
            assertTrue(restored.sourceThreadsEdited)
            assertTrue(Project(objects = listOf(restored)).toJson().contains("\"sourceThreadsEdited\": true"))
        }
    }

    @Test fun absentFilenameAndNullOrMalformedEmbeddedBytesHaveSafeFallbacks() {
        val old = legacy()
        var lookups = 0
        val noName = decode(old.copy(sourceFileName = null)) { lookups++; bytes }
        assertEquals(0, lookups)
        assertEquals(old.source.threads, noName.source.threads)
        val root = Json.parseToJsonElement(Project(objects = listOf(old)).toJson()).jsonObject
        for (value in listOf(JsonNull, JsonPrimitive("not base64 !!!"))) {
            val objectJson = root.getValue("objects").jsonArray.single().jsonObject
            val modified = JsonObject(root + ("objects" to JsonArray(listOf(JsonObject(objectJson + ("sourceBytes" to value))))))
            val restored = ProjectJson.decode(modified.toString(), sourceResolver = ProjectSourceResolver { bytes }).objects.single() as StitchObject
            assertEquals(correct, restored.source.threads)
        }
    }
}
