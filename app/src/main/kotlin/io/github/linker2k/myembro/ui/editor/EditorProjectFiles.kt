// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.ProjectJson
import io.github.linker2k.myembro.engine.io.PesExport
import java.io.File

internal fun listProjectFiles(directory: File): List<File> = directory.listFiles {
    f -> f.isFile && f.extension.equals(ProjectJson.EXTENSION, ignoreCase = true)
}?.sortedByDescending { it.lastModified() }.orEmpty()

/** Keep the last successful save intact even when encoding or writing fails. */
internal fun saveProjectSnapshot(directory: File, name: String, project: Project): File {
    val encoded = ProjectJson.encode(project)
    check(directory.isDirectory || directory.mkdirs())
    val base = name.removeSuffix(".myembro").map { if (it == '/' || it == '\\' || it < ' ') '_' else it }
        .joinToString("").trim().take(60).ifBlank { "myembro" }
    val target = File(directory, PesExport.uniqueName("$base.myembro") { File(directory, it).exists() })
    val temp = File.createTempFile("project-", ".tmp", directory)
    try {
        temp.writeText(encoded)
        check(temp.renameTo(target)) { "Could not finish project save" }
    } finally { temp.delete() }
    return target
}
