// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import java.io.File
import java.io.InputStream

/**
 * One-time 'myembro logo' default design in the library. The marker holds the installed file
 * name, so the file stays deletable (a deleted logo is not reinstalled) and backups can skip it:
 * it is regenerated app data, not user work.
 */
internal class DefaultLogoInstaller(private val directory: File) {
    private val marker = File(directory, MARKER)

    @Synchronized
    fun install(name: String, open: () -> InputStream) {
        if (marker.exists()) return
        require('/' !in name && '\\' !in name && name.endsWith(".pes")) { "Logo name must be a PES file name" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create design directory" }
        val target = File(directory, name)
        if (!target.exists()) {
            val staging = File.createTempFile(".logo-", ".tmp", directory)
            try {
                open().use { input -> staging.outputStream().use { input.copyTo(it) } }
                check(staging.renameTo(target)) { "Cannot install default logo" }
            } finally { staging.delete() }
        }
        marker.writeText(name + "\n")
    }

    companion object {
        const val MARKER = ".default-logo-v1"
        const val ASSET = "designs/myembro_logo.pes"

        /** Installed default-logo file name in [directory] (the "designs" folder), if any. */
        fun installedName(directory: File): String? =
            File(directory, MARKER).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
