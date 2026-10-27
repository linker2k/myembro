// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.R

/** Shared by generated ShapeObjects and the stitch-only fallback layer. */
internal fun photoObjectName(mode: PhotoMode): Int = when(mode) {
    PhotoMode.COLOR -> R.string.photo_color_name
    PhotoMode.SILHOUETTE -> R.string.photo_name
    PhotoMode.OUTLINE -> R.string.photo_outline_name
}
