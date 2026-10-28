// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.edit.HoopSpec
import java.io.File
import io.github.linker2k.myembro.engine.sewout.SewoutLimits

object HoopConfigCli {
    @JvmStatic fun main(args:Array<String>) {
        val hoop=HoopSpec.H100
        File(args.single()).apply { parentFile?.mkdirs() }.writeText(
            """{"source":"io.github.linker2k.myembro.engine.edit.HoopSpec.H100","width":${hoop.width},"height":${hoop.height},"units":"0.1mm","limits":{"hoop_safety_margin_units_0p1mm":${SewoutLimits.HOOP_SAFETY_MARGIN_UNITS_0P1MM},"max_stitch_units_0p1mm":${SewoutLimits.MAX_STITCH_LENGTH_UNITS_0P1MM},"density_cell_units_0p1mm":${SewoutLimits.DENSITY_CELL_UNITS_0P1MM},"density_warning_penetrations":${SewoutLimits.DENSITY_WARNING_PENETRATIONS_PER_CELL},"max_generated_penetrations":${SewoutLimits.MAX_GENERATED_PENETRATIONS_PER_CELL},"max_imported_penetrations":${SewoutLimits.MAX_IMPORTED_PENETRATIONS_PER_CELL},"max_consecutive_positions":${SewoutLimits.MAX_CONSECUTIVE_POSITIONS},"max_zero_length_motion_commands":${SewoutLimits.MAX_ZERO_LENGTH_MOTION_COMMANDS}}}"""+"\n")
    }
}
