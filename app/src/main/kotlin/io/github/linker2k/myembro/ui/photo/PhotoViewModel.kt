// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
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

class PhotoViewModel(app: Application) : AndroidViewModel(app) {
    var step by mutableIntStateOf(0); private set
    var source by mutableStateOf<Bitmap?>(null); private set
    var zoom by mutableFloatStateOf(1f); private set
    var panX by mutableFloatStateOf(0f); private set
    var panY by mutableFloatStateOf(0f); private set
    var detail by mutableIntStateOf(1); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<Int?>(null); private set
    var render by mutableStateOf<RenderData?>(null); private set
    var report by mutableStateOf<SewoutReport?>(null); private set
    private var bytes: ByteArray? = null
    private var work: Job? = null
    private var revision = 0
    private var settings = EmbroiderySettings()
    val hoop: HoopSpec get() = settings.defaultFrame.spec
    val passed get() = report?.let { it.analysisComplete && !it.hasBlockingIssues } == true
    val canSend get() = !busy && bytes != null && passed

    fun begin() {
        work?.cancel(); revision++; busy = false; step = 0; source = null; error = null
        zoom = 1f; panX = 0f; panY = 0f; detail = 1; invalidateResult()
    }
    fun configure(value: EmbroiderySettings) { if (settings != value) { settings = value; if (step > 0) prepare() } }
    fun load(uri: Uri) {
        work?.cancel(); val version = ++revision
        busy = true; error = null
        work = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
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
                    if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
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
    fun changeDetail(value: Int) { if (value != detail) { detail = value; prepare() } }
    fun back() { work?.cancel(); revision++; busy = false; error = null; step = (step - 1).coerceAtLeast(0) }
    fun next() { if (step == 0) { step = 1; prepare() } else if (render != null && !busy) step = 2 }
    private fun invalidateResult() { render = null; report = null; bytes = null }
    private fun prepare() {
        val bitmap = source ?: return
        work?.cancel(); val version = ++revision
        val snapshot = settings; val frame = snapshot.defaultFrame.spec
        val crop = photoCrop(bitmap.width, bitmap.height, frame.width.toDouble() / frame.height, zoom, panX, panY)
        val edge = photoRasterSize(detail)
        busy = true; error = null; invalidateResult()
        work = viewModelScope.launch {
            try {
                delay(180)
                val output = withContext(Dispatchers.Default) {
                    val aspect = frame.width.toDouble() / frame.height
                    val w = if (aspect >= 1) edge else (edge * aspect).toInt().coerceAtLeast(1)
                    val h = if (aspect >= 1) (edge / aspect).toInt().coerceAtLeast(1) else edge
                    val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // RGB-only engine contract: composite transparent photo pixels onto white paper.
                    Canvas(scaled).apply {
                        drawColor(android.graphics.Color.WHITE)
                        drawBitmap(bitmap, Rect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height),
                            Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    val pixels = IntArray(w * h); scaled.getPixels(pixels, 0, w, 0, 0, w, h); scaled.recycle()
                    pixels.indices.forEach { pixels[it] = pixels[it] and 0xFFFFFF }
                    val generated = RedworkPipeline.generate(w, h, pixels, frame.width / 10.0, 0,
                        EmbThread(0xFFB5492A.toInt(), getApplication<Application>().getString(R.string.photo_name)))
                    ensureActive()
                    val pes = PesWriter.write(generated.pattern, snapshot.writeSettings)
                    val check = SewoutCheck.checkPes(pes, viewerSewoutMachine(snapshot.machineProfile?.id, snapshot.machineModel),
                        SewoutOptions(hoop = frame, placement = Placement.HOOP_COORDINATES, hotspotPenetrations = 16))
                    Triple(RenderData(generated.pattern), pes, check)
                }
                if (version == revision) { render = output.first; bytes = output.second; report = output.third }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (version == revision) error = R.string.photo_generate_failed
            } finally { if (version == revision) busy = false }
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
                    val name = suggestDesignName(getApplication<Application>().getString(R.string.photo_file_stem)) { stem ->
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
