// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.ProjectJson
import io.github.linker2k.myembro.engine.edit.ProjectSourceResolver
import io.github.linker2k.myembro.engine.edit.contains
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.model.Bounds
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions

/** [source] is null when the project cannot be exported as is; [exportError] then says why. */
internal data class ViewerDesign(val pattern: EmbPattern, val source: ByteArray?, val sourceName: String, val hoop: HoopSpec?,
    val projectSource: ByteArray? = null, val sewingOptions: SewingOptions? = null,
    val writeSettings: PesWriteSettings? = null, val exportError: String? = null)

/** Render project coordinates directly; PES encoding is used for save/share, never for re-centering the preview.
 * Opening never depends on the export checks: a project outside the hoop still opens, and save/share refuse it. */
internal fun readViewerDesign(bytes: ByteArray, name: String?, resolver: ProjectSourceResolver? = null,
    sewingOptions: SewingOptions? = null, writeSettings: PesWriteSettings = PesWriteSettings()): ViewerDesign {
    if (name?.substringAfterLast('.')?.equals("myembro", true) == true) {
        val project = ProjectJson.decode(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"), sourceResolver = resolver)
        val pattern = io.github.linker2k.myembro.deviceGeneration { project.flatten(sewingOptions) }
        val (pes, exportError) = try {
            io.github.linker2k.myembro.engine.sewout.SewoutLimits.requirePlaced(project.hoop,pattern.bounds)
            PesWriter.write(pattern, writeSettings.copy(hoop = project.hoop)) to null
        } catch (e: IllegalArgumentException) { null to (e.message ?: e.javaClass.simpleName) }
        return ViewerDesign(pattern, pes, name.substringBeforeLast('.') + ".pes", project.hoop,
            projectSource = bytes, sewingOptions = sewingOptions, writeSettings = writeSettings, exportError = exportError)
    }
    return ViewerDesign(PatternReader.read(bytes, name), bytes, name.orEmpty(), null)
}

internal fun viewerBounds(pattern: EmbPattern, preservePlacement: Boolean): Bounds? = pattern.bounds?.let { b ->
    if (preservePlacement) b else {
        val movement = viewerMovementBounds(pattern) ?: b
        val x = ((movement.minX.toLong() + movement.maxX) / 2).toInt()
        val y = ((movement.minY.toLong() + movement.maxY) / 2).toInt()
        Bounds(b.minX - x, b.minY - y, b.maxX - x, b.maxY - y)
    }
}
internal fun viewerFits(pattern: EmbPattern, hoop: HoopSpec, preservePlacement: Boolean): Boolean =
    viewerBounds(pattern, preservePlacement)?.let(hoop::contains) ?: true
