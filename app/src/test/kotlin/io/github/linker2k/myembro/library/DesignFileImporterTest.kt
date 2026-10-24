// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import io.github.linker2k.myembro.engine.io.PecWriter
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class DesignFileImporterTest {
    private val pattern = EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(20, 20, Command.STITCH),
        Stitch(0, 20, Command.STITCH), Stitch(20, 0, Command.STITCH), Stitch(20, 20, Command.END)), listOf(EmbThread(0xFF123456.toInt())))
    private val pes get() = PesWriter.write(pattern)
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }.toByteArray()
    private fun withDirectory(test: (File) -> Unit) {
        val root = Files.createTempDirectory("myembro-import-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun dst(): ByteArray = listOf(File("../engine/src/test/resources/fixtures/flower.dst"),
        File("engine/src/test/resources/fixtures/flower.dst")).first { it.isFile }.readBytes()

    @Test fun importsOnlySupportedDesignsAndFlattensUnixAndWindowsTraversal() = withDirectory { root ->
        val directory = File(root, "library")
        val archive = zip("../../escape.PES" to pes, "C:\\temp\\nested\\outline.PEC" to PecWriter.write(pattern),
            "folder/stitches.dst" to dst(), "folder/readme.txt" to "ignored".toByteArray(),
            "nested.zip" to zip("hidden.pes" to pes), "__MACOSX/._thing.pes" to byteArrayOf(1),
            "folder/" to byteArrayOf())
        val result = DesignFileImporter(directory).import(archive.inputStream(), "download.zip")
        assertEquals(listOf("escape.pes", "outline.pec", "stitches.dst"), result.map { it.name })
        assertTrue(result.all { it.canonicalFile.parentFile == directory.canonicalFile })
        assertFalse(File(root, "escape.PES").exists())
        assertEquals(result.toSet(), directory.listFiles()!!.toSet())
        assertContentEquals(pes, result[0].readBytes())
    }

    @Test fun duplicateLeafNamesAndExistingDesignsAreNeverOverwritten() = withDirectory { directory ->
        val existing = File(directory, "same.pes").apply { writeText("user original") }
        val archive = zip("first/same.pes" to pes, "second/same.pes" to pes)
        val result = DesignFileImporter(directory).import(archive.inputStream(), "bundle.zip")
        assertEquals(listOf("same (1).pes", "same (2).pes"), result.map { it.name })
        assertEquals("user original", existing.readText())
    }

    @Test fun zipSignatureIsDetectedWithGenericMimeAndNoZipExtension() = withDirectory { directory ->
        val result = DesignFileImporter(directory).import(zip("a.pes" to pes).inputStream(), "download.bin", "application/octet-stream")
        assertEquals("a.pes", result.single().name)
    }

    @Test fun singlePesImportIsBoundedValidatedAndSanitized() = withDirectory { directory ->
        val result = DesignFileImporter(directory).import(pes.inputStream(), "../../my:design.PES")
        assertEquals("my_design.pes", result.single().name)
        assertContentEquals(pes, result.single().readBytes())
        assertFails { DesignFileImporter(directory).import("not PES".byteInputStream(), "fake.pes") }
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun overlongUnicodeNamesStayInsideFilesystemLimits() = withDirectory { directory ->
        val result = DesignFileImporter(directory).import(zip(("사".repeat(150) + ".pes") to pes).inputStream(), "long.zip")
        assertTrue(result.single().name.toByteArray().size < 255)
        assertTrue(result.single().name.endsWith(".pes"))
    }

    @Test fun badDesignAfterGoodOneRollsBackTheWholeArchive() = withDirectory { directory ->
        assertFails {
            DesignFileImporter(directory).import(zip("good.pes" to pes, "bad.pes" to byteArrayOf(1,2,3)).inputStream(), "bad.zip")
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun actualExpandedBytesAreCountedEvenForIgnoredEntriesAndUnknownHeaderSizes() = withDirectory { directory ->
        val archive = zip("notes.txt" to ByteArray(10000))
        val limits = DesignImportLimits(maxFileBytes = 1000)
        assertFailsWith<IOException> { DesignFileImporter(directory, limits).import(archive.inputStream(), "bomb.zip") }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun aggregateExpandedAndCompressedInputLimitsAreEnforced() = withDirectory { directory ->
        val totalLimits = DesignImportLimits(maxFileBytes = 1000, maxTotalBytes = 1200)
        assertFailsWith<IOException> {
            DesignFileImporter(directory, totalLimits).import(zip("one.txt" to ByteArray(800), "two.txt" to ByteArray(800)).inputStream(), "total.zip")
        }
        val rawLimits = DesignImportLimits(maxInputBytes = 10)
        assertFailsWith<IOException> { DesignFileImporter(directory, rawLimits).import(pes.inputStream(), "large.pes") }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun entryAndDesignCountLimitsAbortWithoutPublishing() = withDirectory { directory ->
        assertFailsWith<IOException> {
            DesignFileImporter(directory, DesignImportLimits(maxEntries = 1))
                .import(zip("a.txt" to byteArrayOf(), "b.txt" to byteArrayOf()).inputStream(), "entries.zip")
        }
        assertFailsWith<IOException> {
            DesignFileImporter(directory, DesignImportLimits(maxDesigns = 1))
                .import(zip("a.pes" to pes, "b.pes" to pes).inputStream(), "designs.zip")
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun unsupportedOrMalformedArchivesAndInvalidLimitsFailClearly() = withDirectory { directory ->
        for (bytes in listOf(zip("notes.txt" to "notes".toByteArray()), byteArrayOf(1,2,3), zip())) {
            assertFails { DesignFileImporter(directory).import(bytes.inputStream(), "download.zip", "application/zip") }
        }
        assertFailsWith<IllegalArgumentException> { DesignImportLimits(maxFileBytes = 0) }
        assertFailsWith<IllegalArgumentException> { DesignImportLimits(maxEntries = 0) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
