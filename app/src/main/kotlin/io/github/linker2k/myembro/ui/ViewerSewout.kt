// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.Bounds
import io.github.linker2k.myembro.engine.sewout.*
import kotlin.math.abs
import kotlin.math.ceil
import java.util.Locale

internal fun viewerSewoutMachine(id: String?, name: String): SewoutMachine =
    SewoutMachines.all.firstOrNull { it.id == id }
        ?: SewoutMachine("custom", name, Specification(), Specification(), knownHoopsMm = emptySet())

internal fun checkViewerSewout(pattern: EmbPattern, machine: SewoutMachine, hoop: HoopSpec,
    preservePlacement: Boolean, pesBytes: ByteArray? = null,
    origin: DesignOrigin = SewoutCheck.originOf(pattern)): SewoutReport {
    val options = SewoutOptions(origin = origin, hoop = hoop, placement = if (preservePlacement) Placement.HOOP_COORDINATES else Placement.CENTER_DESIGN)
    // Generated project review uses the same terminal safety pass as PES export; raw PES is checked verbatim.
    return if (!preservePlacement && pesBytes != null) SewoutCheck.checkPes(pesBytes, machine, options)
        else SewoutCheck.check(if (origin == DesignOrigin.APP_GENERATED) io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.forSewout(pattern) else pattern, machine, options)
}

internal fun sewoutNeedsConfirmation(report: SewoutReport): Boolean = report.hasBlockingIssues || !report.analysisComplete
internal fun sewoutMinutes(seconds: Double): Int = ceil(seconds / 60).toInt().coerceAtLeast(0)
internal fun sewoutClock(seconds: Double): String {
    val value = ceil(seconds).toLong().coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", value / 60, value % 60)
}

/** Highlight coordinates remain in the engine input space; never subtract the design center here. */
internal fun viewerHighlightArea(pattern: EmbPattern, highlight: Highlight): CheckArea? {
    highlight.area?.let { a ->
        if (listOf(a.minX, a.minY, a.maxX, a.maxY).all { it.isFinite() } && a.minX <= a.maxX && a.minY <= a.maxY) return a
    }
    val points = listOfNotNull(highlight.from, highlight.to).filter { it.x.isFinite() && it.y.isFinite() }
    if (points.isNotEmpty()) return CheckArea(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    val start = highlight.commandIndex?.takeIf { it in pattern.stitches.indices } ?: return null
    val end = (highlight.endCommandIndex ?: start).coerceIn(start, pattern.stitches.lastIndex)
    val stitches = pattern.stitches.subList(start, end + 1)
    return CheckArea(stitches.minOf { it.x }.toDouble(), stitches.minOf { it.y }.toDouble(),
        stitches.maxOf { it.x }.toDouble(), stitches.maxOf { it.y }.toDouble())
}

internal fun viewerCanvasFit(bounds: Bounds, hoopW: Float, hoopH: Float, preservePlacement: Boolean, width: Float, height: Float): Float {
    val viewW = maxOf(hoopW, if (preservePlacement) maxOf(abs(bounds.minX.toFloat()), abs(bounds.maxX.toFloat())) * 2 else (bounds.maxX.toLong() - bounds.minX).toFloat())
    val viewH = maxOf(hoopH, if (preservePlacement) maxOf(abs(bounds.minY.toFloat()), abs(bounds.maxY.toFloat())) * 2 else (bounds.maxY.toLong() - bounds.minY).toFloat())
    return minOf(width / viewW, height / viewH) * 0.92f
}
internal data class ViewerFocusCamera(val zoom: Float, val panX: Float, val panY: Float)
internal fun viewerFocusCamera(area: CheckArea, fit: Float, centerX: Float, centerY: Float, width: Float, height: Float): ViewerFocusCamera {
    val desired = minOf(width / ((area.maxX - area.minX).toFloat() + 60), height / ((area.maxY - area.minY).toFloat() + 60)) * .75f
    val zoom = (desired / fit).coerceIn(1f, 40f)
    return ViewerFocusCamera(zoom, -(((area.minX + area.maxX) / 2).toFloat() - centerX) * fit * zoom,
        -(((area.minY + area.maxY) / 2).toFloat() - centerY) * fit * zoom)
}

/** The centered hoop follows all active motion, matching SewoutCheck.CENTER_DESIGN. */
internal fun viewerMovementBounds(pattern: EmbPattern): Bounds? {
    if (pattern.stitches.isEmpty()) return null
    var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
    for (stitch in pattern.stitches) {
        minX = minOf(minX, stitch.x); minY = minOf(minY, stitch.y)
        maxX = maxOf(maxX, stitch.x); maxY = maxOf(maxY, stitch.y)
        if (stitch.command == io.github.linker2k.myembro.engine.model.Command.END) break
    }
    return Bounds(minX, minY, maxX, maxY)
}

internal fun sewoutVerdictResource(report: SewoutReport): Int = when (report.verdict) {
    SewoutVerdict.PASS -> io.github.linker2k.myembro.R.string.sewout_badge_pass
    SewoutVerdict.CAUTION -> io.github.linker2k.myembro.R.string.sewout_badge_caution
    SewoutVerdict.BLOCK -> io.github.linker2k.myembro.R.string.sewout_badge_block
}

/** User decision 2026-10-28: no thread-cut/cleanup instructions in the UI.
 * Engine tasks, requirements and timing remain untouched for analysis and stored data.
 */
internal fun sewoutDisplayTasks(tasks: List<OperatorTask>): List<OperatorTask> =
    tasks.filter { it.kind !in setOf(TaskKind.JUMP_THREAD_CUT, TaskKind.TRIM) }
