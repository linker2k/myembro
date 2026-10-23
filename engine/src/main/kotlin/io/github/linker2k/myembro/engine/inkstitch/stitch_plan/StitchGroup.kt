// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/stitch_group.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.model.EmbThread

/** Wire values correspond to Ink/Stitch's "start"/"end" positions. */
enum class LockPosition { START, END }

/** Contract only: concrete lock types are supplied by the lock_stitch port. */
fun interface LockStitch {
    fun stitches(stitches: List<Stitch>, position: LockPosition): List<Stitch>
}

/** A continuous stitch run. Jumps may be inserted BETWEEN groups by the explicit plan builder. */
data class StitchGroup(
    val color: EmbThread? = null,
    val stitches: List<Stitch> = emptyList(),
    val trimAfter: Boolean = false,
    val stopAfter: Boolean = false,
    val lockStart: LockStitch? = null,
    val lockEnd: LockStitch? = null,
    val minStitchLengthMm: Double? = null,
    val minJumpLengthMm: Double? = null,
    val forceLockStitches: Boolean = false,
) {
    init {
        require(minStitchLengthMm == null || minStitchLengthMm.isFinite())
        require(minJumpLengthMm == null || minJumpLengthMm.isFinite())
    }
    fun lockStitches(position: LockPosition, disableTies: Boolean = false): List<Stitch> =
        if (disableTies || stitches.size < 2) emptyList()
        else (if (position == LockPosition.START) lockStart else lockEnd)?.stitches(stitches, position).orEmpty()
}
