// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

/** Shared physical thread diameter: engine positions are measured in 0.1 mm. */
object ThreadRendering {
    const val WIDTH_MM = 0.4f
    const val WIDTH_UNITS = WIDTH_MM * 10f

    /** Follow zoom proportionally, keeping a caller-supplied visibility floor when zoomed out. */
    fun widthPx(unitsToPixels: Float, minimumPx: Float = 1f): Float =
        maxOf(WIDTH_UNITS * unitsToPixels, minimumPx)
}
