// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/guided_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Offset ring connections ported from lib/stitches/utils/connect_geometries.py at the same commit.
// Smoothing ported from lib/utils/smoothing.py at the same commit.
package io.github.linker2k.myembro.engine.inkstitch.stitches

import org.locationtech.jts.algorithm.MinimumAreaRectangle
import org.locationtech.jts.algorithm.Orientation
import org.locationtech.jts.geom.*
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.buffer.OffsetCurve
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import kotlin.math.*

/** Guided fill in SVG pixels (+y down). Public parameters have their inkstitch:* SVG units. */
object GuidedFill {
    private const val PX_PER_MM = 96.0 / 25.4
    private const val EPS = 1e-8
    private const val MAX_ROWS = 100_000
    private val factory = GeometryFactory()

    data class Parameters(
        val guided_fill_strategy: Int = 0,
        val guided_fill_angle: Double? = null,
        val angle: Double = 0.0,
        val row_spacing_mm: Double = 0.25,
        val max_stitch_length_mm: Double = 4.0,
        val staggers: Double = 4.0,
        val skip_last: Boolean = false,
        val underpath: Boolean = true,
        val running_stitch_length_mm: Double = 2.5,
        // Actual upstream getter default is 0.2 (the parameter decorator displays 0.1).
        val running_stitch_tolerance_mm: Double = 0.2,
        val min_stitch_length_mm: Double? = null,
        val stitch_position_method: String = "flexible",
        val smoothness_mm: Double = 0.0,
        val bean_stitch_repeats: List<Int> = listOf(0),
        val enable_random_stitch_length: Boolean = false,
        val random_stitch_length_jitter_percent: Double = 10.0,
        val random_seed: String = "",
    ) {
        init {
            require(guided_fill_strategy in 0..2)
            require(guided_fill_angle == null || guided_fill_angle.isFinite())
            require(angle.isFinite())
            require(listOf(row_spacing_mm, max_stitch_length_mm, running_stitch_length_mm,
                running_stitch_tolerance_mm, smoothness_mm,
                random_stitch_length_jitter_percent).all { it.isFinite() && it >= 0 })
            require(min_stitch_length_mm == null || (min_stitch_length_mm.isFinite() && min_stitch_length_mm >= 0))
            require(staggers.isFinite() && staggers >= 0)
            require(stitch_position_method in setOf("flexible", "flexible_stagger"))
            require(bean_stitch_repeats.isNotEmpty() && bean_stitch_repeats.all { it in 0..100 })
            require(!enable_random_stitch_length) { "Guided fill random stitch lengths are not ported yet" }
        }
        internal val rowSpacing get() = max(row_spacing_mm, 0.1) * PX_PER_MM
        internal val maxLength get() = max(max_stitch_length_mm, 0.1) * PX_PER_MM
        internal val runningLength get() = max(running_stitch_length_mm * PX_PER_MM, 0.01)
        internal val tolerance get() = max(running_stitch_tolerance_mm * PX_PER_MM, 0.01)
        internal val minimum get() = (min_stitch_length_mm?.takeIf { it != 0.0 } ?: 0.1) * PX_PER_MM
    }

    /**
     * Generates guided rows and routes them with the common fill/travel graph.
     * A multi-part region is repaired first; disconnected components remain separate jump runs.
     * Empty guides and empty gratings fall back to tatami with the upstream angle rule.
     */
    fun generate(
        shape: Geometry,
        guideline: Geometry,
        parameters: Parameters = Parameters(),
        anchor_line: Geometry? = null,
        starting_point: FillPoint? = null,
        ending_point: FillPoint? = null,
    ): List<FillPoint> {
        require(shape.numPoints <= 200_000 && guideline.numPoints <= 200_000) { "Guided fill exceeds 200000 input vertices" }
        require(shape.coordinates.all { it.x.isFinite() && it.y.isFinite() })
        require(guideline.coordinates.all { it.x.isFinite() && it.y.isFinite() })
        val repaired = FillGeometry.makeValid(shape)
        if (guideline.isEmpty) return tatamiFallback(repaired, parameters, Math.toRadians(parameters.angle), starting_point, ending_point)
        val result = mutableListOf<FillPoint>()
        for (polygon in polygons(repaired)) {
            val segments = stitchSegments(polygon, guideline, parameters, anchor_line)
            if (segments.isEmpty()) {
                val firstGuide = lines(guideline).first()
                val a = firstGuide.coordinates.first(); val b = firstGuide.coordinates.last()
                val fallback = tatamiFallback(polygon, parameters, -atan2(b.y-a.y,b.x-a.x), starting_point ?: result.lastOrNull(), ending_point)
                if (result.isNotEmpty() && fallback.isNotEmpty()) result += fallback.first().tagged("jump")
                result += fallback
                continue
            }
            val points = FillRouting.routeSegments(
                polygon, segments, starting_point ?: result.lastOrNull(), ending_point,
                parameters.underpath, parameters.runningLength, parameters.tolerance,
                Math.toRadians(parameters.guided_fill_angle ?: 0.0), parameters.skip_last,
            )
            val sewn = if (parameters.bean_stitch_repeats.any { it > 0 })
                bean(filterSmall(points, parameters.minimum), parameters.bean_stitch_repeats) else points
            if (result.isNotEmpty() && sewn.isNotEmpty())
                result += sewn.first().copy(tags = sewn.first().tags + "jump")
            result += sewn
        }
        return result
    }

    private fun tatamiFallback(shape: Geometry, p: Parameters, angle: Double, start: FillPoint?, end: FillPoint?): List<FillPoint> =
        TatamiFill.fill(shape, angle, p.rowSpacing, null, p.maxLength, p.runningLength, p.tolerance,
            p.staggers, p.skip_last, start, end, p.underpath)

    /** Resampled, clipped rows before graph routing, exposed for geometric regression fixtures. */
    fun stitchSegments(
        shape: Polygon,
        guideline: Geometry,
        parameters: Parameters = Parameters(),
        anchor_line: Geometry? = null,
    ): List<List<FillPoint>> {
        if (shape.isEmpty) return emptyList()
        require(shape.isValid) { "stitchSegments requires a valid Polygon; generate repairs invalid regions" }
        val guide = prepareGuideLine(guideline, shape, parameters.guided_fill_strategy)
        var segments = grating(shape, guide, parameters)
        if (parameters.guided_fill_strategy > 0 && segments.isNotEmpty())
            segments = connectOffsetLines(shape, segments, parameters)
        val sorted = sortSegments(segments, parameters.guided_fill_strategy)
        return sorted.flatMapIndexed { index, original ->
            var line = if (parameters.smoothness_mm > 0)
                smooth(original, parameters.smoothness_mm * PX_PER_MM) else original
            var rolled = false
            if (line.isRing && (shape.contains(point(line.coordinates.first())) || anchor_line != null)) {
                if (anchor_line != null) {
                    val anchored = anchoredLine(line, guide, anchor_line, index, parameters)
                    line = anchored.first
                    rolled = anchored.second
                }
                if (!rolled) {
                    outsideMidpoint(line, shape)?.let { outside ->
                        line = stagger(roll(line, project(line, outside)), guide, index, parameters)
                    }
                }
            } else line = stagger(line, guide, index, parameters)
            if (rolled || shape.contains(point(line.coordinates.first()))) {
                outsideMidpoint(line, shape)?.let { line = roll(line, project(line, it)) }
            }
            clipSegments(shape, line).map { coords ->
                var points = coords.map { FillPoint(it.x, it.y, setOf("guided_fill", "fill_row")) }
                if (points.isNotEmpty()) {
                    points = points.toMutableList().apply {
                        this[0] = first().copy(tags = first().tags + "fill_row_start")
                        this[lastIndex] = last().copy(tags = last().tags + "fill_row_end")
                    }
                }
                points
            }.filter { it.isNotEmpty() }
        }
    }

    /** Upstream prepare_copy_guide / prepare_offset_guide / buffer normalization. */
    internal fun prepareGuideLine(guideline: Geometry, shape: Polygon, strategy: Int): Geometry {
        require(!guideline.isEmpty)
        if (strategy == 2) return DouglasPeuckerSimplifier.simplify(guideline, 0.0001).norm()
        var line = lines(guideline).firstOrNull()
            ?: throw IllegalArgumentException("Guideline must contain a LineString")
        require(line.length > EPS) { "Guideline must have nonzero length" }
        if (strategy == 0) {
            if (!line.isSimple) line = repairNonSimple(line)
            if (line.isRing) {
                val coords = line.coordinates.dropLast(2)
                require(coords.size >= 2) { "Closed copy guide requires at least four vertices" }
                line = line(coords)
            }
            return extend(line, shape)
        }
        if (!line.isRing) line = extend(line, shape)
        if (!line.isSimple) line = repairNonSimple(line)
        return line
    }

    /** Raw row geometries follow upstream's positive sweep, then negative sweep. */
    internal fun grating(shape: Polygon, guide: Geometry, p: Parameters): List<LineString> {
        val spacing = p.rowSpacing
        val strategy = p.guided_fill_strategy
        val base = if (strategy == 2) null else guide as LineString
        val direction = if (base != null) {
            val a = base.coordinates.first(); val b = base.coordinates.last()
            if (strategy == 1 || p.guided_fill_angle == null) {
                val length = a.distance(b)
                if (length <= EPS) Coordinate(0.0, 0.0) else Coordinate(-(b.y-a.y)/length, (b.x-a.x)/length)
            } else Coordinate(cos(Math.toRadians(p.guided_fill_angle)), sin(Math.toRadians(p.guided_fill_angle)))
        } else null
        // For a closed parallel guide the upstream endpoint unit vector is zero, hence start row 0.
        val startRow = if (strategy == 2) (guide.distance(shape) / spacing).toInt() else {
            if (guide.intersects(shape) || (direction!!.x == 0.0 && direction.y == 0.0)) 0 else {
                val near = DistanceOp.nearestPoints(guide, shape.centroid)
                val row = (near[0].distance(near[1]) / spacing).toInt()
                if ((near[1].x-near[0].x)*direction.x + (near[1].y-near[0].y)*direction.y < 0) -row else row
            }
        }
        val result = mutableListOf<LineString>()
        var row = startRow
        var step = 1
        var iterations = 0
        while (true) {
            require(++iterations <= MAX_ROWS) { "Guided fill exceeds $MAX_ROWS grating rows" }
            val segment: Geometry = when (strategy) {
                1 -> OffsetCurve.getCurve(guide, if (row == 0) 0.0001 else row * spacing, 16, BufferParameters.JOIN_ROUND, 5.0)
                2 -> {
                    if (step == -1) break
                    guide.buffer(row * spacing + spacing / 2, 16).boundary
                }
                else -> {
                    var shifted = AffineTransformation.translationInstance(direction!!.x*row*spacing, direction.y*row*spacing).transform(guide) as LineString
                    if (p.smoothness_mm > 0) shifted = smooth(shifted, p.smoothness_mm * PX_PER_MM)
                    shifted
                }
            }
            if (!segment.isEmpty && shape.envelope.intersects(segment)) {
                result += mergeLines(segment).filter { it.intersects(shape) }
                row += step
            } else if (step == 1) { step = -1; row = startRow-1 }
            else break
        }
        return result
    }

    private fun stagger(line: LineString, guide: Geometry, index: Int, p: Parameters): LineString {
        val staggers = if (p.staggers == 0.0) 1.0 else p.staggers
        val position = if (p.guided_fill_strategy == 2) Math.rint(line.distance(guide) / p.rowSpacing) else index.toDouble()
        val start = ((position / staggers) % 1) * p.maxLength
        val section = substring(line, start, line.length)
        val points = FillRunning.run(section.coordinates.map { FillPoint(it.x, it.y) }, p.maxLength,
            p.tolerance, p.minimum, p.stitch_position_method == "flexible")
        return line(listOf(line.coordinates.first()) + points.map { Coordinate(it.x, it.y) })
    }

    private fun anchoredLine(line: LineString, guide: Geometry, anchor: Geometry, index: Int, p: Parameters): Pair<LineString, Boolean> {
        val intersection = pointCoordinates(line.intersection(anchor))
        if (intersection.isEmpty()) return line to false
        val rolled = roll(line, project(line, intersection.first()))
        if (intersection.size == 1) return stagger(rolled, guide, index, p) to true
        val positions = (listOf(0.0, rolled.length) + intersection.drop(1).map { project(rolled, it) }).sorted()
        return line(positions.zipWithNext().flatMap { (a,b) -> stagger(substring(rolled,a,b),guide,index,p).coordinates.toList() }) to true
    }

    private fun sortSegments(segments: List<LineString>, strategy: Int): List<LineString> {
        if (strategy == 2 || segments.isEmpty()) return segments
        val points = factory.createMultiPointFromCoords(segments.map { l ->
            val c = l.coordinates.first(); Coordinate(Math.rint(c.x * 10000)/10000, Math.rint(c.y*10000)/10000)
        }.toTypedArray())
        val rectangle = MinimumAreaRectangle.getMinimumRectangle(points)
        val projectionLine = when (rectangle) {
            is LineString -> rectangle
            is Polygon -> rectangle.envelopeInternal.let { line(listOf(Coordinate(it.minX,it.minY),Coordinate(it.maxX,it.maxY))) }
            else -> return segments
        }
        return segments.sortedBy { project(projectionLine, it.coordinates.first()) }
    }

    /** Cut each actual traversal independently: noding a self-crossing row would lose its order. */
    private fun clipSegments(shape: Polygon, line: LineString): List<List<Coordinate>> {
        val output = mutableListOf<MutableList<Coordinate>>()
        var active: MutableList<Coordinate>? = null
        for ((a,b) in line.coordinates.toList().zipWithNext()) {
            if (a.equals2D(b)) continue
            val edge = line(listOf(a,b))
            val parts = lines(edge.intersection(shape)).sortedBy { project(edge,it.coordinates.first()) }
            for (part in parts) {
                val coords = part.coordinates.let { if (project(edge,it.first()) > project(edge,it.last())) it.reversedArray() else it }
                if (coords.size < 2 || part.length <= EPS || !shape.contains(point(LengthIndexedLine(part).extractPoint(part.length / 2)))) continue
                if (active?.last()?.equals2D(coords.first()) != true) {
                    active = mutableListOf(coords.first())
                    output += active
                }
                active.addAll(coords.drop(1))
            }
            if (!shape.covers(point(b))) active = null
        }
        return output
    }

    private fun repairNonSimple(original: LineString): LineString {
        var repaired = mergeLines(UnaryUnionOp.union(original))
        repeat(4) {
            if (repaired.size == 1) return repaired.first()
            repaired = mergeLines(UnaryUnionOp.union(repaired.filterNot { it.isRing }))
        }
        return if (repaired.size == 1) repaired.first() else line(listOf(original.coordinates.first(),original.coordinates.last()))
    }

    private fun extend(original: LineString, shape: Polygon): LineString {
        val coords = original.coordinates
        val start = coords.first(); val end = coords.last()
        val distance = start.distance(end)
        require(distance > EPS) { "Guide endpoints must differ" }
        val extent = shape.envelope.coordinates.maxOf { max(it.distance(start), it.distance(end)) }
        val dx = (end.x-start.x)/distance*extent; val dy=(end.y-start.y)/distance*extent
        // Upstream applies nondeterministic [0,.01) perturbations for an old GEOS bug.
        // JTS has no corresponding requirement; stable coordinates allow reproducible PES output.
        return line(listOf(Coordinate(start.x-dx,start.y-dy)) + coords + Coordinate(end.x+dx,end.y+dy))
    }

    private fun smooth(original: LineString, smoothness: Double): LineString {
        var points = FillRunning.curveEvenly(original.coordinates.map { FillPoint(it.x,it.y) }, smoothness*5, smoothness*2)
        repeat(5) {
            if (points.size >= 2) {
                val next = mutableListOf(points.first())
                for ((a,b) in points.zipWithNext()) {
                    next += FillPoint(a.x*.75+b.x*.25,a.y*.75+b.y*.25)
                    next += FillPoint(a.x*.25+b.x*.75,a.y*.25+b.y*.75)
                }
                next += points.last(); points = next
            }
        }
        return line(listOf(original.coordinates.first()) + points.map { Coordinate(it.x,it.y) } + original.coordinates.last())
    }

    private fun outsideMidpoint(line: LineString, shape: Polygon): Coordinate? {
        val exterior = factory.createPolygon(shape.exteriorRing.coordinates)
        val outside = mergeLines(line.difference(exterior)).firstOrNull() ?: return null
        return LengthIndexedLine(outside).extractPoint(outside.length/2)
    }

    // connect_offset_lines: keep nested offset loops attached to an exterior-crossing row.
    private fun connectOffsetLines(shape: Polygon, segments: List<LineString>, p: Parameters): List<LineString> {
        val outlines = mutableListOf<LineString>()
        val within = mutableListOf<Polygon>()
        for (original in segments) {
            if (!shape.intersects(original)) continue
            var current = original
            if (!shape.exteriorRing.intersects(current)) {
                var coords = current.coordinates.toList()
                if (coords.size < 4) coords = segmentize(current, current.length/3).coordinates.toList()
                if (!coords.first().equals2D(coords.last())) coords = coords + coords.first()
                val polygon = factory.createPolygon(coords.toTypedArray())
                if (!polygon.isEmpty) within += polygon
            } else {
                if (current.isClosed && shape.contains(point(current.coordinates.first()))) {
                    lines(current.difference(shape)).firstOrNull()?.let { outside ->
                        current = roll(current,project(current,LengthIndexedLine(outside).extractPoint(outside.length/2)))
                    }
                }
                outlines += current
            }
        }
        if (within.isEmpty()) return segments
        if (outlines.isEmpty()) outlines += lines(shape.boundary)
        within.sortBy { it.area }
        val relations = within.indices.associateWith { i -> ((i+1) until within.size).firstOrNull { within[it].covers(within[i]) } }
        val rings = within.map { it.exteriorRing as LineString }.toMutableList()
        val remove = mutableSetOf<Int>()
        for ((innerIndex,outerIndex) in relations) {
            if (outerIndex == null) continue
            val inner = ensureCcw(rings[innerIndex]); val outer=ensureCcw(rings[outerIndex])
            var offset=0.0
            var joined=false
            while (offset+p.rowSpacing < inner.length) {
                val c=connector(inner,outer,offset,p.rowSpacing)
                if (c.d1 < p.rowSpacing*2.5 && c.d2 < p.rowSpacing*2.5) {
                    val ri=roll(inner,project(inner,c.p2));val ro=roll(outer,project(outer,c.p4))
                    val openedInner=substring(ri,0.0,ri.length-p.rowSpacing)
                    val openedOuter=substring(ro,0.0,ro.length-p.rowSpacing)
                    var icoords=(if(p.skip_last) segmentize(openedInner,p.maxLength) else openedInner).coordinates.reversed()
                    if(p.skip_last) icoords=icoords.drop(1).dropLast(1)
                    val coordinates=openedOuter.coordinates.toList()+icoords
                    rings[outerIndex]=close(coordinates)
                    remove+=innerIndex;joined=true;break
                }
                offset+=1.0
            }
            if(!joined) remove+=innerIndex // matches upstream: unconnectable nested loops are discarded.
        }
        val remaining=rings.filterIndexed { i,_ -> i !in remove }
        connectRings(shape,outlines,remaining,p.rowSpacing)
        return outlines
    }

    private data class Connector(val p1:Coordinate,val p2:Coordinate,val p3:Coordinate,val p4:Coordinate,val d1:Double,val d2:Double)
    private fun connector(inner:LineString,outer:Geometry,offset:Double,spacing:Double):Connector {
        val indexed=LengthIndexedLine(inner)
        val p1=indexed.extractPoint(offset);val p2=indexed.extractPoint(offset+spacing)
        val p3=DistanceOp.nearestPoints(outer,point(p1))[0];val p4=DistanceOp.nearestPoints(outer,point(p2))[0]
        return Connector(p1,p2,p3,p4,p1.distance(p3),p2.distance(p4))
    }

    private fun connectRings(shape:Polygon,outlines:MutableList<LineString>,rings:List<LineString>,spacing:Double,iteration:Int=0) {
        val remove=mutableSetOf<Int>();val add=mutableListOf<LineString>();val unconnected=mutableListOf<LineString>()
        for(original in rings) {
            var ring=original
            var connected:Int?=null
            var done=false
            for(i in outlines.indices) {
                if(i in remove || original.distance(outlines[i])>spacing+.001) continue
                val candidate=outlines[i]
                if(connected==null) {
                    val combined=connectRingToLine(shape,ring,candidate,spacing)?:continue
                    outlines[i]=combined;ring=combined;connected=i
                    if(candidate.intersects(shape.exteriorRing)){done=true;break}
                } else {
                    if(shape.contains(point(candidate.coordinates.first()))) {
                        val combined=connectRingToLine(shape,ring,candidate,spacing)?:continue
                        outlines[connected]=combined
                    }else{
                        val combined=connectLines(ring,candidate,spacing)
                        outlines[connected]=combined.first;add+=combined.second
                    }
                    remove+=i;done=true;break
                }
            }
            if(!done) unconnected+=ring
        }
        for(i in remove.sortedDescending()) outlines.removeAt(i)
        outlines+=add
        if(unconnected.isNotEmpty() && iteration<5) connectRings(shape,outlines,unconnected,spacing,iteration+1)
    }

    private fun connectRingToLine(shape:Polygon,ring:LineString,line:LineString,spacing:Double):LineString? {
        var offset=0.0
        val fraction=line.intersection(shape)
        if(fraction.isEmpty) return null
        while(ring.length>=spacing+offset) {
            val c=connector(ring,fraction,offset,spacing)
            if(c.d1<spacing*1.5 && c.d2<spacing*1.5) {
                val a=min(project(line,c.p3),project(line,c.p4))
                val before=substring(line,0.0,a);val after=substring(line,a+spacing,line.length)
                val rolled=roll(ring,project(ring,c.p1));val opened=substring(rolled,spacing,rolled.length)
                val coords=before.coordinates.toList()+opened.coordinates+after.coordinates
                val combined=line(coords)
                return if(combined.isSimple)combined else line(before.coordinates.toList()+opened.coordinates.reversed()+after.coordinates)
            }
            offset+=1.0
        }
        return null
    }

    private fun connectLines(first:LineString,second:LineString,spacing:Double):Pair<LineString,LineString> {
        var other=second
        val nearest=DistanceOp.nearestPoints(first,other);val p1=nearest[0];val p3=nearest[1]
        val p2=LengthIndexedLine(first).extractPoint(project(first,p1)+spacing)
        var p4=LengthIndexedLine(other).extractPoint(project(other,p3)+spacing)
        if(line(listOf(p1,p3)).intersects(line(listOf(p2,p4)))) {
            p4=LengthIndexedLine(other).extractPoint(project(other,p3)-spacing)
            other=other.reverse() as LineString
        }
        val a=substring(first,0.0,project(first,p1));val b=substring(other,0.0,project(other,p3)).reverse()
        val c=substring(first,project(first,p2),first.length).reverse();val d=substring(other,project(other,p4),other.length)
        return line(a.coordinates.toList()+b.coordinates) to line(c.coordinates.toList()+d.coordinates)
    }

    private fun filterSmall(points:List<FillPoint>,minimum:Double):List<FillPoint> {
        val filtered=mutableListOf<FillPoint>()
        for(p in points) if(filtered.isEmpty() || hypot(p.x-filtered.last().x,p.y-filtered.last().y)>minimum) filtered+=p
        return filtered
    }
    private fun bean(points:List<FillPoint>,repeats:List<Int>):List<FillPoint> {
        if(points.size<2)return points
        val result=mutableListOf(points.first())
        points.drop(1).forEachIndexed { i,p ->
            result+=p
            if("travel" !in p.tags && "fill_row_start" !in p.tags && "jump" !in p.tags)
                repeat(repeats[i%repeats.size]) {
                    require(result.size + 2 <= 1_000_000) { "Guided bean fill exceeds 1000000 stitches" }
                    result += result.takeLast(2)
                }
        }
        return result
    }

    private fun ensureCcw(line:LineString):LineString = if(Orientation.isCCW(line.coordinates))line else line.reverse() as LineString
    private fun roll(line:LineString,distance:Double):LineString {
        if(distance<=EPS || distance>=line.length-EPS)return line
        val a=substring(line,distance,line.length);val b=substring(line,0.0,distance)
        return line(a.coordinates.toList()+b.coordinates.drop(1))
    }
    private fun close(coords:List<Coordinate>) = line(if(coords.first().equals2D(coords.last()))coords else coords+coords.first())
    private fun segmentize(line:LineString,maxLength:Double):LineString {
        if(maxLength<=EPS)return line
        val coords=mutableListOf(line.coordinates.first())
        for((a,b) in line.coordinates.toList().zipWithNext()) {
            val count=ceil(a.distance(b)/maxLength)
            require(count.isFinite() && count<=1_000_000-coords.size) { "Guided ring exceeds 1000000 vertices" }
            val n=max(1,count.toInt())
            for(i in 1..n)coords+=Coordinate(a.x+(b.x-a.x)*i/n,a.y+(b.y-a.y)*i/n)
        }
        return line(coords)
    }
    private fun substring(line:LineString,start:Double,end:Double):LineString {
        val indexed=LengthIndexedLine(line)
        val a=start.coerceIn(0.0,line.length);val b=end.coerceIn(0.0,line.length)
        return if(abs(a-b)<EPS)line(listOf(indexed.extractPoint(a),indexed.extractPoint(a)))
        else indexed.extractLine(a,b) as LineString
    }
    private fun project(line:LineString,p:Coordinate)=LengthIndexedLine(line).project(p)
    private fun point(c:Coordinate)=factory.createPoint(c)
    private fun line(coords:List<Coordinate>):LineString {
        require(coords.isNotEmpty())
        return factory.createLineString((if(coords.size==1)coords+coords.first() else coords).toTypedArray())
    }
    private fun lines(geometry:Geometry):List<LineString> = when {
        geometry.isEmpty->emptyList()
        geometry is LineString->listOf(geometry)
        geometry is GeometryCollection -> (0 until geometry.numGeometries).flatMap { lines(geometry.getGeometryN(it)) }
        else->emptyList()
    }
    private fun polygons(geometry:Geometry):List<Polygon> = when {
        geometry.isEmpty->emptyList()
        geometry is Polygon->listOf(geometry)
        geometry is GeometryCollection -> (0 until geometry.numGeometries).flatMap { polygons(geometry.getGeometryN(it)) }
        else->emptyList()
    }
    private fun mergeLines(geometry:Geometry):List<LineString> = mergeLines(lines(geometry))
    @Suppress("UNCHECKED_CAST")
    private fun mergeLines(lines:List<LineString>):List<LineString> = LineMerger().apply { add(lines) }.mergedLineStrings.toList() as List<LineString>
    private fun pointCoordinates(geometry:Geometry):List<Coordinate> = when {
        geometry.isEmpty->emptyList()
        geometry is org.locationtech.jts.geom.Point->listOf(geometry.coordinate)
        geometry is GeometryCollection -> (0 until geometry.numGeometries).flatMap { pointCoordinates(geometry.getGeometryN(it)) }
        else->emptyList()
    }
}
