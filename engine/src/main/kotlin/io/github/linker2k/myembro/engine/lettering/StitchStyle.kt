// SPDX-License-Identifier: GPL-3.0-or-later
// Bean expansion port: Ink/Stitch 16e192849e619399ce8fdefb4c4d249dacdb4db1,
// lib/stitches/running_stitch.py: bean_stitch (repeats=[1]); Copyright (c) 2010 Authors.
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.Stitch

enum class StitchStyle { RUNNING, BEAN, SATIN, FILL }

/** Expand only sewn movements A→B into A→B→A→B, after quantization/subdivision. */
internal fun EmbPattern.withStitchStyle(style: StitchStyle, maxStitchLengthMm: Double? = null): EmbPattern {
    // SATIN already crossed the shared group builder; preserve its element parameters and locks.
    if (style == StitchStyle.SATIN) return this
    // Other styles are expanded before a single shared finishing pass.
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

/** Recenter with provenance so later editor transforms can safely rebuild the shared S1 locks. */
internal fun EmbPattern.centeredGenerated(): EmbPattern {
    val b = bounds ?: return this
    val cx = ((b.minX.toLong() + b.maxX) / 2).toInt()
    val cy = ((b.minY.toLong() + b.maxY) / 2).toInt()
    return GeneratedPatternProcessor.mapCoordinates(this) { x, y -> x - cx to y - cy }
}
