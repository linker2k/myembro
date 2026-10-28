// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.texture.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.sewout.*

/** UI mapping approved against the 108-case engine raster-pitch sweep. */
enum class TextureDetail {
    COARSE, NORMAL, FINE;
    val pitchMmPerPixel: Double get() = when (this) {
        FINE -> TextureRasterPolicy.fineMinimumMmPerPixel
        NORMAL -> TextureRasterPolicy.coarseMmPerPixel.first()
        COARSE -> TextureRasterPolicy.coarseMmPerPixel.last()
    }
}
data class ThreadTextureOptions(val enabled: Boolean = false, val detail: TextureDetail = TextureDetail.FINE)

/** Corrected pre-quantization ARGB and matched Brother threads; native output units are 0.1mm. */
fun interface ThreadTextureGenerator {
    fun generate(pixels: IntArray, w: Int, h: Int, palette: List<EmbThread>, detail: TextureDetail): EmbPattern
}

/** Only the engine supplies plotting, running stitches, locks and finalization. */
class EngineThreadTextureGenerator(private val widthMm: Double, private val hoop: HoopSpec,
    private val isCancelled: () -> Boolean = { false }, private val onProgress: (Double) -> Unit = {},
) : ThreadTextureGenerator {
    override fun generate(pixels: IntArray, w: Int, h: Int, palette: List<EmbThread>, detail: TextureDetail): EmbPattern =
        ThreadTextureAdapter.generate(w,h,pixels,widthMm,palette,hoop,
            pitchMmPerPixel=detail.pitchMmPerPixel,
            isCancelled={ Thread.currentThread().isInterrupted || isCancelled() },
            onProgress={ onProgress(it.actual.coerceIn(0.0,1.0)) }).pattern
}

/** Prepared in step 2 without contour tracing/stitching. Original pixels retain tonal variation. */
class ThreadTexturePreview private constructor(pixels: IntArray, val width: Int, val height: Int,
    palette: List<EmbThread>, displayPixels: IntArray,
) {
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
        return requireNotNull(generator).generate(pixels.copyOf(),width,height,palette.toList(),options.detail)
    }
    companion object {
        fun prepare(correctedPixels: IntArray, width: Int, height: Int, colors: Int, grayscale: Boolean): ThreadTexturePreview {
            val input = if(grayscale) IntArray(correctedPixels.size) { Bt709Grayscale.argb(correctedPixels[it]) } else correctedPixels
            val palette = ThreadTextureAdapter.brotherPalette(input,colors).let { threads ->
                if(grayscale) threads.map { Bt709Grayscale.nearestThread(it.color) }.distinctBy { it.color } else threads
            }
            return ThreadTexturePreview(input,width,height,palette,input)
        }
        // Legacy shared-color preview adapter retained for the default-off regression tests.
        fun from(preview: ColorDrawingPipeline.Preview, correctedPixels: IntArray, width: Int, height: Int, grayscale: Boolean) =
            ThreadTexturePreview(if(grayscale) IntArray(correctedPixels.size) { Bt709Grayscale.argb(correctedPixels[it]) }
                else correctedPixels,width,height,preview.layers.map { it.thread },preview.previewPixels())
    }
}

/** Check normalized actual PES once; preserve warning highlights and typed rejection reports. */
object ThreadTextureOutputGate {
    /** Use codes/severity, never engine-localized messages. Count limit wins over density. */
    fun blockerMessage(severities: Map<FindingCode, Severity>): Int = when {
        severities[FindingCode.STITCH_LIMIT_EXCEEDED] == Severity.BLOCKER -> io.github.linker2k.myembro.R.string.photo_texture_stitch_limit
        severities[FindingCode.DENSITY_HOTSPOT] == Severity.BLOCKER -> io.github.linker2k.myembro.R.string.photo_texture_dense
        severities.any { (code,severity) -> severity==Severity.BLOCKER && code in setOf(FindingCode.HOOP_EXCEEDED,FindingCode.MACHINE_AREA_EXCEEDED) } -> io.github.linker2k.myembro.R.string.block_sewout_hoop
        severities.any { (code,severity) -> severity==Severity.BLOCKER && code in setOf(FindingCode.ZERO_LENGTH_MOTION,FindingCode.REPEATED_PENETRATION) } -> io.github.linker2k.myembro.R.string.block_sewout_repeated
        severities[FindingCode.MISSING_LOCK_STITCH] == Severity.BLOCKER -> io.github.linker2k.myembro.R.string.block_sewout_lock
        severities[FindingCode.LONG_STITCH] == Severity.BLOCKER -> io.github.linker2k.myembro.R.string.block_sewout_long
        severities[FindingCode.INVALID_PES] == Severity.BLOCKER -> io.github.linker2k.myembro.R.string.block_sewout_file
        severities[FindingCode.ANALYSIS_LIMIT] == Severity.BLOCKER -> io.github.linker2k.myembro.R.string.block_sewout_incomplete
        else -> io.github.linker2k.myembro.R.string.photo_texture_blocked
    }

    data class Result(val report: SewoutReport, val bytes: ByteArray?) {
        val canUse get() = report.analysisComplete && !report.hasBlockingIssues && bytes != null
        val densityBlocked get() = report.findingSeverities[FindingCode.DENSITY_HOTSPOT] == Severity.BLOCKER
    }
    fun check(pattern: EmbPattern, settings: PesWriteSettings, machine: SewoutMachine): Result {
        return try {
            val checked=PesWriter.writeChecked(pattern,settings,machine,DesignOrigin.APP_GENERATED)
            Result(checked.report,checked.bytes)
        } catch(rejected:PesOutputSafety.Rejected) {
            Result(rejected.report,null)
        }
    }
    fun canEdit(texture: Boolean, hasStitches: Boolean, busy: Boolean, report: SewoutReport?, hasBytes: Boolean): Boolean =
        !busy && hasStitches && (!texture || (hasBytes && report?.let { it.analysisComplete && !it.hasBlockingIssues } == true))
}
