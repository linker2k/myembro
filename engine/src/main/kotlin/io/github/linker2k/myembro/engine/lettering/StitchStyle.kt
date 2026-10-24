// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/running_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Delegates bean_stitch (repeats=[1], three passes) to the shared worker port.
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
    val run = ArrayList<io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch>()
    fun flush() {
        // bean_stitch only repeats coordinates: native integer coordinates stay exact here.
        result += io.github.linker2k.myembro.engine.inkstitch.stitches.RunningStitch.beanStitch(run, listOf(1))
            .map { Stitch(it.x.toInt(), it.y.toInt(), Command.STITCH) }
        run.clear()
    }
    for (stitch in stitches) {
        if (stitch.command == Command.STITCH) {
            run += io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch(stitch.x.toDouble(), stitch.y.toDouble())
        } else {
            flush()
            result += stitch
        }
    }
    flush()
    return GeneratedPatternProcessor.finish(EmbPattern(result, threads, metadata, warnings), maxStitchLengthMm = maxStitchLengthMm)
}

/** Recenter with provenance so later editor transforms can safely rebuild the shared S1 locks. */
internal fun EmbPattern.centeredGenerated(): EmbPattern {
    val b = bounds ?: return this
    val cx = ((b.minX.toLong() + b.maxX) / 2).toInt()
    val cy = ((b.minY.toLong() + b.maxY) / 2).toInt()
    return GeneratedPatternProcessor.mapCoordinates(this) { x, y -> x - cx to y - cy }
}
