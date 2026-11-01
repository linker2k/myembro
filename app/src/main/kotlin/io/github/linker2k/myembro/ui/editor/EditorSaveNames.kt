// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.export.Preflight
import java.io.File

internal enum class EditorSaveKind(val extension: String) { PROJECT("myembro"), PES("pes") }
/** One save dialog for both formats; [form] false = only the replace confirmation of an opened file. */
internal data class EditorSavePrompt(val kind: EditorSaveKind, val suggestedName: String, val form: Boolean = true)
/** Shown when saving a design that was opened from an app file: replace it, or save under a new name. */
internal data class EditorSaveChoice(val file: File, val kind: EditorSaveKind)
internal class EditorSaveConflict(val fileName: String) : Exception(fileName)

internal fun designSaveStem(name: String): String =
    io.github.linker2k.myembro.engine.agent.documentSaveStem(name)

/** Existing numeric suffixes advance rather than accumulating (_2 -> _3). */
internal fun suggestDesignName(original: String, exists: (String) -> Boolean): String {
    val originalStem = designSaveStem(original)
    require(originalStem.isNotBlank())
    // designSaveStem already replaced the long-name characters export preflight rejects.
    val stem = originalStem
    val match = Regex("^(.*)_([0-9]+)$").matchEntire(stem)
    val root = match?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: stem
    val previous = match?.groupValues?.get(2)?.toLongOrNull()
    var number = previous?.takeIf { it in 1 until Long.MAX_VALUE }?.plus(1) ?: 2L
    while (true) {
        val suffix = "_$number"
        val candidate = root.take(80 - suffix.length) + suffix
        check(Preflight.isValidFileNameStem(candidate))
        if (!exists(candidate)) return candidate
        check(number < Long.MAX_VALUE)
        number++
    }
}

internal fun designSaveTarget(directory: File, name: String, extension: String): File {
    val stem = designSaveStem(name)
    require(stem.isNotBlank())
    val leaf = "$stem.$extension"
    return directory.listFiles()?.firstOrNull { it.name.equals(leaf, ignoreCase = true) } ?: File(directory, leaf)
}

/** Encoding is completed before opening/replacing a target. Never silently overwrite a user file. */
internal fun writeNamedDesign(directory: File, name: String, extension: String, overwrite: Boolean, bytes: ByteArray): File {
    check(directory.isDirectory || directory.mkdirs())
    val target = designSaveTarget(directory, name, extension)
    if (target.exists() && !overwrite) throw EditorSaveConflict(target.name)
    val temp = File.createTempFile("save-", ".tmp", directory)
    try {
        temp.writeBytes(bytes)
        if (target.exists() && !overwrite) throw EditorSaveConflict(target.name)
        check(temp.renameTo(target)) { "Could not finish design save" }
    } finally { temp.delete() }
    return target
}
