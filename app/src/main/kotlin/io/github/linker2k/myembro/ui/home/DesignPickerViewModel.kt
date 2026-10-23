// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.library.BundledDesigns
import io.github.linker2k.myembro.library.DesignLibrary
import io.github.linker2k.myembro.library.DesignFormats
import io.github.linker2k.myembro.library.Thumbnails
import io.github.linker2k.myembro.ui.RenderData
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Selection only: no USB watcher, import, deletion, or library-selection state. */
class DesignPickerViewModel(app: Application) : AndroidViewModel(app) {
    data class Item(
        val file: File,
        val sample: Boolean,
        val thumbnail: Bitmap? = null,
        val stitchCount: Int = 0,
        val colorCount: Int = 0,
        val error: String? = null,
        val modifiedAt: Long = file.lastModified(),
        val byteSize: Long = file.length(),
    ) {
        val title: String get() = file.nameWithoutExtension.removePrefix("sample_").replace('_', ' ')
        val ready: Boolean get() = thumbnail != null && error == null
    }

    val items = mutableStateListOf<Item>()
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    private var refreshJob: Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            loading = true
            failed = false
            try {
                val files = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    DesignLibrary(app).list().filterNot(DesignLibrary::isSample).map { Item(it, false) } +
                        BundledDesigns.files(app).map { Item(it, true) }
                }
                val cache = items.associateBy { it.file }
                items.clear()
                items += files.map { item ->
                    cache[item.file]?.takeIf {
                        it.ready && it.modifiedAt == item.modifiedAt && it.byteSize == item.byteSize
                    } ?: item
                }
                for (item in items.toList()) {
                    if (item.ready) continue
                    val loaded = withContext(Dispatchers.IO) {
                        try {
                            val data = RenderData(DesignFormats.read(item.file))
                            item.copy(thumbnail = Thumbnails.render(data, 320),
                                stitchCount = data.pattern.stitchCount, colorCount = data.pattern.colorBlocks.size)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            item.copy(error = error.message ?: error.javaClass.simpleName)
                        }
                    }
                    val index = items.indexOfFirst { it.file == item.file }
                    if (index >= 0) items[index] = loaded
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
}
