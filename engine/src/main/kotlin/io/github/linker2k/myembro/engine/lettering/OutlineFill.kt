// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.inkstitch.elements.InkSatin
import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchPlanFinisher
import io.github.linker2k.myembro.engine.digitizing.RunningSettings
import io.github.linker2k.myembro.engine.digitizing.RunningStitch
import io.github.linker2k.myembro.engine.digitizing.TatamiFill
import io.github.linker2k.myembro.engine.digitizing.TatamiSettings
import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.geometry.Polyline as GeometryPath
import io.github.linker2k.myembro.engine.model.*
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import kotlin.math.*

enum class FillBorder { NONE, RUNNING, BEAN }
enum class FillStrategy { AUTO, TATAMI }

/** Public dimensions are mm; a positive pull compensation expands exteriors, preserving counters. */
data class FillOptions(
    val densityMm: Double = 0.4,
    val angleDeg: Double = 0.0,
    val pullCompensationMm: Double = 0.2,
    val underlay: Boolean = true,
    val border: FillBorder = FillBorder.RUNNING,
    val strategy: FillStrategy = FillStrategy.AUTO,
    val autoDensity: Boolean = true,
) {
    init {
        require(densityMm.isFinite() && densityMm in 0.3..2.0)
        require(angleDeg.isFinite() && angleDeg in -180.0..180.0)
        require(pullCompensationMm.isFinite() && pullCompensationMm in 0.0..2.0)
    }
    fun densityForHeight(heightMm: Double, threadWeight: ThreadWeight = ThreadWeight.WT40): Double = when {
        !autoDensity -> densityMm
        threadWeight != ThreadWeight.WT40 -> threadWeight.spacingForHeight(heightMm)
        heightMm < 7.0 -> max(0.6, densityMm)
        else -> densityMm
    }
}

internal data class OutlineGlyph(val contours: List<List<Pair<Double, Double>>>, val thread: EmbThread, val heightMm: Double? = null)

/** Even-odd filled glyphs. Public inputs use mm; needle positions use centered 0.1 mm units. */
object OutlineFill {
    fun generate(
        contours: List<List<Pair<Double, Double>>>,
        options: FillOptions = FillOptions(),
        thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
        satin: Boolean = false,
        heightMm: Double? = null,
    ): EmbPattern {
        require(heightMm == null || heightMm.isFinite() && heightMm > 0)
        return generateGlyphs(listOf(OutlineGlyph(contours, thread, heightMm)), options, satin)
    }

    internal fun generateGlyphs(glyphs: List<OutlineGlyph>, options: FillOptions, satin: Boolean): EmbPattern {
        val builder = FillStitches()
        for (glyph in glyphs) {
            val raw = PolygonRegion.fromPaths(glyph.contours.map { path ->
                GeometryPath(path.map { (x, y) -> Point(x * 10, y * 10) }, closed = true)
            }, FillRule.EVEN_ODD).let { PolygonRegion(it.geometry.norm()) }
            if (raw.isEmpty) continue
            // Buffering must not close a small 'ㅇ' or the counters of B, even with compensation.
            fun shells(g: org.locationtech.jts.geom.Geometry): List<GeometryPath> = when (g) {
                is Polygon -> listOf(GeometryPath(g.exteriorRing.coordinates.map { Point(it.x, it.y) }, true))
                is org.locationtech.jts.geom.GeometryCollection -> (0 until g.numGeometries).flatMap { shells(g.getGeometryN(it)) }
                else -> emptyList()
            }
            val holes = PolygonRegion.fromPaths(shells(raw.geometry)).difference(raw)
            val region = raw.offset(options.pullCompensationMm * 10).difference(holes).let { PolygonRegion(it.geometry.norm()) }
            val height = glyph.heightMm ?: raw.geometry.envelopeInternal.height / 10
            val density = options.densityForHeight(height)
            val rails = if (satin) OutlineSatinValidation.rails(raw) else null
            if (satin && rails == null) builder.warnings += "새틴 기둥 검증을 통과하지 못해 자동 채우기를 사용했습니다."
            builder.begin(region, glyph.thread)
            if (rails != null) {
                val settings = SatinOptions(densityMm = density, pullCompensationMm = options.pullCompensationMm,
                    centerUnderlay = options.underlay, contourUnderlay = options.underlay && rails.first.first().distance(rails.second.first()) > 7.0)
                InkSatin.paths(rails.first, rails.second, settings).forEach { path ->
                    builder.path(path.map { Point(it.x * 10, it.y * 10) },
                        maxStitchLengthMm = if (rails.first.first().distance(rails.second.first()) > 7.0) 4.0 else 7.0)
                }
            } else {
                // Ink/Stitch fill_stitch.py: default underlay is angle+90, 3x row spacing, inset 0.
                if (options.underlay) InkAutoFill.paths(raw, InkAutoFill.Settings(
                    angleDegrees = options.angleDeg + 90, rowSpacing = density * 30,
                    underpath = options.strategy == FillStrategy.AUTO,
                )).forEach { builder.path(it) }
                InkAutoFill.paths(region, InkAutoFill.Settings(
                    angleDegrees = options.angleDeg, rowSpacing = density * 10,
                    underpath = options.strategy == FillStrategy.AUTO,
                )).forEach { builder.path(it) }
            }
            if (options.border != FillBorder.NONE) region.boundaries().forEach {
                builder.path(InkRunning.even(it.points, 25.0, 0.0), options.border == FillBorder.BEAN)
            }
        }
        return builder.finish()
    }

}

/** Prepared polygon indexes make per-needle containment checks practical for flattened fonts. */
private class FillCoverage(region: PolygonRegion) {
    private val prepared = PreparedGeometryFactory.prepare(region.geometry)
    private val factory = GeometryFactory()
    fun coversSegment(a: Point, b: Point): Boolean = prepared.covers(
        factory.createLineString(arrayOf(Coordinate(a.x, a.y), Coordinate(b.x, b.y))))
}

/** Enforce 0.3..7 mm on sewn moves AFTER quantization; a tiny corner may require a jump. */
private class FillStitches {
    private val stitches = arrayListOf<Stitch>()
    private val threads = arrayListOf<EmbThread>()
    val warnings = linkedSetOf<String>()
    private lateinit var region: FillCoverage
    private var first = true
    private var lastWasBean = false
    fun begin(region: PolygonRegion, thread: EmbThread) {
        this.region = FillCoverage(region.offset(0.75)) // <= sqrt(2)*0.05 mm machine-rounding envelope
        if (stitches.isNotEmpty()) stitches += stitches.last().copy(command = Command.TRIM)
        if (threads.isEmpty() || !threads.last().sameThread(thread)) {
            if (threads.isNotEmpty()) stitches += stitches.last().copy(command = Command.COLOR_CHANGE)
            threads += thread
        }
        first = true
    }
    private fun point(s: Stitch) = Point(s.x.toDouble(), s.y.toDouble())
    private fun jump(target: Stitch) {
        stitches += target.copy(command = Command.JUMP)
        stitches += target
        lastWasBean = false
    }
    private fun sew(p: Point, bean: Boolean = false, maxStitchLengthMm: Double = 7.0) {
        val target = Stitch(p.x.roundToInt(), p.y.roundToInt(), Command.STITCH)
        val last = stitches.lastOrNull()
        if (last == null || first) { jump(target); first = false; return }
        val a = point(last); val b = point(target)
        val distance = a.distance(b)
        if (distance < 3.0) {
            // Move the preceding penetration to a nearby corner instead of either sewing
            // a sub-0.3 mm stitch or cutting the corner on the following movement.
            val before = stitches.getOrNull(stitches.lastIndex - 1)
            if (distance > 0 && !bean && !lastWasBean && before?.command == Command.STITCH &&
                point(before).distance(b) in 3.0..(maxStitchLengthMm * 10) && region.coversSegment(point(before), b)
            ) stitches[stitches.lastIndex] = target
            return
        }
        if (!region.coversSegment(a, b)) { jump(target); return }
        val parts = ceil(distance / (maxStitchLengthMm * 10 - 2)).toInt().coerceAtLeast(1)
        require(parts <= 1_000_000)
        for (i in 1..parts) {
            val next = a.interpolate(b, i.toDouble() / parts).let { Stitch(it.x.roundToInt(), it.y.roundToInt(), Command.STITCH) }
            val previous = stitches.last()
            if (point(previous).distance(point(next)) < 3.0) continue
            stitches += next
            if (bean) { stitches += previous; stitches += next }
            lastWasBean = bean
        }
        require(stitches.size <= 1_000_000) { "Filled lettering exceeds command limit" }
    }
    fun path(points: List<Point>, bean: Boolean = false, maxStitchLengthMm: Double = 7.0) {
        if (points.isEmpty()) return
        if (!first) {
            val start = point(stitches.last())
            val destination = points.first()
            // stitch_groups_to_stitch_plan: collapse_len defaults to 3mm; never sew through holes.
            if (start.distance(destination) <= 30.0 && region.coversSegment(start, destination)) {
                InkRunning.split(start, destination, 25.0).forEach { sew(it) }
                sew(destination)
            } else jump(Stitch(destination.x.roundToInt(), destination.y.roundToInt(), Command.STITCH))
        }
        points.forEach { sew(it, bean, maxStitchLengthMm) }
    }
    fun finish(): EmbPattern {
        val sewn = stitches.filter { it.command == Command.STITCH }
        val cx = if (sewn.isEmpty()) 0 else (sewn.minOf { it.x }.toLong() + sewn.maxOf { it.x }).div(2).toInt()
        val cy = if (sewn.isEmpty()) 0 else (sewn.minOf { it.y }.toLong() + sewn.maxOf { it.y }).div(2).toInt()
        val centered = stitches.map { it.copy(x = it.x - cx, y = it.y - cy) }
        val last = centered.lastOrNull()
        return StitchPlanFinisher.finish(EmbPattern(centered + Stitch(last?.x ?: 0, last?.y ?: 0, Command.END), threads,
            mapOf("name" to "Filled lettering", "geometry.centerXUnits" to cx.toString(), "geometry.centerYUnits" to cy.toString()), warnings.toList()))
    }
}
