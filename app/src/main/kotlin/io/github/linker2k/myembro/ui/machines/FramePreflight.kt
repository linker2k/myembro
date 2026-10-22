// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.machines

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.export.MachineProfile as ExportMachineProfile
import io.github.linker2k.myembro.engine.export.Preflight
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings

/** Supplies actual selected-frame dimensions to the unchanged engine Preflight.
 * A larger/custom frame never expands a verified machine's continuous sewing area.
 * Unknown/free machines impose no claimed machine limit; the chosen physical frame still applies.
 */
fun EmbroiderySettings.exportProfile(frame: HoopSpec = defaultFrame.spec): ExportMachineProfile {
    val known = machineProfile?.takeUnless { it.id == "free" }
    val base = if (known?.id == "se400") ExportMachineProfile.SE400 else ExportMachineProfile(machineModel, frame.width, frame.height)
    return base.copy(name = machineModel,
        maxWidth = minOf(frame.width, known?.maximumArea?.width ?: frame.width),
        maxHeight = minOf(frame.height, known?.maximumArea?.height ?: frame.height))
}

fun EmbroiderySettings.checkFrameExport(pattern: EmbPattern, fileName: String, frame: HoopSpec = defaultFrame.spec): List<String> =
    Preflight.check(pattern, fileName, exportProfile(frame))
