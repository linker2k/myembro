// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BatchDesignImporterTest {
    private val pattern = EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(20, 20, Command.STITCH),
        Stitch(20, 20, Command.END)), listOf(EmbThread(0xFF123456.toInt())))
    private val pes get() = PesWriter.write(pattern)
    private fun source(name: String, bytes: ByteArray, id: String = name): DesignImportEntry = object : DesignImportEntry {
        override val id = id
        override val name = name
        override val directory = false
        override fun open() = bytes.inputStream()
    }
    private fun folder(name: String, vararg nodes: DesignImportEntry): DesignImportEntry = object : DesignImportEntry {
        override val id = name
        override val name = name
        override val directory = true
        override fun children() = nodes.toList()
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { stream -> entries.forEach { (name, bytes) ->
            stream.putNextEntry(ZipEntry(name)); stream.write(bytes); stream.closeEntry()
        } }
    }.toByteArray()
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("batch-designs-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
    private fun files(directory: File) = directory.listFiles().orEmpty().filter { !it.name.startsWith('.') }
    private fun skipAll(items: List<DuplicateDesign>) = items.associate { it.id to DuplicateAction.SKIP }

    @Test fun `mixed selection checks extension AND magic and keeps valid files`() = withDirectory { directory ->
        val report = BatchDesignImporter(directory).import(listOf(
            source("a.PES", pes), source("photo.jpg", pes), source("fake.pes", "<html>error</html>".toByteArray()),
            source("wrong.dst", pes), source("b.pec", PecWriter.write(pattern))))
        assertEquals(listOf("a.pes", "b.pec"), report.imported.map { it.name })
        assertEquals(setOf("photo.jpg", "fake.pes", "wrong.dst"), report.skipped.map { it.name }.toSet())
        assertTrue(report.skipped.all { it.reason == ImportSkipReason.UNSUPPORTED })
        assertTrue("application/octet-stream" in DesignFormats.mimeTypes)
        assertFalse("*/*" in DesignFormats.mimeTypes)
    }

    @Test fun `SVG is sewn on import, stored as PES, with notes on defaults and left-out elements`() = withDirectory { directory ->
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="20mm" height="20mm" viewBox="0 0 20 20">
            <rect width="10" height="10" fill="#c00"/><text x="0" y="15">hi</text></svg>""".toByteArray()
        val tuned = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkstitch="http://inkstitch.org/namespace" width="20mm" height="20mm"
            viewBox="0 0 20 20"><rect width="10" height="10" fill="#c00" inkstitch:angle="45"/></svg>""".toByteArray()
        val report = BatchDesignImporter(directory).import(listOf(source("logo.svg", svg), source("tuned.svg", tuned),
            source("broken.svg", "<svg><path".toByteArray())))
        assertEquals(listOf("logo.pes", "tuned.pes"), report.imported.map { it.name })
        assertTrue(DesignFormats.read(report.imported.first()).stitchCount > 50)
        val note = report.skipped.single { it.reason == ImportSkipReason.CONVERTED }
        assertEquals("logo.svg", note.name)
        assertTrue(note.autoAssigned)
        assertEquals(mapOf("text" to 1), note.excluded)
        assertEquals(ImportSkipReason.UNSUPPORTED, report.skipped.single { it.name == "broken.svg" }.reason)
        assertTrue("image/svg+xml" in DesignFormats.mimeTypes)
        assertFalse("svg" in DesignFormats.extensions, "the library itself holds machine files only")
    }

    @Test fun `DST header is checked rather than interpreting arbitrary bytes as stitches`() {
        val file = listOf(File("../engine/src/test/resources/fixtures/flower.dst"),
            File("engine/src/test/resources/fixtures/flower.dst")).first { it.isFile }
        assertTrue(DesignFormats.read(file).stitchCount > 0)
        assertFails { DesignFormats.read(ByteArray(1024), "fake.dst") }
    }

    @Test fun `projects preserve editable payload and can also be read and exported as designs`() = withDirectory { directory ->
        val project = Project(HoopSpec.H100, listOf(StitchObject("original", "needle", pattern)))
        val bytes = ProjectJson.encode(project).toByteArray()
        val report = BatchDesignImporter(directory).import(listOf(source("saved.myembro", bytes)))
        val saved = report.imported.single()
        assertContentEquals(bytes, saved.readBytes())
        assertEquals(ProjectJson.encode(project), ProjectJson.encode(ProjectJson.decode(saved.readText())))
        assertEquals(project.flatten().stitches, DesignFormats.read(saved).stitches)
        val exported = DesignFormats.preparePes(saved, PesWriteSettings())
        assertTrue(exported.converted)
        assertTrue(PatternReader.read(exported.bytes, exported.fileName).stitchCount > 0)
        val bad = BatchDesignImporter(directory).import(listOf(source("bad.myembro", "{}".toByteArray())))
        assertEquals(1, bad.skipped.size)
        assertTrue(bad.imported.isEmpty())
    }

    @Test fun `recursive folder and ZIP skip duplicates but keep same-name different-content designs`() = withDirectory { directory ->
        val different = PesWriter.write(EmbPattern(listOf(Stitch(0, 0, Command.STITCH),
            Stitch(50, 50, Command.STITCH), Stitch(50, 50, Command.END)), pattern.threads))
        val bundle = zip("a/one.pes" to pes, "b/invalid.pes" to byteArrayOf(1, 2), "photo.jpg" to byteArrayOf(0))
        val tree = folder("root", source("one.pes", pes, "root/one"), folder("child",
            source("one.pes", pes, "child/one"), source("one.pes", different, "child/two"),
            source("bundle.zip", bundle), source("other-name.pes", pes)))
        val report = BatchDesignImporter(directory).import(listOf(tree))
        assertEquals(3, report.imported.size)
        assertEquals(2, report.skipped.count { it.reason == ImportSkipReason.DUPLICATE })
        assertEquals(2, report.skipped.count { it.reason == ImportSkipReason.UNSUPPORTED })
        assertEquals(setOf("one.pes", "one (1).pes", "other-name.pes"), files(directory).map { it.name }.toSet())
        // Provenance survives a new importer and the collision suffix assigned to the second file.
        val repeated = BatchDesignImporter(directory).import(listOf(source("one.pes", different)), decide = ::skipAll)
        assertTrue(repeated.imported.isEmpty())
        assertEquals(ImportSkipReason.DUPLICATE, repeated.skipped.single().reason)
        File(directory, "one (1).pes").delete()
        assertEquals(1, BatchDesignImporter(directory).import(listOf(source("one.pes", different))).imported.size)
    }

    @Test fun `provider failures and directory cycles do not stop other files`() = withDirectory { directory ->
        val broken = object : DesignImportEntry {
            override val id = "broken"
            override val name = "unavailable"
            override val directory = true
            override fun children(): List<DesignImportEntry> = error("Provider offline")
        }
        val cyclic = object : DesignImportEntry {
            override val id = "cyclic"
            override val name = "cycle"
            override val directory = true
            override fun children() = listOf(this, source("ok.pes", pes))
        }
        val report = BatchDesignImporter(directory).import(listOf(broken, cyclic))
        assertEquals(1, report.imported.size)
        assertEquals(ImportSkipReason.FAILED, report.skipped.single().reason)
    }

    @Test fun `archive signature must match zip extension and existing files require explicit choice`() = withDirectory { directory ->
        val importer = BatchDesignImporter(directory)
        val wrong = importer.import(listOf(source("pretend.pes", zip("hidden.pes" to pes))))
        assertTrue(wrong.imported.isEmpty())
        assertEquals(ImportSkipReason.UNSUPPORTED, wrong.skipped.single().reason)
        val first = importer.import(listOf(source("a.pes", pes)))
        assertFailsWith<IllegalArgumentException> { importer.import(listOf(source("a.pes", pes))) }
        val duplicate = importer.import(listOf(source("a.pes", pes)), decide = ::skipAll)
        assertTrue(duplicate.imported.isEmpty())
        assertEquals(ImportSkipReason.DUPLICATE, duplicate.skipped.single().reason)
        assertContentEquals(pes, first.imported.single().readBytes())
    }

    @Test fun `hundreds of designs report completed progress and a second pass imports none`() = withDirectory { directory ->
        val bytes = pes
        val entries = (1..350).map { source("design-$it.pes", bytes) }
        val progress = mutableListOf<DesignImportProgress>()
        val report = BatchDesignImporter(directory).import(entries, progress::add)
        assertEquals(350, report.imported.size)
        assertEquals(350, progress.last().completed)
        assertEquals(350, progress.last().total)
        val determinate = progress.filter { it.total != null && !it.committing }.map { it.completed }
        assertEquals(determinate.sorted(), determinate)
        assertEquals(350, BatchDesignImporter(directory).import(entries, decide = ::skipAll).skipped.count { it.reason == ImportSkipReason.DUPLICATE })
        assertEquals(350, files(directory).size)
    }

    @Test fun `cancelled streams close and leave no staging or partial archive files`() = withDirectory { directory ->
        var closed = false
        var reading = false
        val entry = object : DesignImportEntry {
            override val id = "cancelled"
            override val name = "large.pes"
            override val directory = false
            override fun open(): InputStream = object : InputStream() {
                override fun read(): Int { reading = true; return 0 }
                override fun close() { closed = true }
            }
        }
        assertFailsWith<CancellationException> {
            BatchDesignImporter(directory).import(listOf(entry), checkpoint = { if (reading) throw CancellationException() })
        }
        assertTrue(closed)
        assertTrue(files(directory).isEmpty())
        assertFalse(directory.listFiles().orEmpty().any { it.isDirectory })
    }

    private fun snapshot(directory: File) = directory.listFiles().orEmpty().filter { it.isFile }
        .associate { it.name to (it.readBytes().toList() to it.lastModified()) }

    @Test fun `cancelling duplicate choices imports neither new files nor replacements`() = withDirectory { directory ->
        val importer = BatchDesignImporter(directory)
        importer.import(listOf(source("existing.pes", pes)))
        val before = snapshot(directory)
        val report = importer.import(listOf(source("new.pes", pes), source("existing.pes", pes)), decide = { duplicates ->
            assertEquals(listOf("existing.pes"), duplicates.map { it.name })
            assertEquals(before, snapshot(directory)) // New files are still staged, even at the prompt.
            null
        })
        assertTrue(report.cancelled)
        assertTrue(report.imported.isEmpty())
        assertEquals(before, snapshot(directory))
        assertFalse(directory.listFiles().orEmpty().any { it.isDirectory })
    }

    @Test fun `skip copy and replace are explicit and preserve original data as chosen`() = withDirectory { directory ->
        val importer = BatchDesignImporter(directory)
        val original = importer.import(listOf(source("existing.pes", pes))).imported.single()
        assertTrue(original.setLastModified(1000))
        val before = snapshot(directory)
        val skipped = importer.import(listOf(source("existing.pes", pes)), decide = ::skipAll)
        assertTrue(skipped.imported.isEmpty())
        assertEquals(before, snapshot(directory))
        val copy = importer.import(listOf(source("existing.pes", pes)), decide = { it.associate { d -> d.id to DuplicateAction.COPY } })
        assertEquals("existing (1).pes", copy.imported.single().name)
        assertEquals(1000L, original.lastModified())
        assertContentEquals(pes, copy.imported.single().readBytes())
        val replaced = importer.import(listOf(source("existing.pes", pes)), decide = { it.associate { d -> d.id to DuplicateAction.REPLACE } })
        assertEquals(original, replaced.imported.single())
        assertNotEquals(1000L, original.lastModified())
        assertEquals(2, files(directory).size)
        assertContentEquals(pes, original.readBytes())
    }

    @Test fun `same name with different bytes defaults to copy and remembers original-name provenance`() = withDirectory { directory ->
        val importer = BatchDesignImporter(directory)
        val original = importer.import(listOf(source("a.pes", pes))).imported.single()
        // A different valid PES version gives different bytes under the same name.
        val different = PesWriter.write(EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(70, 70, Command.STITCH),
            Stitch(70, 70, Command.END)), pattern.threads))
        assertFalse(pes.contentEquals(different))
        val copy = importer.import(listOf(source("a.pes", different)), decide = { error("Different contents must default to copy") }).imported.single()
        assertEquals("a (1).pes", copy.name)
        assertContentEquals(pes, original.readBytes())
        importer.prepare(listOf(source("a.pes", different))).use { plan ->
            assertEquals(copy, plan.duplicates.single().existing)
            assertTrue(plan.commit(plan.duplicates.associate { it.id to DuplicateAction.SKIP }).imported.isEmpty())
        }
    }

    @Test fun `incomplete decisions cannot publish any candidate`() = withDirectory { directory ->
        val importer = BatchDesignImporter(directory)
        importer.import(listOf(source("a.pes", pes), source("b.pes", pes)))
        val before = snapshot(directory)
        importer.prepare(listOf(source("a.pes", pes), source("b.pes", pes), source("new.pes", pes))).use { plan ->
            assertFailsWith<IllegalArgumentException> { plan.commit(mapOf(plan.duplicates.first().id to DuplicateAction.REPLACE)) }
        }
        assertEquals(before, snapshot(directory))
    }

    @Test fun `cancel during commit rolls back copied files replacements provenance and timestamps`() = withDirectory { directory ->
        val importer = BatchDesignImporter(directory)
        importer.import(listOf(source("old.pes", pes)))
        File(directory, "old.pes").setLastModified(1000)
        val before = snapshot(directory)
        importer.prepare(listOf(source("old.pes", pes), source("new.pes", pes), source("last.pes", pes))).use { plan ->
            var cancel = false
            assertFailsWith<CancellationException> {
                plan.commit(plan.duplicates.associate { it.id to DuplicateAction.REPLACE }, progress = {
                    if (it.committing && it.completed == 2) cancel = true // Replace + copy already occurred.
                }, checkpoint = { if (cancel) throw CancellationException() })
            }
        }
        assertEquals(before, snapshot(directory))
        assertFalse(directory.listFiles().orEmpty().any { it.isDirectory })
    }

    @Test fun `files edited while the prompt was open are not replaced`() = withDirectory { directory ->
        val importer = BatchDesignImporter(directory)
        importer.import(listOf(source("old.pes", pes)))
        importer.prepare(listOf(source("old.pes", pes), source("new.pes", pes))).use { plan ->
            File(directory, "old.pes").writeText("edited elsewhere")
            assertFailsWith<IllegalStateException> { plan.commit(plan.duplicates.associate { it.id to DuplicateAction.REPLACE }) }
        }
        assertEquals("edited elsewhere", File(directory, "old.pes").readText())
        assertFalse(File(directory, "new.pes").exists())
    }

    @Test fun `repeated files in an archive cannot collide with following staged candidates`() = withDirectory { directory ->
        val report = BatchDesignImporter(directory).import(listOf(source("one.pes", pes),
            source("archive.zip", zip("one.pes" to pes, "two.pes" to pes)), source("three.pes", pes)))
        assertEquals(setOf("one.pes", "two.pes", "three.pes"), report.imported.map { it.name }.toSet())
        assertEquals(1, report.skipped.size)
    }

    @Test fun `unknown project types inside ZIP are skipped without losing valid siblings`() = withDirectory { directory ->
        val invalid = """{"format":"myembro","version":1,"hoop":{"name":"H","width":1000,"height":1000},"objects":[{"type":"unknown"}]}""".toByteArray()
        val report = BatchDesignImporter(directory).import(listOf(source("mixed.zip", zip(
            "good.pes" to pes, "unknown.myembro" to invalid, "also.pec" to PecWriter.write(pattern)))))
        assertEquals(2, report.imported.size)
        assertEquals("unknown.myembro", report.skipped.single().name)
        assertEquals(ImportSkipReason.UNSUPPORTED, report.skipped.single().reason)
        val empty = BatchDesignImporter(directory).import(listOf(source("empty.zip", zip())))
        assertEquals("empty.zip", empty.skipped.single().name)
    }
}
