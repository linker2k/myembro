// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

internal fun isSvgPicture(context: Context, uri: Uri): Boolean {
    if (runCatching { context.contentResolver.getType(uri)?.substringBefore(';') }.getOrNull() == "image/svg+xml") return true
    if (uri.lastPathSegment?.endsWith(".svg", ignoreCase = true) == true) return true
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            it.moveToFirst() && it.getString(0)?.endsWith(".svg", ignoreCase = true) == true
        } == true
    }.getOrDefault(false)
}
