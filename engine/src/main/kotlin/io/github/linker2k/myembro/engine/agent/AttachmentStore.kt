// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.geometry.PICTURE_MIN_WIDTH_MM
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import java.util.UUID

/** Host-local capability lookup. Only IDs issued for a user's app-picked photo may resolve.
 * Never interpret an ID as a URI/path, fetch a URL, or put image data in a tool response.
 * Render using the existing photoCrop(frame aspect, zoom=1, pan=0) and filtered bitmap
 * sampling at the requested dimensions. Return uncorrected pixels: the engine applies
 * the photo preset exactly once. Color/BW preserve alpha; other modes composite white.
 */
fun interface AttachmentStore {
    fun raster(attachmentId: String, request: PhotoRasterRequest): PhotoRaster?
}
data class PhotoRasterRequest(val width: Int, val height: Int, val hoop: HoopSpec, val compositeWhite: Boolean)
class PhotoRaster(val width: Int, val height: Int, argb: IntArray) {
    private val pixels = argb.copyOf()
    internal fun pixels() = pixels.copyOf()
    init { require(width > 0 && height > 0 && width.toLong()*height == pixels.size.toLong()) }
}

/** Same local raster boundary as PhotoViewModel; never serialized in tool output. */
internal fun photoToolRaster(raster: PhotoRaster, mode: String, filter: String = "original"): PhotoCorrections.Raster =
    PhotoCorrections.apply(raster.width, raster.height, raster.pixels(), mode !in setOf("color", "bw"), photoCorrection(mode, filter))

private fun photoCorrection(mode: String, filter: String): PhotoCorrection {
    val correction = when(filter) {
        "original" -> PhotoCorrection.ORIGINAL
        "clean" -> PhotoCorrection.CLEAN
        "contrast" -> PhotoCorrection.CONTRAST
        "threshold" -> PhotoCorrection.OTSU
        else -> throw EmojiToolRejection("INVALID_ARGUMENTS", "Unknown photo filter")
    }
    // BW is the photo screen's COLOR + grayscale path, not a single-thread mode.
    if(correction == PhotoCorrection.OTSU && mode in setOf("color", "bw"))
        throw EmojiToolRejection("FILTER_NOT_SUPPORTED", "Threshold is available only for silhouette and outline")
    return correction
}

/** Wraps existing PhotoViewModel's default tracing/generation; no new stitch algorithms. */
internal class PhotoPreparation(private val attachments: AttachmentStore) {
    fun prepare(id: String, mode: String, colors: Int, widthMm: Double, hoop: HoopSpec, filter: String = "original"): PreparedEmoji {
        val correction = photoCorrection(mode, filter)
        val max = PhotoPreparationPolicy.photoMaxWidthMm(hoop)
        val range = PICTURE_MIN_WIDTH_MM..max
        if (!widthMm.isFinite() || widthMm !in range) throw EmojiToolRejection("WIDTH_OUT_OF_RANGE",
            "Width is outside the existing photo policy", kotlinx.serialization.json.buildJsonObject {
                put("minWidthMm", kotlinx.serialization.json.JsonPrimitive(range.start))
                put("maxWidthMm", kotlinx.serialization.json.JsonPrimitive(range.endInclusive))
            })
        val color = mode == "color" || mode == "bw"
        val edge = PhotoPreparationPolicy.photoRasterSize(1, widthMm, widthMm*hoop.height/hoop.width)
        val aspect = hoop.width.toDouble()/hoop.height
        val request = PhotoRasterRequest(if(aspect>=1) edge else (edge*aspect).toInt().coerceAtLeast(1),
            if(aspect>=1) (edge/aspect).toInt().coerceAtLeast(1) else edge, hoop, !color)
        val raster = try { attachments.raster(id, request) } catch (_: java.io.IOException) {
            throw EmojiToolRejection("ATTACHMENT_READ_FAILED", "Cannot read the selected attachment")
        } ?: throw EmojiToolRejection("ATTACHMENT_NOT_FOUND", "Select a photo in the app first")
        require(raster.width == request.width && raster.height == request.height) { "Unexpected attachment raster dimensions" }
        val corrected = photoToolRaster(raster, mode, filter)
        val pixels = corrected.pixels
        val thread = EmbThread(0xFFB5492A.toInt(), "Photo")
        // ColorDrawingPipeline is not VectorArt: keep its pattern verbatim until ShapeObject
        // accepts the resolved source regions + cached pattern. AutoTrace/redwork remain StitchObject.
        val pattern = if(color) {
            val preview = ColorDrawingPipeline.trace(raster.width,raster.height,pixels,widthMm,
                if(mode=="bw") 2 else colors,minAreaMm2=ColorDrawingPipeline.MINIMUM_REGION_AREA_MM2,grayscale=mode=="bw")
            ColorDrawingPipeline.generate(preview,hoop).pattern
        } else {
            pixels.indices.forEach { pixels[it] = pixels[it] and 0xFFFFFF }
            fun trace(rgb: IntArray) = RedworkPipeline.trace(raster.width,raster.height,rgb,widthMm,0,thread,
                minAreaMm2=PhotoPreparationPolicy.photoMinimumArea(1),maxContours=128)
            val outline = if(corrected.mask != null) {
                RedworkPipeline.traceMask(raster.width,raster.height,corrected.mask,widthMm,thread,
                    minAreaMm2=PhotoPreparationPolicy.photoMinimumArea(1),maxContours=128)
            } else try { trace(pixels) } catch(e: IllegalArgumentException) {
                if(correction == PhotoCorrection.CONTRAST || e.message?.contains("PHOTO_TOO_COMPLEX")==true) throw e
                trace(PhotoPreparationPolicy.photoContrastStretch(pixels))
            }
            RedworkPipeline.generate(outline,thread,photoMode=true,
                style=if(mode=="outline") PhotoStyle.OUTLINE else PhotoStyle.SILHOUETTE).pattern
        }
        val pes = try { PesWriter.writeV1(pattern,hoop) } catch (_: IllegalArgumentException) {
            throw EmojiToolRejection("SEWOUT_BLOCKED", "Photo did not pass the existing output gate")
        }
        val report = SewoutCheck.checkPes(pes,options=SewoutOptions(hoop=hoop,origin=DesignOrigin.APP_GENERATED))
        if(!report.analysisComplete || report.hasBlockingIssues) throw EmojiToolRejection("SEWOUT_BLOCKED","Photo did not pass the existing sewout gate")
        // Opaque local result capability, not a hash of private pixels/bytes or a path.
        return PreparedEmoji("photo-${UUID.randomUUID()}","Photo",pattern,widthMm,range,report.verdict)
    }
}
