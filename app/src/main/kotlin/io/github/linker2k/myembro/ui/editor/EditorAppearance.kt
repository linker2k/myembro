// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.math.abs

internal fun inspectorTransform(obj: DesignObject, width: String, height: String,
    x: String, y: String, rotation: String): Transform? {
    val b = obj.localPattern().bounds ?: return null
    fun percent(input: String, units: Int, current: Double): String? {
        if (units == 0) return (abs(current) * 100).toString()
        val mm = input.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        return (mm * 1000 / units).toString()
    }
    return parseEditorTransform(x, y, rotation, percent(width, b.width, obj.transform.scaleX) ?: return null,
        percent(height, b.height, obj.transform.scaleY) ?: return null, obj.transform)
}

/** Changing a thread retains editable geometry; the imported byte copy is no longer current. */
internal fun DesignObject.recolored(index: Int, thread: EmbThread): DesignObject = when (this) {
    is TextObject -> if (index == 0 && this.thread != thread) copy(thread = thread) else this
    is StitchObject -> {
        val count = maxOf(source.threads.size, source.colorBlocks.maxOfOrNull { it.threadIndex + 1 } ?: 0)
        if (index !in 0 until count || source.threadAt(index) == thread) this else {
            val threads = (0 until count).map { if (it == index) thread else source.threadAt(it) }
            copy(source = EmbPattern(source.stitches, threads, source.metadata, source.warnings), sourceBytes = null, sourceThreadsEdited = true)
        }
    }
}

internal fun parseThreadHex(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
    return hex.toInt(16) or (-1 shl 24)
}
