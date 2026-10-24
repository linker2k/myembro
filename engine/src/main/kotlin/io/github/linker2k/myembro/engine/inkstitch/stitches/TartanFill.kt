// Ported from Ink/Stitch lib/stitches/tartan_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/tartan/utils.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import org.locationtech.jts.algorithm.MinimumBoundingCircle
import org.locationtech.jts.geom.*
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.operation.buffer.OffsetCurve
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.simplify.TopologyPreservingSimplifier
import kotlin.math.*

/** Typed equivalent of the stripes parsed from inkstitch:tartan JSON. Widths are millimetres. */
object TartanFill {
    data class Stripe(val color: String, val width: Double, val render: Int = 1) {
        init { require(color.isNotBlank() && width.isFinite() && width >= 0 && render in 0..2) }
    }
    data class Settings(
        val warp: List<Stripe> = listOf(Stripe("#101010", 5.0), Stripe("#FFFFFF", 5.0, 0)),
        val weft: List<Stripe> = warp,
        val rotate: Double = 0.0,
        val offset_x: Double = 0.0,
        val offset_y: Double = 0.0,
        val symmetry: Boolean = true,
        val scale: Double = 100.0,
        val min_stripe_width: Double = 1.0,
    ) {
        init {
            require(warp.size <= 1000 && weft.size <= 1000)
            require(listOf(rotate, offset_x, offset_y, scale, min_stripe_width).all { it.isFinite() })
            require(scale > 0 && min_stripe_width >= 0)
        }
    }
    data class Parameters(
        val row_spacing_mm: Double = 0.25,
        val max_stitch_length_mm: Double = 4.0,
        val running_stitch_length_mm: Double = 2.5,
        val running_stitch_tolerance_mm: Double = 0.2,
        val staggers: Double = 4.0,
        val angle: Double = 0.0,
        val tartan_angle: Double = -45.0,
        val rows_per_thread: Int = 2,
        val herringbone_width_mm: Double = 0.0,
        val skip_last: Boolean = false,
        val fill_underlay: Boolean = true,
        val stop_at_ending_point: Boolean = false,
        val bean_stitch_repeats: List<Int> = listOf(0),
        val enable_random_stitch_length: Boolean = false,
    ) {
        init {
            require(listOf(row_spacing_mm, max_stitch_length_mm, running_stitch_length_mm,
                running_stitch_tolerance_mm).all { it.isFinite() && it > 0 })
            require(listOf(staggers, angle, tartan_angle, herringbone_width_mm).all { it.isFinite() })
            require(staggers >= 0 && rows_per_thread in 1..1000 && herringbone_width_mm >= 0)
            require(bean_stitch_repeats.isNotEmpty() && bean_stitch_repeats.all { it in 0..100 })
            require(!enable_random_stitch_length) { "Ink/Stitch seeded random stitch lengths are not ported" }
        }
    }
    data class ColorSection(val color: String, val stitches: List<FillPoint>, val running: Boolean = false)
    data class SettStripe(val color: String?, val width: Double, val is_stroke: Boolean, val render: Boolean)
    data class StripeShapes(val polygons: List<Polygon>, val runs: List<LineString>)
    data class Dimensions(val minx: Double, val miny: Double, val maxx: Double, val maxy: Double)

    fun generate(shape: Geometry, parameters: Parameters = Parameters(), settings: Settings = Settings(),
        startingPoint: FillPoint? = null, endingPoint: FillPoint? = null): List<ColorSection> {
        val region = FillGeometry.makeValid(shape)
        if (region.isEmpty) return emptyList()
        val p = parameters
        val spacing = max(0.1, p.row_spacing_mm) * PIXELS_PER_MM
        val length = max(0.1, p.max_stitch_length_mm) * PIXELS_PER_MM
        val runningLength = p.running_stitch_length_mm * PIXELS_PER_MM
        val dimensions = dimensions(region, p, settings)
        val warp = stripeShapes(settings.warp, dimensions, region, settings, false)
        val weft = stripeShapes(settings.weft, dimensions, region, settings, true)
        if (warp.isEmpty() && weft.isEmpty()) return emptyList()
        val herringbone = if (p.herringbone_width_mm > 0) herringboneSegments(region, dimensions, settings,
            p, warp, weft) else null
        val (warpLines, weftLines) = if (herringbone == null) splitWarpWeft(generateLines(region, dimensions,
            settings.rotate + p.tartan_angle, spacing, length, p.staggers), p.rows_per_thread) else emptyList<LineString>() to emptyList()
        val fillLines = linkedMapOf<String, MutableList<LineString>>()
        val runLines = linkedMapOf<String, MutableList<LineString>>()
        for ((weftDirection, groups, directionLines) in listOf(Triple(false, warp, warpLines), Triple(true, weft, weftLines))) {
            for ((color, stripes) in groups) {
                val segments = herringbone?.let { (if (weftDirection) it.second else it.first)[color].orEmpty() }
                    ?: stripes.polygons.flatMap { polygon ->
                    segmentLines(polygon, directionLines, region, settings.rotate, runningLength, weftDirection)
                }
                if (segments.isNotEmpty()) fillLines.getOrPut(color) { mutableListOf() }.addAll(segments)
                val runLength = if (weftDirection) length else runningLength
                val runs = stripes.runs.map { segmentize(it, runLength) }
                if (runs.isNotEmpty()) runLines.getOrPut(color) { mutableListOf() }.addAll(runs)
            }
        }
        // sort_fills_and_strokes: join the last common fill/run color at the transition.
        val common = fillLines.keys.lastOrNull { it in runLines }
        if (common != null) {
            val last = fillLines.remove(common)!!
            fillLines[common] = last
            val first = runLines.remove(common)!!
            val remainder = runLines.toMap()
            runLines.clear(); runLines[common] = first; runLines.putAll(remainder)
        }
        val result = mutableListOf<ColorSection>()
        var start = startingPoint
        val defaultPoint = FillGeometry.lines(region.boundary).first().coordinates[1].let { FillPoint(it.x, it.y) }
        var iteration = 0
        for ((color, segments) in fillLines) {
            if (!p.stop_at_ending_point) {
                iteration++
                if (result.isNotEmpty()) start = result.last().stitches.lastOrNull() ?: start
            }
            var stitches = route(region, segments, start ?: defaultPoint, endingPoint ?: defaultPoint, p)
            if (iteration > 0) {
                // Upstream passes the color string, rather than the color list, to this helper.
                stitches = LinearGradientFill.removeStartEndTravel(stitches, color.length, iteration,
                    p.fill_underlay, p.stop_at_ending_point)
            }
            if (stitches.isNotEmpty()) result += ColorSection(color, stitches)
        }
        if (result.isNotEmpty() && !p.stop_at_ending_point) start = result.last().stitches.last()
        var lastRun: List<FillPoint>? = null
        for ((color, linesForColor) in runLines) {
            if (!p.stop_at_ending_point && lastRun != null) start = lastRun.lastOrNull() ?: start
            val segments = linesForColor.filter { it.length > 0.5 * PIXELS_PER_MM }
            if (segments.isEmpty()) continue
            val stitches = CircularFill.applyBeanStitchAndRepeats(route(region, segments, start, endingPoint, p),
                1, p.bean_stitch_repeats)
            if (stitches.isNotEmpty()) { result += ColorSection(color, stitches, true); lastRun = stitches }
        }
        return result
    }

    /** _stripes_to_sett: stroke-center half widths, invisible spaces, inherited fill, reflected pivots. */
    fun stripesToSett(stripes: List<Stripe>, symmetry: Boolean, scale: Double, minStripeWidth: Double): List<SettStripe> {
        require(scale.isFinite() && scale > 0 && minStripeWidth.isFinite() && minStripeWidth >= 0)
        val fillColors = stripes.mapNotNull { stripe -> when {
            stripe.render == 0 -> listOf<String?>(null)
            stripe.render == 2 -> null
            stripe.width * scale / 100 > minStripeWidth -> listOf(stripe.color)
            else -> null
        } }.flatten()
        var lastFillColor = if (symmetry) fillColors.firstOrNull() else fillColors.lastOrNull()
        var firstWasStroke = false
        var lastWasStroke = false
        var addWidth = 0.0
        val sett = mutableListOf<SettStripe>()
        for (stripe in stripes) {
            var width = stripe.width * PIXELS_PER_MM * scale / 100
            if (stripe.render == 0) {
                sett += SettStripe(null, width + addWidth, false, false)
                lastFillColor = null; addWidth = 0.0; lastWasStroke = false
            } else if (stripe.render == 2) {
                sett += SettStripe(lastFillColor, width + addWidth, false, true)
                addWidth = 0.0; lastWasStroke = false
            } else if (width <= minStripeWidth * PIXELS_PER_MM) {
                if (sett.isEmpty()) firstWasStroke = true
                width /= 2
                sett += SettStripe(lastFillColor, width + addWidth, false, true)
                sett += SettStripe(stripe.color, 0.0, true, true)
                addWidth = width; lastWasStroke = true
            } else {
                sett += SettStripe(stripe.color, width + addWidth, false, true)
                lastFillColor = stripe.color; lastWasStroke = false
            }
        }
        if (addWidth > 0) sett += SettStripe(lastFillColor, addWidth, false, true)
        if (symmetry) {
            var reverse = sett.drop(1).dropLast(1).reversed()
            if (firstWasStroke) reverse = reverse.dropLast(1)
            if (lastWasStroke) reverse = reverse.drop(1)
            sett += reverse
        }
        return sett
    }

    /** _split_warp_weft followed by _sort_lines, preserving block order and alternating direction. */
    fun splitWarpWeft(lines: List<LineString>, rowsPerThread: Int): Pair<List<LineString>, List<LineString>> {
        require(rowsPerThread in 1..1000)
        fun sorted(offset: Int): List<LineString> {
            val selected = (0 until rowsPerThread).flatMap { i ->
                (i + offset until lines.size step rowsPerThread * 2).map { lines[it] }
            }.sortedWith(compareBy<LineString> { it.getCoordinateN(0).x }.thenBy { it.getCoordinateN(0).y })
            return selected.mapIndexed { i, line -> if (i % 2 == 0) line else line.reverse() as LineString }
        }
        return sorted(0) to sorted(rowsPerThread)
    }

    fun stripeShapes(stripes: List<Stripe>, dimensions: Dimensions, outline: Geometry, settings: Settings,
        weft: Boolean = false): Map<String, StripeShapes> {
        if (stripes.none { it.render == 1 }) return emptyMap()
        val sett = stripesToSett(stripes, settings.symmetry, settings.scale, settings.min_stripe_width)
        if (sett.isEmpty()) return emptyMap()
        val width = sett.sumOf { it.width }
        if (width <= 0) return emptyMap()
        val d = dimensions
        val center = rotationCenter(outline)
        val rotation = AffineTransformation.rotationInstance(Math.toRadians(settings.rotate), center.x, center.y)
        val raw = linkedMapOf<String, MutableList<Geometry>>()
        var left = d.minx
        var top = d.miny
        var count = 0
        while (true) {
            for (stripe in sett) {
                require(++count <= 200_000) { "Tartan exceeds stripe limit" }
                if (if (weft) top > d.maxy else left > d.maxx) {
                    return raw.mapValues { (_, geometries) ->
                        val visible = geometries.filter { it.intersects(outline) }
                        val polygons = visible.filterIsInstance<Polygon>()
                        val merged = if (polygons.isEmpty()) outline.factory.createPolygon() else
                            TopologyPreservingSimplifier.simplify(UnaryUnionOp.union(polygons), 0.01)
                        StripeShapes(FillGeometry.polygons(merged), visible.filterIsInstance<LineString>())
                    }
                }
                val right = left + stripe.width
                val bottom = top + stripe.width
                if (stripe.color == null || !stripe.render) { left = right; top = bottom; continue }
                val geometry = if (stripe.is_stroke) {
                    val line = if (weft) listOf(FillPoint(d.minx, top), FillPoint(d.maxx, top)) else
                        listOf(FillPoint(left, d.miny), FillPoint(left, d.maxy))
                    rotation.transform(FillGeometry.line(line)).intersection(outline)
                } else {
                    val coords = if (weft) arrayOf(Coordinate(d.minx, top), Coordinate(d.maxx, top),
                        Coordinate(d.maxx, bottom), Coordinate(d.minx, bottom), Coordinate(d.minx, top)) else
                        arrayOf(Coordinate(left, d.miny), Coordinate(right, d.miny), Coordinate(right, d.maxy),
                            Coordinate(left, d.maxy), Coordinate(left, d.miny))
                    left = right; top = bottom
                    if (stripe.width == 0.0) continue
                    rotation.transform(outline.factory.createPolygon(coords))
                }
                val shapes = raw.getOrPut(stripe.color) { mutableListOf() }
                if (stripe.is_stroke) shapes.addAll(FillGeometry.lines(geometry)) else shapes += geometry
            }
        }
    }

    private fun dimensions(shape: Geometry, p: Parameters, settings: Settings): Dimensions {
        val center = rotationCenter(shape)
        val radius = MinimumBoundingCircle(shape).radius
        fun paletteWidth(stripes: List<Stripe>): Double {
            var width = 0.0; var strokeWidth = 0.0
            for (s in stripes) {
                val scaled = s.width * settings.scale / 100
                if (scaled >= settings.min_stripe_width && s.render != 2) width += scaled
                else if (scaled < settings.min_stripe_width && s.render != 0) strokeWidth += scaled
            }
            return if (width == 0.0) strokeWidth else width
        }
        val extra = max(max(paletteWidth(settings.warp), paletteWidth(settings.weft)) * PIXELS_PER_MM,
            2 * max(0.1, p.row_spacing_mm) * PIXELS_PER_MM * p.rows_per_thread)
        return Dimensions(center.x - radius - extra - abs(settings.offset_x) * PIXELS_PER_MM,
            center.y - radius - extra - abs(settings.offset_y) * PIXELS_PER_MM,
            center.x + radius + extra, center.y + radius + extra)
    }

    private fun rotationCenter(shape: Geometry): FillPoint = shape.envelopeInternal.let {
        FillPoint((it.minX + it.maxX) / 2, (it.minY + it.maxY) / 2)
    }
    private fun generateLines(shape: Geometry, d: Dimensions, degrees: Double, spacing: Double,
        length: Double, staggers: Double): List<LineString> {
        val center = rotationCenter(shape)
        val rotation = AffineTransformation.rotationInstance(Math.toRadians(degrees), center.x, center.y)
        val rowCount = ceil((d.maxy - d.miny) / spacing)
        require(rowCount.isFinite() && rowCount in 1.0..200_000.0) { "Tartan exceeds row limit" }
        var points = 0
        return (0..rowCount.toInt()).map { row ->
            val y = d.miny + (d.maxy - d.miny) * row / rowCount
            val original = rotation.transform(FillGeometry.line(listOf(FillPoint(d.minx, y), FillPoint(d.maxx, y)))) as LineString
            val a = original.getCoordinateN(0).let { FillPoint(it.x, it.y) }
            val b = original.getCoordinateN(1).let { FillPoint(it.x, it.y) }
            val phase = positiveModulo(row / (if (staggers == 0.0) 1.0 else staggers), 1.0) * length
            val lineLength = a.distance(b)
            val coordinates = mutableListOf(a, a)
            var distance = phase
            while (distance < lineLength) {
                require(coordinates.size <= 1_000_000) { "Tartan row exceeds stitch limit" }
                coordinates += a.interpolate(b, distance / lineLength)
                distance += length
            }
            if (coordinates.last().distance(b) > spacing / 2) coordinates += b
            coordinates += b
            points += coordinates.size
            require(points <= 1_000_000) { "Tartan exceeds stitch limit" }
            FillGeometry.line(coordinates)
        }
    }

    private fun segmentLines(polygon: Polygon, lines: List<LineString>, outline: Geometry,
        rotation: Double, length: Double, weft: Boolean, herringbone: Boolean = false): List<LineString> {
        if (lines.isEmpty()) return emptyList()
        val multiline = outline.factory.createMultiLineString(lines.toTypedArray())
        if (!multiline.intersects(polygon)) return emptyList()
        val clipped = FillGeometry.lines(multiline.intersection(polygon)).filter { it.length > 0.5 }
        val segments = mutableListOf<LineString>()
        var previous: LineString? = null
        for (line in clipped) {
            segments += line
            val from = previous?.endPoint
            val to = line.startPoint
            if (from != null && !from.equalsExact(to) && from.distance(outline.boundary) > 0.005 &&
                to.distance(outline.boundary) > 0.005) {
                segments += segmentize(outline.factory.createLineString(arrayOf(from.coordinate, to.coordinate)), length)
            }
            previous = line
        }
        val merged = if (herringbone && weft) merge(segments) else
            merge(segments).flatMap { FillGeometry.lines(it.intersection(outline)) }
        if (herringbone) return merged
        val connectors = mutableListOf<LineString>()
        for (line in merged) for (point in listOf(line.startPoint, line.endPoint)) {
            if (!outline.intersects(point) || point.distance(outline.boundary) <= 0.05) continue
            val angle = Math.toRadians(rotation + if (weft) 0.0 else 90.0)
            val scale = outline.coordinates.maxOf { it.distance(point.coordinate) } * 2
            val dx = cos(angle); val dy = sin(angle)
            val ray = outline.factory.createLineString(arrayOf(
                Coordinate(point.x + dx * (1 - scale) / 2, point.y + dy * (1 - scale) / 2),
                Coordinate(point.x + dx * (1 + scale) / 2, point.y + dy * (1 + scale) / 2)))
            val intersection = outline.boundary.intersection(ray)
            if (intersection.isEmpty) continue
            val nearest = DistanceOp.nearestPoints(intersection, point)[0]
            connectors += segmentize(outline.factory.createLineString(arrayOf(point.coordinate, nearest)), length)
        }
        return merge(merged + connectors)
    }

    /** _generate_herringbone_lines, keeping the 0.005px gap needed by GEOS/JTS line merging. */
    private fun herringboneSegments(outline: Geometry, d: Dimensions, settings: Settings, p: Parameters,
        warpShapes: Map<String, StripeShapes>, weftShapes: Map<String, StripeShapes>):
        Pair<Map<String, List<LineString>>, Map<String, List<LineString>>> {
        val center = rotationCenter(outline)
        val rotation = AffineTransformation.rotationInstance(Math.toRadians(settings.rotate), center.x, center.y)
        val width = p.herringbone_width_mm * PIXELS_PER_MM
        val spacing = max(0.1, p.row_spacing_mm) * PIXELS_PER_MM
        val stitchLength = max(0.1, p.max_stitch_length_mm) * PIXELS_PER_MM
        val runningLength = p.running_stitch_length_mm * PIXELS_PER_MM
        val blocks = arrayOf(mutableListOf<List<LineString>>(), mutableListOf<List<LineString>>())
        var left = d.minx
        var odd = true
        var totalPoints = 0L
        var columns = 0
        while (left < d.maxx) {
            require(++columns <= 20_000) { "Tartan exceeds herringbone column limit" }
            odd = !odd
            val right = left + width
            val leftStartY = if (odd) d.miny else d.miny - width
            val leftEndY = if (odd) d.maxy + width else d.maxy
            val rightStartY = if (odd) d.miny - width else d.miny
            val rightEndY = if (odd) d.maxy else d.maxy + width
            val count = ceil((leftEndY - leftStartY) / spacing)
            require(count.isFinite() && count in 1.0..200_000.0) { "Tartan exceeds herringbone row limit" }
            val lines = (0..count.toInt()).map { row ->
                val a = FillPoint(left, leftStartY + (leftEndY - leftStartY) * row / count)
                val b = FillPoint(right, rightStartY + (rightEndY - rightStartY) * row / count)
                val points = staggeredLine(a, b, row, spacing, stitchLength, p.staggers)
                totalPoints += points.size
                require(totalPoints <= 1_000_000) { "Tartan exceeds herringbone stitch limit" }
                rotation.transform(FillGeometry.line(points)) as LineString
            }
            blocks[if (odd) 0 else 1] += lines
            left += width + 0.005
        }
        val warpBlocks = mutableListOf<List<LineString>>()
        val weftBlocks = mutableListOf<List<LineString>>()
        for ((direction, columnBlocks) in blocks.withIndex()) for (block in columnBlocks) {
            val (first, second) = splitWarpWeft(block, p.rows_per_thread)
            warpBlocks += if (direction == 0) first else second
            val weft = if (direction == 0) second else first
            val connected = mutableListOf<LineString>()
            for ((index, line) in weft.withIndex()) {
                connected += line
                if (index < weft.lastIndex) connected += segmentize(outline.factory.createLineString(
                    arrayOf(line.endPoint.coordinate, weft[index + 1].startPoint.coordinate)), runningLength)
            }
            weftBlocks += merge(connected)
        }
        val warp = linkedMapOf<String, MutableList<LineString>>()
        for (block in warpBlocks) {
            val merged = merge(block)
            for ((color, shapes) in warpShapes) for (polygon in shapes.polygons) {
                warp.getOrPut(color) { mutableListOf() }.addAll(segmentLines(polygon, merged, outline,
                    settings.rotate, runningLength, false, true))
            }
        }
        val weft = linkedMapOf<String, MutableList<List<LineString>>>()
        for (block in weftBlocks) for ((color, shapes) in weftShapes) {
            val segments = shapes.polygons.flatMap { polygon -> segmentLines(polygon, block, outline,
                settings.rotate, runningLength, true, true) }
            if (segments.isNotEmpty()) weft.getOrPut(color) { mutableListOf() } += segments
        }
        val connectedWeft = linkedMapOf<String, List<LineString>>()
        for ((color, groups) in weft) {
            val colorLines = mutableListOf<LineString>()
            for (stripe in weftShapes.getValue(color).polygons) {
                val coords = (stripe.norm() as Polygon).exteriorRing.coordinates
                if (coords.size < 5) continue
                var top = outline.factory.createLineString(arrayOf(coords[0], coords[1]))
                var bottom = outline.factory.createLineString(arrayOf(coords[3], coords[2]))
                if (!top.intersects(outline) && !bottom.intersects(outline)) {
                    top = outline.factory.createLineString(arrayOf(coords[1], coords[2]))
                    bottom = outline.factory.createLineString(arrayOf(coords[4], coords[3]))
                }
                val indexedBottom = LengthIndexedLine(bottom)
                val sorted = groups.sortedBy { group ->
                    val multiline = outline.factory.createMultiLineString(group.toTypedArray())
                    indexedBottom.project(multiline.centroid.coordinate)
                }.flatten().filter { it.intersects(stripe) }
                if (sorted.isEmpty()) continue
                colorLines += sorted
                if (top.intersects(outline) || bottom.intersects(outline))
                    colorLines += herringboneWeftConnectors(sorted, top, bottom, runningLength)
            }
            val shifted = AffineTransformation.translationInstance(0.00123, 0.00123)
            connectedWeft[color] = merge(colorLines).flatMap {
                FillGeometry.lines(shifted.transform(it).intersection(outline))
            }
        }
        return warp.mapValues { it.value.toList() } to connectedWeft
    }

    private fun herringboneWeftConnectors(lines: List<LineString>, originalTop: LineString,
        originalBottom: LineString, length: Double): List<LineString> {
        val result = mutableListOf<LineString>()
        var top = originalTop
        var bottom = originalBottom
        var previous: Point? = null
        for (line in lines.reversed()) {
            var start = line.startPoint
            var end = line.endPoint
            if (previous == null) {
                if (LengthIndexedLine(top).project(start.coordinate) / top.length > 0.5) {
                    top = top.reverse() as LineString; bottom = bottom.reverse() as LineString
                }
                val indexed = LengthIndexedLine(top)
                if (indexed.project(start.coordinate) > indexed.project(end.coordinate)) {
                    val swap = start; start = end; end = swap
                }
                previous = end
                continue
            }
            val previousBoundary = if (previous.distance(top) <= previous.distance(bottom)) top else bottom
            val currentBoundary = if (start.distance(top) <= start.distance(bottom)) top else bottom
            if (previousBoundary != currentBoundary) { val swap = start; start = end; end = swap }
            if (!previous.equalsExact(start)) {
                val connector = line.factory.createLineString(arrayOf(previous.coordinate, start.coordinate))
                val offset = OffsetCurve.getCurve(connector, if (previousBoundary == top) -0.0001 else 0.0001)
                val offsetLines = FillGeometry.lines(offset)
                if (offsetLines.isNotEmpty()) {
                    result += line.factory.createLineString(arrayOf(previous.coordinate, offsetLines.first().startPoint.coordinate))
                    result += offsetLines.map { segmentize(it, length) }
                    result += line.factory.createLineString(arrayOf(offsetLines.last().endPoint.coordinate, start.coordinate))
                }
            }
            previous = end
        }
        return result
    }

    private fun staggeredLine(a: FillPoint, b: FillPoint, row: Int, spacing: Double,
        length: Double, staggers: Double): List<FillPoint> {
        val phase = positiveModulo(row / (if (staggers == 0.0) 1.0 else staggers), 1.0) * length
        val lineLength = a.distance(b)
        val coordinates = mutableListOf(a, a)
        var distance = phase
        while (distance < lineLength) {
            require(coordinates.size <= 1_000_000) { "Tartan row exceeds stitch limit" }
            coordinates += a.interpolate(b, distance / lineLength)
            distance += length
        }
        if (coordinates.last().distance(b) > spacing / 2) coordinates += b
        coordinates += b
        return coordinates
    }

    private fun merge(lines: List<LineString>): List<LineString> {
        val merger = LineMerger()
        merger.add(lines)
        @Suppress("UNCHECKED_CAST")
        return (merger.mergedLineStrings as Collection<LineString>).toList()
    }
    private fun segmentize(line: LineString, length: Double): LineString {
        val result = mutableListOf<FillPoint>()
        val coordinates = FillGeometry.points(line)
        if (coordinates.isEmpty()) return line
        result += coordinates.first()
        for ((a, b) in coordinates.zipWithNext()) {
            val count = max(1.0, ceil(a.distance(b) / length))
            require(count <= 1_000_000 - result.size) { "Tartan segment exceeds stitch limit" }
            for (step in 1..count.toInt()) result += a.interpolate(b, step / count)
        }
        return FillGeometry.line(result)
    }
    private fun route(shape: Geometry, lines: List<LineString>, start: FillPoint?, end: FillPoint?,
        p: Parameters): List<FillPoint> = FillRouting.routeSegments(shape, lines.map(FillGeometry::points),
        start, end, false, p.running_stitch_length_mm * PIXELS_PER_MM,
        p.running_stitch_tolerance_mm * PIXELS_PER_MM, Math.toRadians(p.angle), p.skip_last)
}
