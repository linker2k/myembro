// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.library.BundledDesigns
import io.github.linker2k.myembro.library.DesignLibrary
import io.github.linker2k.myembro.library.DesignFormats
import io.github.linker2k.myembro.library.Thumbnails
import io.github.linker2k.myembro.ui.RenderData
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeProject(val file: File, val thumbnail: Bitmap? = null)

/** Local content only; the home screen never fabricates recent files or downloads templates. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val preferences = SettingsPreferences(app)
    var settings by mutableStateOf(preferences.read())
        private set
    private val stopObserving = preferences.observe { settings = preferences.read() }

    override fun onCleared() { stopObserving() }
    var recent by mutableStateOf<List<HomeProject>>(emptyList())
        private set
    var samples by mutableStateOf<List<HomeProject>>(emptyList())
        private set
    val sample: HomeProject? get() = samples.firstOrNull()
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
                    val files = (projects + DesignLibrary(app).list().filterNot(DesignLibrary::isSample))
                        .sortedByDescending { it.lastModified() }.take(6)
                    val gallery = BundledDesigns.files(app).take(6)
                    files.map(::describe) to gallery.map(::describe)
                }
                recent = content.first
                samples = content.second
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
            Thumbnails.render(RenderData(DesignFormats.read(file)), 320)
        } catch (_: Exception) { null }
        return HomeProject(file, thumbnail)
    }
}
