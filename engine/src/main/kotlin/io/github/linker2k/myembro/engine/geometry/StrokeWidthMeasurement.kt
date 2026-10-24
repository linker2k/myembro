// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import org.locationtech.jts.densify.Densifier
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder

/** Measurements only, never an automatic fill approval. Inputs and results are millimetres. */
object StrokeWidthMeasurement {
    data class Result(val minimumSampledMm: Double, val p10Mm: Double, val medianMm: Double,
        val maximumSampledMm: Double, val samples: Int, val boundarySpacingMm: Double,
        val approvedThresholdMm: Double? = null)

    /**
     * Sampled medial-circle diameters: JTS Voronoi vertices of a densified boundary, inside ink,
     * twice the distance to the continuous boundary. Corners/tapered tips approach zero; the
     * sampled minimum depends on resolution and is NOT a proven minimum stroke thickness.
     * W remains unknown until physical comparison; percentiles are reported separately.
     */
    fun measure(shapeMm: Geometry, boundarySpacingMm: Double = 0.1): Result {
        require(boundarySpacingMm.isFinite() && boundarySpacingMm > 0 && !shapeMm.isEmpty && shapeMm.isValid)
        val boundary = shapeMm.boundary
        val sites = Densifier.densify(boundary, boundarySpacingMm).coordinates.distinctBy { it.x to it.y }
        require(sites.size in 3..100_000) { "Boundary sample resource limit" }
        val builder = VoronoiDiagramBuilder()
        builder.setSites(shapeMm.factory.createMultiPointFromCoords(sites.toTypedArray()))
        // Raw subdivision avoids the builder's legacy rectangle-overlay clipping;
        // only vertices strictly inside ink are measured below.
        val diagram = builder.subdivision.getVoronoiDiagram(shapeMm.factory)
        val widths = diagram.coordinates.distinctBy { it.x to it.y }.mapNotNull { c ->
            val point = shapeMm.factory.createPoint(c)
            if (shapeMm.contains(point)) (2 * boundary.distance(point)).takeIf { it > 0 && it.isFinite() } else null
        }.sorted()
        require(widths.isNotEmpty()) { "No interior Voronoi measurement at this resolution" }
        fun percentile(fraction: Double) = widths[((widths.size - 1) * fraction).toInt()]
        return Result(widths.first(), percentile(0.1), percentile(0.5), widths.last(), widths.size, boundarySpacingMm)
    }
}
