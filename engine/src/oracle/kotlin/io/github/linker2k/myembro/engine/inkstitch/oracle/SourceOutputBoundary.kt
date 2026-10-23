// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/output.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors.
// Matrix/rounding also follows pystitch v1.0.1 EmbMatrix.py/EmbEncoder.py (MIT); Python round ties-to-even.
// Pyembroidery 1.5.1 shares round semantics, but its older matrix multiplication order differs.
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.EmbPatternAdapter
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchPlan
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbPattern
import kotlin.math.round

/** File-output boundary only. Pure contract/native adapter/editor coordinate systems stay unchanged. */
internal object SourceOutputBoundary {
    data class Output(val pattern:EmbPattern,val origin:Point)
    internal fun roundNative(value:Double):Int {
        val native=round(value)
        require(native.isFinite() && native in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) { "Source output coordinate out of range" }
        return native.toInt()
    }
    fun convert(plan:StitchPlan):Output {
        val stitches=plan.colorBlocks.flatMap { it.stitches }
        require(stitches.isNotEmpty()) { "Empty source output plan" }
        // ColorBlock.bounding_box includes ALL flags/commands, and StitchPlan unions those boxes.
        val origin=Point((stitches.minOf { it.x }+stitches.maxOf { it.x })/2,(stitches.minOf { it.y }+stitches.maxOf { it.y })/2)
        val scale=10/Units.PIXELS_PER_MM
        fun quantize(px:Double,base:Double):Double {
            // Pystitch matrix evaluates point*scale + translated*scale; retain that order at half ties.
            val native=roundNative(px*scale + (-base*scale))
            // Present an already-quantized px point to the unchanged, lossless public adapter.
            return Units.nativeToPixels(native)
        }
        val centered=plan.copy(colorBlocks=plan.colorBlocks.map { block->block.copy(stitches=block.stitches.map {
            it.copy(x=quantize(it.x,origin.x),y=quantize(it.y,origin.y))
        }) })
        return Output(EmbPatternAdapter.toEmbPattern(centered),origin)
    }
}
