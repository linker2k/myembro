// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import android.content.Context
import io.github.linker2k.myembro.R
import java.io.File

/**
 * The user's design collection: files copied into app storage so they stay available even if
 * the original (a download, a chat attachment) disappears.
 */
class DesignLibrary(private val context: Context) {
    private val dir = File(context.filesDir, "designs").apply { mkdirs() }

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in SUPPORTED }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun delete(file: File) {
        if (file.parentFile == dir) file.delete()
    }

    /** Installs the whole pack once, including upgrades from the former single-flower demo.
     * Deleted samples stay deleted after installation; user files are never overwritten.
     */
    fun seedSampleIfEmpty() {
        SampleInstaller(dir).install(context.assets.list("samples").orEmpty().toList()) { name ->
            context.assets.open("samples/$name")
        }
    }

    /** Adds the 'myembro logo' default design once, under its localized name. */
    fun seedDefaultLogo() {
        DefaultLogoInstaller(dir).install(context.getString(R.string.library_logo_name) + ".pes") {
            context.assets.open(DefaultLogoInstaller.ASSET)
        }
    }

    fun isDefaultLogo(file: File): Boolean = file.parentFile == dir && file.name == DefaultLogoInstaller.installedName(dir)

    companion object {
        fun isSample(file: File): Boolean = file.name.startsWith("sample_")

        val SUPPORTED = DesignFormats.extensions
    }
}
