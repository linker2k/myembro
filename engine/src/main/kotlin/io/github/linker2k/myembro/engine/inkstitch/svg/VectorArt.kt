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

    /** Bounds of the shared [StitchTypeRule]; overridable only for sweeps. */
    data class Rules(val satinMinMm: Double = StitchTypeRule.SATIN_MIN_MM, val satinMaxMm: Double = StitchTypeRule.SATIN_MAX_MM) {
        fun classify(region: Geometry, scale: Double) =
            StitchTypeRule.classify(StitchTypeRule.widths(region, 1 / scale), satinMinMm, satinMaxMm)
    }

    /** What one source shape became: method, Brother thread and its measured width. */
    data class Part(val shape: Int, val role: String, val method: StitchTypeRule.Type, val thread: EmbThread, val widthMm: Double, val sourceRgb: Int,
        /** Measured region widths (mm) the shared rule decided on. */
        val widths: StitchTypeRule.Widths? = null)
    class Result(val pattern: EmbPattern, val parts: List<Part>, val scale: Double, val excludedCount:Int=0)

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
        var excludedCount=0
        fun visible(index:Int,area:Geometry):Geometry {
            val later=footprints.drop(index+1)
            return if(later.isEmpty()) area else area.difference(UnaryUnionOp.union(later))
        }
        fun boundaryPaths(area:Geometry)= (0 until area.numGeometries).map { area.getGeometryN(it) }.filterIsInstance<Polygon>()
            .flatMap { poly -> (listOf(poly.exteriorRing)+(0 until poly.numInteriorRing).map(poly::getInteriorRingN))
                .map { ring -> Polyline(ring.coordinates.map { MmPoint(it.x,it.y) },true) } }
        fun emit(index:Int,role:String,area:Geometry,thread:EmbThread,w:Double,rgb:Int,preferred:StitchTypeRule.Type,
            satin:()->List<StitchGroup> = { emptyList() },running:()->List<StitchGroup> = {
                Stroke(boundaryPaths(area).map { line -> line.points.map { px(it.x,it.y) } },thread).toStitchGroups(previous())
            }) {
            val exposed=visible(index,area)
            val pieces=(0 until exposed.numGeometries).map { exposed.getGeometryN(it) }.filterIsInstance<Polygon>()
                .filter { rules.classify(it,scale)!=StitchTypeRule.Type.RUNNING }
            // A completely occluded source is not a failed region.
            if(preferred==StitchTypeRule.Type.TATAMI && pieces.isEmpty()) return
            val resolved=StitchTypeRule.generateRegion(preferred,groups) { attempt -> when(attempt) {
                StitchTypeRule.Type.SATIN -> satin()
                StitchTypeRule.Type.TATAMI -> {
                    val paths=pieces.flatMap { boundaryPaths(it) }
                    if(paths.isEmpty()) emptyList() else FontTatami.stitchGroups(
                        InkFill(paths,FillRule.EVEN_ODD,thread.color,InkStitchParams(emptyMap())),
                        { q -> px(q.x,q.y) },emptyMap(),thread,previous())
                }
                StitchTypeRule.Type.RUNNING -> if(preferred==StitchTypeRule.Type.RUNNING) running() else
                    Stroke(boundaryPaths(exposed).map { line -> line.points.map { px(it.x,it.y) } },thread).toStitchGroups(previous())
            } }
            if(resolved.excluded) { excludedCount++;return }
            groups+=resolved.groups
            parts+=Part(index,role,resolved.type!!,thread,w,rgb,StitchTypeRule.widths(area,1/scale))
        }
        art.shapes.forEachIndexed { index,shape ->
            shape.fill?.let { rgb ->
                val area=fillGeometry(shape);val thread=nearestThread(rgb)
                val preferred=if(rules.classify(area,scale)==StitchTypeRule.Type.RUNNING) StitchTypeRule.Type.RUNNING else StitchTypeRule.Type.TATAMI
                emit(index,"fill",area,thread,StitchTypeRule.maxWidth(area)*scale,rgb,preferred)
            }
            shape.stroke?.let { rgb ->
                val thread=nearestThread(rgb);val w=shape.strokeWidth*scale;val area=strokeGeometry(shape)
                val lines=subpaths(shape.d)
                emit(index,"stroke",area,thread,w,rgb,rules.classify(area,scale),satin={
                    val candidate=mutableListOf<StitchGroup>()
                    for(line in lines) {
                        val section=requireNotNull(StrokeToSatin.convertPathToSatin(line.points.map { px(it.x,it.y) },
                            Units.mmToPixels(w),StrokeToSatin.Style.fromSvg(shape.lineJoin)))
                        candidate+=section.column(SatinParameters(),thread).toStitchGroups(candidate.lastOrNull()?.stitches?.lastOrNull() ?: previous())
                    }
                    candidate
                },running={ Stroke(lines.map { line -> line.points.map { px(it.x,it.y) } },thread).toStitchGroups(previous()) })
            }
        }
        // Same finishing as Ink/Stitch lettering: locks at thread boundaries, no zero-length motions.
        val pattern = if(groups.isEmpty()) EmbPattern(emptyList(),emptyList()) else GeneratedPatternProcessor.fromGroups(groups, collapse_len = 3.0, min_stitch_len = .1, pecJumpTrims = true,
            lockAtThreadBoundaries = true, omitZeroLengthMotions = true)
        return Result(EmbPattern(pattern.stitches, pattern.threads, pattern.metadata + mapOf("name" to name,"myembro.excluded_regions" to excludedCount.toString()), pattern.warnings), parts, scale, excludedCount)
    }
}
