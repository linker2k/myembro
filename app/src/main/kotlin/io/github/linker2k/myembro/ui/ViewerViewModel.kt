// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.engine.io.PatternReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    var fileName by mutableStateOf<String?>(null)
        private set
    var render by mutableStateOf<RenderData?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** 100×100 is the default; oversized designs show a warning rather than switching hoops. */
    var hoop by mutableStateOf(Hoop.H100)

    /** Index into the pattern's stitch list that has been sewn so far. */
    var progress by mutableIntStateOf(0)
    var playing by mutableStateOf(false)

    /** Machine commands (stitches, jumps, trims…) advanced per second while playing. */
    var speed by mutableIntStateOf(400)
    private var pending = 0f
    var showJumps by mutableStateOf(false)

    fun open(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        load {
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Cannot read $uri")
            name to bytes
        }
    }

    /** Opens a design stored in the library. */
    fun openFile(file: java.io.File) = load { file.name to file.readBytes() }

    fun openSample() = load {
        "flower.pes" to getApplication<Application>().assets.open("samples/flower.pes").use { it.readBytes() }
    }

    private fun load(source: () -> Pair<String?, ByteArray>) {
        viewModelScope.launch {
            loading = true
            error = null
            playing = false
            runCatching {
                withContext(Dispatchers.IO) {
                    val (name, bytes) = source()
                    name to RenderData(PatternReader.read(bytes, name))
                }
            }.onSuccess { (name, data) ->
                fileName = name
                render = data
                progress = data.pattern.stitches.lastIndex
            }.onFailure {
                error = it.message ?: it.javaClass.simpleName
            }
            loading = false
        }
    }

    fun togglePlay() {
        val data = render ?: return
        if (!playing && progress >= data.pattern.stitches.lastIndex) progress = 0
        pending = 0f
        playing = !playing
    }

    /** Advance playback by [seconds] of wall time; stops at the end. */
    fun tick(seconds: Float) {
        val last = render?.pattern?.stitches?.lastIndex ?: return
        // Carry the fractional part so slow speeds stay accurate at any frame rate.
        pending += speed * seconds
        val steps = pending.toInt()
        pending -= steps
        progress = (progress + steps).coerceAtMost(last)
        if (progress == last) playing = false
    }
}
