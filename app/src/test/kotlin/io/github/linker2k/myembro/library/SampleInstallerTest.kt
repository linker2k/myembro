// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.*

class SampleInstallerTest {
    private fun withDirectory(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("myembro-samples-").toFile()
        try { test(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun installsEverySampleOnFirstRunAndLeavesExistingDesignsUntouched() = withDirectory { dir ->
        File(dir, "user.pes").writeText("user")
        File(dir, "flower.pes").writeText("old demo")
        val names = (1..27).map { "sample_$it.pes" }
        SampleInstaller(dir).install(names + listOf("LICENSES.md", "manifest.json", "flower.pes")) { it.byteInputStream() }
        names.forEach { assertEquals(it, File(dir, it).readText()) }
        assertEquals("user", File(dir, "user.pes").readText())
        assertEquals("old demo", File(dir, "flower.pes").readText())
        assertFalse(File(dir, "LICENSES.md").exists())
    }

    @Test fun deletedSamplesStayDeletedOnFutureLaunches() = withDirectory { dir ->
        SampleInstaller(dir).install(listOf("sample_a.pes")) { "a".byteInputStream() }
        File(dir, "sample_a.pes").delete()
        SampleInstaller(dir).install(listOf("sample_a.pes")) { error("must not reinstall") }
        assertFalse(File(dir, "sample_a.pes").exists())
    }

    @Test fun failedCopiesAreRetriedWithoutLeavingPartialFilesOrOverwritingCompletedFiles() = withDirectory { dir ->
        val names = listOf("sample_a.pes", "sample_b.pes")
        assertFailsWith<IOException> {
            SampleInstaller(dir).install(names) { name ->
                if (name == "sample_b.pes") object : InputStream() {
                    private var count = 0
                    override fun read(): Int = if (count++ < 8) 'x'.code else throw IOException("interrupted mid-copy")
                } else "complete".byteInputStream()
            }
        }
        assertEquals("complete", File(dir, "sample_a.pes").readText())
        assertFalse(File(dir, "sample_b.pes").exists())
        assertTrue(dir.listFiles()!!.none { it.extension == "tmp" || it.name.endsWith("installed") })
        SampleInstaller(dir).install(names) { name ->
            assertEquals("sample_b.pes", name)
            "retried".byteInputStream()
        }
        assertEquals("retried", File(dir, "sample_b.pes").readText())
    }

    @Test fun existingNamesAreNotOverwrittenAndUnsafeNamesAreRejected() = withDirectory { dir ->
        File(dir, "sample_a.pes").writeText("user edits")
        SampleInstaller(dir).install(listOf("sample_a.pes")) { error("must not replace") }
        assertEquals("user edits", File(dir, "sample_a.pes").readText())
        val fresh = File(dir, "fresh")
        assertFailsWith<IllegalArgumentException> { SampleInstaller(fresh).install(listOf("sample_/bad.pes")) { byteArrayOf().inputStream() } }
        assertFailsWith<IllegalArgumentException> { SampleInstaller(fresh).install(emptyList()) { byteArrayOf().inputStream() } }
    }
}
