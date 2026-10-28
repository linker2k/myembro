// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import java.io.File
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** Archive copy only. Never reads a design format, generates stitches, or checks machine/hoop limits. */
object OriginalFileExporter {
    interface Document {
        val name: String
        fun open(): OutputStream
        fun delete(): Boolean
    }
    interface Folder {
        fun names(): Set<String>
        fun create(name: String): Document
    }
    enum class FailureReason { FOLDER_ACCESS, SOURCE_READ, DESTINATION_CREATE, COPY }
    data class Copied(val source: File, val name: String, val bytes: Long)
    data class Failure(val source: File, val reason: FailureReason, val detail: String?, val partialFileRemains: Boolean = false)
    data class Report(val copied: List<Copied>, val failures: List<Failure>)

    /** Conservative case-insensitive collision avoidance also works on FAT/external providers. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if(!exists(name)) return name
        val dot=name.lastIndexOf('.').takeIf { it>0 } ?: name.length
        val stem=name.take(dot);val extension=name.drop(dot)
        var suffix=1
        while(exists("$stem ($suffix)$extension")) suffix++
        return "$stem ($suffix)$extension"
    }
    fun folderFailure(files: List<File>, error: Exception) = Report(emptyList(),files.map {
        Failure(it,FailureReason.FOLDER_ACCESS,error.message ?: error.javaClass.simpleName)
    })

    fun export(files: List<File>, folder: Folder, checkpoint: () -> Unit = {}): Report {
        val names=try { folder.names().toMutableSet() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { return folderFailure(files,e) }
        val copied=mutableListOf<Copied>();val failures=mutableListOf<Failure>()
        for(file in files) {
            checkpoint()
            val name=uniqueName(file.name) { candidate -> names.any { it.equals(candidate,ignoreCase=true) } }
            copy(file,checkpoint,create={folder.create(name).also { names.add(name) }},copied,failures)
            copied.lastOrNull()?.takeIf { it.source==file }?.let { names.add(it.name) }
        }
        return Report(copied,failures)
    }
    /** Target was just created by ACTION_CREATE_DOCUMENT; no overwrite/re-encoding is performed. */
    fun single(file: File, document: Document, checkpoint: () -> Unit = {}): Report {
        val copied=mutableListOf<Copied>();val failures=mutableListOf<Failure>()
        copy(file,checkpoint,{document},copied,failures,created=document)
        return Report(copied,failures)
    }
    private fun copy(file: File, checkpoint: () -> Unit, create: () -> Document,
        copied: MutableList<Copied>, failures: MutableList<Failure>, created: Document? = null) {
        var target=created
        var stage=FailureReason.SOURCE_READ
        fun cleanup(): Boolean = target?.let { runCatching { it.delete() }.getOrDefault(false).not() } ?: false
        try {
            val count=file.inputStream().use { input ->
                checkpoint()
                stage=FailureReason.DESTINATION_CREATE
                target=target ?: create()
                stage=FailureReason.COPY
                requireNotNull(target).open().use { output ->
                    val buffer=ByteArray(DEFAULT_BUFFER_SIZE)
                    var total=0L
                    while(true) {
                        checkpoint()
                        val read=input.read(buffer)
                        if(read<0) break
                        output.write(buffer,0,read);total+=read
                    }
                    output.flush()
                    total
                }
            }
            copied+=Copied(file,requireNotNull(target).name,count)
        } catch(e: CancellationException) { cleanup();throw e }
        catch(e: Exception) { failures+=Failure(file,stage,e.message ?: e.javaClass.simpleName,cleanup()) }
    }
}
