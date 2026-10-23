// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/stitch_plan.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch as MachineStitch

/** Explicit boundary, not a global writer hook. Existing PES/lettering/editor output is unaffected. */
object EmbPatternAdapter {
    fun fromEmbPattern(pattern: EmbPattern): StitchPlan {
        val blocks = mutableListOf<ColorBlock>()
        var stitches = mutableListOf<Stitch>()
        var color = 0
        for (s in pattern.stitches) {
            stitches += Stitch(Units.nativeToPixels(s.x), Units.nativeToPixels(s.y),
                jump = s.command == Command.JUMP, trim = s.command == Command.TRIM,
                stop = s.command == Command.STOP, colorChange = s.command == Command.COLOR_CHANGE,
                end = s.command == Command.END)
            if (s.command == Command.COLOR_CHANGE) {
                blocks += ColorBlock(pattern.threadAt(color++), stitches.toList())
                stitches = mutableListOf()
            }
        }
        if (stitches.isNotEmpty()) blocks += ColorBlock(pattern.threadAt(color), stitches.toList())
        return StitchPlan(blocks, pattern.metadata.toMap(), pattern.warnings.toList(), pattern.threads.toList())
    }

    /** Generated blocks get color-change boundaries and END; imported streams retain exact commands. */
    fun toEmbPattern(plan: StitchPlan): EmbPattern {
        val imported = plan.originalThreadPalette != null
        val blocks = if (imported) plan.colorBlocks else plan.colorBlocks.filter { it.stitches.isNotEmpty() }
        val threads = plan.originalThreadPalette ?: blocks.map { it.color ?: EmbThread(0xFF000000.toInt(), "Black") }
        val out = mutableListOf<MachineStitch>()
        for ((index, block) in blocks.withIndex()) {
            if (!imported && index > 0 && out.lastOrNull()?.command != Command.COLOR_CHANGE) {
                val previous = out.last()
                out += previous.copy(command = Command.COLOR_CHANGE)
            }
            for (s in block.stitches) {
                val x = Units.pixelsToNative(s.x); val y = Units.pixelsToNative(s.y)
                // Python can attach several flags to one point; native commands are emitted in this order.
                val commands = buildList {
                    if (s.isJump) add(Command.JUMP)
                    if (s.isTrim) add(Command.TRIM)
                    if (s.isStop) add(Command.STOP)
                    if (s.isColorChange) add(Command.COLOR_CHANGE)
                    if (s.isEnd) add(Command.END)
                    if (isEmpty()) add(Command.STITCH)
                }
                for (command in commands) out += MachineStitch(x, y, command)
            }
        }
        if (!imported && out.isNotEmpty() && out.last().command != Command.END) out += out.last().copy(command = Command.END)
        return EmbPattern(out.toList(), threads.toList(), plan.metadata.toMap(), plan.warnings.toList())
    }
}
