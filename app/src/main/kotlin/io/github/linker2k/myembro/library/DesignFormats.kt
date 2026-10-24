// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import io.github.linker2k.myembro.engine.edit.ProjectJson
import io.github.linker2k.myembro.engine.edit.ProjectSourceResolver
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.io.PesExport
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import java.io.File
import java.util.Locale

/** MIME hints narrow the SAF picker; extension AND content are checked after selection. */
object DesignFormats {
    val extensions = setOf("pes", "pec", "dst", "myembro")
    val mimeTypes = arrayOf(
        "application/octet-stream", "application/x-pes", "application/x-pec", "application/x-dst",
        "application/vnd.brother.pes", "application/vnd.tajima.dst", "application/x-myembro",
        "application/json", "application/zip", "application/x-zip", "application/x-zip-compressed",
    )
    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun accepts(name: String) = extension(name) in extensions || extension(name) == "zip"
    fun read(file: File): EmbPattern = read(file, null)
    fun read(file: File, sewingOptions: SewingOptions?): EmbPattern {
        return read(file.readBytes(), file.name, sourceResolver(file), sewingOptions)
    }

    private fun sourceResolver(file: File): ProjectSourceResolver {
        val parent = file.absoluteFile.parentFile!!
        val sources = if (parent.name == "projects") File(parent.parentFile, "designs") else parent
        return DesignSourceResolver(sources)
    }

    fun preparePes(file: File, settings: PesWriteSettings, sewingOptions: SewingOptions? = null): PesExport.Result =
        if (extension(file.name) == "myembro") {
            val project = ProjectJson.decode(file.readText().removePrefix("\uFEFF"), sourceResolver = sourceResolver(file))
            val flattened = project.flatten(sewingOptions)
            io.github.linker2k.myembro.engine.sewout.SewoutLimits.requirePlaced(project.hoop,flattened.bounds)
            PesExport.Result(PesWriter.write(flattened, settings.copy(hoop = project.hoop)),
                "${file.nameWithoutExtension}.pes", true)
        } else PesExport.prepare(file.readBytes(), file.name, settings)

    fun read(bytes: ByteArray, name: String, sourceResolver: ProjectSourceResolver? = null, sewingOptions: SewingOptions? = null): EmbPattern {
        val prefix = bytes.take(8).toByteArray().toString(Charsets.ISO_8859_1)
        val valid = when (extension(name)) {
            "pes" -> prefix.startsWith("#PES")
            "pec" -> prefix.startsWith("#PEC0001")
            // DST has a fixed 512-byte ASCII header, beginning with its LA: label.
            "dst" -> bytes.size >= 515 && prefix.startsWith("LA:")
            "myembro" -> bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{")
            else -> false
        }
        require(valid) { "Unsupported file or mismatched signature: $name" }
        return if (extension(name) == "myembro") ProjectJson.decode(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"), sourceResolver = sourceResolver).flatten(sewingOptions)
        else PatternReader.read(bytes, name)
    }
}
