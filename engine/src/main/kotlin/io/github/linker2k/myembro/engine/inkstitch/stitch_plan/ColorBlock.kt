// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/color_block.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.model.EmbThread

/** Blocks are explicit, ordered sewing sequences. Color equality during planning uses sameColor(). */
data class ColorBlock(val color: EmbThread? = null, val stitches: List<Stitch> = emptyList()) {
    val lastStitch: Stitch? get() = stitches.lastOrNull()
}
