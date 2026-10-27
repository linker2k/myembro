// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.content.Intent

/** Stream payloads win over data; clip duplicates must not reorder a multi-share's last image. */
internal fun <T> externalInputUris(action: String?, data: T?, streams: List<T>, clips: List<T>,
    readable: (T) -> Boolean): List<T> {
    val candidates = when(action) {
        Intent.ACTION_VIEW -> listOfNotNull(data)
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> (streams + clips).ifEmpty { listOfNotNull(data) }
        else -> emptyList()
    }
    return candidates.filter(readable).distinct()
}

internal data class PictureRoute<T>(val picture: T?, val imports: List<T>)

/** Used after bounded content inspection for picker, VIEW and both share actions. */
internal fun <T> routePictureSources(sources: List<T>, kind: (T) -> PictureIntake.Kind): PictureRoute<T> {
    val (pictures, other) = sources.partition { kind(it).picture }
    return PictureRoute(pictures.lastOrNull(), other)
}
