// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.inkstitch.svg.image.Bt709Grayscale
import io.github.linker2k.myembro.engine.model.EmbPattern

/** UI choices only. Numerical parameters must be supplied by the verified engine adapter. */
enum class TextureDetail { COARSE, NORMAL, FINE }
enum class TextureRunLength { SHORT, NORMAL, LONG }
data class ThreadTextureOptions(
    val enabled: Boolean = false,
    val detail: TextureDetail = TextureDetail.NORMAL,
    val runLength: TextureRunLength = TextureRunLength.NORMAL,
)

/**
 * App-side seam, not a stitch algorithm. Input is the corrected (and optionally grayscale) ARGB
 * raster before quantization and the shared RGB Wu palette. Output uses engine 0.1mm
 * coordinates. The future adapter must bind current physical size/hoop/thread settings and
 * deliver a finished pattern; the existing PES writer and SewoutCheck still gate transfer.
 * There is deliberately no production fake or fallback to ordinary fill.
 */
fun interface ThreadTextureGenerator {
    fun generate(pixels: IntArray, w: Int, h: Int, palette: List<Int>,
        detail: TextureDetail, runLength: TextureRunLength): EmbPattern
}

/** Prepared in step 2; does no stitching. Generation is explicit in step 3 only. */
class ThreadTexturePreview private constructor(pixels: IntArray, val width: Int, val height: Int, palette: List<Int>, displayPixels: IntArray) {
    private val pixels = pixels.copyOf()
    private val palette = palette.toList()
    private val displayPixels = displayPixels.copyOf()
    init {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        require(palette.isNotEmpty())
    }
    fun previewPixels() = displayPixels.copyOf()
    fun generate(options: ThreadTextureOptions, generator: ThreadTextureGenerator?, ordinary: () -> EmbPattern): EmbPattern {
        if (!options.enabled) return ordinary()
        return requireNotNull(generator) { "Thread texture generator is not connected" }
            .generate(pixels.copyOf(), width, height, palette.toList(), options.detail, options.runLength)
    }
    companion object {
        fun from(preview: ColorDrawingPipeline.Preview, correctedPixels: IntArray, width: Int, height: Int, grayscale: Boolean) =
            ThreadTexturePreview(if (grayscale) IntArray(correctedPixels.size) { Bt709Grayscale.argb(correctedPixels[it]) }
                else correctedPixels, width, height, preview.palette, preview.previewPixels())
    }
}
