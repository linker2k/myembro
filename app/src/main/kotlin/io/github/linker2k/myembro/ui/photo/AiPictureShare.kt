// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Each invocation's explicit choice, never persisted. No photo file exists on the text-only path. */
internal fun aiPictureShareIntent(context: Context, prompt: String, photo: Bitmap?, includePhoto: Boolean): Intent {
    val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, prompt).setType("text/plain")
    if (includePhoto) {
        requireNotNull(photo)
        // A new bounded decoded PNG, not original bytes, EXIF, source URI or photo-library access.
        val directory = File(context.cacheDir, "ai_picture_shares").apply { mkdirs() }
        val file = File(directory, "reference-${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { check(photo.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            send.type = "image/png"
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newUri(context.contentResolver, "reference", uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (error: Exception) { file.delete(); throw error }
    }
    return send
}
