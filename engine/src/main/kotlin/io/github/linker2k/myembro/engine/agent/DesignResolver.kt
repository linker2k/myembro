// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.edit.HoopSpec
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Approved host catalog value. IDs are opaque lookup keys, never paths or URLs. */
class DesignAsset(val id: String, val name: String, val fileName: String, val pattern: EmbPattern,
    sourceBytes: ByteArray?, val modifiedAt: Long = 0, val favorite: Boolean = false) {
    private val original = sourceBytes?.copyOf()
    val sourceBytes: ByteArray? get() = original?.copyOf()
    val sha256: String? = original?.let { MessageDigest.getInstance("SHA-256").digest(it)
        .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
    companion object {
        fun read(id: String, name: String, fileName: String, bytes: ByteArray,
            modifiedAt: Long = 0, favorite: Boolean = false) =
            DesignAsset(id,name,fileName,PatternReader.read(bytes,fileName),bytes,modifiedAt,favorite)
    }
}

/** Implementations access only installed/approved storage; the dispatcher never opens caller paths. */
interface DesignResolver {
    fun builtins(): List<DesignAsset>
    fun designs(): List<DesignAsset>
    fun resolve(id: String): DesignAsset? = (builtins()+designs()).firstOrNull { it.id == id }
}

/** Android-free samples/logo from the same shipped assets as the phone, without network access. */
class BundledDesignResolver : DesignResolver {
    private val catalog by lazy {
        fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
        val manifest=Json.parseToJsonElement(resource("/agent/designs/manifest.json").toString(Charsets.UTF_8)).jsonObject
        listOf(DesignAsset.read("builtin:myembro-logo","myembro logo","myembro_logo.pes",
            resource("/agent/builtins/myembro_logo.pes"))) + manifest.getValue("samples").jsonArray.map { item ->
            val row=item.jsonObject;val file=row.getValue("file").jsonPrimitive.content
            DesignAsset.read("builtin:$file",row.getValue("title").jsonPrimitive.content,file,resource("/agent/designs/$file"))
        }
    }
    // JVM has no user directory: shipped samples form its reproducible local library fixtures.
    private val library by lazy { catalog.drop(1).map { asset ->
        DesignAsset("library:${asset.fileName}",asset.name,asset.fileName,asset.pattern,asset.sourceBytes)
    } }
    override fun builtins() = catalog.toList()
    override fun designs() = library.toList()
}

/** Same filters/sort as LibraryViewModel, including its existing seven-day RECENT window. */
internal fun List<DesignAsset>.queryDesigns(query: String, filter: String, sort: String, now: Long): List<DesignAsset> {
    val cutoff=now-TimeUnit.DAYS.toMillis(7)
    val rows=filter { d -> d.name.contains(query.trim(),true) || d.fileName.contains(query.trim(),true) }.filter { d ->
        when(filter) {
            "ALL" -> true
            "PES" -> d.fileName.substringAfterLast('.').equals("pes",true)
            "FAVORITES" -> d.favorite
            "RECENT" -> d.modifiedAt>=cutoff
            "FITS_100" -> d.pattern.bounds?.let { it.width<=HoopSpec.H100.width &&
                it.height<=HoopSpec.H100.height } == true
            else -> false
        }
    }
    val byName=compareBy<DesignAsset> { it.fileName.lowercase(Locale.ROOT) }.thenBy { it.id }
    return when(sort) {
        "NAME" -> rows.sortedWith(byName)
        "STITCHES" -> rows.sortedWith(compareBy<DesignAsset> { it.pattern.stitchCount }.then(byName))
        else -> rows.sortedWith(compareByDescending<DesignAsset> { it.modifiedAt }.then(byName))
    }
}
