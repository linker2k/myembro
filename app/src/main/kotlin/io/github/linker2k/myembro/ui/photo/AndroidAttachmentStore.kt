// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import io.github.linker2k.myembro.engine.agent.AttachmentStore
import io.github.linker2k.myembro.engine.agent.PhotoRaster

/**
 * prepare_photo's image source on the phone (docs/agent/dispatcher.md "App-picked photo tools").
 * [source] gives the picker URI only for an id the app issued to the pending prompt; any other id
 * (a path, a URL, another prompt's photo) is null. Rasterizes exactly like the photo screen: same
 * decode, centered zoom=1 crop in the hoop aspect, filtered sampling, white paper unless color/BW.
 */
internal class AndroidAttachmentStore(private val context: Context, private val source: (String) -> String?) : AttachmentStore {
    override fun raster(attachmentId: String, request: io.github.linker2k.myembro.engine.agent.PhotoRasterRequest): PhotoRaster? {
        val uri = source(attachmentId)?.let(Uri::parse) ?: return null
        val bitmap = try { decodeUprightPhoto(context.contentResolver, uri) }
            catch (e: SecurityException) { throw java.io.IOException(e) }
            catch (e: IllegalArgumentException) { throw java.io.IOException(e) }
        val hoop = request.hoop
        val crop = photoCrop(bitmap.width, bitmap.height, hoop.width.toDouble() / hoop.height, 1f, 0f, 0f)
        val (w, h) = request.width to request.height
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(scaled).apply {
            if (request.compositeWhite) drawColor(android.graphics.Color.WHITE)
            drawBitmap(bitmap, Rect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height),
                Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(w * h); scaled.getPixels(pixels, 0, w, 0, 0, w, h); scaled.recycle(); bitmap.recycle()
        return PhotoRaster(w, h, pixels)
    }
}
