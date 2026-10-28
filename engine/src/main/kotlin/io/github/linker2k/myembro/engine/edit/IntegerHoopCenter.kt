// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.Bounds

/** Integer midpoint for the centered hoop index convention [-floor(W/2), W-1-floor(W/2)].
 * Odd extents have a negative half-unit center: 0..999 becomes -500..499.
 * Round the midpoint toward +infinity, using Long arithmetic before floorDiv to avoid overflow.
 * Subtract this midpoint from coordinates; applying the operation again is a no-op.
 */
fun integerHoopCenter(bounds: Bounds): Pair<Int, Int> =
    Math.floorDiv(bounds.minX.toLong() + bounds.maxX + 1, 2L).toInt() to
        Math.floorDiv(bounds.minY.toLong() + bounds.maxY + 1, 2L).toInt()
