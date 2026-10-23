// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/svg/units.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.stitches.FillGeometry
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.model.EmbThread
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.precision.GeometryPrecisionReducer
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Narrow, fail-closed reader for the frozen fill oracle fixtures, not the S5 SVG importer. */
internal object FillMatrixInput {
    const val INKSTITCH = "http://inkstitch.org/namespace"
    data class Region(val geometry: Geometry, val thread: EmbThread, val attributes: Map<String,String>) {
        val parameters get() = FillParameters.fromSvgAttributes(attributes.filterKeys { it.startsWith("inkstitch:") })
    }
    data class Document(val regions: List<Region>, val minStitchMm: Double, val collapseMm: Double)

    fun read(bytes: ByteArray): Document {
        require(bytes.size <= 2_000_000) { "Fixture SVG exceeds size limit" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            setFeature("http://xml.org/sax/features/external-general-entities",false)
            setFeature("http://xml.org/sax/features/external-parameter-entities",false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        require(root.localName == "svg") { "Not an SVG fixture" }
        val view = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).map(String::toDouble)
        require(view.size == 4 && view.all(Double::isFinite) && view[2]>0 && view[3]>0)
        var sx = length(root.getAttribute("width"))/view[2]
        var sy = length(root.getAttribute("height"))/view[3]
        val aspect = root.getAttribute("preserveAspectRatio").ifBlank { "xMidYMid meet" }
        if (aspect != "none") { val scale = if ("slice" in aspect) maxOf(sx,sy) else minOf(sx,sy); sx=scale; sy=scale }
        // Match upstream's translate @ scale and its %f precision. Do not silently substitute
        // the standard SVG scale @ translate: absolute row/stagger phase would change.
        fun six(value: Double) = String.format(Locale.ROOT,"%.6f",value).toDouble()
        val viewport = Affine2D.translation(six(-view[0]),six(-view[1])) * Affine2D.scale(six(sx),six(sy))
        val regions = mutableListOf<Region>()
        var minimum = 0.1
        var collapse = 3.0
        fun walk(element: Element, matrix: Affine2D, inherited: Map<String,String>) {
            require(!element.hasAttribute("style")) { "CSS style fixtures require the S5 importer" }
            val own = inherited.toMutableMap()
            for (i in 0 until element.attributes.length) {
                val attribute = element.attributes.item(i)
                if (attribute.namespaceURI == INKSTITCH) own["inkstitch:"+attribute.localName] = attribute.nodeValue
            }
            listOf("fill","fill-rule").forEach { if (element.hasAttribute(it)) own[it]=element.getAttribute(it) }
            val transform = matrix * Affine2D.parse(element.getAttribute("transform"))
            when (element.localName) {
                "svg", "g" -> for (i in 0 until element.childNodes.length) {
                    (element.childNodes.item(i) as? Element)?.let { walk(it,transform,own) }
                }
                "metadata" -> for (i in 0 until element.childNodes.length) {
                    val child = element.childNodes.item(i) as? Element ?: continue
                    if (child.namespaceURI != INKSTITCH) continue
                    when (child.localName) {
                        "min_stitch_len_mm" -> minimum=child.textContent.toDouble()
                        "collapse_len_mm" -> collapse=child.textContent.toDouble()
                        "inkstitch_svg_version" -> require(child.textContent.trim()=="4")
                        else -> error("Unknown fixture metadata ${child.localName}")
                    }
                }
                "path", "rect" -> {
                    require(own["fill"] != "none") { "Unfilled fixture is outside fill matrix scope" }
                    val path = if (element.localName == "path") element.getAttribute("d") else {
                        require(!element.hasAttribute("rx") && !element.hasAttribute("ry")) { "Rounded rect fixture unsupported" }
                        fun number(name:String, default:Double=0.0) = element.getAttribute(name).ifBlank { "$default" }.toDouble()
                        val x=number("x"); val y=number("y"); val w=number("width"); val h=number("height")
                        require(w>0 && h>0)
                        "M$x $y H${x+w} V${y+h} H$x Z"
                    }
                    // Upstream FillStitch.original_shape uses the largest flattened path as
                    // shell and the remaining paths as holes, before make_valid and filtering.
                    val rings=SvgPath.flatten(path,transform,0.1).map { polyline ->
                        var points=polyline.points
                        if(points.size<3) {
                            val first=points.first()
                            points=listOf(first,first.copy(x=first.x+1),first.copy(y=first.y+1))
                        }
                        if(points.first()!=points.last()) points=points+points.first()
                        FillGeometry.factory.createLinearRing(points.map { Coordinate(it.x,it.y) }.toTypedArray())
                    }.sortedByDescending { FillGeometry.factory.createPolygon(it).area }
                    require(rings.isNotEmpty())
                    val raw=FillGeometry.factory.createPolygon(rings.first(),rings.drop(1).toTypedArray())
                    val precision=PrecisionModel(if(raw.isValid) 1e10 else 1e11)
                    val fixed=FillGeometry.makeValid(raw)
                    val rounded=GeometryPrecisionReducer.reduce(fixed,precision)
                    val geometry=FillGeometry.factory.createMultiPolygon(FillGeometry.polygons(rounded).filter { it.area>3.0 }.toTypedArray())
                    require(!geometry.isEmpty) { "Empty fill fixture" }
                    val color = own["fill"] ?: "#000000"
                    require(color.matches(Regex("#[0-9a-fA-F]{6}"))) { "Unresolved fixture fill color $color" }
                    regions += Region(geometry,EmbThread(0xff000000.toInt() or color.drop(1).toInt(16),color),own)
                }
                else -> error("Unsupported fixture element ${element.localName}")
            }
        }
        walk(root,viewport,emptyMap())
        require(regions.isNotEmpty() && minimum.isFinite() && minimum>=0 && collapse.isFinite() && collapse>=0)
        return Document(regions,minimum,collapse)
    }

    private fun length(text:String):Double {
        val match = Regex("([+\\-0-9.eE]+)(mm|cm|in|px)?").matchEntire(text.trim()) ?: error("Unknown viewport length $text")
        val n=match.groupValues[1].toDouble()
        require(n.isFinite() && n>0)
        return n*when(match.groupValues[2]) { "mm"->PIXELS_PER_MM; "cm"->10*PIXELS_PER_MM; "in"->96.0; else->1.0 }
    }
}
