// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.utils.Point

/** SVG px, +y downward. Opaque source tags are retained through planning. */
data class Stitch(
    val x: Double, val y: Double,
    val tags: Set<String> = emptySet(),
    val jump: Boolean = false, val trim: Boolean = false, val stop: Boolean = false,
    val colorChange: Boolean = false,
    val minStitchLengthMm: Double? = null,
    /** Adapter extension: explicit machine END; Ink/Stitch normally adds END while writing. */
    val end: Boolean = false,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Non-finite stitch coordinate" }
        require(minStitchLengthMm == null || minStitchLengthMm.isFinite())
    }
    constructor(point: Point, tags: Set<String> = emptySet()) : this(point.x, point.y, tags)
    val point: Point get() = Point(x, y)
    val isJump: Boolean get() = jump || "jump" in tags
    val isTrim: Boolean get() = trim || "trim" in tags
    val isStop: Boolean get() = stop || "stop" in tags
    val isColorChange: Boolean get() = colorChange || "color_change" in tags
    val isEnd: Boolean get() = end || "end" in tags
    val isLock: Boolean get() = "lock_stitch" in tags || "lock" in tags
    val isTerminator: Boolean get() = isTrim || isStop || isColorChange || isEnd
    fun distance(other: Stitch): Double = point.distance(other.point)
}
