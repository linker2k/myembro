// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.io.PesExport
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipInputStream

/** Limits cover all ZIP entries, including ignored files, rather than trusting ZIP size headers. */
data class DesignImportLimits(
    val maxFileBytes: Long = 16L * 1024 * 1024,
    val maxTotalBytes: Long = 64L * 1024 * 1024,
    val maxInputBytes: Long = 64L * 1024 * 1024,
    val maxEntries: Int = 512,
    val maxDesigns: Int = 100,
) {
    init {
        require(maxFileBytes > 0 && maxTotalBytes > 0 && maxInputBytes > 0 && maxEntries > 0 && maxDesigns > 0)
    }
}

/**
 * Imports one design or a ZIP, flattening every entry name to a sanitized leaf file name.
 * Only PES/PEC/DST are extracted; nested archives and macOS resource forks are ignored.
 * All files are staged and parsed before publishing; a failed archive leaves no partial import.
 * Callers should close their stream with use and call on an IO dispatcher in Android UI.
 */
class DesignFileImporter(private val directory: File, private val limits: DesignImportLimits = DesignImportLimits()) {
    fun import(input: InputStream, fileName: String, mimeType: String? = null): List<File> {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create design library" }
        val staging = Files.createTempDirectory(directory.toPath(), ".design-import-").toFile()
        val staged = mutableListOf<Pair<File, String>>()
        val published = mutableListOf<File>()
        var total = 0L
        fun copyEntry(source: InputStream, output: File?) {
            var count = 0L
            val buffer = ByteArray(8192)
            val sink = output?.outputStream()
            try {
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    count += n
                    total += n
                    if (count > limits.maxFileBytes || total > limits.maxTotalBytes) {
                        throw IOException("Design import exceeds the file or total size limit")
                    }
                    sink?.write(buffer, 0, n)
                }
            } finally {
                sink?.close()
            }
        }
        fun stage(source: InputStream, name: String) {
            if (staged.size >= limits.maxDesigns) throw IOException("Too many embroidery files in ZIP")
            val file = File(staging, "${staged.size}.tmp")
            copyEntry(source, file)
            // Avoid importing HTML/error pages or arbitrary data renamed to .pes.
            PatternReader.read(file.readBytes(), name)
            staged += file to name
        }
        try {
            val stream = PushbackInputStream(LimitedInputStream(input, limits.maxInputBytes), 4)
            val header = ByteArray(4)
            var size = 0
            while (size < header.size) {
                val n = stream.read(header, size, header.size - size)
                if (n < 0) break
                size += n
            }
            stream.unread(header, 0, size)
            val zipSignature = size == 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
                ((header[2] == 3.toByte() && header[3] == 4.toByte()) || (header[2] == 5.toByte() && header[3] == 6.toByte()))
            val zip = zipSignature || fileName.endsWith(".zip", true) || mimeType in ZIP_MIMES
            if (zip) {
                // Closing ZipInputStream also closes the caller's stream, as normal for archive readers.
                ZipInputStream(stream).use { archive ->
                    var entries = 0
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        if (++entries > limits.maxEntries) throw IOException("Too many entries in ZIP")
                        if (entry.size > limits.maxFileBytes) throw IOException("ZIP entry exceeds size limit")
                        val path = entry.name.replace('\\', '/')
                        val leaf = path.substringAfterLast('/')
                        val ignored = entry.isDirectory || path.split('/').contains("__MACOSX") || leaf.startsWith("._")
                        if (!ignored && leaf.substringAfterLast('.', "").lowercase(Locale.ROOT) in SUPPORTED) {
                            stage(archive, safeName(leaf))
                        } else {
                            copyEntry(archive, null) // count ignored data too, including unknown-size entries
                        }
                        archive.closeEntry()
                    }
                }
            } else {
                stage(stream, safeName(fileName))
            }
            require(staged.isNotEmpty()) { "No PES, PEC or DST embroidery files found" }
            for ((file, name) in staged) {
                while (true) {
                    val target = File(directory, PesExport.uniqueName(name) { File(directory, it).exists() })
                    try {
                        Files.move(file.toPath(), target.toPath())
                        published += target
                        break
                    } catch (_: FileAlreadyExistsException) {
                        // Another importer chose the same name; choose a new one without replacing it.
                    }
                }
            }
            return published.toList()
        } catch (error: Exception) {
            published.forEach { it.delete() }
            throw error
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun safeName(name: String): String {
        val leaf = name.replace('\\', '/').substringAfterLast('/')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim()
        val extension = leaf.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in SUPPORTED) { "Unsupported embroidery file: $leaf" }
        var base = leaf.substringBeforeLast('.').trim().trim('.')
        if (base.isBlank()) base = "design"
        while (base.toByteArray(Charsets.UTF_8).size > 180) {
            val pair = base.length >= 2 && base.last().isLowSurrogate() && base[base.lastIndex - 1].isHighSurrogate()
            base = base.dropLast(if (pair) 2 else 1)
        }
        return "$base.$extension"
    }

    private class LimitedInputStream(private val source: InputStream, private val limit: Long) : InputStream() {
        private var count = 0L
        private fun account(size: Int): Int {
            if (size > 0 && (count + size).also { count = it } > limit) throw IOException("Input exceeds size limit")
            return size
        }
        override fun read(): Int = source.read().also { if (it >= 0) account(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = account(source.read(buffer, offset, length))
        override fun close() = source.close()
    }

    companion object {
        private val SUPPORTED = setOf("pes", "pec", "dst")
        private val ZIP_MIMES = setOf("application/zip", "application/x-zip", "application/x-zip-compressed")
    }
}
