// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import org.locationtech.jts.geom.Geometry

/** do_underlay + underlay_shape. This deliberately uses the unexpanded input outline. */
object FillUnderlay {
    data class Layer(val stitches: List<FillPoint>, val angle: Double, val shape: Geometry)

    fun generate(shape: Geometry, parameters: FillParameters,
        startingPoint: FillPoint? = null): List<Layer> {
        if (!parameters.fill || !parameters.fill_underlay ||
            parameters.fill_method in listOf("legacy_fill", "cross_stitch")) return emptyList()
        val valid = FillGeometry.makeValid(shape)
        val parts = FillGeometry.polygons(valid).sortedBy {
            startingPoint?.let { start -> it.distance(FillGeometry.point(start)) } ?: it.envelopeInternal.minX
        }
        val layers = mutableListOf<Layer>()
        var start = startingPoint
        for (part in parts) {
            // Match shrink_or_grow_shape: an inset that erases the shape falls back to the original.
            val inset = FillGeometry.shrinkOrGrow(part, -parameters.underlayInsetPx)
            for (underlayShape in FillGeometry.polygons(inset)) {
                for (angle in parameters.resolvedUnderlayAnglesDegrees()) {
                    val stitches = TatamiFill.fill(underlayShape, Math.toRadians(angle),
                        parameters.underlayRowSpacingPx, parameters.underlayRowSpacingPx,
                        parameters.underlayMaxStitchLengthPx, parameters.runningLengthPx,
                        parameters.runningTolerancePx, parameters.staggers,
                        parameters.fill_underlay_skip_last, start, underpath = parameters.underlay_underpath)
                        .map { it.tagged("tatami_fill", "fill_underlay") }
                    if (stitches.isNotEmpty()) {
                        layers += Layer(stitches, angle, underlayShape)
                        start = stitches.last()
                    }
                }
            }
        }
        return layers
    }
}
