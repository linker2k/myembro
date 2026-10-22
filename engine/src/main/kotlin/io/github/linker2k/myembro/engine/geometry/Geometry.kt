// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.operation.polygonize.Polygonizer
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.geom.util.AffineTransformation
import kotlin.math.*

/** Engine geometry uses 0.1 mm, +y downward. SVG user units must be mapped explicitly. */
data class Point(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite() && abs(x) <= 1e9 && abs(y) <= 1e9) { "Invalid geometry coordinate" } }
    fun distance(other: Point): Double = hypot(x - other.x, y - other.y)
    fun interpolate(other: Point, t: Double) = Point(x + (other.x - x) * t, y + (other.y - y) * t)
}

/** A closed line may repeat its first point; consumers also close it when [closed] is true. */
data class Polyline(val points: List<Point>, val closed: Boolean = false) {
    internal fun closedPoints(): List<Point> = if (points.size > 1 && points.last() != points.first()) points + points.first() else points
}

/** SVG matrix(a b c d e f), column vectors. A * B applies B first, then A. */
data class Affine2D(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0,
    val d: Double = 1.0, val e: Double = 0.0, val f: Double = 0.0) {
    init { require(listOf(a, b, c, d, e, f).all { it.isFinite() }) }
    fun apply(p: Point) = Point(a * p.x + c * p.y + e, b * p.x + d * p.y + f)
    operator fun times(q: Affine2D) = Affine2D(a*q.a+c*q.b, b*q.a+d*q.b, a*q.c+c*q.d, b*q.c+d*q.d,
        a*q.e+c*q.f+e, b*q.e+d*q.f+f)
    companion object {
        val IDENTITY = Affine2D()
        fun translation(x: Double, y: Double) = Affine2D(e = x, f = y)
        fun scale(x: Double, y: Double = x) = Affine2D(a = x, d = y)
        fun rotation(degrees: Double): Affine2D {
            val r = Math.toRadians(degrees)
            return Affine2D(cos(r), sin(r), -sin(r), cos(r))
        }
        /** SVG transform attribute; nested parent/child matrices compose as parent * child. */
        fun parse(text: String): Affine2D {
            val input = SvgNumbers(text)
            var result = IDENTITY
            while (!input.done()) {
                val name = input.word()
                input.expect('(')
                val values = mutableListOf<Double>()
                while (!input.peek(')')) values += input.number()
                input.expect(')')
                fun size(vararg accepted: Int) { require(values.size in accepted) { "Invalid $name arguments" } }
                val next = when (name) {
                    "matrix" -> { size(6); Affine2D(values[0], values[1], values[2], values[3], values[4], values[5]) }
                    "translate" -> { size(1, 2); translation(values[0], values.getOrElse(1) { 0.0 }) }
                    "scale" -> { size(1, 2); scale(values[0], values.getOrElse(1) { values[0] }) }
                    "rotate" -> { size(1, 3); if (values.size == 1) rotation(values[0]) else
                        translation(values[1], values[2]) * rotation(values[0]) * translation(-values[1], -values[2]) }
                    "skewX" -> { size(1); Affine2D(c = tan(Math.toRadians(values[0]))) }
                    "skewY" -> { size(1); Affine2D(b = tan(Math.toRadians(values[0]))) }
                    else -> throw IllegalArgumentException("Unsupported SVG transform: $name")
                }
                result *= next
            }
            return result
        }
    }
}

enum class FillRule { EVEN_ODD, NON_ZERO }

/** Immutable polygonal set. JTS objects stay internal; operations return independent sets. */
class PolygonRegion internal constructor(internal val geometry: Geometry) {
    val area: Double get() = geometry.area
    val isEmpty: Boolean get() = geometry.isEmpty
    fun union(other: PolygonRegion) = overlay(other, OverlayNG.UNION)
    fun intersection(other: PolygonRegion) = overlay(other, OverlayNG.INTERSECTION)
    fun difference(other: PolygonRegion) = overlay(other, OverlayNG.DIFFERENCE)
    fun xor(other: PolygonRegion) = overlay(other, OverlayNG.SYMDIFFERENCE)
    private fun overlay(other: PolygonRegion, operation: Int) = polygonal(OverlayNGRobust.overlay(geometry, other.geometry, operation))
    /** Positive expands; negative insets (and expands holes). Collapsed parts disappear. */
    fun offset(distance: Double): PolygonRegion {
        require(distance.isFinite())
        return polygonal(geometry.buffer(distance))
    }
    fun transformed(matrix: Affine2D): PolygonRegion = if (matrix.a * matrix.d - matrix.b * matrix.c == 0.0) empty() else
        polygonal(AffineTransformation(matrix.a, matrix.c, matrix.e, matrix.b, matrix.d, matrix.f).transform(geometry))
    fun covers(point: Point): Boolean = geometry.covers(FACTORY.createPoint(Coordinate(point.x, point.y)))
    fun coversSegment(a: Point, b: Point): Boolean = geometry.covers(line(listOf(a, b)))
    fun boundaries(): List<Polyline> = polygons(geometry).flatMap { polygon ->
        (listOf(polygon.exteriorRing) + (0 until polygon.numInteriorRing).map(polygon::getInteriorRingN))
            .map { ring -> Polyline(ring.coordinates.map { Point(it.x, it.y) }, closed = true) }
    }
    /** Intersect a horizontal scanline with this set, including its holes; intervals ascend in x. */
    internal fun horizontalSpans(y: Double): List<Pair<Double, Double>> {
        if (isEmpty) return emptyList()
        val bounds = geometry.envelopeInternal
        val cut = geometry.intersection(line(listOf(Point(bounds.minX - 1, y), Point(bounds.maxX + 1, y))))
        fun spans(g: Geometry): List<Pair<Double, Double>> = when {
            g.isEmpty -> emptyList()
            g is org.locationtech.jts.geom.LineString -> listOf(g.envelopeInternal.minX to g.envelopeInternal.maxX)
            g is GeometryCollection -> (0 until g.numGeometries).flatMap { spans(g.getGeometryN(it)) }
            else -> emptyList()
        }
        return spans(cut).filter { it.second - it.first > 1e-8 }.sortedBy { it.first }
    }
    companion object {
        private val FACTORY = GeometryFactory()
        fun empty() = PolygonRegion(FACTORY.createPolygon())
        private fun line(points: List<Point>) = FACTORY.createLineString(points.map { Coordinate(it.x, it.y) }.toTypedArray())
        private fun polygons(g: Geometry): List<Polygon> = when {
            g.isEmpty -> emptyList()
            g is Polygon -> listOf(g)
            g is GeometryCollection -> (0 until g.numGeometries).flatMap { polygons(g.getGeometryN(it)) }
            else -> emptyList()
        }
        private fun polygonal(g: Geometry): PolygonRegion {
            val parts = polygons(g)
            return PolygonRegion(if (parts.isEmpty()) FACTORY.createPolygon() else FACTORY.createMultiPolygon(parts.toTypedArray()))
        }
        /** SVG fill implicitly closes open subpaths. Noding + face classification supports self intersections. */
        fun fromPaths(paths: List<Polyline>, rule: FillRule = FillRule.NON_ZERO): PolygonRegion {
            val rings = paths.map { it.closedPoints() }.filter { it.distinct().size >= 3 }
            if (rings.isEmpty()) return empty()
            require(rings.sumOf { it.size.toLong() } <= 200_000) { "Too many polygon vertices" }
            val polygonizer = Polygonizer()
            polygonizer.add(UnaryUnionOp.union(rings.map(::line)))
            fun winding(p: Coordinate): Int {
                var result = 0
                for (ring in rings) for ((a, b) in ring.zipWithNext()) {
                    val cross = (b.x-a.x)*(p.y-a.y) - (p.x-a.x)*(b.y-a.y)
                    if (a.y <= p.y && b.y > p.y && cross > 0) result++
                    if (a.y > p.y && b.y <= p.y && cross < 0) result--
                }
                return result
            }
            val selected = polygonizer.polygons.map { it as Polygon }.filter {
                val w = winding(it.interiorPoint.coordinate)
                if (rule == FillRule.NON_ZERO) w != 0 else abs(w) % 2 == 1
            }
            return if (selected.isEmpty()) empty() else polygonal(UnaryUnionOp.union(selected))
        }
    }
}

/** Cursor consumes every character: unsupported commands and malformed numbers cannot be skipped. */
internal class SvgNumbers(private val text: String) {
    private var index = 0
    private fun skip() { while (index < text.length && (text[index].isWhitespace() || text[index] == ',')) index++ }
    fun done(): Boolean { skip(); return index == text.length }
    fun peek(c: Char): Boolean { skip(); return text.getOrNull(index) == c }
    fun command(): Char? { skip(); return text.getOrNull(index)?.takeIf { it.isLetter() }?.also { index++ } }
    fun word(): String { skip(); val start = index; while (text.getOrNull(index)?.isLetter() == true) index++; require(index > start); return text.substring(start, index) }
    fun expect(c: Char) { skip(); require(text.getOrNull(index) == c) { "Expected '$c' at $index" }; index++ }
    fun flag(): Boolean { skip(); val c = text.getOrNull(index); require(c == '0' || c == '1') { "Invalid arc flag" }; index++; return c == '1' }
    fun number(): Double {
        skip()
        val match = NUMBER.matchAt(text, index) ?: throw IllegalArgumentException("Expected SVG number at $index")
        index = match.range.last + 1
        return match.value.toDouble().also { require(it.isFinite()) { "Non-finite SVG number" } }
    }
    companion object { private val NUMBER = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?") }
}
