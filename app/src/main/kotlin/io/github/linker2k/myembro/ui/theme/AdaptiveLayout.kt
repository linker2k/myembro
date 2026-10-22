// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Use the available container width, including multi-window/fold resizing, rather than device type. */
object MyembroLayout {
    val ExpandedWidth = 600.dp
    val WideWidth = 840.dp

    fun designColumns(width: Dp): Int = when {
        width >= WideWidth -> 3
        width >= 360.dp -> 2
        else -> 1
    }
}
