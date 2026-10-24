// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.emoji

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.emoji.CatalogEmoji
import io.github.linker2k.myembro.emoji.EmojiRepository
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.DesignOrigin
import io.github.linker2k.myembro.engine.sewout.SewoutCheck
import io.github.linker2k.myembro.engine.sewout.SewoutOptions
import io.github.linker2k.myembro.engine.sewout.SewoutReport
import io.github.linker2k.myembro.ui.RenderData
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import io.github.linker2k.myembro.ui.viewerSewoutMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Emoji picker: bundled pictures first, the rest downloaded on demand (pinned + sha256). A picked
 * emoji is stitched through the shared vector-art path and checked like every app design.
 */
class EmojiViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = EmojiRepository(app)
    private var settings = EmbroiderySettings()
    val hoop: HoopSpec get() = settings.defaultFrame.spec

    var query by mutableStateOf(""); private set
    /** null = bundled set; otherwise a catalog group. */
    var group by mutableStateOf<String?>(null); private set
    var all by mutableStateOf<List<CatalogEmoji>>(emptyList()); private set
    /** Parsed pictures for thumbnails, by id (only for files already on the device). */
    val art = mutableStateMapOf<String, VectorArt.Art>()

    var selected by mutableStateOf<CatalogEmoji?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<Int?>(null); private set
    var render by mutableStateOf<RenderData?>(null); private set
    var report by mutableStateOf<SewoutReport?>(null); private set
    var sizeMm by mutableStateOf<Pair<Double, Double>?>(null); private set
    val passed get() = report?.let { it.analysisComplete && !it.hasBlockingIssues } == true
    private var work: Job? = null

    init {
        viewModelScope.launch {
            all = withContext(Dispatchers.IO) { repository.catalog.emoji.sortedByDescending { it.bundled } }
            for (e in all.filter(repository::isAvailable)) load(e)
        }
    }

    val groups: List<String> get() = all.map { it.group }.filter { it.isNotEmpty() }.distinct()

    /** Bundled set or one group; a search looks through the whole catalog by name and keywords. */
    val visible: List<CatalogEmoji> get() {
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) return all.filter { e -> q in e.name.lowercase() || e.keywords.any { q in it.lowercase() } || q == e.glyph }
        return if (group == null) all.filter { it.bundled } else all.filter { it.group == group }
    }

    fun configure(value: EmbroiderySettings) { settings = value }
    fun search(value: String) { query = value }
    fun showGroup(value: String?) { group = value; query = "" }
    fun isAvailable(e: CatalogEmoji) = repository.isAvailable(e)

    private suspend fun load(e: CatalogEmoji) {
        runCatching { withContext(Dispatchers.IO) { VectorArt.parseSvg(repository.svg(e)) } }.onSuccess { art[e.id] = it }
    }

    /** Downloads when needed, stitches at [WIDTH_MM] (within the hoop) and runs the sewing check. */
    fun pick(e: CatalogEmoji) {
        work?.cancel()
        selected = e; busy = true; error = null; render = null; report = null; sizeMm = null
        val snapshot = settings
        work = viewModelScope.launch {
            try {
                val picture = art[e.id] ?: withContext(Dispatchers.IO) { VectorArt.parseSvg(repository.svg(e)) }.also { art[e.id] = it }
                val frame = snapshot.defaultFrame.spec
                val width = minOf(WIDTH_MM, (minOf(frame.width, frame.height) - 1) / 10.0)
                val result = runInterruptible(Dispatchers.Default) {
                    val pattern = VectorArt.render(picture, width, name = e.name).pattern
                    val pes = PesWriter.write(pattern, snapshot.writeSettings)
                    Triple(pattern, SewoutCheck.checkPes(pes, viewerSewoutMachine(snapshot.machineProfile?.id, snapshot.machineModel),
                        SewoutOptions(hoop = frame, origin = DesignOrigin.APP_GENERATED)), pattern.bounds)
                }
                render = RenderData(result.first); report = result.second
                sizeMm = result.third?.let { (it.maxX - it.minX) / 10.0 to (it.maxY - it.minY) / 10.0 }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: java.io.IOException) { error = R.string.emoji_download_failed
            } catch (_: Exception) { error = R.string.emoji_stitch_failed
            } finally { busy = false }
        }
    }

    fun close() { work?.cancel(); selected = null; busy = false; render = null; report = null; error = null }

    companion object { const val WIDTH_MM = 50.0 }
}
