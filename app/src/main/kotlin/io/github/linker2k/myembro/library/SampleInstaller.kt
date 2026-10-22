// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import java.io.File
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/** One-time pack installation, independent of Android so interrupted installs can be tested. */
internal class SampleInstaller(private val directory: File) {
    private val marker = File(directory, ".sample-pack-v1-installed")

    @Synchronized
    fun install(assetNames: List<String>, open: (String) -> InputStream) {
        if (marker.exists()) return
        val names = assetNames.filter { it.startsWith("sample_") && it.endsWith(".pes") }
            .onEach { require('/' !in it && '\\' !in it) { "Sample name must be a file name" } }
            .sorted()
        require(names.isNotEmpty()) { "Bundled sample pack is missing" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create sample directory" }
        for (name in names) {
            val target = File(directory, name)
            if (target.exists()) continue // never replace user edits or a completed previous copy
            val staging = File.createTempFile(".sample-", ".tmp", directory)
            try {
                open(name).use { input -> staging.outputStream().use { input.copyTo(it) } }
                try {
                    Files.move(staging.toPath(), target.toPath())
                } catch (_: FileAlreadyExistsException) {
                    // Another installer finished first; its completed file wins.
                }
            } finally {
                staging.delete()
            }
        }
        // Written only after every file succeeds; retries complete partial installs.
        marker.writeText("1\n")
    }
}
