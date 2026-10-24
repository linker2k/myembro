// SPDX-License-Identifier: GPL-3.0-or-later
// Original myembro automatic stitch-type choice, not a source port: Ink/Stitch has no width-based selection.
package io.github.linker2k.myembro.engine.inkstitch.svg

import org.locationtech.jts.algorithm.construct.MaximumInscribedCircle
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Location
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.distance.IndexedFacetDistance
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The one region → stitch-type rule shared by every automatic source (logo, emoji, image/editor):
 * median width in satin range → satin, wider → tatami, narrower → running.
 *
 * 0.9–6mm was set by the coordinator; there is no upstream value, so both bounds are **unverified**
 * until the satin-width sweep (docs/emoji-to-design.md B5) and live here only, in one place.
 */
object StitchTypeRule {
    enum class Type { TATAMI, SATIN, RUNNING }

    const val SATIN_MIN_MM = 0.9
    const val SATIN_MAX_MM = 6.0

    fun classify(medianWidthMm: Double, satinMinMm: Double = SATIN_MIN_MM, satinMaxMm: Double = SATIN_MAX_MM): Type {
        require(medianWidthMm.isFinite() && medianWidthMm >= 0 && satinMinMm <= satinMaxMm)
        return when {
            medianWidthMm > satinMaxMm -> Type.TATAMI
            medianWidthMm >= satinMinMm -> Type.SATIN
            else -> Type.RUNNING
        }
    }

    /** Classifies a region given in any unit; [unitsPerMm] converts its width to mm. */
    fun classify(region: Geometry, unitsPerMm: Double): Type = classify(medianWidth(region) / unitsPerMm)

    /**
     * Area-weighted median of local thickness, in the geometry's units: each interior point gets the
     * diameter of the largest inscribed disk that contains it (centers on the distance ridge). A strip
     * or ring gives its band width, a disk its diameter, a rectangle its short side; corners and
     * bends do not pull the value down as a medial-axis average would.
     *
     * Sampled on a square grid of at most ~[maxSamples] interior points, at least 8 per inscribed radius.
     */
    fun medianWidth(region: Geometry, maxSamples: Int = 60_000): Double {
        if (region.isEmpty || region.area <= 0) return 0.0
        val polygons = (0 until region.numGeometries).map(region::getGeometryN).filterIsInstance<Polygon>().filter { !it.isEmpty }
        val radius = polygons.maxOf { MaximumInscribedCircle.getRadiusLine(it, 1e-3 * sqrt(it.area)).length }
        if (radius <= 0) return 0.0
        val env = region.envelopeInternal
        val step = max(radius / 8, sqrt(env.width * env.height / maxSamples))
        val nx = ceil(env.width / step).toInt() + 1
        val ny = ceil(env.height / step).toInt() + 1
        val inside = IndexedPointInAreaLocator(region)
        val boundary = IndexedFacetDistance(region.boundary)
        val factory = region.factory
        val distance = DoubleArray(nx * ny) { i ->
            val c = Coordinate(env.minX + (i % nx) * step, env.minY + (i / nx) * step)
            if (inside.locate(c) == Location.INTERIOR) boundary.distance(factory.createPoint(c)) else 0.0
        }
        // Ridge: interior samples at least as far from the boundary as all eight neighbours.
        val ridge = (0 until nx * ny).filter { i ->
            val d = distance[i]
            d > 0 && (-1..1).all { dy -> (-1..1).all { dx ->
                val x = i % nx + dx; val y = i / nx + dy
                x !in 0 until nx || y !in 0 until ny || distance[y * nx + x] <= d } }
        }.sortedByDescending { distance[it] }
        // Paint each sample with the largest ridge disk covering it (largest first, first paint wins).
        val thickness = DoubleArray(nx * ny)
        for (center in ridge) {
            val r = distance[center]; val cx = center % nx; val cy = center / nx
            val reach = (r / step).toInt()
            for (y in max(0, cy - reach)..min(ny - 1, cy + reach)) for (x in max(0, cx - reach)..min(nx - 1, cx + reach)) {
                val i = y * nx + x
                if (distance[i] > 0 && thickness[i] == 0.0 && ((x - cx) * (x - cx) + (y - cy) * (y - cy)) * step * step <= r * r)
                    thickness[i] = 2 * r
            }
        }
        val values = thickness.filter { it > 0 }.sorted()
        return if (values.isEmpty()) 2 * radius else values[values.size / 2]
    }
}
