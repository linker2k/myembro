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
 *
 * - maximum width > 6.0mm → tatami. Satin needs every crossing within its limit; the maximum width is
 *   the largest inscribed circle diameter (JTS MaximumInscribedCircle). 6.0mm = corpus satin crossing p95.
 * - else median width ≥ 1.0mm and centerline ≥ 2 × maximum width → satin
 * - else median width ≥ 1.0mm (a short blob: satin would only cover its middle) → tatami
 * - else → running
 *
 * 1.0mm = Ink/Stitch v3.3.0 `lib/utils/settings.py` `min_satin_stroke_width` default (upstream default; the
 * gate passes satin down to 0.2mm, so it gives no lower bound: docs/qa/satin-minimum-sweep.md).
 * Centerline ≥ 2 × maximum width is a **policy value** (coordinator, 2026-10-24: satin is for strokes, i.e.
 * elongated shapes), to be checked by sweep and real sew-out. All bounds live here only.
 */
object StitchTypeRule {
    enum class Type { TATAMI, SATIN, RUNNING }

    const val SATIN_MIN_MM = 1.0
    const val SATIN_MAX_MM = 6.0
    /** Policy value: satin only when the centerline is at least this many maximum widths long. */
    const val SATIN_MIN_ELONGATION = 2.0

    /**
     * Maximum and median width of one region and its centerline length, in mm. The centerline length
     * is area ÷ median width (exact for a band of constant width); infinite when unknown.
     */
    data class Widths(val maxMm: Double, val medianMm: Double, val centerlineMm: Double = Double.POSITIVE_INFINITY)

    fun classify(widths: Widths, satinMinMm: Double = SATIN_MIN_MM, satinMaxMm: Double = SATIN_MAX_MM): Type {
        require(widths.maxMm.isFinite() && widths.medianMm.isFinite() && widths.medianMm >= 0 && satinMinMm <= satinMaxMm)
        return when {
            widths.maxMm > satinMaxMm -> Type.TATAMI
            widths.medianMm < satinMinMm -> Type.RUNNING
            widths.centerlineMm >= SATIN_MIN_ELONGATION * widths.maxMm -> Type.SATIN
            else -> Type.TATAMI
        }
    }

    data class RegionStitches(val groups:List<io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup>, val type:Type?) {
        val excluded:Boolean get()=type==null
    }

    /** Coordinator's failure policy, shared by canvas and editor. Cancellation is never a fallback. */
    fun generateRegion(preferred:Type,
        prefix:List<io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup> = emptyList(),
        hoop:io.github.linker2k.myembro.engine.edit.HoopSpec = io.github.linker2k.myembro.engine.edit.HoopSpec.H100,
        validate:((List<io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup>)->Boolean)? = null,
        generate:(Type)->List<io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup>):RegionStitches {
        val attempts=when(preferred) { Type.SATIN->listOf(Type.SATIN,Type.TATAMI,Type.RUNNING)
            Type.TATAMI->listOf(Type.TATAMI,Type.RUNNING);Type.RUNNING->listOf(Type.RUNNING) }
        for(type in attempts) {
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                val candidate=generate(type)
                if(candidate.isEmpty() || candidate.none { it.stitches.isNotEmpty() }) continue
                val combined=prefix+candidate
                val valid=validate?.invoke(combined) ?: run {
                    val isolated=io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.fromGroups(
                        candidate,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
                    require(isolated.stitchCount>0) { "Region disappeared during postprocessing" }
                    val pattern=if(prefix.isEmpty()) isolated else io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.fromGroups(
                        combined,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
                    val bytes=io.github.linker2k.myembro.engine.io.PesWriter.write(pattern)
                    !io.github.linker2k.myembro.engine.sewout.SewoutCheck.checkPes(bytes,
                        options=io.github.linker2k.myembro.engine.sewout.SewoutOptions(hoop=hoop,
                            origin=io.github.linker2k.myembro.engine.sewout.DesignOrigin.APP_GENERATED)).hasBlockingIssues
                }
                if(Thread.currentThread().isInterrupted) throw InterruptedException()
                if(valid) return RegionStitches(candidate,type)
            } catch(cancelled:java.util.concurrent.CancellationException) { throw cancelled
            } catch(interrupted:InterruptedException) { throw interrupted
            } catch(_:Exception) { /* This region tries the next existing, validated generator. */ }
        }
        return RegionStitches(emptyList(),null)
    }

    /** Widths of a region given in any unit; [unitsPerMm] converts to mm. */
    fun widths(region: Geometry, unitsPerMm: Double): Widths {
        val median = medianWidth(region)
        return Widths(maxWidth(region) / unitsPerMm, median / unitsPerMm,
            if (median > 0) region.area / median / unitsPerMm else 0.0)
    }

    fun classify(region: Geometry, unitsPerMm: Double): Type = classify(widths(region, unitsPerMm))

    /** Largest inscribed circle diameter over all polygons (= 2 × largest distance to the boundary). */
    fun maxWidth(region: Geometry): Double = polygons(region).maxOfOrNull { 2 * inscribedRadius(it) } ?: 0.0

    private fun polygons(region: Geometry) =
        (0 until region.numGeometries).map(region::getGeometryN).filterIsInstance<Polygon>().filter { !it.isEmpty && it.area > 0 }

    private fun inscribedRadius(p: Polygon) = MaximumInscribedCircle.getRadiusLine(p, 1e-3 * sqrt(p.area)).length

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
        val radius = polygons(region).maxOfOrNull(::inscribedRadius) ?: return 0.0
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
