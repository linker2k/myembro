// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/stitch_plan.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.model.EmbThread

/** Pure contract: constructing a plan does NOT filter, add locks, normalize or write anything. */
data class StitchPlan(
    val colorBlocks: List<ColorBlock> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList(),
    /** Adapter extension: non-null preserves an imported command stream/palette verbatim, even empty. */
    val originalThreadPalette: List<EmbThread>? = null,
)
