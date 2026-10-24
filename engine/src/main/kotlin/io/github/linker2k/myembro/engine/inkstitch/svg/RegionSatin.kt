// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.StrokeWidthMeasurement
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.stitches.AutoSatin
import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.AutoTraceCenterline
import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.AutoTraceFit
import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.AutoTraceThinning
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbThread
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Location
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A narrow area as satin, by letter's Hangul satin path (HangulSatin / AndroidCenterlineGlyph)
 * without Android: raster → AutoTrace thinning → centerline → curve fit, then Ink/Stitch
 * stroke-to-satin at the measured median width and auto-satin routing. Only the raster source
 * differs (point-in-area sampling here, Android Paint there).
 */
object RegionSatin {
    /** Raster pitch in mm and padding in pixels; letter's KRSAT3 sampling is ~0.12mm/px with an 8px pad. */
    const val PIXEL_MM = 0.1
    private const val PAD = 8

    /**
     * [regionMm]: area in mm. [px] maps mm to the caller's Ink/Stitch pixel space. Null when no
     * complete satin can be built (no centerline, or routing would discard a section): the
     * caller then keeps tatami for that area.
     */
    fun groups(regionMm: Geometry, px: (Double, Double) -> Point, thread: EmbThread,
        parameters: SatinParameters = SatinParameters()): List<StitchGroup>? {
        if (regionMm.isEmpty) return null
        val env = regionMm.envelopeInternal
        val left = floor(env.minX / PIXEL_MM).toInt() - PAD; val top = floor(env.minY / PIXEL_MM).toInt() - PAD
        val width = ceil(env.maxX / PIXEL_MM).toInt() + PAD - left; val height = ceil(env.maxY / PIXEL_MM).toInt() + PAD - top
        if (width.toLong() * height > 1_048_576) return null // same raster budget as letter's glyph tracer
        val inside = IndexedPointInAreaLocator(regionMm)
        // 0 = ink, 255 = background, top-down rows as AutoTrace expects.
        val pixels = ByteArray(width * height) { i ->
            val c = Coordinate((left + i % width + .5) * PIXEL_MM, (top + i / width + .5) * PIXEL_MM)
            if (inside.locate(c) == Location.EXTERIOR) 255.toByte() else 0
        }
        val traced = runCatching { AutoTraceCenterline.trace(width, height, AutoTraceThinning.thin(width, height, pixels)) }.getOrNull()
        if (traced.isNullOrEmpty()) return null
        val widthPx = Units.mmToPixels(runCatching { StrokeWidthMeasurement.measure(regionMm).medianMm }.getOrNull() ?: return null)
        val sections = AutoTraceFit.fit(traced).mapNotNull { fitted ->
            // AutoTrace fit is y-up: row = height-1-y (HangulComponentOrder.Labeling uses the same mapping).
            fun v(p: AutoTraceFit.Point) = "${fmt(p.x.toDouble())} ${fmt(height - 1 - p.y.toDouble())}"
            val segments = fitted.segments.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val d = buildString {
                append("M ${v(segments.first().v[0])} ")
                for (s in segments) if (s.degree == 1) append("L ${v(s.v[3])} ") else append("C ${v(s.v[1])} ${v(s.v[2])} ${v(s.v[3])} ")
            }
            val path = SvgPath.flatten(d, Affine2D.IDENTITY, tolerance = 0.1).singleOrNull()?.points
                ?.map { px((left + it.x + .5) * PIXEL_MM, (top + it.y + .5) * PIXEL_MM) } ?: return@mapNotNull null // pixel centers
            if (path.size < 2) return@mapNotNull null
            val started = if (path.first() == path.last()) StrokeToSatin.setFirstNode(path, widthPx) else path
            StrokeToSatin.convertPathToSatin(started, widthPx)?.let(StrokeToSatin::filteredRungs)
        }
        if (sections.isEmpty()) return null
        val routed = AutoSatin.route(sections, thread, parameters)
        return routed.groups.takeIf { routed.discardedSatins == 0 && it.isNotEmpty() }
    }

    private fun fmt(value: Double) = String.format(Locale.ROOT, "%.6g", value)
}
