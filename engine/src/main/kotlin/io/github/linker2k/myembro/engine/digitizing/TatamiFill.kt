// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.digitizing

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.math.*

/** All lengths in 0.1 mm. Angle 0 = horizontal; positive rotates clockwise in +y-down space. */
data class TatamiSettings(
    val angleDegrees: Double = 0.0,
    val rowSpacing: Double = 4.0,
    val stitchLength: Double = 40.0,
    val rowOffsets: List<Double> = listOf(0.0, 0.25, 0.5, 0.75),
    val underlay: Boolean = true,
    val underlayInset: Double = 10.0,
    val underlayStitchLength: Double = 25.0,
) {
    init {
        require(listOf(angleDegrees, rowSpacing, stitchLength, underlayInset, underlayStitchLength).all { it.isFinite() })
        require(rowSpacing > 0 && stitchLength > 0 && underlayInset >= 0 && underlayStitchLength > 0)
        require(rowOffsets.isNotEmpty() && rowOffsets.all { it.isFinite() && it >= 0 && it < 1 })
    }
}

/** Simple serpentine scanlines, clipped to polygon components and holes; no graph-based routing. */
object TatamiFill {
    fun generate(region: PolygonRegion, settings: TatamiSettings = TatamiSettings(),
        thread: EmbThread = EmbPattern.DEFAULT_PALETTE.first()): EmbPattern {
        val builder = StitchRuns(thread)
        val metadata = mapOf("name" to "Tatami fill", "comments" to "angle=${settings.angleDegrees}; spacing=${settings.rowSpacing / 10}mm; stitch=${settings.stitchLength / 10}mm")
        if (region.isEmpty) return builder.finish(metadata)
        if (settings.underlay) {
            val inset = region.offset(-settings.underlayInset)
            inset.boundaries().forEach { boundary ->
                // Anchor every underlay vertex so concave corners are never cut across.
                builder.add(RunningStitch.sample(boundary, RunningSettings(settings.underlayStitchLength, 0.0)).points)
            }
        }
        val toScan = Affine2D.rotation(-settings.angleDegrees)
        val toProject = Affine2D.rotation(settings.angleDegrees)
        val scan = region.transformed(toScan)
        val bounds = scan.geometry.envelopeInternal
        val rows = ceil(bounds.height/settings.rowSpacing)
        require(rows.isFinite() && rows <= 100_000) { "Tatami exceeds 100000 rows" }
        var previous: Point? = null
        for (row in 0 until rows.toInt()) {
            val y = bounds.minY + min(settings.rowSpacing / 2, bounds.height / 2) + row*settings.rowSpacing
            if (y >= bounds.maxY) break
            val spans = scan.horizontalSpans(y).let { if (row % 2 == 0) it else it.reversed() }
            for ((left, right) in spans) {
                val phase = settings.rowOffsets[row % settings.rowOffsets.size]*settings.stitchLength
                val points = mutableListOf(Point(left, y))
                var x = (floor((left-phase)/settings.stitchLength)+1)*settings.stitchLength+phase
                val count = ceil((right-left)/settings.stitchLength)
                require(count <= 1_000_000) { "Tatami row exceeds point limit" }
                while (x < right-1e-8) { if (x > left+1e-8) points += Point(x, y); x += settings.stitchLength }
                points += Point(right, y)
                val ordered = if (row % 2 == 0) points else points.reversed()
                val start = ordered.first()
                val connect = previous?.let { it.distance(start) <= settings.stitchLength && scan.coversSegment(it, start) } == true
                builder.add(ordered.map(toProject::apply), connect)
                previous = ordered.last()
            }
        }
        return builder.finish(metadata)
    }
}
