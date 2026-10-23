// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/lettering/font_variant.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/lettering/glyph.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/svg/guides.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/svg/path.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/svg/units.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/utils/nodes.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.geometry.SvgPath as GeometrySvgPath
import java.io.InputStream
import java.text.Normalizer
import javax.xml.parsers.SAXParserFactory
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

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
    /** Whether every second line may be sewn backwards (lettering "back and forth"). */
    val reversible: Boolean = true,
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
                reversible = (o["reversible"] as? JsonPrimitive)?.booleanOrNull ?: true,
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
    /** A per-side value ("a b", or one value for both sides). */
    fun pair(name: String): Pair<Double, Double>? = values(name).takeIf { it.isNotEmpty() }?.let { it[0] to (it.getOrNull(1) ?: it[0]) }
    val names: Set<String> get() = values.keys
}

/** One embroidered element of a glyph, in SVG px, +y down. */
sealed interface InkStitchElement {
    val color: Int
    val params: InkStitchParams
}

/**
 * Satin column path as SatinColumn reads it: every subpath (flattened) and its node list. Which
 * subpaths are rails or rungs is decided by the satin port, not here.
 */
data class InkSatin(
    val subpaths: List<List<Point>>,
    val nodePaths: List<List<Point>>,
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
 * Ink/Stitch embroidery font: the `GlyphLayer-…` groups of a font variant plus `font.json`. Only
 * geometry and `inkstitch:*` parameters are read; nothing is executed. Elements this engine cannot
 * sew are counted in [unsupported] (by kind) and left out of the glyphs.
 */
class InkStitchFont(
    val info: InkStitchFontInfo,
    val glyphs: Map<String, InkStitchGlyph>,
    /** Baseline in px (+y down). */
    val baseline: Double,
    val unsupported: Map<String, Int>,
    /** The file's "baseline" guide in px, null when absent (diagnostics). */
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
        private val SKIPPED = setOf("defs", "clipPath", "mask", "symbol", "metadata", "marker", "pattern", "style", "title", "desc")
        private val SHAPES = setOf("rect", "circle", "ellipse", "line", "polyline", "polygon")
        private val MARKERS = listOf("anchor-line", "pattern", "guide-line")

        fun parse(svg: InputStream, fontJson: String): InkStitchFont = parse(listOf(svg), InkStitchFontInfo.parse(fontJson))

        fun parse(svg: InputStream, info: InkStitchFontInfo): InkStitchFont = parse(listOf(svg), info)

        /**
         * A font variant from its files (`ltr.svg`, or every `.svg` of a variant folder). Glyph
         * layers of later files replace earlier ones of the same name, as in Ink/Stitch.
         */
        fun parse(svgs: List<InputStream>, info: InkStitchFontInfo): InkStitchFont {
            val glyphs = LinkedHashMap<String, InkStitchGlyph>()
            val unsupported = sortedMapOf<String, Int>()
            var guide: Double? = null
            for (svg in svgs) {
                val handler = Handler()
                val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
                // No DTDs or external entities: the file is downloaded data.
                for ((feature, on) in listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
                    "http://xml.org/sax/features/external-general-entities" to false,
                    "http://xml.org/sax/features/external-parameter-entities" to false)) {
                    runCatching { factory.setFeature(feature, on) }
                }
                factory.newSAXParser().parse(svg, handler)
                glyphs.putAll(handler.glyphs)
                handler.unsupported.forEach { (k, v) -> unsupported[k] = (unsupported[k] ?: 0) + v }
                if (guide == null) guide = handler.baselineGuide
            }
            val probe = InkStitchFont(info, glyphs, 0.0, unsupported)
            // Ink/Stitch aligns by the guide. All glyphs share it, so only a missing guide needs a stand-in.
            val baseline = guide ?: probe.referenceGlyph?.inkBottom ?: 0.0
            return InkStitchFont(info, glyphs, baseline, unsupported, guide)
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
            fun extent(paths: List<Polyline>) = paths.forEach { p -> p.points.forEach { q -> minX = min(minX, q.x); maxX = max(maxX, q.x) } }
            fun ink(points: List<Point>) = points.forEach { q -> inkTop = min(inkTop, q.y); inkBottom = max(inkBottom, q.y) }
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
                    // svg.units.get_viewbox_transform: page scale, then translate by the (unscaled) viewBox origin.
                    val box = attrs.getValue("viewBox")?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }
                    val width = attrs.getValue("width")?.let(::lengthPx)
                    val height = attrs.getValue("height")?.let(::lengthPx)
                    pageHeight = box?.get(3) ?: height
                    if (box != null && box[2] > 0 && box[3] > 0) {
                        var sx = (width ?: box[2]) / box[2]
                        var sy = (height ?: box[3]) / box[3]
                        val aspect = attrs.getValue("preserveAspectRatio") ?: "xMidYMid meet"
                        if (aspect != "none") { val k = if ("slice" in aspect) max(sx, sy) else min(sx, sy); sx = k; sy = k }
                        root = Affine2D.translation(-box[0], -box[1]) * Affine2D.scale(sx, sy)
                    } else if (box != null) root = Affine2D.translation(-box[0], -box[1])
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
                // font_variant._clean_group: glyph layers are often hidden in the file; only hidden content inside them is skipped.
                val hiddenInGlyph = if (glyph != null && glyph !== parent?.glyph) false else hidden
                stack.addLast(Frame(transform, style, hiddenInGlyph, false, glyph))
                if (glyph == null) return
                when {
                    local == "path" -> path(attrs, transform, style, hiddenInGlyph, glyph)
                    local == "use" -> {
                        val href = attrs.getValue(NS_XLINK, "href") ?: attrs.getValue("href") ?: ""
                        if (!hiddenInGlyph && !href.startsWith("#inkstitch_")) note("clone")
                    }
                    local == "text" && !hiddenInGlyph -> note("text")
                    local in SHAPES && !hiddenInGlyph -> note("shape:$local")
                }
            }

            override fun endElement(uri: String, local: String, qName: String) {
                val frame = stack.removeLast()
                if (uri == NS_SODIPODI && local == "namedview") inNamedView--
                val glyph = frame.glyph ?: return
                // Later layers of the same name replace earlier ones, as a dict assignment does.
                if (stack.lastOrNull()?.glyph !== glyph && glyph.maxX >= glyph.minX) {
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
                val paths = runCatching { GeometrySvgPath.flatten(d, transform, TOLERANCE_PX) }.getOrNull() ?: run { note("bad-path"); return }
                // glyph._process_bbox: every path except command connectors spaces the glyph.
                if (attrs.getValue(NS_INKSCAPE, "connection-end") == null) glyph.extent(paths)
                val connector = attrs.getValue(NS_INKSCAPE, "connector-type") != null ||
                    attrs.getValue(NS_INKSCAPE, "connection-start") != null || attrs.getValue(NS_INKSCAPE, "connection-end") != null
                val ownStyle = attrs.getValue("style").orEmpty()
                // marker.has_marker: Ink/Stitch guide and pattern paths are not sewn.
                if (connector || hidden || MARKERS.any { "marker-start:url(#inkstitch-$it-marker" in ownStyle }) return
                val params = InkStitchParams((0 until attrs.length).filter { attrs.getURI(it) == NS_INKSTITCH }
                    .associate { attrs.getLocalName(it) to attrs.getValue(it) })
                val fill = color(style["fill"] ?: "black").takeIf { style["fill-opacity"] != "0" }
                val stroke = color(style["stroke"] ?: "none")
                val made = ArrayList<InkStitchElement>()
                if (fill != null) {
                    val method = params["fill_method"] ?: "tatami_fill"
                    if (method in FILL_METHODS) {
                        made += InkFill(paths, if (style["fill-rule"] == "evenodd") FillRule.EVEN_ODD else FillRule.NON_ZERO, fill, params)
                    } else note("fill:$method")
                }
                if (stroke != null) {
                    // Font._set_style: rendered lettering gives every unfilled path a 1px stroke.
                    val unfilled = style(attrs)["fill"] == "none"
                    val width = if (unfilled) 1.0 else (style["stroke-width"]?.let(::lengthPx) ?: 1.0) *
                        (hypot(transform.a, transform.b) + hypot(transform.c, transform.d)) / 2
                    val lines = paths.filter { it.points.size > 1 }
                    val satin = params.bool("satin_column", false) && (paths.size > 1 || width > MIN_SATIN_STROKE_PX)
                    when {
                        satin && (params["satin_method"] ?: "satin_column") !in SATIN_DEFAULT -> note("satin:${params["satin_method"]}")
                        satin && params.bool("e_stitch", false) -> note("satin:e_stitch")
                        satin && lines.size < 2 -> note("satin:single-path")
                        satin -> {
                            val nodes = runCatching { GeometrySvgPath.flatten(d, transform, NODES_ONLY) }.getOrNull().orEmpty()
                                .filter { it.points.size > 1 }
                            made += InkSatin(lines.map { it.points }, if (nodes.size == lines.size) nodes.map { it.points } else lines.map { it.points }, stroke, params)
                        }
                        (params["stroke_method"] ?: "running_stitch") !in RUNNING_DEFAULT -> note("stroke:${params["stroke_method"]}")
                        params.bool("manual_stitch", false) -> note("stroke:manual_stitch")
                        lines.isNotEmpty() -> made += InkRun(lines, stroke, params)
                    }
                }
                if (params.bool("stroke_first", false)) made.reverse()
                for (e in made) when (e) {
                    is InkFill -> e.paths.forEach { glyph.ink(it.points) }
                    is InkRun -> e.paths.forEach { glyph.ink(it.points) }
                    // Rungs reach past the rails; the two longest subpaths are the rails of a ladder.
                    is InkSatin -> e.subpaths.sortedByDescending { p -> p.zipWithNext().sumOf { (a, b) -> a.distance(b) } }.take(2).forEach(glyph::ink)
                }
                glyph.elements += made
            }
        }

        // Old files store a combo's index instead of its name; 0 is the default method.
        private val FILL_METHODS = setOf("tatami_fill", "auto_fill", "0")
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
