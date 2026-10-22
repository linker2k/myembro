// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.exceedsHoop
import io.github.linker2k.myembro.export.ExportChecks
import io.github.linker2k.myembro.ui.machines.MachineProfile
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings

/** Read the actual profile hoops, including accessories absent from HoopSpec.ALL (e.g. SA431). */
internal fun editorHoops(profile: MachineProfile?): List<HoopSpec> = profile?.hoops.orEmpty()
    .map { it.sewingArea }
    .filter { it.width <= profile!!.maximumArea.width && it.height <= profile.maximumArea.height }
    .distinctBy { it.width to it.height }

internal fun HoopSpec.sameArea(other: HoopSpec) = width == other.width && height == other.height

/** Returned problems block PES creation; .myembro remains savable to preserve unfinished work. */
internal fun editorExportProblems(project: Project, settings: EmbroiderySettings, fileName: String): List<String> {
    val problems = mutableListOf<String>()
    if (settings.machineProfile == null) problems += "machine_unknown"
    else if (editorHoops(settings.machineProfile).none { it.sameArea(project.hoop) }) problems += "hoop_unsupported"
    if (project.exceedsHoop()) problems += "hoop_overflow"
    problems += ExportChecks.check(project.flatten(), fileName, settings)
    return problems
}
