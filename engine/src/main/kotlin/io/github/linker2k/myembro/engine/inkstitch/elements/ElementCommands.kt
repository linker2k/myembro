// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/element.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Command aliases originate in lib/update.py; ignore traversal in lib/elements/utils/nodes.py.
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.utils.Point

/** Resolved SVG commands. Parsing symbol connectors and choosing routes belong to SVG/element layers. */
data class ElementCommands(
    val ignore_object: Boolean = false,
    val ignore_layer: Boolean = false,
    val trim: Boolean = false,
    val stop: Boolean = false,
    val starting_point: Point? = null,
    val ending_point: Point? = null,
    /** Legacy aliases are consumed only when the modern command is absent. */
    val fill_start: Point? = null,
    val fill_end: Point? = null,
) {
    val startingPoint: Point? get() = starting_point ?: fill_start
    val endingPoint: Point? get() = ending_point ?: fill_end
    val ignored: Boolean get() = ignore_object || ignore_layer

    /** Clone flags are retained through OR, matching EmbroideryElement.embroider(). */
    fun applyTo(groups: List<StitchGroup>, trim_after: Boolean = false, stop_after: Boolean = false): List<StitchGroup> {
        if (ignored) return emptyList()
        val last = groups.indexOfLast { it.stitches.isNotEmpty() }
        if (last < 0) return groups
        return groups.mapIndexed { index, group ->
            if (index == last) group.copy(trimAfter = group.trimAfter || trim || trim_after, stopAfter = group.stopAfter || stop || stop_after)
            else group
        }
    }
}
