// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgPictureInput

internal data class PictureSource(val uri: Uri, val name: String, val kind: PictureIntake.Kind)
/** Call on IO: bounded sniff only, no XML parse, download, write or URI permission expansion. */
internal fun inspectPicture(context: Context, uri: Uri): PictureSource {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // Same bounded prefix as the SVG limit, plus its overflow sentinel. Raster magic needs 12 bytes.
            val buffer = ByteArray(SvgPictureInput.MAX_BYTES + 1); var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count <= 0) break
                size += count
            }
            buffer.copyOf(size)
        } ?: byteArrayOf()
    } catch (_: java.io.IOException) { byteArrayOf() }
      catch (_: SecurityException) { byteArrayOf() }
    return PictureSource(uri, name, PictureIntake.sniff(bytes, name))
}
