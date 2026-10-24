// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

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
import io.github.linker2k.myembro.engine.lettering.GlyphStrokeWidths
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Flat vector art (logo, emoji) as embroidery: one shared path for every picture source.
 *
 * Each SVG `<path>` is a color area (fill) or a constant-width stroke. Its width in mm picks the
 * stitch: wide areas Ink/Stitch tatami, strokes within the satin range Ink/Stitch stroke-to-satin,
 * narrower strokes Ink/Stitch running stitch. Every stitch uses the upstream port with default
 * parameters; only the width classification and the occlusion clip below are ours.
 */
object VectorArt {
    /** One `<path>`: fill and/or stroke colors as 0xRRGGBB, stroke width in viewBox units. */
    data class Shape(val d: String, val fill: Int?, val stroke: Int?, val strokeWidth: Double = 1.0,
        val lineCap: String? = null, val lineJoin: String? = null)
    data class Art(val viewBox: List<Double>, val shapes: List<Shape>)

    enum class Method { TATAMI, SATIN, RUNNING }

    /**
     * Width rule in mm. 0.9–6mm was set by the coordinator; Ink/Stitch has no width-based choice,
     * so the bounds are **unverified** until the satin-width sweep (docs/emoji-to-design.md B5).
     */
    data class Rules(
        val satinMinMm: Double = io.github.linker2k.myembro.engine.lettering.TatamiLettering.MIN_SATIN_WIDTH_MM,
        val satinMaxMm: Double = io.github.linker2k.myembro.engine.lettering.TatamiLettering.MAX_SATIN_WIDTH_MM,
    )

    /** What one source shape became: method, Brother thread and its measured width. */
    data class Part(val shape: Int, val role: String, val method: Method, val thread: EmbThread, val widthMm: Double, val sourceRgb: Int)
    class Result(val pattern: EmbPattern, val parts: List<Part>, val scale: Double)

    /** Minimal reader for flat SVG: root viewBox and `<path>` elements. Gradients, masks, transforms are rejected. */
    fun parseSvg(svg: String): Art {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(svg.byteInputStream())
        val root = doc.documentElement
        val viewBox = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).map(String::toDouble)
        require(viewBox.size == 4) { "SVG viewBox is required" }
        val shapes = ArrayList<Shape>()
        fun walk(e: Element) {
            require(e.localName !in setOf("linearGradient", "radialGradient", "mask", "clipPath", "filter", "image")) {
                "Unsupported SVG element <${e.localName}>"
            }
            require(!e.hasAttribute("transform")) { "SVG transforms are not supported yet" }
            if (e.localName == "path") {
                fun color(name: String) = e.getAttribute(name).takeIf { it.startsWith("#") }?.let(::parseColor)
                shapes += Shape(e.getAttribute("d"), color("fill").takeIf { e.getAttribute("fill") != "none" },
                    color("stroke"), e.getAttribute("stroke-width").toDoubleOrNull() ?: 1.0,
                    e.getAttribute("stroke-linecap").ifEmpty { null }, e.getAttribute("stroke-linejoin").ifEmpty { null })
            }
            val children = e.childNodes
            for (i in 0 until children.length) (children.item(i) as? Element)?.let(::walk)
        }
        walk(root)
        return Art(viewBox, shapes)
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
    fun nearestThread(rgb: Int): EmbThread = threadCandidates(rgb, 1).first().first

    /** The [count] nearest Brother/PEC threads with their red-mean distance (0 = identical). */
    fun threadCandidates(rgb: Int, count: Int): List<Pair<EmbThread, Int>> =
        PecThreads.SET.filterNotNull().map { it to PecThreads.distance(it.color, rgb or (0xFF shl 24)) }
            .sortedBy { it.second }.take(count)

    private val factory = GeometryFactory()

    private fun parseColor(text: String): Int {
        val hex = text.removePrefix("#").let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        return hex.takeLast(6).toInt(16)
    }

    private fun subpaths(d: String): List<Polyline> = SvgPath.flatten(d, tolerance = 0.05)

    private fun fillGeometry(shape: Shape): Geometry =
        PolygonRegion.fromPaths(subpaths(shape.d).map { Polyline(it.points, true) }, FillRule.NON_ZERO).geometry

    private fun strokeGeometry(shape: Shape): Geometry {
        val p = BufferParameters().apply {
            endCapStyle = when (shape.lineCap) { "round" -> BufferParameters.CAP_ROUND; "square" -> BufferParameters.CAP_SQUARE; else -> BufferParameters.CAP_FLAT }
            joinStyle = when (shape.lineJoin) { "round" -> BufferParameters.JOIN_ROUND; "bevel" -> BufferParameters.JOIN_BEVEL; else -> BufferParameters.JOIN_MITRE }
        }
        val lines = subpaths(shape.d).map { line -> factory.createLineString(line.points.map { Coordinate(it.x, it.y) }.toTypedArray()) }
        return UnaryUnionOp.union(lines.map { org.locationtech.jts.operation.buffer.BufferOp.bufferOp(it, shape.strokeWidth / 2, p) })
    }

    /** Shared logo/PNG decision. Bounds are coordinator policy, not an Ink/Stitch default. */
    fun classifyWidth(widthMm: Double, rules: Rules = Rules()): Method {
        require(widthMm.isFinite() && widthMm >= 0)
        require(rules.satinMinMm.isFinite() && rules.satinMaxMm.isFinite() &&
            rules.satinMinMm > 0 && rules.satinMaxMm >= rules.satinMinMm)
        return when {
            widthMm < rules.satinMinMm -> Method.RUNNING
            widthMm <= rules.satinMaxMm -> Method.SATIN
            else -> Method.TATAMI
        }
    }

    /** Geometry is already in mm. Reuses model's exact per-outline median, including holes. */
    fun medianWidthMm(shapeMm: Geometry): Double {
        val contours = (0 until shapeMm.numGeometries).flatMap { i ->
            val poly = shapeMm.getGeometryN(i) as Polygon
            (listOf(poly.exteriorRing) + (0 until poly.numInteriorRing).map(poly::getInteriorRingN))
                .map { ring -> ring.coordinates.map { it.x to it.y } }
        }
        val row = GlyphStrokeWidths.measure(GlyphStrokeWidths.FontOutlines("vector-art", 1.0,
            listOf(GlyphStrokeWidths.Glyph(0, 0, contours))), 1.0).rows.single()
        return requireNotNull(row.medianWidthMm) { "No measurable image ink" }
    }

    private fun width(g: Geometry, scale: Double): Double = medianWidthMm(
        org.locationtech.jts.geom.util.AffineTransformation.scaleInstance(scale, scale).transform(g))

    /** Footprints in document order: what each shape covers once sewn (fill area ∪ stroke band). */
    private fun footprint(shape: Shape): Geometry = listOfNotNull(
        shape.fill?.let { fillGeometry(shape) }, shape.stroke?.let { strokeGeometry(shape) }).let { UnaryUnionOp.union(it) }

    /**
     * [widthMm]: width of the whole picture (every footprint) in mm. Shapes are sewn in document
     * order, so later shapes cover earlier ones: a tatami area is clipped by everything drawn after
     * it, and slivers narrower than the satin minimum are dropped instead of sewn.
     */
    fun render(art: Art, widthMm: Double, rules: Rules = Rules(), name: String = "Art"): Result {
        require(widthMm.isFinite() && widthMm > 0)
        val footprints = art.shapes.map(::footprint)
        val bounds = UnaryUnionOp.union(footprints).envelopeInternal
        val scale = widthMm / bounds.width
        val cx = (bounds.minX + bounds.maxX) / 2; val cy = (bounds.minY + bounds.maxY) / 2
        // Ink/Stitch works in SVG px; source units → mm (scale) → px, centered on the picture.
        fun px(x: Double, y: Double) = Point(Units.mmToPixels((x - cx) * scale), Units.mmToPixels((y - cy) * scale))
        val groups = ArrayList<StitchGroup>()
        val parts = ArrayList<Part>()
        fun previous() = groups.lastOrNull()?.stitches?.lastOrNull()
        fun tatami(index: Int, role: String, area: Geometry, thread: EmbThread, widthMm: Double, sourceRgb: Int) {
            val later = footprints.drop(index + 1)
            val visible = if (later.isEmpty()) area else area.difference(UnaryUnionOp.union(later))
            val pieces = (0 until visible.numGeometries).map { visible.getGeometryN(it) }.filterIsInstance<Polygon>()
                .filter { classifyWidth(width(it, scale), rules) != Method.RUNNING }
            if (pieces.isEmpty()) return
            val paths = pieces.flatMap { poly -> (listOf(poly.exteriorRing) + (0 until poly.numInteriorRing).map(poly::getInteriorRingN))
                .map { ring -> Polyline(ring.coordinates.map { MmPoint(it.x, it.y) }, true) } }
            val element = InkFill(paths, FillRule.EVEN_ODD, thread.color, InkStitchParams(emptyMap()))
            groups += FontTatami.stitchGroups(element, { q -> px(q.x, q.y) }, emptyMap(), thread, previous())
            parts += Part(index, role, Method.TATAMI, thread, widthMm, sourceRgb)
        }
        art.shapes.forEachIndexed { index, shape ->
            shape.fill?.let { rgb ->
                val thread = nearestThread(rgb)
                val area = fillGeometry(shape)
                val w = width(area, scale)
                if (classifyWidth(w, rules) != Method.RUNNING) tatami(index, "fill", area, thread, w, rgb)
                else {
                    groups += Stroke(subpaths(shape.d).map { it.points.map { q -> px(q.x, q.y) } }, thread).toStitchGroups(previous())
                    parts += Part(index, "fill", Method.RUNNING, thread, w, rgb)
                }
            }
            shape.stroke?.let { rgb ->
                val thread = nearestThread(rgb)
                val w = shape.strokeWidth * scale
                when (classifyWidth(w, rules)) {
                    Method.TATAMI -> tatami(index, "stroke", strokeGeometry(shape), thread, w, rgb)
                    Method.SATIN -> {
                        for (line in subpaths(shape.d)) {
                            val section = StrokeToSatin.convertPathToSatin(line.points.map { px(it.x, it.y) },
                                Units.mmToPixels(w), StrokeToSatin.Style.fromSvg(shape.lineJoin)) ?: continue
                            groups += section.column(SatinParameters(), thread).toStitchGroups(previous())
                        }
                        parts += Part(index, "stroke", Method.SATIN, thread, w, rgb)
                    }
                    Method.RUNNING -> {
                        groups += Stroke(subpaths(shape.d).map { it.points.map { q -> px(q.x, q.y) } }, thread).toStitchGroups(previous())
                        parts += Part(index, "stroke", Method.RUNNING, thread, w, rgb)
                    }
                }
            }
        }
        // Same finishing as Ink/Stitch lettering: locks at thread boundaries, no zero-length motions.
        val pattern = GeneratedPatternProcessor.fromGroups(groups, collapse_len = 3.0, min_stitch_len = .1, pecJumpTrims = true,
            lockAtThreadBoundaries = true, omitZeroLengthMotions = true)
        return Result(EmbPattern(pattern.stitches, pattern.threads, pattern.metadata + mapOf("name" to name), pattern.warnings), parts, scale)
    }
}
