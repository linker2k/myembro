// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.sewout.*

internal fun FindingCode.labelRes(): Int = when (this) {
    FindingCode.HOOP_EXCEEDED -> R.string.sewout_finding_hoop_exceeded
    FindingCode.MACHINE_AREA_EXCEEDED -> R.string.sewout_finding_machine_area_exceeded
    FindingCode.HOOP_COMPATIBILITY_UNKNOWN -> R.string.sewout_finding_hoop_compatibility_unknown
    FindingCode.LONG_STITCH -> R.string.sewout_finding_long_stitch
    FindingCode.SHORT_STITCH -> R.string.sewout_finding_short_stitch
    FindingCode.MACHINE_STITCH_LENGTH_EXCEEDED -> R.string.sewout_finding_machine_stitch_length_exceeded
    FindingCode.PEC_DELTA_EXCEEDED -> R.string.sewout_finding_pec_delta_exceeded
    FindingCode.DENSITY_HOTSPOT -> R.string.sewout_finding_density_hotspot
    FindingCode.REPEATED_PENETRATION -> R.string.sewout_finding_repeated_penetration
    FindingCode.OVERLAP_ACCUMULATION -> R.string.sewout_finding_overlap_accumulation
    FindingCode.EMPTY_DESIGN -> R.string.sewout_finding_empty_design
    FindingCode.EMPTY_COLOR_STAGE -> R.string.sewout_finding_empty_color_stage
    FindingCode.READER_WARNING -> R.string.sewout_finding_reader_warning
    FindingCode.INVALID_PES -> R.string.sewout_finding_invalid_pes
    FindingCode.MISSING_THUMBNAIL -> R.string.sewout_finding_missing_thumbnail
    FindingCode.ANALYSIS_LIMIT -> R.string.sewout_finding_analysis_limit
    FindingCode.STITCH_LIMIT_EXCEEDED -> R.string.sewout_finding_stitch_limit_exceeded
    FindingCode.COLOR_LIMIT_EXCEEDED -> R.string.sewout_finding_color_limit_exceeded
    FindingCode.FILE_SIZE_EXCEEDED -> R.string.sewout_finding_file_size_exceeded
    FindingCode.PES_VERSION_UNCONFIRMED -> R.string.sewout_finding_pes_version_unconfirmed
    FindingCode.COLOR_LIMIT_UNCONFIRMED -> R.string.sewout_finding_color_limit_unconfirmed
    FindingCode.STITCH_LIMIT_UNCONFIRMED -> R.string.sewout_finding_stitch_limit_unconfirmed
    FindingCode.FILE_SIZE_UNCONFIRMED -> R.string.sewout_finding_file_size_unconfirmed
    FindingCode.THUMBNAIL_REQUIREMENT_UNCONFIRMED -> R.string.sewout_finding_thumbnail_requirement_unconfirmed
    FindingCode.MACHINE_SPEED_UNCONFIRMED -> R.string.sewout_finding_machine_speed_unconfirmed
    FindingCode.JUMP_TRIM_UNCONFIRMED -> R.string.sewout_finding_jump_trim_unconfirmed
    FindingCode.MACHINE_STITCH_LENGTH_UNCONFIRMED -> R.string.sewout_finding_machine_stitch_length_unconfirmed
    FindingCode.ESTIMATE_ASSUMPTIONS -> R.string.sewout_finding_estimate_assumptions
    FindingCode.SPEED_ABOVE_MAXIMUM -> R.string.sewout_finding_speed_above_maximum
    FindingCode.BOBBIN_CAPACITY_UNCONFIRMED -> R.string.sewout_finding_bobbin_capacity_unconfirmed
    FindingCode.BOBBIN_INSUFFICIENT -> R.string.sewout_finding_bobbin_insufficient
}

internal fun CompatibilityRule.labelRes(): Int = when (this) {
    CompatibilityRule.FORMAT -> R.string.sewout_rule_format
    CompatibilityRule.PES_VERSION -> R.string.sewout_rule_pes_version
    CompatibilityRule.DESIGN_SIZE -> R.string.sewout_rule_design_size
    CompatibilityRule.SELECTED_HOOP -> R.string.sewout_rule_selected_hoop
    CompatibilityRule.STITCH_COUNT -> R.string.sewout_rule_stitch_count
    CompatibilityRule.COLOR_COUNT -> R.string.sewout_rule_color_count
    CompatibilityRule.FILE_SIZE -> R.string.sewout_rule_file_size
    CompatibilityRule.THUMBNAIL -> R.string.sewout_rule_thumbnail
}
