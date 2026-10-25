// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import java.nio.file.*
import java.security.MessageDigest

enum class DocumentKind(val extension:String) { PROJECT("myembro"), PES("pes") }
data class DocumentState(val name:String,val version:String)
data class StoredDocument(val name:String,val location:String,val version:String)
class DocumentConflict(message:String):IllegalStateException(message)

/** Approved destinations only. Null expectedVersion means CREATE_NEW; a version permits only
 * replacement of those exact existing bytes. Hosts serialize cooperating writes to a destination. */
interface DocumentStore {
    fun recordGeneratedPes(bytes:ByteArray) {}
    fun inspect(destinationId:String,name:String,kind:DocumentKind):DocumentState?
    fun write(destinationId:String,name:String,kind:DocumentKind,bytes:ByteArray,expectedVersion:String?):StoredDocument
}
fun documentDigest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Existing editor naming policy (EditorSaveNames), shared so UI and agent cannot disagree. */
fun documentSaveStem(name:String):String {
    val leaf=name.replace('\\','/').substringAfterLast('/')
    val stem=if(leaf.substringAfterLast('.',"").lowercase() in setOf("pes","pec","dst","myembro")) leaf.substringBeforeLast('.') else leaf
    return stem.map { if(it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }.joinToString("").trim().trim('.').take(80)
}
fun documentFileName(name:String,kind:DocumentKind):String {
    require(name.isNotBlank() && '/' !in name && '\\' !in name && name.none(Char::isISOControl)) { "Use a plain document name" }
    val stem=documentSaveStem(name);require(stem.isNotBlank());return "$stem.${kind.extension}"
}

/** JVM adapter. Host chooses folders; model supplies only a destination ID and leaf name. */
class DirectoryDocumentStore(destinations:Map<String,Path>):DocumentStore {
    private val directories=destinations.mapValues { (_,path)->Files.createDirectories(path).toRealPath() }
    constructor(directory:Path):this(mapOf("output" to directory))
    private fun target(id:String,name:String,kind:DocumentKind):Path {
        val folder=directories[id] ?: throw IllegalArgumentException("Unknown approved destination")
        val leaf=documentFileName(name,kind)
        val target=Files.list(folder).use { files->files.filter { it.fileName.toString().equals(leaf,true) }.findFirst().orElse(folder.resolve(leaf)) }
        require(!Files.isSymbolicLink(target)) { "Symlink targets are forbidden" }
        require(!Files.exists(target) || Files.isRegularFile(target)) { "Destination is not a regular file" }
        return target
    }
    @Synchronized override fun inspect(destinationId:String,name:String,kind:DocumentKind):DocumentState? {
        val p=target(destinationId,name,kind)
        return if(Files.exists(p)) DocumentState(p.fileName.toString(),documentDigest(Files.readAllBytes(p))) else null
    }
    @Synchronized override fun write(destinationId:String,name:String,kind:DocumentKind,bytes:ByteArray,expectedVersion:String?):StoredDocument {
        val target=target(destinationId,name,kind)
        if(inspect(destinationId,name,kind)?.version!=expectedVersion)throw DocumentConflict("Target changed; request confirmation again")
        val temp=Files.createTempFile(target.parent,".document-",".tmp")
        try {
            Files.write(temp,bytes)
            if(expectedVersion==null) Files.createLink(target,temp) else {
                if(inspect(destinationId,name,kind)?.version!=expectedVersion)throw DocumentConflict("Target changed while saving")
                Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temp) }
        return StoredDocument(target.fileName.toString(),target.toString(),documentDigest(bytes))
    }
}
