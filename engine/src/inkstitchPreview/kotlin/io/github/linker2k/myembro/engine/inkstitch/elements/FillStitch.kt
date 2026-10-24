// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.distance.DistanceOp
import kotlin.math.atan2

/** Typed SVG resources/method-specific attributes. Common attributes are in [FillParameters]. */
sealed interface FillMethod {
    val id: String
    data object Tatami : FillMethod { override val id = "tatami_fill" }
    data object Legacy : FillMethod { override val id = "legacy_fill" }
    data class Contour(val options: ContourFillParameters = ContourFillParameters()) : FillMethod { override val id = "contour_fill" }
    data class Guided(val guideline: Geometry? = null, val anchor_line: Geometry? = null,
        val options: GuidedFill.Parameters = GuidedFill.Parameters()) : FillMethod { override val id = "guided_fill" }
    data class Circular(val target: FillPoint? = null, val options: CircularFill.Parameters = CircularFill.Parameters()) : FillMethod { override val id = "circular_fill" }
    data class Meander(val options: MeanderFillParameters = MeanderFillParameters()) : FillMethod { override val id = "meander_fill" }
    data class Gradient(val gradient: LinearGradientFill.Gradient? = null,
        val options: LinearGradientFill.Parameters = LinearGradientFill.Parameters()) : FillMethod { override val id = "linear_gradient_fill" }
    data class Tartan(val settings: TartanFill.Settings = TartanFill.Settings(),
        val options: TartanFill.Parameters = TartanFill.Parameters()) : FillMethod { override val id = "tartan_fill" }

    companion object {
        fun fromId(id: String): FillMethod = when (id) {
            "tatami_fill", "auto_fill" -> Tatami
            "legacy_fill" -> Legacy
            "contour_fill" -> Contour()
            "guided_fill" -> Guided()
            "circular_fill" -> Circular()
            "meander_fill" -> Meander()
            "linear_gradient_fill" -> Gradient()
            "tartan_fill" -> Tartan()
            "cross_stitch" -> throw IllegalArgumentException("Cross stitch is not part of this fill port")
            else -> Tatami // Original FillStitch defaults unknown methods to tatami.
        }
    }
}

/** SVG pixel geometry, no UI or writer hook. Shared plan generation performs locks/filtering explicitly. */
class FillStitch(
    shape: Geometry,
    private val color: EmbThread = EmbPattern.DEFAULT_PALETTE.first(),
    val parameters: FillParameters = FillParameters(),
    private val starting_point: FillPoint? = null,
    private val ending_point: FillPoint? = null,
    method: FillMethod? = null,
    /** Thread metadata for typed CSS colors in gradient/tartan inputs. */
    private val resolveColor: (String) -> EmbThread = ::hexThread,
) : EmbroideryElement {
    private val shape = FillGeometry.makeValid(shape)
    val method: FillMethod = method ?: FillMethod.fromId(parameters.fill_method)

    override fun toStitchGroups(previousStitch: Stitch?): List<StitchGroup> {
        if (!parameters.fill || shape.isEmpty) return emptyList()
        val p = parameters.copy(fill_method = method.id,row_spacing_mm=parameters.rowSpacingPx/PIXELS_PER_MM,
            max_stitch_length_mm=parameters.maxStitchLengthPx/PIXELS_PER_MM,
            running_stitch_length_mm=parameters.runningLengthPx/PIXELS_PER_MM,
            running_stitch_tolerance_mm=parameters.runningTolerancePx/PIXELS_PER_MM)
        val locks = LockStitches.fromParameters(LockStitchParameters(p.ties,p.force_lock_stitches,
            p.lock_start,p.lock_custom_start.orEmpty(),p.lock_start_scale_mm,p.lock_start_scale_percent,
            p.lock_end,p.lock_custom_end.orEmpty(),p.lock_end_scale_mm,p.lock_end_scale_percent))
        var start = starting_point ?: previousStitch?.let { FillPoint(it.x,it.y) }
        val parts = FillGeometry.polygons(shape).sortedBy {
            start?.let { point -> it.distance(FillGeometry.point(point)) } ?: it.envelopeInternal.minX
        }
        val layers = mutableListOf<Layer>()
        for ((index, part) in parts.withIndex()) {
            val layerStart = layers.size
            val end = if (index == parts.lastIndex) ending_point else DistanceOp.nearestPoints(part,parts[index+1])[0].let { FillPoint(it.x,it.y) }
            val gradient = (method as? FillMethod.Gradient)?.gradient
            val underlayParameters = if (gradient != null && p.fill_underlay_angle == null) p.copy(
                fill_underlay_angle = (-Math.toDegrees(atan2(gradient.end.y-gradient.start.y,gradient.end.x-gradient.start.x))).toString()) else p
            val underlayColor = gradient?.stops?.firstOrNull()?.color?.let(resolveColor) ?: color
            for (underlay in FillUnderlay.generate(part,underlayParameters,start)) {
                layers += Layer(underlayColor,underlay.stitches,true)
                start = underlay.stitches.lastOrNull() ?: start
            }
            for ((shapeIndex, expanded) in FillGeometry.polygons(FillGeometry.shrinkOrGrow(part,p.expandPx)).withIndex()) {
                val generated = top(expanded,part,shapeIndex,p,start,end)
                layers += generated
                start = generated.lastOrNull()?.points?.lastOrNull() ?: start
            }
            if (index > 0 && layers.size > layerStart && layers[layerStart].points.isNotEmpty()) {
                val first = layers[layerStart]
                layers[layerStart] = first.copy(points=listOf(first.points.first().tagged("jump"))+first.points.drop(1))
            }
        }
        // Original color_sort preserves first-seen order and leaves tartan stroke runs over its fills.
        val sorted = if (parts.size > 1 && method is FillMethod.Gradient) colorSort(layers) else
            if (parts.size > 1 && method is FillMethod.Tartan) colorSort(layers.filter { !it.running })+colorSort(layers.filter { it.running }) else layers
        val groups = mutableListOf<StitchGroup>()
        for (layer in sorted) for ((points, startsWithJump) in splitRuns(layer.points)) {
            if (startsWithJump && groups.isNotEmpty()) groups[groups.lastIndex] = groups.last().copy(forceLockStitches=true)
            groups += StitchGroup(layer.color,points.map { Stitch(it.x,it.y,it.tags) },
                lockStart=locks.start,lockEnd=locks.end,minStitchLengthMm=p.min_stitch_length_mm,
                minJumpLengthMm=p.min_jump_stitch_length_mm,forceLockStitches=!layer.underlay && p.force_lock_stitches)
        }
        if (groups.isEmpty()) return groups
        return groups.dropLast(1)+groups.last().copy(trimAfter=p.trim_after,stopAfter=p.stop_after)
    }

    private data class Layer(val color: EmbThread, val points: List<FillPoint>, val underlay: Boolean = false, val running: Boolean = false)
    private fun top(shape: Polygon, original: Polygon, index: Int, p: FillParameters,
        start: FillPoint?, end: FillPoint?): List<Layer> {
        fun single(points: List<FillPoint>, vararg tags: String) = listOf(Layer(color,points.map { it.tagged(*tags) }))
        return when (val method = method) {
            FillMethod.Tatami -> single(tatami(shape,p,start,end),"tatami_fill","fill_top")
            FillMethod.Legacy -> Fill.legacyFill(shape,p.angleRadians,p.rowSpacingPx,p.endRowSpacingPx,
                p.maxStitchLengthPx,p.flip,p.reverse,p.staggers,p.skip_last).map { Layer(color,it) }
            is FillMethod.Contour -> single(ContourFill.fill(shape,method.options.copy(row_spacing_mm=p.row_spacing_mm,
                max_stitch_length_mm=p.max_stitch_length_mm,running_stitch_tolerance_mm=p.running_stitch_tolerance_mm),
                start ?: FillPoint(0.0,0.0)),"tatami_fill","fill_top")
            is FillMethod.Guided -> if (method.guideline == null) single(tatami(shape,p,start,end),"tatami_fill","fill_top") else
                single(GuidedFill.generate(shape,method.guideline,method.options.copy(angle=p.angle,row_spacing_mm=p.row_spacing_mm,
                    max_stitch_length_mm=p.max_stitch_length_mm,staggers=p.staggers,skip_last=p.skip_last,underpath=p.underpath,
                    min_stitch_length_mm=p.min_stitch_length_mm, running_stitch_length_mm=p.running_stitch_length_mm,running_stitch_tolerance_mm=p.running_stitch_tolerance_mm),
                    method.anchor_line,start,end),"guided_fill","guided_fill_top")
            is FillMethod.Circular -> single(CircularFill.generate(shape,method.options.copy(angle=p.angle,row_spacing_mm=p.row_spacing_mm,
                end_row_spacing_mm=p.end_row_spacing_mm,running_stitch_length_mm=p.running_stitch_length_mm,
                running_stitch_tolerance_mm=p.running_stitch_tolerance_mm,skip_last=p.skip_last,underpath=p.underpath),start,end,method.target),"circular_fill","fill_top")
            is FillMethod.Meander -> single(MeanderFill.fill(shape,method.options.copy(running_stitch_length_mm=p.running_stitch_length_mm,
                running_stitch_tolerance_mm=p.running_stitch_tolerance_mm,random_seed=p.random_seed),original,start,end,index),"meander_fill","meander_fill_top")
            is FillMethod.Gradient -> LinearGradientFill.generate(shape,method.options.copy(row_spacing_mm=p.row_spacing_mm,
                max_stitch_length_mm=p.max_stitch_length_mm,staggers=p.staggers,angle=p.angle,
                running_stitch_length_mm=p.running_stitch_length_mm,running_stitch_tolerance_mm=p.running_stitch_tolerance_mm,
                skip_last=p.skip_last,fill_underlay=p.fill_underlay),method.gradient,colorHex(color),start,end)
                .map { Layer(resolveColor(it.color),it.stitches) }
            is FillMethod.Tartan -> TartanFill.generate(shape,method.options.copy(row_spacing_mm=p.row_spacing_mm,
                max_stitch_length_mm=p.max_stitch_length_mm,staggers=p.staggers,angle=p.angle,
                running_stitch_length_mm=p.running_stitch_length_mm,running_stitch_tolerance_mm=p.running_stitch_tolerance_mm,
                skip_last=p.skip_last,fill_underlay=p.fill_underlay),method.settings,start,end)
                .map { Layer(resolveColor(it.color),it.stitches,running=it.running) }
        }
    }

    private fun tatami(shape: Geometry,p: FillParameters,start: FillPoint?,end: FillPoint?) = TatamiFill.fill(shape,
        p.angleRadians,p.rowSpacingPx,p.endRowSpacingPx,p.maxStitchLengthPx,p.runningLengthPx,p.runningTolerancePx,
        p.staggers,p.skip_last,start,end,p.underpath,p.gap_fill_rows,p.pullCompensationPx,p.pullCompensationRatio)

    private fun colorSort(layers: List<Layer>): List<Layer> {
        val order = layers.map { it.color.color and 0xFFFFFF }.distinct()
        return layers.sortedBy { order.indexOf(it.color.color and 0xFFFFFF) }
    }
    private fun splitRuns(points: List<FillPoint>): List<Pair<List<FillPoint>,Boolean>> {
        val result = mutableListOf<Pair<List<FillPoint>,Boolean>>()
        var current = mutableListOf<FillPoint>()
        var startsWithJump = false
        for (point in points) {
            if ("jump" in point.tags) {
                if (current.isNotEmpty()) result += current.toList() to startsWithJump
                current = mutableListOf(point.copy(tags=point.tags-"jump"))
                startsWithJump = true
            } else current += point
        }
        if (current.isNotEmpty()) result += current.toList() to startsWithJump
        return result
    }

    companion object {
        private fun colorHex(thread: EmbThread) = "#%06X".format(thread.color and 0xFFFFFF)
        private fun hexThread(text: String): EmbThread {
            // Inkex/CSS parsing is the SVG layer's responsibility; typed callers can inject full thread metadata.
            val raw = text.removePrefix("#")
            val expanded = if (raw.length == 3) raw.flatMap { listOf(it,it) }.joinToString("") else raw
            val rgb = expanded.takeIf { it.length == 6 }?.toIntOrNull(16) ?: 0
            return EmbThread(0xFF000000.toInt() or rgb, text)
        }
    }
}
