// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.sewout.*

/** User policy: preserve small paint with existing generators; never erase it just for its area.
 * 1.0mm is the existing Ink/Stitch satin lower-width default, not a new generator parameter.
 */
internal object SmallRegionStitches {
    fun type(maximumWidthMm: Double, preferred: StitchTypeRule.Type): StitchTypeRule.Type =
        if(maximumWidthMm < StitchTypeRule.SATIN_MIN_MM) StitchTypeRule.Type.RUNNING
        else if(preferred==StitchTypeRule.Type.RUNNING) StitchTypeRule.Type.TATAMI else preferred

    /** Only an unrepresentable tiny run may disappear. Other blockers remain visible at final export. */
    fun running(groups: List<StitchGroup>, hoop: HoopSpec): StitchTypeRule.RegionStitches {
        val p=GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
        // Lock needles cannot stand in for the drawing itself. Count the finished visible run.
        val locks=p.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY].orEmpty().split(',').mapNotNull(String::toIntOrNull).toSet()
        val ink=p.stitches.withIndex().filter { it.value.command==Command.STITCH && it.index !in locks }.map { it.value }
        if(ink.size<2 || ink.map { it.x to it.y }.distinct().size<2) return StitchTypeRule.RegionStitches(emptyList(),null)
        try { PesWriter.writeChecked(p,PesWriteSettings(hoop=hoop),origin=DesignOrigin.APP_GENERATED) }
        catch(e:PesOutputSafety.Rejected) {
            if(e.report.findings.any { it.severity==Severity.BLOCKER && it.code in setOf(FindingCode.ZERO_LENGTH_MOTION,FindingCode.REPEATED_PENETRATION) })
                return StitchTypeRule.RegionStitches(emptyList(),null)
            // Never hide a density/hoop/length/lock blocker by dropping a representable region.
        }
        return StitchTypeRule.RegionStitches(groups,StitchTypeRule.Type.RUNNING)
    }
}
