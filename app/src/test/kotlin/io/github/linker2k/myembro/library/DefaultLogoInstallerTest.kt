// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DefaultLogoInstallerTest {
    private val asset = File("src/main/assets/${DefaultLogoInstaller.ASSET}")

    @Test fun installsOnceAndADeletedLogoStaysDeleted() {
        val dir = Files.createTempDirectory("logo-").toFile()
        try {
            val installer = DefaultLogoInstaller(dir)
            installer.install("myembro 로고.pes") { asset.inputStream() }
            val logo = File(dir, "myembro 로고.pes")
            assertContentEquals(asset.readBytes(), logo.readBytes())
            assertEquals("myembro 로고.pes", DefaultLogoInstaller.installedName(dir))
            logo.delete()
            installer.install("myembro 로고.pes") { asset.inputStream() }
            assertFalse(logo.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun neverOverwritesAUserFileWithTheSameName() {
        val dir = Files.createTempDirectory("logo-").toFile()
        try {
            File(dir, "myembro logo.pes").writeText("mine")
            DefaultLogoInstaller(dir).install("myembro logo.pes") { asset.inputStream() }
            assertEquals("mine", File(dir, "myembro logo.pes").readText())
        } finally { dir.deleteRecursively() }
    }
}
