// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point as MmPoint
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.elements.Stroke
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin
import io.github.linker2k.myembro.engine.inkstitch.lettering.FontTatami
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkFill
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchParams
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Flat vector art (logo, emoji) as embroidery: one shared path for every picture source.
 *
 * Each SVG `<path>` is a color area (fill) or a constant-width stroke. Its width in mm picks the
 * stitch: wide areas Ink/Stitch tatami, strokes within the satin range Ink/Stitch stroke-to-satin,
 * narrower strokes Ink/Stitch running stitch. Every stitch uses the upstream port with default
 * parameters; only the width classification and the occlusion clip below are ours.
 */
object VectorArt {
    /**
     * One painted element as path data: fill and/or stroke colors as 0xRRGGBB (opacity already
     * composited on white), [transform] = accumulated SVG transform to root user units, stroke
     * width already in root units.
     */
    data class Shape(val d: String, val fill: Int?, val stroke: Int?, val strokeWidth: Double = 1.0,
        val lineCap: String? = null, val lineJoin: String? = null,
        val transform: Affine2D = Affine2D.IDENTITY, val fillRule: FillRule = FillRule.NON_ZERO)
    data class Art(val viewBox: List<Double>, val shapes: List<Shape>)

    /** Bounds of the shared [StitchTypeRule]; overridable only for sweeps. */
    data class Rules(val satinMinMm: Double = StitchTypeRule.SATIN_MIN_MM, val satinMaxMm: Double = StitchTypeRule.SATIN_MAX_MM) {
        fun classify(region: Geometry, scale: Double) =
            StitchTypeRule.classify(StitchTypeRule.widths(region, 1 / scale), satinMinMm, satinMaxMm)
    }

    /** What one source shape became: method, Brother thread and its measured width. */
    data class Part(val shape: Int, val role: String, val method: StitchTypeRule.Type, val thread: EmbThread, val widthMm: Double, val sourceRgb: Int,
        /** Measured region widths (mm) the shared rule decided on. */
        val widths: StitchTypeRule.Widths? = null,
        /** Actual emitted region boundaries, centered object-local millimeters (including holes). */
        val paths: List<Polyline> = emptyList(), val regionId: String = "")
    data class RegionInput(val id: String, val paths: List<Polyline>, val thread: EmbThread,
        val previous: io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch?)
    class Result(val pattern: EmbPattern, val parts: List<Part>, val scale: Double, val excludedCount: Int = 0, val regionPatterns: Map<String, EmbPattern> = emptyMap())

    /**
     * Flat SVG 1.1 reader: `<g>` nesting with inherited presentation attributes (and `style`),
     * `transform` (all SVG 1.1 forms via [Affine2D.parse]), path and basic shapes, fill-rule and
     * opacity. Non-rendered `<defs>`/`<clipPath>`/`<title>` content is skipped. Anything that would
     * change the picture and is not drawn here (gradients, filters, masks, clip-path, images, `<use>`,
     * text) is rejected rather than approximated.
     */
    fun parseSvg(svg: String): Art {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(svg.byteInputStream())
        val root = doc.documentElement
        val viewBox = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).map(String::toDouble)
        require(viewBox.size == 4) { "SVG viewBox is required" }
        val shapes = ArrayList<Shape>()
        fun walk(e: Element, parent: Style, outer: Affine2D) {
            when (e.localName) {
                "defs", "clipPath", "title", "desc", "metadata" -> return
                "linearGradient", "radialGradient", "mask", "filter", "image", "use", "text", "pattern", "symbol" ->
                    throw IllegalArgumentException("Unsupported SVG element <${e.localName}>")
            }
            require(!e.hasAttribute("clip-path") && !e.hasAttribute("mask") && !e.hasAttribute("filter")) {
                "Unsupported SVG clip-path/mask/filter on <${e.localName}>"
            }
            val transform = e.getAttribute("transform").takeIf { it.isNotBlank() }?.let { outer * Affine2D.parse(it) } ?: outer
            val style = parent.inherit(e)
            basicShapeData(e)?.let { d ->
                val scale = sqrt(abs(transform.a * transform.d - transform.b * transform.c))
                shapes += Shape(d, style.paint(style.fill, style.fillOpacity), style.paint(style.stroke, style.strokeOpacity),
                    style.strokeWidth * scale, style.lineCap, style.lineJoin, transform, style.fillRule)
            }
            val children = e.childNodes
            for (i in 0 until children.length) (children.item(i) as? Element)?.let { walk(it, style, transform) }
        }
        walk(root, Style(), Affine2D.IDENTITY)
        return Art(viewBox, shapes)
    }

    /** SVG 1.1 presentation attributes that this reader draws; defaults per the SVG 1.1 property table. */
    private data class Style(val fill: String = "#000000", val stroke: String = "none", val strokeWidth: Double = 1.0,
        val fillOpacity: Double = 1.0, val strokeOpacity: Double = 1.0, val opacity: Double = 1.0,
        val fillRule: FillRule = FillRule.NON_ZERO, val lineCap: String? = null, val lineJoin: String? = null) {
        fun inherit(e: Element): Style {
            val declared = e.getAttribute("style").split(';').mapNotNull { part ->
                part.split(':', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.trim() to v.trim() } }.toMap()
            fun v(name: String) = declared[name] ?: e.getAttribute(name).takeIf { it.isNotEmpty() }
            return Style(
                fill = v("fill") ?: fill, stroke = v("stroke") ?: stroke,
                strokeWidth = v("stroke-width")?.removeSuffix("px")?.toDouble() ?: strokeWidth,
                fillOpacity = v("fill-opacity")?.toDouble() ?: fillOpacity,
                strokeOpacity = v("stroke-opacity")?.toDouble() ?: strokeOpacity,
                // Group opacity composites the group; multiplying it down is exact for non-overlapping shapes.
                opacity = opacity * (v("opacity")?.toDouble() ?: 1.0),
                fillRule = when (v("fill-rule")) { "evenodd" -> FillRule.EVEN_ODD; "nonzero" -> FillRule.NON_ZERO; else -> fillRule },
                lineCap = v("stroke-linecap") ?: lineCap, lineJoin = v("stroke-linejoin") ?: lineJoin)
        }

        /** Paint as 0xRRGGBB composited on white paper by its opacity; null for none. */
        fun paint(value: String, paintOpacity: Double): Int? {
            if (value == "none" || value == "transparent") return null
            require(!value.startsWith("url(")) { "Unsupported SVG paint server $value" }
            val rgb = when (value.lowercase()) {
                "black" -> 0x000000; "white" -> 0xFFFFFF
                else -> { require(value.startsWith("#")) { "Unsupported SVG color $value" }; parseColor(value) }
            }
            val o = (paintOpacity * opacity).coerceIn(0.0, 1.0)
            if (o == 0.0) return null
            fun mix(shift: Int) = (((rgb shr shift) and 0xFF) * o + 255 * (1 - o)).roundToInt() shl shift
            return mix(16) or mix(8) or mix(0)
        }
    }

    /** SVG 1.1 basic shapes as their equivalent path data (SVG 1.1 §9); null for non-shape elements. */
    private fun basicShapeData(e: Element): String? {
        fun n(name: String) = e.getAttribute(name).removeSuffix("px").toDoubleOrNull() ?: 0.0
        return when (e.localName) {
            "path" -> e.getAttribute("d")
            "rect" -> {
                val x = n("x"); val y = n("y"); val w = n("width"); val h = n("height")
                if (w <= 0 || h <= 0) return null
                var rx = e.getAttribute("rx").toDoubleOrNull(); var ry = e.getAttribute("ry").toDoubleOrNull()
                if (rx == null) rx = ry; if (ry == null) ry = rx
                val ax = (rx ?: 0.0).coerceIn(0.0, w / 2); val ay = (ry ?: 0.0).coerceIn(0.0, h / 2)
                if (ax == 0.0 || ay == 0.0) "M$x,$y H${x + w} V${y + h} H$x Z"
                else "M${x + ax},$y H${x + w - ax} A$ax,$ay 0 0 1 ${x + w},${y + ay} V${y + h - ay} " +
                    "A$ax,$ay 0 0 1 ${x + w - ax},${y + h} H${x + ax} A$ax,$ay 0 0 1 $x,${y + h - ay} V${y + ay} A$ax,$ay 0 0 1 ${x + ax},$y Z"
            }
            "circle", "ellipse" -> {
                val cx = n("cx"); val cy = n("cy")
                val rx = if (e.localName == "circle") n("r") else n("rx"); val ry = if (e.localName == "circle") n("r") else n("ry")
                if (rx <= 0 || ry <= 0) return null
                "M${cx + rx},$cy A$rx,$ry 0 1 1 ${cx - rx},$cy A$rx,$ry 0 1 1 ${cx + rx},$cy Z"
            }
            "line" -> "M${n("x1")},${n("y1")} L${n("x2")},${n("y2")}"
            "polyline", "polygon" -> {
                val v = e.getAttribute("points").trim().split(Regex("[\\s,]+")).filter(String::isNotEmpty).map(String::toDouble)
                if (v.size < 4) return null
                "M" + v.chunked(2).joinToString(" L") { "${it[0]},${it[1]}" } + if (e.localName == "polygon") " Z" else ""
            }
            else -> null
        }
    }

    /** Android VectorDrawable XML → flat SVG (pathData, fill/stroke colors, width, cap, join). */
    fun vectorDrawableToSvg(xml: String): String {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(xml.byteInputStream())
        val root = doc.documentElement
        fun Element.a(name: String) = getAttributeNS(ns, name).ifEmpty { null }
        fun rgb(argb: String?) = argb?.let { "#" + it.removePrefix("#").takeLast(6) }
        val paths = doc.getElementsByTagName("path")
        val body = (0 until paths.length).joinToString("\n") { i ->
            val p = paths.item(i) as Element
            val attrs = listOfNotNull(
                "d=\"${p.a("pathData")}\"",
                "fill=\"${rgb(p.a("fillColor")) ?: "none"}\"",
                rgb(p.a("strokeColor"))?.let { "stroke=\"$it\"" },
                p.a("strokeWidth")?.let { "stroke-width=\"$it\"" },
                p.a("strokeLineCap")?.let { "stroke-linecap=\"$it\"" },
                p.a("strokeLineJoin")?.let { "stroke-linejoin=\"$it\"" },
            )
            "  <path ${attrs.joinToString(" ")}/>"
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${root.a("viewportWidth")} ${root.a("viewportHeight")}\">\n$body\n</svg>\n"
    }

    /** Nearest Brother/PEC thread by pyembroidery's red-mean distance ([PecThreads.distance]). */
    fun nearestThread(rgb: Int, myThreads: io.github.linker2k.myembro.engine.io.MyThreads = io.github.linker2k.myembro.engine.io.MyThreads()): EmbThread = threadCandidates(rgb, 1, myThreads).first().first

    /** The [count] nearest Brother/PEC threads with their red-mean distance (0 = identical). */
    fun threadCandidates(rgb: Int, count: Int, myThreads: io.github.linker2k.myembro.engine.io.MyThreads = io.github.linker2k.myembro.engine.io.MyThreads()): List<Pair<EmbThread, Int>> =
        myThreads.candidates().map { it to PecThreads.distance(it.color, rgb or (0xFF shl 24)) }
            .sortedBy { it.second }.take(count)

    private val factory = GeometryFactory()

    private fun parseColor(text: String): Int {
        val hex = text.removePrefix("#").let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        return hex.takeLast(6).toInt(16)
    }

    private fun subpaths(shape: Shape): List<Polyline> = SvgPath.flatten(shape.d, shape.transform, tolerance = 0.05)

    private fun fillGeometry(shape: Shape): Geometry =
        PolygonRegion.fromPaths(subpaths(shape).map { Polyline(it.points, true) }, shape.fillRule).geometry

    private fun strokeGeometry(shape: Shape): Geometry {
        val p = BufferParameters().apply {
            endCapStyle = when (shape.lineCap) { "round" -> BufferParameters.CAP_ROUND; "square" -> BufferParameters.CAP_SQUARE; else -> BufferParameters.CAP_FLAT }
            joinStyle = when (shape.lineJoin) { "round" -> BufferParameters.JOIN_ROUND; "bevel" -> BufferParameters.JOIN_BEVEL; else -> BufferParameters.JOIN_MITRE }
        }
        val lines = subpaths(shape).map { line -> factory.createLineString(line.points.map { Coordinate(it.x, it.y) }.toTypedArray()) }
        return UnaryUnionOp.union(lines.map { org.locationtech.jts.operation.buffer.BufferOp.bufferOp(it, shape.strokeWidth / 2, p) })
    }


    /** Footprints in document order: what each shape covers once sewn (fill area ∪ stroke band). */
    private fun footprint(shape: Shape): Geometry = listOfNotNull(
        shape.fill?.let { fillGeometry(shape) }, shape.stroke?.let { strokeGeometry(shape) }).let { UnaryUnionOp.union(it) }

    /** Same painted envelope used by render, for the shared picture-width policy. */
    fun aspectRatio(art: Art): Double {
        val bounds = UnaryUnionOp.union(art.shapes.map(::footprint)).envelopeInternal
        return bounds.height / bounds.width
    }

    /**
     * [widthMm]: width of the whole picture (every footprint) in mm. Shapes are sewn in document
     * order, so later shapes cover earlier ones: a tatami area is clipped by everything drawn after
     * it, and slivers narrower than the satin minimum are dropped instead of sewn.
     */
    /** [removeOverlaps] false only to measure what overlap removal saves (tests); the app always removes. */
    fun render(art: Art, widthMm: Double, rules: Rules = Rules(), name: String = "Art", removeOverlaps: Boolean = true,
        regionOverride: ((RegionInput) -> StitchTypeRule.RegionStitches?)? = null,
        captureRegions: Boolean = false): Result {
        require(widthMm.isFinite() && widthMm > 0)
        val footprints = art.shapes.map(::footprint)
        val bounds = UnaryUnionOp.union(footprints).envelopeInternal
        val scale = widthMm / bounds.width
        val cx = (bounds.minX + bounds.maxX) / 2; val cy = (bounds.minY + bounds.maxY) / 2
        // Ink/Stitch works in SVG px; source units → mm (scale) → px, centered on the picture.
        fun px(x: Double, y: Double) = Point(Units.mmToPixels((x - cx) * scale), Units.mmToPixels((y - cy) * scale))
        val groups = ArrayList<StitchGroup>()
        val parts = ArrayList<Part>()
        val ownedGroups = ArrayList<StitchGroup>()
        fun previous() = groups.lastOrNull()?.stitches?.lastOrNull()
        val toMm = org.locationtech.jts.geom.util.AffineTransformation.translationInstance(-cx, -cy).scale(scale, scale)
        var excludedCount = 0
        var regionOrdinal = 0
        fun boundaryPaths(area: Geometry) = (0 until area.numGeometries).map { area.getGeometryN(it) }.filterIsInstance<Polygon>()
            .flatMap { poly -> (listOf(poly.exteriorRing) + (0 until poly.numInteriorRing).map(poly::getInteriorRingN))
                .map { ring -> Polyline(ring.coordinates.map { MmPoint(it.x, it.y) }, true) } }
        fun tatamiGroups(area: Geometry, thread: EmbThread) = boundaryPaths(area).takeIf { it.isNotEmpty() }?.let { paths ->
            FontTatami.stitchGroups(InkFill(paths, FillRule.EVEN_ODD, thread.color, InkStitchParams(emptyMap())), { q -> px(q.x, q.y) }, emptyMap(), thread, previous())
        }.orEmpty()
        fun outlineGroups(area: Geometry, thread: EmbThread) =
            Stroke(boundaryPaths(area).map { line -> line.points.map { px(it.x, it.y) } }, thread).toStitchGroups(previous())
        /** One region through the shared failure policy (satin → tatami → running → excluded), recorded as a part. */
        fun emit(index: Int, role: String, region: Geometry, thread: EmbThread, w: Double, rgb: Int, preferred: StitchTypeRule.Type,
            satin: () -> List<StitchGroup>, running: () -> List<StitchGroup> = { outlineGroups(region, thread) }) {
            val regionId = "region-${regionOrdinal++}"
            val localPaths = boundaryPaths(toMm.transform(region)).ifEmpty {
                subpaths(art.shapes[index]).map { line -> Polyline(line.points.map { q ->
                    MmPoint((q.x - cx) * scale, (q.y - cy) * scale)
                }, line.closed) }
            }
            val resolved = regionOverride?.invoke(RegionInput(regionId, localPaths, thread, previous()))
                ?: StitchTypeRule.generateRegion(preferred, groups) { attempt -> when (attempt) {
                StitchTypeRule.Type.SATIN -> satin()
                StitchTypeRule.Type.TATAMI -> tatamiGroups(region, thread)
                StitchTypeRule.Type.RUNNING -> running()
            } }
            if (resolved.excluded) { excludedCount++; return }
            if (captureRegions) {
                val tag = "myembro.region:$regionId"
                fun tagged(lock: io.github.linker2k.myembro.engine.inkstitch.stitch_plan.LockStitch?) =
                    io.github.linker2k.myembro.engine.inkstitch.stitch_plan.LockStitch { stitches, position ->
                        (lock ?: io.github.linker2k.myembro.engine.inkstitch.stitch_plan.InkLockStitch()).stitches(stitches, position)
                            .map { it.copy(tags = it.tags + tag) }
                    }
                ownedGroups += resolved.groups.map { it.copy(stitches = it.stitches.map { s -> s.copy(tags = s.tags + tag) },
                    lockStart = tagged(it.lockStart), lockEnd = tagged(it.lockEnd)) }
            }
            groups += resolved.groups
            parts += Part(index, role, resolved.type!!, thread, w, rgb, StitchTypeRule.widths(region, 1 / scale), localPaths, regionId)
        }
        /**
         * An area after overlap removal (everything drawn later is cut away, JTS difference): each visible
         * piece is classified by the shared rule. Satin pieces use [RegionSatin] (letter's centerline satin
         * path); running-width slivers lie under later shapes' edges and are dropped.
         */
        fun area(index: Int, role: String, area: Geometry, thread: EmbThread, w: Double, rgb: Int) {
            val later = footprints.drop(index + 1)
            val visible = if (later.isEmpty() || !removeOverlaps) area else area.difference(UnaryUnionOp.union(later))
            for (piece in (0 until visible.numGeometries).map { visible.getGeometryN(it) }.filterIsInstance<Polygon>()) {
                val type = rules.classify(piece, scale)
                if (type == StitchTypeRule.Type.RUNNING) continue
                emit(index, role, piece, thread, w, rgb, type, satin = {
                    RegionSatin.groups(toMm.transform(piece), { x, y -> Point(Units.mmToPixels(x), Units.mmToPixels(y)) }, thread).orEmpty()
                })
            }
        }
        art.shapes.forEachIndexed { index, shape ->
            shape.fill?.let { rgb ->
                val thread = nearestThread(rgb)
                val region = fillGeometry(shape)
                val w = StitchTypeRule.maxWidth(region) * scale
                if (rules.classify(region, scale) != StitchTypeRule.Type.RUNNING) area(index, "fill", region, thread, w, rgb)
                else emit(index, "fill", region, thread, w, rgb, StitchTypeRule.Type.RUNNING, satin = { emptyList() },
                    running = { Stroke(subpaths(shape).map { it.points.map { q -> px(q.x, q.y) } }, thread).toStitchGroups(previous()) })
            }
            shape.stroke?.let { rgb ->
                val thread = nearestThread(rgb)
                val w = shape.strokeWidth * scale
                val band = strokeGeometry(shape)
                // The stroke's band is the region: bends widen its maximum, so a 6.5mm arch line is tatami.
                when (val type = rules.classify(band, scale)) {
                    StitchTypeRule.Type.TATAMI -> area(index, "stroke", band, thread, w, rgb)
                    else -> emit(index, "stroke", band, thread, w, rgb, type, satin = {
                        val made = ArrayList<StitchGroup>()
                        for (line in subpaths(shape)) {
                            val section = requireNotNull(StrokeToSatin.convertPathToSatin(line.points.map { px(it.x, it.y) },
                                Units.mmToPixels(w), StrokeToSatin.Style.fromSvg(shape.lineJoin)))
                            made += section.column(SatinParameters(), thread).toStitchGroups(made.lastOrNull()?.stitches?.lastOrNull() ?: previous())
                        }
                        made
                    }, running = { Stroke(subpaths(shape).map { it.points.map { q -> px(q.x, q.y) } }, thread).toStitchGroups(previous()) })
                }
            }
        }
        // Same finishing as Ink/Stitch lettering: locks at thread boundaries, no zero-length motions.
        val owners = mutableListOf<String?>()
        val pattern = if (groups.isEmpty()) EmbPattern(emptyList(), emptyList()) else GeneratedPatternProcessor.fromGroups(if (captureRegions) ownedGroups else groups,
            collapse_len = 3.0, min_stitch_len = .1, pecJumpTrims = true, lockAtThreadBoundaries = true, omitZeroLengthMotions = true, stitchOwners = owners)
        return Result(EmbPattern(pattern.stitches, pattern.threads, pattern.metadata + mapOf("name" to name,
            "myembro.excluded_regions" to excludedCount.toString()), pattern.warnings), parts, scale, excludedCount, if (captureRegions) RegionOrderPatterns.split(pattern, owners, parts.associate { it.regionId to it.thread }) else emptyMap())
    }
}
