// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.ProjectJson
import java.io.File

internal fun listProjectFiles(directory: File): List<File> = directory.listFiles {
    f -> f.isFile && f.extension.equals(ProjectJson.EXTENSION, ignoreCase = true)
}?.sortedByDescending { it.lastModified() }.orEmpty()

/** Keep the last successful save intact even when encoding or writing fails. */
internal fun saveProjectSnapshot(directory: File, name: String, project: Project, overwrite: Boolean = false): File =
    writeNamedDesign(directory, name, ProjectJson.EXTENSION, overwrite, ProjectJson.encode(project).toByteArray(Charsets.UTF_8))
