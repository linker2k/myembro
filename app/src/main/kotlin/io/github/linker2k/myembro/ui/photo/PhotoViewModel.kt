// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoCorrection
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoCorrections
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import android.os.SystemClock
import io.github.linker2k.myembro.engine.inkstitch.svg.image.RedworkOutline
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoStyle
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.image.RedworkPipeline
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import io.github.linker2k.myembro.ui.RenderData
import io.github.linker2k.myembro.ui.editor.suggestDesignName
import io.github.linker2k.myembro.ui.editor.writeNamedDesign
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import io.github.linker2k.myembro.ui.viewerSewoutMachine
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

/** The photo screen's decode: at most 1536px on the long edge (power-of-two sampling), EXIF orientation applied. */
internal fun decodeUprightPhoto(resolver: android.content.ContentResolver, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 1536) {
            inSampleSize = inSampleSize.coerceAtLeast(1) * 2
        }
    }
    val decoded = requireNotNull(resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) })
    val orientation = runCatching { resolver.openInputStream(uri).use { stream ->
        ExifInterface(requireNotNull(stream)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    } }.getOrDefault(1)
    val matrix = Matrix().apply { when (orientation) {
        2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
        5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
        7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
    } }
    return if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

enum class PhotoMode { SILHOUETTE, OUTLINE, COLOR }

class PhotoViewModel(app: Application) : AndroidViewModel(app) {
    var step by mutableIntStateOf(0); private set
    var source by mutableStateOf<Bitmap?>(null); private set
    var zoom by mutableFloatStateOf(1f); private set
    var panX by mutableFloatStateOf(0f); private set
    var panY by mutableFloatStateOf(0f); private set
    var style by mutableStateOf(PhotoMode.SILHOUETTE); private set
    var detail by mutableIntStateOf(1); private set
    var colorCount by mutableIntStateOf(4); private set
    var grayscale by mutableStateOf(false); private set
    fun changeGrayscale(value:Boolean) {
        if(value!=grayscale) { grayscale=value;step=1;prepare() }
    }
    var correction by mutableStateOf(PhotoCorrection.ORIGINAL); private set
    var correctionAlphaBypassed by mutableStateOf(false); private set
    fun changeCorrection(value: PhotoCorrection) {
        if (value == correction || (style == PhotoMode.COLOR && value == PhotoCorrection.OTSU)) return
        correction = value; step = 1; prepare()
    }
    var colorPreview by mutableStateOf<ColorDrawingPipeline.Preview?>(null); private set
    val excludedCount get()=render?.pattern?.metadata?.get("myembro.excluded_regions")?.toIntOrNull() ?: 0
    fun missingThread(thread: EmbThread) = settings.myThreads.missing(thread)
    val requiredThreads get() = render?.pattern?.threads?.distinctBy { it.catalogNumber to it.color }.orEmpty()
    fun changeColorCount(value:Int) {
        val count=value.coerceIn(2,6)
        if(count!=colorCount) { colorCount=count; step=1; prepare() }
    }
    /** Picture width in mm (height follows the frame aspect); recomputed only when a choice is committed. */
    var widthMm by mutableDoubleStateOf(photoMaxWidthMm(EmbroiderySettings().defaultFrame.spec)); private set
    /** Actual stitched width × height in mm of the step-3 result. */
    var resultSizeMm by mutableStateOf<Pair<Double, Double>?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<Int?>(null); private set
    var render by mutableStateOf<RenderData?>(null); private set
    var report by mutableStateOf<SewoutReport?>(null); private set
    var separatedPreview by mutableStateOf<Bitmap?>(null); private set
    var outline by mutableStateOf<RedworkOutline?>(null); private set
    var progressStage by mutableIntStateOf(0); private set
    private var bytes: ByteArray? = null
    private var work: Job? = null
    private var revision = 0
    private var settings = EmbroiderySettings()
    val hoop: HoopSpec get() = settings.defaultFrame.spec
    /** Ink ÷ frame width of the latest outline (kept while re-tracing); null until step 2 has traced. */
    var inkRatio by mutableStateOf<Double?>(null); private set
    /** Slider range and value in embroidery (ink) mm. */
    val inkWidthRange: ClosedFloatingPointRange<Double>? get() = inkRatio?.let { photoInkWidthRange(hoop, it) }
    val inkWidthMm: Double? get() = inkRatio?.let { Math.round(widthMm * it * 10) / 10.0 }
    val passed get() = report?.let { it.analysisComplete && !it.hasBlockingIssues } == true
    val canSend get() = !busy && bytes != null && passed

    fun begin() {
        work?.cancel(); revision++; busy = false; step = 0; source = null; error = null
        correction = PhotoCorrection.ORIGINAL; correctionAlphaBypassed = false
        zoom = 1f; panX = 0f; panY = 0f; detail = 1; colorCount = 4; grayscale=false; style = PhotoMode.SILHOUETTE; widthMm = photoMaxWidthMm(hoop); inkRatio = null; invalidateResult()
    }
    fun configure(value: EmbroiderySettings) { if (settings != value) { settings = value; widthMm = widthMm.coerceIn(PHOTO_MIN_WIDTH_MM..photoMaxWidthMm(hoop)); if (step > 0) { step = 1; prepare() } } }
    /** [inkMm]: wanted embroidery width. Converted to the frame width with the size-independent ink ratio. */
    fun changeSize(inkMm: Double) {
        val ratio = inkRatio ?: return
        val range = photoInkWidthRange(hoop, ratio)
        val frame = (inkMm.coerceIn(range) / ratio).coerceIn(PHOTO_MIN_WIDTH_MM..photoMaxWidthMm(hoop))
        if (abs(frame - widthMm) >= 0.05) { widthMm = frame; step = 1; prepare() }
    }
    fun load(uri: Uri) {
        work?.cancel(); val version = ++revision
        busy = true; progressStage = 0; error = null
        work = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val decodeStart = SystemClock.elapsedRealtime()
                    val resolver = getApplication<Application>().contentResolver
                    val oriented = decodeUprightPhoto(resolver, uri)
                    Log.i("PhotoTiming", "decode_ms=${SystemClock.elapsedRealtime()-decodeStart} decoded=${oriented.width}x${oriented.height}")
                    oriented
                }
                if (version == revision) { source = bitmap; zoom = 1f; panX = 0f; panY = 0f; step = 0; invalidateResult() }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (version == revision) error = R.string.photo_load_failed
            } finally { if (version == revision) busy = false }
        }
    }
    fun transform(scale: Float, dx: Float, dy: Float) {
        zoom = (zoom * scale).coerceIn(1f, 4f)
        panX = (panX - dx * 2).coerceIn(-1f, 1f); panY = (panY - dy * 2).coerceIn(-1f, 1f)
    }
    fun changeStyle(value: PhotoMode) {
        if(value==style) return
        work?.cancel(); revision++; busy=false; style=value; error=null
        if(value == PhotoMode.COLOR && correction == PhotoCorrection.OTSU) correction = PhotoCorrection.ORIGINAL
        render=null; report=null; bytes=null; step=1; prepare()
    }
    fun changeDetail(value: Int) { if (value != detail) { detail = value; step = 1; prepare() } }
    fun cancel() { work?.cancel(); revision++; busy = false; error = null; if (step == 1) step = 0 }
    fun lowerDetail() { if (detail > 0) changeDetail(detail - 1) else { back() } }
    fun back() { work?.cancel(); revision++; busy = false; error = null; step = (step - 1).coerceAtLeast(0) }
    fun next() { if (step == 0) { step = 1; prepare() } else if (outline != null && !busy) { step = 2; generateResult() } }
    private fun invalidateResult() { colorPreview = null; outline = null; separatedPreview = null; render = null; report = null; bytes = null; resultSizeMm = null }
    private fun prepare() {
        val bitmap = source ?: return
        work?.cancel(); val version = ++revision
        val snapshot = settings; val frame = snapshot.defaultFrame.spec
        val crop = photoCrop(bitmap.width, bitmap.height, frame.width.toDouble() / frame.height, zoom, panX, panY)
        val selectedMode = style; val selectedColors=colorCount; val selectedGrayscale=grayscale; val selectedCorrection=correction
        val pictureWidth = widthMm.coerceIn(PHOTO_MIN_WIDTH_MM..photoMaxWidthMm(frame))
        val edge = photoRasterSize(detail, pictureWidth, pictureWidth * frame.height / frame.width)
        busy = true; progressStage = 1; error = null; invalidateResult()
        work = viewModelScope.launch {
            try {
                delay(180)
                val output = runInterruptible(Dispatchers.Default) {
                    val aspect = frame.width.toDouble() / frame.height
                    val w = if (aspect >= 1) edge else (edge * aspect).toInt().coerceAtLeast(1)
                    val h = if (aspect >= 1) (edge / aspect).toInt().coerceAtLeast(1) else edge
                    val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // RGB-only engine contract: composite transparent photo pixels onto white paper.
                    Canvas(scaled).apply {
                        if(selectedMode!=PhotoMode.COLOR) drawColor(android.graphics.Color.WHITE)
                        drawBitmap(bitmap, Rect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height),
                            Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    val pixels = IntArray(w * h); scaled.getPixels(pixels, 0, w, 0, 0, w, h); scaled.recycle()
                    val corrected = PhotoCorrections.apply(w,h,pixels,selectedMode!=PhotoMode.COLOR,selectedCorrection)
                    if (version == revision) {
                        correctionAlphaBypassed = corrected.alphaBypassed
                        if(corrected.alphaBypassed) correction = PhotoCorrection.ORIGINAL
                    }
                    if(selectedMode==PhotoMode.COLOR) {
                        val preview=ColorDrawingPipeline.trace(w,h,corrected.pixels,pictureWidth,selectedColors,
                            minAreaMm2=ColorDrawingPipeline.MINIMUM_REGION_AREA_MM2,grayscale=selectedGrayscale,myThreads=snapshot.myThreads) { phase ->
                            if(Thread.currentThread().isInterrupted) throw InterruptedException()
                            progressStage=if(phase=="quantization") 1 else 2
                        }
                        if(version==revision) {
                            colorPreview=preview
                            separatedPreview=Bitmap.createBitmap(preview.previewPixels(),preview.width,preview.height,Bitmap.Config.ARGB_8888)
                        }
                        return@runInterruptible preview.inkOutline to photoInkRatio(preview.inkOutline)
                    }
                    var tick = SystemClock.elapsedRealtime(); var phase = "raster"
                    fun trace(input: IntArray) = RedworkPipeline.trace(w,h,input,pictureWidth,0,
                        EmbThread(0xFFB5492A.toInt(),getApplication<Application>().getString(R.string.photo_name)),
                        minAreaMm2 = photoMinimumArea(detail), maxContours = 128,
                        onQuantized = { separated ->
                            if (version == revision) separatedPreview = Bitmap.createBitmap(
                                separated.map { it or 0xFF000000.toInt() }.toIntArray(),w,h,Bitmap.Config.ARGB_8888)
                        }) { next ->
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        if (next != phase) {
                            Log.i("PhotoTiming", "${phase}_ms=${SystemClock.elapsedRealtime()-tick} raster=${w}x${h}")
                            tick = SystemClock.elapsedRealtime(); phase = next
                        }
                        progressStage = if (next == "quantization") 1 else 2
                    }
                    val binaryMask = corrected.mask
                    if (binaryMask != null) {
                        val traced = RedworkPipeline.traceMask(w,h,binaryMask,pictureWidth,
                            EmbThread(0xFFB5492A.toInt(),getApplication<Application>().getString(R.string.photo_name)),
                            minAreaMm2=photoMinimumArea(detail),maxContours=128,
                            onQuantized={ separated -> if(version==revision) separatedPreview=Bitmap.createBitmap(
                                separated.map { it or 0xff000000.toInt() }.toIntArray(),w,h,Bitmap.Config.ARGB_8888) }) {
                            if(Thread.currentThread().isInterrupted) throw InterruptedException()
                            progressStage=if(it=="quantization") 1 else 2
                        }
                        return@runInterruptible traced to photoInkRatio(traced)
                    }
                    val denoised=corrected.pixels
                    (try { trace(denoised) } catch (failure: IllegalArgumentException) {
                        if (selectedCorrection == PhotoCorrection.CONTRAST) throw failure
                        if (failure.message?.contains("PHOTO_TOO_COMPLEX") == true) throw failure
                        Log.i("PhotoTiming", "contrast_retry=1")
                        trace(photoContrastStretch(denoised))
                    }).let { it to photoInkRatio(it) }
                }
                if (version == revision) { outline = output.first; inkRatio = output.second ?: inkRatio }

            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { if (version == revision) error = if (failure.message?.contains("PHOTO_TOO_COMPLEX") == true) R.string.photo_too_complex else if(selectedMode==PhotoMode.COLOR) R.string.photo_color_failed else if(selectedCorrection!=PhotoCorrection.ORIGINAL && selectedCorrection!=PhotoCorrection.CLEAN) R.string.photo_correction_failed else R.string.photo_generate_failed
            } finally { if (version == revision) busy = false }
        }
    }
    private fun generateResult() {
        val traced = outline ?: return
        work?.cancel(); val version = ++revision; val snapshot = settings; val frame = hoop; val selectedStyle = style
        busy = true; progressStage = 3; error = null; render = null; report = null; bytes = null; resultSizeMm = null
        work = viewModelScope.launch {
            try {
                val output = withTimeout(30_000) { runInterruptible(Dispatchers.Default) {
                    val tick = SystemClock.elapsedRealtime()
                    val generatedPattern = if(selectedStyle==PhotoMode.COLOR) {
                        ColorDrawingPipeline.generate(requireNotNull(colorPreview),frame) {
                            if(Thread.currentThread().isInterrupted) throw InterruptedException()
                        }.pattern
                    } else RedworkPipeline.generate(traced,
                        EmbThread(0xFFB5492A.toInt(),getApplication<Application>().getString(if(selectedStyle==PhotoMode.OUTLINE) R.string.photo_outline_name else R.string.photo_name)),photoMode=true,style=if(selectedStyle==PhotoMode.OUTLINE) PhotoStyle.OUTLINE else PhotoStyle.SILHOUETTE) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    }.pattern
                    Log.i("PhotoTiming", "stitches_ms=${SystemClock.elapsedRealtime()-tick} contours=${traced.contourCount}")
                    if(generatedPattern.stitchCount==0) return@runInterruptible Triple(RenderData(generatedPattern),null,null)
                    val pes = PesWriter.write(generatedPattern,snapshot.writeSettings)
                    val check = SewoutCheck.checkPes(pes,viewerSewoutMachine(snapshot.machineProfile?.id,snapshot.machineModel),
                        SewoutOptions(hoop=frame, origin=DesignOrigin.APP_GENERATED))
                    Triple(RenderData(generatedPattern),pes,check)
                } }
                if (version == revision) { render=output.first; bytes=output.second; report=output.third
                    resultSizeMm=output.first.pattern.bounds?.let { (it.maxX-it.minX)/10.0 to (it.maxY-it.minY)/10.0 } }
            } catch (_: TimeoutCancellationException) { if(version==revision) error=R.string.photo_timed_out
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if(version==revision) error=R.string.photo_stitches_failed
            } finally { if(version==revision) busy=false }
        }
    }
    fun send(onReady: (File) -> Unit) {
        if (!canSend) return
        val pes = bytes ?: return
        busy = true; error = null
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(getApplication<Application>().filesDir, "designs")
                    val name = suggestDesignName(getApplication<Application>().getString(when(style) { PhotoMode.OUTLINE -> R.string.photo_outline_file_stem; PhotoMode.COLOR -> R.string.photo_color_file_stem; else -> R.string.photo_file_stem })) { stem ->
                        directory.listFiles()?.any { it.nameWithoutExtension.equals(stem, true) } == true
                    }
                    writeNamedDesign(directory, name, "pes", false, pes)
                }
                onReady(file)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = R.string.photo_save_failed
            } finally { busy = false }
        }
    }
}
