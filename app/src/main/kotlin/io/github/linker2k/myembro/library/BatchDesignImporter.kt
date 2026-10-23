// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import io.github.linker2k.myembro.engine.io.PesExport
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CancellationException

/** Lazy provider nodes: enumeration and reads belong on IO, never on the UI thread. */
interface DesignImportEntry {
    val id: String
    val name: String
    val directory: Boolean
    fun children(): List<DesignImportEntry> = emptyList()
    fun open(): InputStream = error("Not a file: $name")
}

data class DesignImportProgress(val completed: Int, val total: Int?, val name: String, val committing: Boolean = false)
enum class ImportSkipReason { UNSUPPORTED, DUPLICATE, FAILED }
data class ImportSkip(val name: String, val reason: ImportSkipReason, val detail: String? = null)
data class DesignImportReport(val imported: List<File>, val skipped: List<ImportSkip>, val cancelled: Boolean = false)
enum class DuplicateAction { SKIP, COPY, REPLACE }
data class DuplicateDesign(val id: Int, val name: String, val existing: File)

/** Prepare all inputs before asking about duplicates. No library file is published during prepare. */
class BatchDesignImporter(private val directory: File) {
    fun prepare(roots: List<DesignImportEntry>, progress: (DesignImportProgress) -> Unit = {}, checkpoint: () -> Unit = {}): PreparedImport {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create design library" }
        val staging = Files.createTempDirectory(directory.toPath(), ".batch-import-").toFile()
        try {
            val skipped = mutableListOf<ImportSkip>()
            val sources = mutableListOf<DesignImportEntry>()
            val queue = ArrayDeque(roots)
            val visited = hashSetOf<String>()
            while (queue.isNotEmpty()) {
                checkpoint()
                val entry = queue.removeFirst()
                if (!visited.add(entry.id)) continue
                progress(DesignImportProgress(sources.size, null, entry.name))
                if (entry.directory) {
                    try { queue.addAll(entry.children()) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { skipped += ImportSkip(entry.name, ImportSkipReason.FAILED, e.message) }
                } else sources += entry
            }
            val index = synchronized(importLock) { DuplicateIndex(directory, checkpoint) }
            val candidates = mutableListOf<Candidate>()
            var stagedId = 0
            val seenContent = hashSetOf<String>()
            sources.forEachIndexed { position, source ->
                checkpoint()
                progress(DesignImportProgress(position, sources.size, source.name))
                if (!DesignFormats.accepts(source.name)) {
                    skipped += ImportSkip(source.name, ImportSkipReason.UNSUPPORTED)
                } else try {
                    val issueCount = skipped.size
                    val archiveCandidates = mutableListOf<Candidate>()
                    source.open().use { stream ->
                        DesignFileImporter(staging).import(stream, source.name,
                            onSkipped = { skipped += ImportSkip(it, ImportSkipReason.UNSUPPORTED) },
                            publish = { staged, name ->
                                checkpoint()
                                val file = File(staging, "candidate-${stagedId++}.tmp")
                                Files.move(staged.toPath(), file.toPath())
                                val hash = digest(file, checkpoint)
                                archiveCandidates += Candidate(file, name, hash, index.find(name, hash))
                                file
                            }, checkpoint = checkpoint)
                    }
                    if (archiveCandidates.isEmpty() && skipped.size == issueCount)
                        skipped += ImportSkip(source.name, ImportSkipReason.UNSUPPORTED)
                    archiveCandidates.forEach { candidate ->
                        // Repeated entries in this selection need only one copy and one decision.
                        if (seenContent.add(key(candidate.name, candidate.hash))) candidates += candidate
                        else { candidate.staged.delete(); skipped += ImportSkip(candidate.name, ImportSkipReason.DUPLICATE) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { skipped += ImportSkip(source.name, ImportSkipReason.FAILED, e.message) }
                progress(DesignImportProgress(position + 1, sources.size, source.name))
            }
            return PreparedImport(directory, staging, candidates.toList(), skipped.toList())
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** Synchronous convenience for JVM callers. Duplicates always require an explicit decision. */
    fun import(roots: List<DesignImportEntry>, progress: (DesignImportProgress) -> Unit = {}, checkpoint: () -> Unit = {},
        decide: (List<DuplicateDesign>) -> Map<Int, DuplicateAction>? = { duplicates ->
            require(duplicates.isEmpty()) { "Duplicate decisions required" }; emptyMap()
        },
    ): DesignImportReport = prepare(roots, progress, checkpoint).use { plan ->
        val actions = if (plan.duplicates.isEmpty()) emptyMap() else decide(plan.duplicates)
        if (actions == null) DesignImportReport(emptyList(), emptyList(), cancelled = true)
        else plan.commit(actions, progress, checkpoint)
    }

    internal data class Candidate(val staged: File, val name: String, val hash: String, val previous: File?)

    class PreparedImport internal constructor(private val directory: File, private val staging: File,
        private val candidates: List<Candidate>, private val skipped: List<ImportSkip>) : Closeable {
        val duplicates = candidates.mapIndexedNotNull { id, c -> c.previous?.let { DuplicateDesign(id, c.name, it) } }
        private var closed = false
        private var committed = false

        /** Decisions are complete before the first mutation. Roll back any IO/cancellation failure. */
        fun commit(actions: Map<Int, DuplicateAction>, progress: (DesignImportProgress) -> Unit = {}, checkpoint: () -> Unit = {}): DesignImportReport = synchronized(importLock) {
            check(!closed && !committed) { "Import plan is no longer available" }
            require(actions.keys == duplicates.map { it.id }.toSet()) { "Every duplicate requires a decision" }
            checkpoint()
            val index = DuplicateIndex(directory, checkpoint)
            // Never overwrite a file changed/deleted while the user was making decisions.
            candidates.forEach { c ->
                check(index.find(c.name, c.hash) == c.previous) { "Library changed; select the files again: ${c.name}" }
            }
            val published = mutableListOf<File>()
            val backups = linkedMapOf<File, File>()
            val imported = mutableListOf<File>()
            val issues = skipped.toMutableList()
            try {
                candidates.forEachIndexed { id, candidate ->
                    checkpoint()
                    progress(DesignImportProgress(id, candidates.size, candidate.name, committing = true))
                    val action = actions[id] ?: DuplicateAction.COPY
                    if (action == DuplicateAction.SKIP) {
                        issues += ImportSkip(candidate.name, ImportSkipReason.DUPLICATE)
                    } else {
                        val previous = candidate.previous
                        val target = if (action == DuplicateAction.REPLACE) {
                            checkNotNull(previous)
                            if (previous !in backups) {
                                val backup = File(staging, "backup-$id.tmp")
                                Files.copy(previous.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                                backups[previous] = backup
                            }
                            Files.move(candidate.staged.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            previous
                        } else {
                            val names = directory.listFiles().orEmpty().map { it.name.lowercase(Locale.ROOT) }.toSet()
                            val target = File(directory, PesExport.uniqueName(candidate.name) { it.lowercase(Locale.ROOT) in names })
                            Files.move(candidate.staged.toPath(), target.toPath())
                            published += target
                            target
                        }
                        imported += target
                        index.record(candidate.name, candidate.hash, target)
                    }
                }
                progress(DesignImportProgress(candidates.size, candidates.size, candidates.lastOrNull()?.name.orEmpty(), committing = true))
                checkpoint()
                if (imported.isNotEmpty()) index.save(staging)
                committed = true
                DesignImportReport(imported.distinct(), issues.toList())
            } catch (e: Exception) {
                published.asReversed().forEach { file -> runCatching { Files.deleteIfExists(file.toPath()) }.exceptionOrNull()?.let(e::addSuppressed) }
                backups.forEach { (target, backup) ->
                    runCatching { Files.move(backup.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                        .exceptionOrNull()?.let(e::addSuppressed)
                }
                throw e
            }
        }
        override fun close() { if (!closed) { closed = true; staging.deleteRecursively() } }
    }

    /** Provenance keeps the original name when a same-name different-content file gets a suffix. */
    private class DuplicateIndex(private val directory: File, checkpoint: () -> Unit) {
        private val file = File(directory, ".import-index.properties")
        private val entries = Properties().apply {
            if (file.isFile) runCatching { file.inputStream().use { load(it) } }.onFailure { clear() }
        }
        private val hashes = directory.listFiles().orEmpty()
            .filter { it.isFile && DesignFormats.extension(it.name) in DesignFormats.extensions }
            .associate { candidate -> candidate.name to digest(candidate, checkpoint) }
        init { hashes.forEach { (name, hash) -> entries[key(name, hash)] = name } }
        fun find(name: String, hash: String): File? {
            val target = entries.getProperty(key(name, hash)) ?: return null
            return File(directory, target).takeIf { it.parentFile == directory && hashes[target] == hash }
        }
        fun record(name: String, hash: String, target: File) { entries[key(name, hash)] = target.name }
        fun save(staging: File) {
            val temporary = File(staging, "index.properties")
            temporary.outputStream().use { entries.store(it, "Original file names and SHA-256") }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
    companion object {
        private val importLock = Any()
        private fun key(name: String, hash: String) = name.lowercase(Locale.ROOT) + "/" + hash
        private fun digest(file: File, checkpoint: () -> Unit): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
