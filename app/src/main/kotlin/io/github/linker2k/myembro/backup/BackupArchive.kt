// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.backup

import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.*
import kotlinx.serialization.json.*

/** Versioned, byte-preserving user-data archive. No embroidery reader/writer is involved. */
object BackupArchive {
    enum class Duplicates { SKIP, COPY }
    data class Restored(val added: Int, val skipped: Int)
    private const val MAX_FILES = 20_000
    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private val roots = setOf("designs", "projects", "fonts", "drafts")
    private val looseFiles = setOf("usb-transfers.json")
    private fun allowed(path: String): Boolean = path.isNotEmpty() && '\\' !in path && '\u0000' !in path &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        (path.substringBefore('/') in roots || path in looseFiles)
    private fun files(root: File) = root.walkTopDown().onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }.filter {
        it.isFile && !Files.isSymbolicLink(it.toPath()) && allowed(it.relativeTo(root).invariantSeparatorsPath) &&
            it.canonicalPath.startsWith(root.canonicalPath + File.separator) &&
            it.extension !in setOf("tmp", "part")
    }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
    private fun sha(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf=ByteArray(65536); while(true) { val n=input.read(buf);if(n<0)break;digest.update(buf,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun write(root: File, preferences: JsonObject, output: OutputStream, progress: (Int,Int)->Unit = {_,_->}) {
        val files=files(root)
        require(files.size<=MAX_FILES && files.sumOf { it.length() }<=MAX_TOTAL_BYTES)
        val entries=files.map { file ->
            require(file.length()<=MAX_ENTRY_BYTES)
            buildJsonObject { put("path",file.relativeTo(root).invariantSeparatorsPath);put("size",file.length());put("sha256",sha(file));put("modified",file.lastModified()) }
        }
        val manifest=buildJsonObject {
            put("format","myembro-backup");put("version",1);put("preferences",preferences)
            put("files",JsonArray(entries))
            put("downloadedFonts",JsonArray(entries.map { it.getValue("path") }.filter { it.jsonPrimitive.content.startsWith("fonts/") }))
        }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString().toByteArray());zip.closeEntry()
            files.forEachIndexed { i,file ->
                zip.putNextEntry(ZipEntry("files/"+file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) };zip.closeEntry();progress(i+1,files.size)
            }
        }
    }

    class Plan internal constructor(private val stage: File, private val entries: List<JsonObject>, val preferences: JsonObject) : Closeable {
        val fileCount get()=entries.size
        fun duplicateCount(root: File)=entries.count { File(root,it.getValue("path").jsonPrimitive.content).exists() }
        /** Only new files are published; failures roll them back. Settings applied by caller inside [commit]. */
        fun restore(root: File, duplicates: Duplicates, commit: (JsonObject)->Unit = {}): Restored {
            val created=mutableListOf<File>();var skipped=0
            try {
                for(entry in entries) {
                    val path=entry.getValue("path").jsonPrimitive.content
                    val source=File(stage,"files/$path")
                    var target=File(root,path)
                    require(target.canonicalPath.startsWith(root.canonicalPath+File.separator)) { "Unsafe destination" }
                    if(target.exists()) {
                        // Font bundles have stable IDs and sidecar references. Preserve an installed bundle rather than making broken filename-only copies.
                        if(duplicates==Duplicates.SKIP || path.startsWith("fonts/")) { skipped++;continue }
                        var n=1;val parent=target.parentFile;val base=target.nameWithoutExtension;val ext=target.extension.let { if(it.isEmpty()) "" else ".$it" }
                        do { target=File(parent,"$base (${n++})$ext") } while(target.exists())
                    }
                    target.parentFile!!.mkdirs()
                    // CREATE_NEW prevents races from overwriting a file created since duplicate selection.
                    Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE).use { output ->
                        created+=target;source.inputStream().use { it.copyTo(output) }
                    }
                    check(sha(target)==entry.getValue("sha256").jsonPrimitive.content) { "Restored byte verification failed" }
                    target.setLastModified(entry.getValue("modified").jsonPrimitive.long)
                }
                commit(preferences)
                return Restored(created.size,skipped)
            } catch(e:Exception) { created.asReversed().forEach { it.delete() };throw e }
        }
        override fun close() { stage.deleteRecursively() }
    }

    /** Validate into isolated cache storage before asking for duplicate policy or mutating app data. */
    fun read(input: InputStream, cache: File): Plan {
        cache.mkdirs();val stage=Files.createTempDirectory(cache.toPath(),"restore-").toFile()
        try {
            val names=mutableSetOf<String>();var total=0L
            ZipInputStream(input.buffered()).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    val name=entry.name
                    require(names.add(name) && names.size<=MAX_FILES+1) { "Duplicate/too many ZIP entries" }
                    require(!entry.isDirectory && (name=="manifest.json" || name.startsWith("files/") && allowed(name.removePrefix("files/")))) { "Not a myembro backup entry" }
                    val target=File(stage,name);target.parentFile!!.mkdirs()
                    var size=0L
                    target.outputStream().use { out -> val buf=ByteArray(65536);while(true) {
                        val n=zip.read(buf);if(n<0)break;size+=n;total+=n
                        require(size<=MAX_ENTRY_BYTES && total<=MAX_TOTAL_BYTES) { "Backup size limit exceeded" };out.write(buf,0,n)
                    } };zip.closeEntry()
                }
            }
            val manifest=Json.parseToJsonElement(File(stage,"manifest.json").readText()).jsonObject
            require(manifest["format"]?.jsonPrimitive?.content=="myembro-backup" && manifest["version"]?.jsonPrimitive?.int==1) { "Unsupported backup version" }
            val entries=manifest.getValue("files").jsonArray.map { it.jsonObject }
            val paths=entries.map { it.getValue("path").jsonPrimitive.content }
            require(paths.distinct().size==paths.size && names==paths.map { "files/$it" }.toSet()+"manifest.json") { "Backup manifest mismatch" }
            for(entry in entries) {
                val path=entry.getValue("path").jsonPrimitive.content;require(allowed(path))
                val file=File(stage,"files/$path")
                require(file.length()==entry.getValue("size").jsonPrimitive.long && sha(file)==entry.getValue("sha256").jsonPrimitive.content) { "Backup checksum mismatch" }
            }
            return Plan(stage,entries,manifest.getValue("preferences").jsonObject)
        } catch(e:Exception) { stage.deleteRecursively();throw e }
    }
}
