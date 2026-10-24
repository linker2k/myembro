// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.digitizing.TatamiSettings
import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.lettering.FillOptions
import io.github.linker2k.myembro.engine.lettering.FillStrategy
import io.github.linker2k.myembro.engine.lettering.FillBorder
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.math.abs

/** Explicit migration candidates only. Existing TatamiFill/OutlineFill call sites remain unchanged. */
object FillAdapters {
    fun tatamiElement(region: PolygonRegion, settings: TatamiSettings = TatamiSettings(),
        thread: EmbThread = EmbPattern.DEFAULT_PALETTE.first()): FillStitch {
        require(settings.rowOffsets.indices.all { abs(settings.rowOffsets[it]-it.toDouble()/settings.rowOffsets.size) < 1e-9 }) {
            "Nonuniform custom TatamiSettings.rowOffsets cannot be represented by Ink/Stitch staggers"
        }
        return FillStitch(region.transformed(Affine2D.scale(PIXELS_PER_MM/10)).geometry,thread,FillParameters(
            angle=-settings.angleDegrees,row_spacing_mm=settings.rowSpacing/10,max_stitch_length_mm=settings.stitchLength/10,
            staggers=settings.rowOffsets.size.toDouble(),fill_underlay=settings.underlay,
            fill_underlay_inset_mm=settings.underlayInset/10,running_stitch_length_mm=settings.underlayStitchLength/10))
    }

    /** Fill only: legacy automatic satin extraction and border passes are intentionally not selected implicitly. */
    fun outlineTatamiElement(contours: List<List<Pair<Double,Double>>>,
        options: FillOptions = FillOptions(strategy=FillStrategy.TATAMI,border=FillBorder.NONE),
        thread: EmbThread = EmbPattern.DEFAULT_PALETTE.first(),heightMm: Double? = null): FillStitch {
        require(options.strategy == FillStrategy.TATAMI && options.border == FillBorder.NONE) {
            "Automatic satin and border passes require separate elements"
        }
        require(heightMm == null || heightMm.isFinite() && heightMm > 0)
        val raw = PolygonRegion.fromPaths(contours.map { path -> Polyline(path.map { Point(it.first*10,it.second*10) },true) },FillRule.EVEN_ODD)
        // Match the old outline adapter's counter-preserving expansion before handing off to Ink/Stitch.
        fun exteriorPaths(g: org.locationtech.jts.geom.Geometry): List<Polyline> = when (g) {
            is org.locationtech.jts.geom.Polygon -> listOf(Polyline(g.exteriorRing.coordinates.map { Point(it.x,it.y) },true))
            is org.locationtech.jts.geom.GeometryCollection -> (0 until g.numGeometries).flatMap { exteriorPaths(g.getGeometryN(it)) }
            else -> emptyList()
        }
        val holes = PolygonRegion.fromPaths(exteriorPaths(raw.geometry)).difference(raw)
        val expanded = raw.offset(options.pullCompensationMm*10).difference(holes)
        val height = heightMm ?: (raw.geometry.envelopeInternal.height/10).takeIf { it > 0 } ?: 1.0
        return FillStitch(expanded.transformed(Affine2D.scale(PIXELS_PER_MM/10)).geometry,thread,FillParameters(
            angle=-options.angleDeg,row_spacing_mm=options.densityForHeight(height),max_stitch_length_mm=3.5,
            fill_underlay=options.underlay))
    }
}
