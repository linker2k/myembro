// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/lettering/font_variant.py@16e192849e619399ce8fdefb4c4d249dacdb4db1 (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/lettering/glyph.py@16e192849e619399ce8fdefb4c4d249dacdb4db1 (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/svg/guides.py@16e192849e619399ce8fdefb4c4d249dacdb4db1 (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/svg/path.py@16e192849e619399ce8fdefb4c4d249dacdb4db1 (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/utils/nodes.py@16e192849e619399ce8fdefb4c4d249dacdb4db1 (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/satin_column/satin_column.py@16e192849e619399ce8fdefb4c4d249dacdb4db1 (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/satin_column/rails.py@16e192849e619399ce8fdefb4c4d249dacdb4db1 (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.lettering.*

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.geometry.SvgPath as GeometrySvgPath
import java.io.InputStream
import java.text.Normalizer
import javax.xml.parsers.SAXParserFactory
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `font.json` of an Ink/Stitch embroidery font (inkstitch/embroidery-fonts). Lengths are SVG
 * user units, which Ink/Stitch treats as CSS px (96 per inch) whatever the file's viewBox says.
 */
data class InkStitchFontInfo(
    val name: String,
    val license: String?,
    /** Nominal size in mm at scale 1 (about the capital height). */
    val sizeMm: Double?,
    /** Scale range the digitizer tested; outside it the satin widths and densities no longer hold. */
    val minScale: Double,
    val maxScale: Double,
    val letterCase: String,
    val defaultGlyph: String?,
    val leading: Double,
    val wordSpacing: Double,
    val defaultAdvance: Double?,
    val advances: Map<String, Double>,
    val kerning: Map<String, Double>,
) {
    companion object {
        fun parse(json: String): InkStitchFontInfo {
            val o = Json.parseToJsonElement(json.removePrefix("﻿")).jsonObject
            fun number(key: String) = (o[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.trim().toDoubleOrNull() }
            fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun numbers(key: String) = (o[key] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }?.let { k to it }
            }?.toMap().orEmpty()
            return InkStitchFontInfo(
                name = text("name") ?: "Ink/Stitch font",
                license = text("font_license"),
                sizeMm = number("size")?.takeIf { it > 0 },
                minScale = number("min_scale")?.takeIf { it > 0 } ?: 1.0,
                maxScale = number("max_scale")?.takeIf { it > 0 } ?: 1.0,
                letterCase = text("letter_case").orEmpty(),
                defaultGlyph = text("default_glyph") ?: "�",
                leading = number("leading") ?: 100.0,
                wordSpacing = number("horiz_adv_x_space") ?: 20.0,
                defaultAdvance = number("horiz_adv_x_default"),
                advances = numbers("horiz_adv_x"),
                kerning = numbers("kerning_pairs"),
            )
        }
    }
}

/** Embroidery parameters of one SVG element: the `inkstitch:*` attributes, unparsed. */
class InkStitchParams(private val values: Map<String, String>) {
    operator fun get(name: String): String? = values[name]?.trim()?.takeIf { it.isNotEmpty() }
    fun bool(name: String, default: Boolean): Boolean = when (get(name)?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> default
    }
    /** First value of a number that Ink/Stitch also allows as "a b" (one per side). */
    fun number(name: String): Double? = values(name).firstOrNull()
    fun values(name: String): List<Double> = get(name)?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }
        ?.filter { it.isFinite() }.orEmpty()
    fun mean(name: String): Double? = values(name).takeIf { it.isNotEmpty() }?.average()
    val names: Set<String> get() = values.keys
}

/** One embroidered element of a glyph, in SVG user units, +y down. */
sealed interface InkStitchElement {
    val color: Int
    val params: InkStitchParams
}

/** Satin column: rail pairs already cut at the rungs and running the same way. */
data class InkSatin(
    val sections: List<Pair<List<Point>, List<Point>>>,
    override val color: Int,
    override val params: InkStitchParams,
) : InkStitchElement

/** Fill region given by its subpaths and SVG fill rule. */
data class InkFill(
    val paths: List<Polyline>,
    val rule: FillRule,
    override val color: Int,
    override val params: InkStitchParams,
) : InkStitchElement

/** Running stitch along every subpath. */
data class InkRun(
    val paths: List<Polyline>,
    override val color: Int,
    override val params: InkStitchParams,
) : InkStitchElement

/**
 * One glyph: [elements] in sewing (document) order. [minX]/[maxX] are the extents of every path
 * in the glyph layer, which is what Ink/Stitch spaces glyphs by.
 */
data class InkStitchGlyph(
    val name: String,
    val elements: List<InkStitchElement>,
    val minX: Double,
    val maxX: Double,
    val inkTop: Double,
    val inkBottom: Double,
)

/**
 * Ink/Stitch embroidery font: the `GlyphLayer-…` groups of its `ltr.svg` plus `font.json`. Only
 * geometry and `inkstitch:*` parameters are read; nothing is executed. Elements this engine cannot
 * sew yet are counted in [unsupported] (by kind) and left out of the glyphs.
 */
class InkStitchFont(
    val info: InkStitchFontInfo,
    val glyphs: Map<String, InkStitchGlyph>,
    /** Baseline in user units (+y down). */
    val baseline: Double,
    val unsupported: Map<String, Int>,
    /** The file's "baseline" guide in user units, null when absent (diagnostics). */
    val baselineGuide: Double? = null,
) {
    /** Glyph whose ink height defines "letter height" (as for other fonts: 'H', else the tallest). */
    val referenceGlyph: InkStitchGlyph? = REFERENCE.firstNotNullOfOrNull { c -> glyphs[c.toString()]?.takeIf { it.elements.isNotEmpty() } }
        ?: glyphs.values.filter { it.elements.isNotEmpty() }.maxByOrNull { it.inkBottom - it.inkTop }

    /** Ink height of [referenceGlyph] at scale 1, in mm. */
    val referenceHeightMm: Double? = referenceGlyph?.let { (it.inkBottom - it.inkTop) * MM_PER_PX }?.takeIf { it > 0 }

    /** The digitizer's tested letter-height range, on the [referenceGlyph] basis. */
    val minHeightMm: Double? get() = referenceHeightMm?.times(info.minScale)
    val maxHeightMm: Double? get() = referenceHeightMm?.times(info.maxScale)

    val supportedCharacters: Set<String> get() = glyphs.keys

    /** Longest glyph name [text] starts with at [index] (ligatures first), as Ink/Stitch matches. */
    internal fun glyphAt(text: String, index: Int): Pair<InkStitchGlyph?, Int> {
        var best: InkStitchGlyph? = null
        var length = 1
        for ((name, glyph) in glyphs) {
            if (name.length >= length && (best == null || name.length > length) && text.startsWith(name, index)) {
                best = glyph; length = name.length
            }
        }
        return best to length
    }

    companion object {
        const val MM_PER_PX = 25.4 / 96
        private const val REFERENCE = "H"
        /** Ink/Stitch default "minimum satin stroke width": thinner single-path satins sew as running. */
        private const val MIN_SATIN_STROKE_PX = 1.0 / MM_PER_PX
        private const val TOLERANCE_PX = 0.1
        private const val NS_INKSTITCH = "http://inkstitch.org/namespace"
        private const val NS_INKSCAPE = "http://www.inkscape.org/namespaces/inkscape"
        private const val NS_SODIPODI = "http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd"
        private const val NS_XLINK = "http://www.w3.org/1999/xlink"
        private val SKIPPED = setOf("defs", "clipPath", "mask", "symbol", "metadata", "marker", "pattern", "style", "title", "desc", "text")
        private val SHAPES = setOf("rect", "circle", "ellipse", "line", "polyline", "polygon", "image")

        fun parse(svg: InputStream, fontJson: String): InkStitchFont = parse(svg, InkStitchFontInfo.parse(fontJson))

        fun parse(svg: InputStream, info: InkStitchFontInfo): InkStitchFont {
            val handler = Handler()
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
            // No DTDs or external entities: the file is downloaded data.
            for (feature in listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false)) {
                runCatching { factory.setFeature(feature.first, feature.second) }
            }
            factory.newSAXParser().parse(svg, handler)
            return handler.font(info)
        }

        private class Frame(
            val transform: Affine2D,
            val style: Map<String, String>,
            val hidden: Boolean,
            val skip: Boolean,
            val glyph: GlyphBuilder?,
        )

        private class GlyphBuilder(val name: String) {
            val elements = ArrayList<InkStitchElement>()
            var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
            var inkTop = Double.POSITIVE_INFINITY; var inkBottom = Double.NEGATIVE_INFINITY
            fun extend(paths: List<Polyline>, ink: Boolean) = paths.forEach { p -> p.points.forEach { q ->
                minX = min(minX, q.x); maxX = max(maxX, q.x)
                if (ink) { inkTop = min(inkTop, q.y); inkBottom = max(inkBottom, q.y) }
            } }
        }

        private class Handler : org.xml.sax.helpers.DefaultHandler() {
            val stack = ArrayDeque<Frame>()
            val glyphs = LinkedHashMap<String, InkStitchGlyph>()
            val unsupported = sortedMapOf<String, Int>()
            var pageHeight: Double? = null
            var baselineGuide: Double? = null
            var root = Affine2D.IDENTITY
            var inNamedView = 0

            fun note(kind: String) { unsupported[kind] = (unsupported[kind] ?: 0) + 1 }

            override fun startElement(uri: String, local: String, qName: String, attrs: org.xml.sax.Attributes) {
                val parent = stack.lastOrNull()
                if (uri == NS_SODIPODI && local == "namedview") inNamedView++
                if (inNamedView > 0 && uri == NS_SODIPODI && local == "guide") guide(attrs)
                if (parent == null) {
                    // Ink/Stitch works in px: the viewBox mapping to the page size is part of every glyph's transform.
                    val box = attrs.getValue("viewBox")?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }
                    val width = attrs.getValue("width")?.let(::lengthPx)
                    val height = attrs.getValue("height")?.let(::lengthPx)
                    pageHeight = box?.get(3) ?: height
                    if (box != null && box[2] > 0 && box[3] > 0) {
                        val sx = (width ?: box[2]) / box[2]
                        val sy = (height ?: box[3]) / box[3]
                        val k = if (attrs.getValue("preserveAspectRatio")?.trim() == "none") null else min(sx, sy)
                        root = Affine2D.scale(k ?: sx, k ?: sy) * Affine2D.translation(-box[0], -box[1])
                    }
                }
                if (parent?.skip == true || local in SKIPPED || uri == NS_SODIPODI) {
                    stack.addLast(Frame(Affine2D.IDENTITY, emptyMap(), true, true, null)); return
                }
                val transform = (parent?.transform ?: root) *
                    (attrs.getValue("transform")?.let { runCatching { Affine2D.parse(it) }.getOrNull() } ?: Affine2D.IDENTITY)
                val own = style(attrs)
                val style = (parent?.style.orEmpty()) + own.filterKeys { it in INHERITED }
                val hidden = (parent?.hidden ?: false) || own["display"] == "none"
                val label = attrs.getValue(NS_INKSCAPE, "label")
                var glyph = parent?.glyph
                if (local == "g" && label != null && label.startsWith("GlyphLayer-") && glyph == null) {
                    glyph = GlyphBuilder(Normalizer.normalize(label.removePrefix("GlyphLayer-"), Normalizer.Form.NFC))
                }
                // Glyph layers are often hidden in the font file; only hidden content inside them is skipped.
                val hiddenInGlyph = if (glyph != null && glyph !== parent?.glyph) false else hidden
                stack.addLast(Frame(transform, style, hiddenInGlyph, false, glyph))
                if (glyph == null) return
                when {
                    local == "path" -> path(attrs, transform, style, hiddenInGlyph, glyph)
                    local == "use" -> {
                        val href = attrs.getValue(NS_XLINK, "href") ?: attrs.getValue("href") ?: ""
                        if (!hiddenInGlyph && !href.startsWith("#inkstitch_")) note("clone")
                    }
                    local in SHAPES && !hiddenInGlyph -> note("shape:$local")
                }
            }

            override fun endElement(uri: String, local: String, qName: String) {
                val frame = stack.removeLast()
                if (uri == NS_SODIPODI && local == "namedview") inNamedView--
                val glyph = frame.glyph ?: return
                if (stack.lastOrNull()?.glyph !== glyph && glyph.maxX >= glyph.minX && glyph.name !in glyphs) {
                    glyphs[glyph.name] = InkStitchGlyph(glyph.name, glyph.elements.toList(), glyph.minX, glyph.maxX,
                        glyph.inkTop.takeIf { it.isFinite() } ?: 0.0, glyph.inkBottom.takeIf { it.isFinite() } ?: 0.0)
                }
            }

            private fun guide(attrs: org.xml.sax.Attributes) {
                if (baselineGuide != null || attrs.getValue(NS_INKSCAPE, "label") != "baseline") return
                // Inkscape stores guides y-up from the page bottom.
                val y = attrs.getValue("position")?.split(',')?.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return
                baselineGuide = root.apply(Point(0.0, (pageHeight ?: return) - y)).y
            }

            private fun path(attrs: org.xml.sax.Attributes, transform: Affine2D, style: Map<String, String>, hidden: Boolean, glyph: GlyphBuilder) {
                val d = attrs.getValue("d") ?: return
                val connector = attrs.getValue(NS_INKSCAPE, "connector-type") != null ||
                    attrs.getValue(NS_INKSCAPE, "connection-start") != null || attrs.getValue(NS_INKSCAPE, "connection-end") != null
                val paths = runCatching { GeometrySvgPath.flatten(d, transform, TOLERANCE_PX) }.getOrNull() ?: run { note("bad-path"); return }
                if (connector) return
                val params = InkStitchParams((0 until attrs.length).filter { attrs.getURI(it) == NS_INKSTITCH }
                    .associate { attrs.getLocalName(it) to attrs.getValue(it) })
                if (hidden || params.bool("ignore_object", false)) { glyph.extend(paths, ink = false); return }
                val fill = color(style["fill"] ?: "black").takeIf { style["fill-opacity"]?.toDoubleOrNull() != 0.0 }
                val stroke = color(style["stroke"] ?: "none")
                val made = ArrayList<InkStitchElement>()
                if (fill != null) {
                    val method = params["fill_method"] ?: "tatami_fill"
                    if (method in FILL_METHODS) {
                        made += InkFill(paths, if (style["fill-rule"] == "evenodd") FillRule.EVEN_ODD else FillRule.NON_ZERO, fill, params)
                    } else note("fill:$method")
                }
                if (stroke != null) {
                    val width = (style["stroke-width"]?.let(::lengthPx) ?: 1.0) * sqrt(abs(transform.a * transform.d - transform.b * transform.c))
                    val nodes = runCatching { GeometrySvgPath.flatten(d, transform, NODES_ONLY) }.getOrNull().orEmpty()
                    val satin = params.bool("satin_column", false) && (paths.count { it.points.size > 1 } > 1 || width > MIN_SATIN_STROKE_PX)
                    when {
                        satin && (params["satin_method"] ?: "satin_column") !in SATIN_DEFAULT -> note("satin:${params["satin_method"]}")
                        satin && params.bool("e_stitch", false) -> note("satin:e_stitch")
                        satin -> InkSatinRails.sections(paths, nodes, width, params)?.let { made += InkSatin(it, stroke, params) }
                            ?: note("satin:unreadable")
                        (params["stroke_method"] ?: "running_stitch") !in RUNNING_DEFAULT -> note("stroke:${params["stroke_method"]}")
                        params.bool("manual_stitch", false) -> note("stroke:manual_stitch")
                        else -> made += InkRun(paths.filter { it.points.size > 1 }, stroke, params)
                    }
                }
                if (params.bool("stroke_first", false)) made.reverse()
                glyph.extend(paths, ink = made.isNotEmpty())
                glyph.elements += made
            }

            fun font(info: InkStitchFontInfo): InkStitchFont {
                // Ink/Stitch aligns by the guide. All glyphs share it, so only a missing guide needs a stand-in.
                val baseline = baselineGuide ?: InkStitchFont(info, glyphs, 0.0, unsupported).referenceGlyph?.inkBottom ?: 0.0
                return InkStitchFont(info, glyphs, baseline, unsupported, baselineGuide)
            }
        }

        // Old files store a combo's index instead of its name; 0 is the default method.
        private val FILL_METHODS = setOf("tatami_fill", "auto_fill", "legacy_fill", "0")
        private val SATIN_DEFAULT = setOf("satin_column", "0")
        private val RUNNING_DEFAULT = setOf("running_stitch", "0")
        private val INHERITED = setOf("fill", "stroke", "stroke-width", "fill-rule", "fill-opacity")
        private val PRESENTATION = INHERITED + "display"
        /** Flatten tolerance so large that curves collapse to their end nodes: the path's node list. */
        private const val NODES_ONLY = 1e12

        private fun style(attrs: org.xml.sax.Attributes): Map<String, String> {
            val out = HashMap<String, String>()
            for (name in PRESENTATION) attrs.getValue(name)?.let { out[name] = it.trim() }
            attrs.getValue("style")?.split(';')?.forEach { part ->
                val i = part.indexOf(':')
                if (i > 0) part.substring(0, i).trim().let { k -> if (k in PRESENTATION) out[k] = part.substring(i + 1).trim() }
            }
            return out
        }

        internal fun lengthPx(text: String): Double? {
            val m = Regex("^\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*([a-z%]*)\\s*$").find(text) ?: return null
            val v = m.groupValues[1].toDouble()
            return when (m.groupValues[2]) {
                "", "px" -> v
                "mm" -> v / MM_PER_PX
                "cm" -> v * 10 / MM_PER_PX
                "in" -> v * 96
                "pt" -> v * 96 / 72
                "pc" -> v * 16
                else -> null
            }
        }

        /** 0xAARRGGBB, or null for "none". Unknown paints sew as black. */
        internal fun color(paint: String): Int? {
            val p = paint.trim().lowercase()
            if (p == "none" || p.startsWith("url(")) return null
            val hex = p.removePrefix("#")
            if (p.startsWith("#") && (hex.length == 3 || hex.length == 6) && hex.all { it in "0123456789abcdef" }) {
                val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
                return (0xFF000000 or full.toLong(16)).toInt()
            }
            return NAMED[p] ?: 0xFF000000.toInt()
        }

        private val NAMED = mapOf(
            "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(),
            "green" to 0xFF008000.toInt(), "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
            "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(),
        )
    }
}

/**
 * Satin rails and rungs of one path, after Ink/Stitch's documented model: two subpaths are the
 * rails; with more, the rungs are the subpaths crossing the rails and the rails are the rest (the
 * two longest when ambiguous). Without rungs, rail nodes pair up in order as implicit rungs. A
 * single wide stroke becomes a column of its stroke width.
 */
internal object InkSatinRails {
    fun sections(paths: List<Polyline>, nodes: List<Polyline>, strokeWidth: Double, params: InkStitchParams): List<Pair<List<Point>, List<Point>>>? {
        val usable = paths.indices.filter { paths[it].points.size > 1 }
        if (usable.isEmpty()) return null
        if (usable.size == 1) {
            val stroke = paths[usable[0]].points.map { it.x to it.y }
            val (l, r) = SatinColumn.rails(stroke, strokeWidth)
            if (l.size < 2) return null
            return listOf(l.map { Point(it.first, it.second) } to r.map { Point(it.first, it.second) })
        }
        val lines = usable.map { paths[it].points }
        val railIndices = railIndices(lines)
        var rail0 = lines[railIndices[0]]
        var rail1 = lines[railIndices[1]]
        if (params["swap_satin_rails"]?.lowercase() == "true") { val t = rail0; rail0 = rail1; rail1 = t }
        val (reverse0, reverse1) = reverse(params["reverse_rails"] ?: "automatic", rail0, rail1)
        if (reverse0) rail0 = rail0.asReversed()
        if (reverse1) rail1 = rail1.asReversed()
        val cuts0 = ArrayList<Double>()
        val cuts1 = ArrayList<Double>()
        if (lines.size == 2) {
            // Implicit rungs at the nodes; the ends are not cut.
            val n0 = nodes.getOrNull(usable[railIndices[0]])?.points.orEmpty().let { if (reverse0) it.asReversed() else it }
            val n1 = nodes.getOrNull(usable[railIndices[1]])?.points.orEmpty().let { if (reverse1) it.asReversed() else it }
            if (n0.size == n1.size && n0.size > 2) {
                n0.drop(1).dropLast(1).forEach { cuts0 += project(rail0, it) }
                n1.drop(1).dropLast(1).forEach { cuts1 += project(rail1, it) }
            }
        } else {
            for (i in lines.indices) {
                if (i in railIndices) continue
                val rung = lines[i]
                if (crossings(rung, rail0).size + crossings(rung, rail1).size > 2) continue
                cuts0 += nearest(rung, rail0)
                cuts1 += nearest(rung, rail1)
            }
        }
        val pieces0 = cut(rail0, cuts0.sorted())
        val pieces1 = cut(rail1, cuts1.sorted())
        return pieces0.zip(pieces1).filter { (a, b) -> a.size > 1 || b.size > 1 }.ifEmpty { null }
    }

    private fun railIndices(lines: List<List<Point>>): List<Int> {
        if (lines.size <= 2) return lines.indices.toList()
        val counts = lines.indices.map { i -> lines.indices.count { j -> j != i && crossings(lines[i], lines[j]).isNotEmpty() } }
        val candidates = lines.indices.filter { i ->
            (if (lines.size == 3) counts[i] == 1 else counts[i] > 2) && length(lines[i]) > 0.1
        }
        return if (candidates.size == 2) candidates else lines.indices.sortedByDescending { length(lines[it]) }.take(2).sorted()
    }

    /** Which rails to reverse; "automatic" reverses the second when that pairs the rails up better. */
    private fun reverse(choice: String, a: List<Point>, b: List<Point>): Pair<Boolean, Boolean> = when (choice) {
        "first" -> true to false
        "second" -> false to true
        "both" -> true to true
        "none" -> false to false
        else -> {
            var same = 0.0; var flipped = 0.0
            for (i in 0 until 10) {
                val t = i / 10.0
                same += at(a, t).distance(at(b, t))
                flipped += at(a, t).distance(at(b, 1 - t))
            }
            false to (same > flipped)
        }
    }

    private fun length(p: List<Point>) = p.zipWithNext().sumOf { (a, b) -> a.distance(b) }

    private fun at(p: List<Point>, t: Double): Point {
        val total = length(p)
        var left = t * total
        for ((a, b) in p.zipWithNext()) {
            val d = a.distance(b)
            if (left <= d && d > 0) return a.interpolate(b, left / d)
            left -= d
        }
        return p.last()
    }

    /** Arc length along [line] of the point nearest [q]. */
    private fun project(line: List<Point>, q: Point): Double = closest(line, q).first

    /** Arc length along [line] of the point nearest [q], and the distance to it. */
    private fun closest(line: List<Point>, q: Point): Pair<Double, Double> {
        var best = Double.POSITIVE_INFINITY; var bestAt = 0.0; var run = 0.0
        for ((a, b) in line.zipWithNext()) {
            val dx = b.x - a.x; val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (((q.x - a.x) * dx + (q.y - a.y) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(a.x + dx * t - q.x, a.y + dy * t - q.y)
            if (d < best) { best = d; bestAt = run + t * sqrt(len2) }
            run += sqrt(len2)
        }
        return bestAt to best
    }

    /** Arc lengths along [rail] where [rung] crosses it. */
    private fun crossings(rung: List<Point>, rail: List<Point>): List<Double> {
        val out = ArrayList<Double>()
        var run = 0.0
        for ((c, d) in rail.zipWithNext()) {
            val len = c.distance(d)
            for ((a, b) in rung.zipWithNext()) {
                val rx = b.x - a.x; val ry = b.y - a.y; val sx = d.x - c.x; val sy = d.y - c.y
                val den = rx * sy - ry * sx
                if (abs(den) < 1e-12) continue
                val t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / den
                val u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / den
                if (t in 0.0..1.0 && u in 0.0..1.0) out += run + u * len
            }
            run += len
        }
        return out
    }

    /** Where a rung meets a rail: its crossing, else the rail point nearest the rung's nearer end. */
    private fun nearest(rung: List<Point>, rail: List<Point>): Double =
        crossings(rung, rail).firstOrNull() ?: rung.map { closest(rail, it) }.minBy { it.second }.first

    /** Pieces of [line] between consecutive arc-length [cuts]. */
    private fun cut(line: List<Point>, cuts: List<Double>): List<List<Point>> {
        val total = length(line)
        val bounds = listOf(0.0) + cuts.map { it.coerceIn(0.0, total) } + total
        return bounds.zipWithNext().map { (from, to) ->
            val piece = ArrayList<Point>()
            piece += at(line, if (total > 0) from / total else 0.0)
            var run = 0.0
            for (i in 1 until line.size) {
                run += line[i - 1].distance(line[i])
                if (run > from + 1e-9 && run < to - 1e-9) piece += line[i]
            }
            piece += at(line, if (total > 0) to / total else 0.0)
            piece.filterIndexed { i, p -> i == 0 || p != piece[i - 1] }
        }
    }
}
