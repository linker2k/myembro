// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import io.github.linker2k.myembro.engine.edit.ProjectSourceResolver
import java.io.ByteArrayOutputStream
import java.io.File

/** Recover an old project's missing source bytes from the app's designs directory only. */
class DesignSourceResolver(private val directory: File) : ProjectSourceResolver {
    override fun resolve(fileName: String): ByteArray? = runCatching {
        // Old snapshots can contain an absolute import path. Use only its basename.
        val leaf = fileName.replace('\\', '/').substringAfterLast('/')
        if (leaf.isBlank() || leaf == "." || leaf == ".." || '\u0000' in leaf) return null
        val exact = File(directory, leaf)
        val source = if (exact.isFile) exact else directory.listFiles()
            ?.filter { it.isFile && it.name.equals(leaf, ignoreCase = true) }?.singleOrNull() ?: return null
        val root = directory.canonicalFile
        if (source.canonicalFile.parentFile != root || source.length() > MAX_BYTES) return null
        source.inputStream().use { input ->
            // A changed/oversize file must not prevent the saved project from opening.
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }.getOrNull()

    companion object { private const val MAX_BYTES = 32 * 1024 * 1024 }
}
