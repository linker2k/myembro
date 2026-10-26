// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.svg.RegionSatin
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.sewout.SewoutLimits
import kotlin.math.hypot

/** An option precheck, not a sewout verdict. Null [reason] means this local prerequisite passed. */
data class BlockStitchAvailability(
    val possible: Boolean,
    val reason: String? = null,
    /** Measured sewn segments only, in placed mm; jumps between groups do not count. */
    val maxStitchLengthMm: Double? = null,
)

/** Cheap prerequisites for a region. Final override/export still requires the full sewing check. */
data class BlockStitchCapabilities(
    val satin: BlockStitchAvailability,
    val running: BlockStitchAvailability,
    val fill: BlockStitchAvailability,
) {
    companion object {
        /** Implemented block backends; gradients require color-stop data absent from BlockStitch. */
        val fillMethods: Set<String> = setOf("tatami_fill", "meander_fill", "contour_fill", "circular_fill")

        /** Uses the same RegionSatin backend/parameters as the override renderer, without a PES or fill render.
         * Geometry/satin probing is lazy and cached on the immutable region. Pose-only copies reuse it.
         * Scale affects measured satin length; rotation/translation preserve segment lengths. */
        fun forRegion(region: ShapeRegion, transform: Transform = Transform()): BlockStitchCapabilities {
            val probe = region.capabilityProbe
            val satin = when {
                probe.satinFailed -> BlockStitchAvailability(false, "SATIN_GENERATION_FAILED")
                probe.satinGroups.isNullOrEmpty() -> BlockStitchAvailability(false, "SATIN_GEOMETRY_UNSUPPORTED")
                else -> satinLengths(probe.satinGroups, transform)
            }
            return BlockStitchCapabilities(satin,
                BlockStitchAvailability(probe.running, if (probe.running) null else "NO_RUNNING_PATH"),
                BlockStitchAvailability(probe.fill, if (probe.fill) null else "EMPTY_FILL_REGION"))
        }

        internal fun probe(region: ShapeRegion): RegionCapabilityProbe {
            val running = region.paths.any { path -> path.points.distinct().size >= 2 }
            val geometry = runCatching { PolygonRegion.fromPaths(region.paths, FillRule.EVEN_ODD) }.getOrNull()
            val fill = geometry != null && !geometry.isEmpty && geometry.area > 0.0
            val result = runCatching {
                geometry?.let { RegionSatin.groups(it.geometry,
                    { x, y -> Point(x * PIXELS_PER_MM, y * PIXELS_PER_MM) }, region.color,
                    region.stitch.satin ?: SatinParameters()) }
            }
            return RegionCapabilityProbe(running, fill, result.getOrNull(), result.isFailure)
        }

        internal fun satinLengths(groups: List<StitchGroup>, transform: Transform): BlockStitchAvailability {
            var max = 0.0
            var sewn = false
            for (group in groups) {
                var previous: Stitch? = null
                for (stitch in group.stitches) {
                    val start = previous
                    if (start != null && !stitch.isJump && !stitch.isTerminator) {
                        max = maxOf(max, hypot((stitch.x - start.x) * transform.scaleX,
                            (stitch.y - start.y) * transform.scaleY) / PIXELS_PER_MM)
                        sewn = true
                    }
                    // A jump positions the next stitch. A trim/stop does not itself sew a segment.
                    previous = if (stitch.isTerminator) null else stitch
                }
            }
            if (!sewn) return BlockStitchAvailability(false, "SATIN_GEOMETRY_UNSUPPORTED")
            val limitMm = SewoutLimits.MAX_STITCH_LENGTH_UNITS_0P1MM / 10.0
            return BlockStitchAvailability(max <= limitMm, if (max <= limitMm) null else "SATIN_STITCH_TOO_LONG",
                max.takeIf { it.isFinite() })
        }
    }
}

internal data class RegionCapabilityProbe(val running: Boolean, val fill: Boolean,
    val satinGroups: List<StitchGroup>?, val satinFailed: Boolean)
