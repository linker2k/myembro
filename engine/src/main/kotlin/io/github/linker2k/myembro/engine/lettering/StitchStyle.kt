// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.Stitch

enum class StitchStyle { RUNNING, BEAN, SATIN, FILL }

/** Expand only sewn movements A→B into A→B→A→B, after quantization/subdivision. */
internal fun EmbPattern.withStitchStyle(style: StitchStyle, maxStitchLengthMm: Double? = null): EmbPattern {
    // SATIN is constructed from geometry before sampling, never from sewn movements.
    if (style != StitchStyle.BEAN) return GeneratedPatternProcessor.finish(this, maxStitchLengthMm = maxStitchLengthMm)
    val result = ArrayList<Stitch>()
    var previous: Stitch? = null
    for (stitch in stitches) {
        result += stitch
        val last = previous
        if (stitch.command == Command.STITCH && last?.command == Command.STITCH &&
            (last.x != stitch.x || last.y != stitch.y)
        ) {
            result += last
            result += stitch
        }
        previous = stitch
    }
    return GeneratedPatternProcessor.finish(EmbPattern(result, threads, metadata, warnings), maxStitchLengthMm = maxStitchLengthMm)
}
