// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.library.DesignLibrary
import io.github.linker2k.myembro.library.Thumbnails
import io.github.linker2k.myembro.ui.RenderData
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeProject(val file: File, val thumbnail: Bitmap? = null)

/** Local content only; the home screen never fabricates recent files or downloads templates. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    var recent by mutableStateOf<List<HomeProject>>(emptyList())
        private set
    var sample by mutableStateOf<HomeProject?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set

    fun refresh() {
        if (refreshRunning) return
        refreshRunning = true
        viewModelScope.launch {
            loading = true
            failed = false
            try {
                val content = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val projects = File(app.filesDir, "projects").listFiles()
                        .orEmpty().filter { it.isFile && it.extension.equals("myembro", ignoreCase = true) }
                    val files = (projects + DesignLibrary(app).list())
                        .sortedByDescending { it.lastModified() }.take(6)
                    val gallery = File(app.cacheDir, "home_samples/flower.pes")
                    gallery.parentFile?.mkdirs()
                    if (!gallery.exists()) app.assets.open("samples/flower.pes").use { input ->
                        gallery.outputStream().use { input.copyTo(it) }
                    }
                    files.map(::describe) to describe(gallery)
                }
                recent = content.first
                sample = content.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
                refreshRunning = false
            }
        }
    }

    private var refreshRunning = false

    private fun describe(file: File): HomeProject {
        val thumbnail = try {
            if (file.extension.equals("myembro", ignoreCase = true)) null
            else Thumbnails.render(RenderData(PatternReader.read(file.readBytes(), file.name)), 320)
        } catch (_: Exception) { null }
        return HomeProject(file, thumbnail)
    }
}
