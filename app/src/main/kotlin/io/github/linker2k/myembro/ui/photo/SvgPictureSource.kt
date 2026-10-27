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
            readPicturePrefix(input, name)
        } ?: byteArrayOf()
    } catch (_: java.io.IOException) { byteArrayOf() }
      catch (_: SecurityException) { byteArrayOf() }
    return PictureSource(uri, name, PictureIntake.sniff(bytes, name))
}

/** Do not drain a shared provider's large/streamed image just to recognize its header. */
internal fun readPicturePrefix(input: java.io.InputStream, name: String?): ByteArray {
    val output=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    val limit=SvgPictureInput.MAX_BYTES+1
    while(output.size()<limit) {
        val count=input.read(buffer,0,minOf(buffer.size,limit-output.size()))
        if(count<=0) break
        output.write(buffer,0,count)
        val prefix=output.toByteArray()
        if(PictureIntake.sniff(prefix,name)!=PictureIntake.Kind.UNKNOWN) return prefix
    }
    return output.toByteArray()
}
