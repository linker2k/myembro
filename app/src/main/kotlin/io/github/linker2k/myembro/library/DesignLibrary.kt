// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.linker2k.myembro.engine.io.PesExport
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

    /** Copies [uri] into the library and returns the stored file; never overwrites an existing design. */
    fun import(uri: Uri): File {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "design.pes"
        val safeName = displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        require(safeName.substringAfterLast('.', "").lowercase() in SUPPORTED) {
            "Unsupported file: $safeName"
        }
        val target = File(dir, PesExport.uniqueName(safeName) { File(dir, it).exists() })
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: error("Cannot read $displayName")
        return target
    }

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

    companion object {
        fun isSample(file: File): Boolean = file.name.startsWith("sample_")

        val SUPPORTED = setOf("pes", "pec", "dst")
    }
}
