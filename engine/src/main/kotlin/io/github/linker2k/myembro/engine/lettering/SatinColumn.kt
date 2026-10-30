// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.stitches.InkRunning
import io.github.linker2k.myembro.engine.inkstitch.elements.InkSatin
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.math.hypot

/** All lengths are mm. Density is the distance between penetrations on the SAME rail. */
data class SatinOptions(
    val widthMm: Double = 2.0,
    val densityMm: Double = 0.4,
    val maxStitchLengthMm: Double = 7.0,
    /** Extra width on EACH side, applied only to the top layer. */
    val pullCompensationMm: Double = 0.0,
    val centerUnderlay: Boolean = false,
    val zigzagUnderlay: Boolean = false,
    val underlayStitchLengthMm: Double = 2.5,
    val underlayDensityMm: Double = 1.5,
    val autoDensity: Boolean = true,
    val contourUnderlay: Boolean = false,
    val underlayInsetMm: Double = 0.4,
    val parameters: SatinParameters? = null,
) {
    /** Existing projects retain explicit legacy values; new UI satin selections store source defaults. */
    fun sourceParameters(): SatinParameters = parameters ?: SatinParameters(
        zigzag_spacing_mm = densityMm, max_stitch_length_mm = maxStitchLengthMm,
        pull_compensation_mm = pullCompensationMm to pullCompensationMm,
        center_walk_underlay = centerUnderlay, contour_underlay = contourUnderlay,
        zigzag_underlay = zigzagUnderlay,
        center_walk_underlay_stitch_length_mm = underlayStitchLengthMm,
        contour_underlay_stitch_length_mm = underlayStitchLengthMm,
        contour_underlay_inset_mm = underlayInsetMm to underlayInsetMm,
        zigzag_underlay_spacing_mm = underlayDensityMm,
        zigzag_underlay_max_stitch_length_mm = maxStitchLengthMm,
    )
    fun densityForHeight(heightMm: Double, threadWeight: ThreadWeight = ThreadWeight.WT40): Double =
        when {
            !autoDensity -> parameters?.zigzag_spacing_mm ?: densityMm
            threadWeight != ThreadWeight.WT40 -> threadWeight.spacingForHeight(heightMm)
            heightMm < 7.0 -> maxOf(0.6, parameters?.zigzag_spacing_mm ?: densityMm)
            else -> parameters?.zigzag_spacing_mm ?: densityMm
        }
    init {
        require(underlayInsetMm.isFinite() && underlayInsetMm >= 0)
        require(widthMm.isFinite() && widthMm > 0) { "widthMm must be positive" }
        require(densityMm.isFinite() && densityMm >= 0.2) { "densityMm must be at least 0.2 mm" }
        require(maxStitchLengthMm.isFinite() && maxStitchLengthMm in 0.2..7.0) { "Satin stitch limit must be 0.2..7 mm" }
        require(pullCompensationMm.isFinite() && pullCompensationMm >= 0) { "Pull compensation must be non-negative" }
        require(underlayStitchLengthMm.isFinite() && underlayStitchLengthMm in 0.2..7.0)
        require(underlayDensityMm.isFinite() && underlayDensityMm >= 0.2)
    }
}

/**
 * Basic two-rail satin. Input rails are mm, output is centered 0.1 mm machine coordinates.
 * Existing app adapter to the stable Ink/Stitch rail/rung core; SVG defaults live in SatinParameters.
 * The older centerline offset helper below is independent code, pending phase-2 convert-to-satin.
 */
object SatinColumn {
    fun generate(
        leftRail: List<Pair<Double, Double>>,
        rightRail: List<Pair<Double, Double>>,
        options: SatinOptions = SatinOptions(),
        thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
        rungs: List<InkSatin.Rung> = emptyList(),
    ): EmbPattern {
        val left = clean(leftRail).map { Point(it.first, it.second) }
        val right = clean(rightRail).map { Point(it.first, it.second) }
        // Measure rail separation, not the longer diagonal zigzag stitch (auto-rules width domain).
        val factory=org.locationtech.jts.geom.GeometryFactory()
        fun rail(path:List<Point>)=factory.createLineString(path.map { org.locationtech.jts.geom.Coordinate(it.x,it.y) }.toTypedArray())
        val width=if(left.size<2 || right.size<2) 0.0 else {
            val l=rail(left);val r=rail(right)
            maxOf(left.maxOf { a -> factory.createPoint(org.locationtech.jts.geom.Coordinate(a.x,a.y)).distance(r) },
                right.maxOf { b -> factory.createPoint(org.locationtech.jts.geom.Coordinate(b.x,b.y)).distance(l) })
        }
        // width = maximum distance between the two rails (not a writing-stroke width); above 6.0mm -> fill.
        if(TatamiLettering.methodForWidth(if(kotlin.math.abs(width-6.0)<1e-9) 6.0 else width)==StitchStyle.FILL)
            return TatamiLettering.generateGlyphs(listOf(OutlineGlyph(listOf(leftRail+rightRail.asReversed()),thread)))
        val groups = if (left.size >= 2 && right.size >= 2)
            InkSatin.element(left,right,options,rungs,thread).toStitchGroups(null) else emptyList()
        return GeneratedPatternProcessor.fromGroups(groups).centeredGenerated()
    }

    /** Offset a flattened centerline, then sew it as satin. Closed paths retain a closed seam. */
    fun strokeToSatin(
        stroke: List<Pair<Double, Double>>,
        widthMm: Double,
        options: SatinOptions = SatinOptions(),
        thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
    ): EmbPattern {
        val (left, right) = rails(stroke, widthMm)
        return generate(left, right, options, thread)
    }

    /** Mitered polyline offsets, with a 2× half-width miter limit at sharp corners. */
    fun rails(stroke: List<Pair<Double, Double>>, widthMm: Double): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        require(widthMm.isFinite() && widthMm > 0)
        val clean = clean(stroke)
        if (clean.size < 2) return emptyList<Pair<Double, Double>>() to emptyList()
        val closed = clean.size > 3 && clean.first() == clean.last()
        val points = if (closed) clean.dropLast(1) else clean
        val half = widthMm / 2
        val left = ArrayList<Pair<Double, Double>>()
        val right = ArrayList<Pair<Double, Double>>()
        fun normal(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
            val length = distance(a, b)
            return -(b.second - a.second) / length to (b.first - a.first) / length
        }
        points.forEachIndexed { i, p ->
            val before = if (i > 0) normal(points[i - 1], p) else if (closed) normal(points.last(), p) else normal(p, points[1])
            val after = if (i < points.lastIndex) normal(p, points[i + 1]) else if (closed) normal(p, points.first()) else before
            val sum = before.first + after.first to before.second + after.second
            val length = hypot(sum.first, sum.second)
            val bisector = if (length > 1e-9) sum.first / length to sum.second / length else before
            val projection = (bisector.first * before.first + bisector.second * before.second).coerceAtLeast(0.5)
            val offset = half / projection
            left += p.first + bisector.first * offset to p.second + bisector.second * offset
            right += p.first - bisector.first * offset to p.second - bisector.second * offset
        }
        if (closed) { left += left.first(); right += right.first() }
        return left to right
    }

    internal fun strokePoints(stroke: List<Pair<Double, Double>>, options: SatinOptions): List<Pair<Double, Double>> {
        val (left, right) = rails(stroke, options.widthMm)
        return columnPoints(left, right, options)
    }

    /** Kotlin port of Ink/Stitch rails/underlay/stitches.py; provenance in InkSatin.kt. */
    private fun columnPoints(left: List<Pair<Double, Double>>, right: List<Pair<Double, Double>>, options: SatinOptions): List<Pair<Double, Double>> =
        connectLayers(InkSatin.paths(clean(left).map { Point(it.first, it.second) }, clean(right).map { Point(it.first, it.second) }, options), options)
            .map { it.x to it.y }

    // Ink/Stitch satin_column.py:add_running_stitches, connecting underlay groups before top stitching.
    private fun connectLayers(paths: List<List<Point>>, options: SatinOptions): List<Point> {
        val result = arrayListOf<Point>()
        for (path in paths.filter { it.isNotEmpty() }) {
            if (result.isNotEmpty()) result += InkRunning.split(result.last(), path.first(), options.underlayStitchLengthMm)
            result += path
        }
        return result
    }

    private fun clean(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        require(points.all { (x, y) -> x.isFinite() && y.isFinite() && kotlin.math.abs(x) < 1e7 && kotlin.math.abs(y) < 1e7 }) {
            "Satin coordinates must be finite and within machine range"
        }
        return points.filterIndexed { i, p -> i == 0 || distance(p, points[i - 1]) > 1e-9 }
    }

    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(b.first - a.first, b.second - a.second)
}
