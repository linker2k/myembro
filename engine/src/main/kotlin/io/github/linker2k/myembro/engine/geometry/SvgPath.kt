// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import kotlin.math.*

/** SVG path-data parser, not an SVG/XML/CSS document loader. See docs/geometry.md for units. */
object SvgPath {
    /** Tolerance is measured AFTER [transform], in output units (default 0.05 mm).
     * Optional [canonicalize] models upstream path serialization before curve subdivision;
     * existing callers retain full precision. It is not applied to generated needle coordinates.
     */
    fun flatten(data: String, transform: Affine2D = Affine2D.IDENTITY, tolerance: Double = 0.5,
        canonicalize: ((Point) -> Point)? = null): List<Polyline> {
        require(tolerance.isFinite() && tolerance > 0)
        fun mapped(p: Point): Point = transform.apply(p).let { canonicalize?.invoke(it) ?: it }
        val input = SvgNumbers(data)
        val output = mutableListOf<Polyline>()
        var points = mutableListOf<Point>()
        var current = Point(0.0, 0.0)
        var start = current
        var command: Char? = null
        var previous = ' '
        var cubicControl: Point? = null
        var quadControl: Point? = null
        var count = 0
        var started = false
        fun emit(p: Point) {
            require(++count <= 200_000) { "SVG flattening exceeds 200000 points" }
            if (points.lastOrNull() != p) points += p
        }
        fun flush(closed: Boolean = false) {
            if (points.isNotEmpty()) output += Polyline(points.toList(), closed)
            points = mutableListOf()
        }
        fun prepare() { if (points.isEmpty()) emit(mapped(current)) }
        fun bezier(p0: Point, p1: Point, p2: Point, p3: Point, depth: Int = 0) {
            fun distance(p: Point): Double {
                val dx = p3.x - p0.x; val dy = p3.y - p0.y
                val squared = dx*dx + dy*dy
                val t = if (squared == 0.0) 0.0 else (((p.x-p0.x)*dx+(p.y-p0.y)*dy)/squared).coerceIn(0.0, 1.0)
                return p.distance(p0.interpolate(p3, t))
            }
            if (max(distance(p1), distance(p2)) <= tolerance) { emit(p3); return }
            require(depth < 24) { "SVG tolerance requires excessive subdivision" }
            val p01 = p0.interpolate(p1, 0.5); val p12 = p1.interpolate(p2, 0.5); val p23 = p2.interpolate(p3, 0.5)
            val p012 = p01.interpolate(p12, 0.5); val p123 = p12.interpolate(p23, 0.5)
            val middle = p012.interpolate(p123, 0.5)
            bezier(p0, p01, p012, middle, depth + 1)
            bezier(middle, p123, p23, p3, depth + 1)
        }
        while (!input.done()) {
            val explicit = input.command()
            if (explicit != null) command = explicit
            val cmd = command ?: throw IllegalArgumentException("SVG path requires a command")
            require(cmd.uppercaseChar() in "MLHVCSQTAZ") { "Unsupported SVG path command $cmd" }
            require(started || cmd.uppercaseChar() == 'M') { "SVG path must start with moveto" }
            val relative = cmd.isLowerCase()
            fun point(): Point {
                val p = Point(input.number(), input.number())
                return if (relative) Point(p.x + current.x, p.y + current.y) else p
            }
            val upper = cmd.uppercaseChar()
            when (upper) {
                'M' -> {
                    flush(); current = point(); start = current; started = true
                    emit(mapped(current)); command = if (relative) 'l' else 'L'
                }
                'L', 'H', 'V' -> {
                    val end = when (upper) {
                        'H' -> Point(input.number() + if (relative) current.x else 0.0, current.y)
                        'V' -> Point(current.x, input.number() + if (relative) current.y else 0.0)
                        else -> point()
                    }
                    prepare(); emit(mapped(end)); current = end
                }
                'C', 'S', 'Q', 'T' -> {
                    prepare()
                    val p0 = current
                    val p1: Point
                    val p2: Point
                    val end: Point
                    if (upper == 'C' || upper == 'S') {
                        p1 = if (upper == 'C') point() else if (previous in "CS")
                            Point(2*current.x-cubicControl!!.x, 2*current.y-cubicControl!!.y) else current
                        p2 = point(); end = point(); cubicControl = p2
                    } else {
                        val q = if (upper == 'Q') point() else if (previous in "QT")
                            Point(2*current.x-quadControl!!.x, 2*current.y-quadControl!!.y) else current
                        end = point(); quadControl = q
                        p1 = p0.interpolate(q, 2.0/3); p2 = end.interpolate(q, 2.0/3)
                    }
                    bezier(mapped(p0), mapped(p1), mapped(p2), mapped(end))
                    current = end
                }
                'A' -> {
                    val rx = input.number(); val ry = input.number(); val rotation = input.number()
                    val large = input.flag(); val sweep = input.flag(); val end = point()
                    prepare()
                    arc(current, end, rx, ry, rotation, large, sweep, transform, tolerance) { emit(canonicalize?.invoke(it) ?: it) }
                    current = end
                }
                'Z' -> {
                    prepare(); emit(mapped(start)); flush(closed = true); current = start; command = null
                }
            }
            if (upper !in "CS") cubicControl = null
            if (upper !in "QT") quadControl = null
            previous = upper
        }
        flush()
        return output
    }

    fun polygons(data: String, rule: FillRule = FillRule.NON_ZERO,
        transform: Affine2D = Affine2D.IDENTITY, tolerance: Double = 0.5): PolygonRegion =
        PolygonRegion.fromPaths(flatten(data, transform, tolerance), rule)

    /** W3C SVG endpoint-to-center conversion and radii correction (SVG 2, appendix B.2). */
    private fun arc(from: Point, to: Point, radiusX: Double, radiusY: Double, rotation: Double,
        large: Boolean, sweep: Boolean, matrix: Affine2D, tolerance: Double, emit: (Point) -> Unit) {
        if (from == to) return
        var rx = abs(radiusX); var ry = abs(radiusY)
        if (rx == 0.0 || ry == 0.0) { emit(matrix.apply(to)); return }
        val phi = Math.toRadians(rotation % 360)
        val cp = cos(phi); val sp = sin(phi)
        val dx = (from.x-to.x)/2; val dy = (from.y-to.y)/2
        val x1 = cp*dx+sp*dy; val y1 = -sp*dx+cp*dy
        val ratio = (x1/rx).pow(2) + (y1/ry).pow(2)
        if (ratio > 1) { val scale = sqrt(ratio); rx *= scale; ry *= scale }
        val normalized = (x1/rx).pow(2) + (y1/ry).pow(2)
        val factor = (if (large == sweep) -1 else 1) * sqrt(max(0.0, (1-normalized)/normalized))
        val cx1 = factor*rx*y1/ry; val cy1 = -factor*ry*x1/rx
        val cx = cp*cx1-sp*cy1+(from.x+to.x)/2
        val cy = sp*cx1+cp*cy1+(from.y+to.y)/2
        val ux = (x1-cx1)/rx; val uy = (y1-cy1)/ry
        val vx = (-x1-cx1)/rx; val vy = (-y1-cy1)/ry
        val begin = atan2(uy, ux)
        var delta = atan2(ux*vy-uy*vx, ux*vx+uy*vy)
        if (sweep && delta < 0) delta += 2*PI
        if (!sweep && delta > 0) delta -= 2*PI
        // Frobenius norm bounds the maximum radius after arbitrary skew/nonuniform scale.
        val radius = max(rx, ry) * sqrt(matrix.a*matrix.a+matrix.b*matrix.b+matrix.c*matrix.c+matrix.d*matrix.d)
        require(radius.isFinite() && normalized > 0.0) { "Unrepresentable SVG arc" }
        if (radius == 0.0) { emit(matrix.apply(to)); return }
        val step = min(PI/2, 2*acos((1-tolerance/radius).coerceIn(-1.0, 1.0)))
        val subdivisions = ceil(abs(delta)/step)
        require(subdivisions.isFinite() && subdivisions <= 200_000) { "SVG arc exceeds point limit" }
        val n = max(1, subdivisions.toInt())
        for (i in 1..n) {
            val angle = begin+delta*i/n
            val x = rx*cos(angle); val y = ry*sin(angle)
            emit(if (i == n) matrix.apply(to) else matrix.apply(Point(cp*x-sp*y+cx, sp*x+cp*y+cy)))
        }
    }
}
