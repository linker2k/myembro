// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/svg/path.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/svg/units.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.geometry.Affine2D
import kotlin.math.max
import kotlin.math.min

/**
 * Transforms, lengths, styles and shape outlines of [SvgNode]s, as Ink/Stitch reads them through
 * inkex. Coordinates are SVG px (96 per inch) after the root viewBox mapping.
 */
object SvgGeometry {
    private fun transformOf(node: SvgNode): Affine2D =
        node["transform"]?.takeIf { it.isNotBlank() }?.let {
            runCatching { Affine2D.parse(it) }.getOrElse { e -> throw SvgImportException(SvgRejection.INVALID_INPUT, "bad transform on $node: ${e.message}") }
        } ?: Affine2D.IDENTITY

    /** svg.units.get_viewbox_transform: translate(-viewBox origin) composed after the page scale. */
    fun viewBoxTransform(root: SvgNode): Affine2D {
        val box = root["viewBox"]?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }
        val width = root["width"]?.let(::lengthPx)?.takeIf { !it.isNaN() } ?: box?.get(2) ?: 0.0
        val height = root["height"]?.let(::lengthPx)?.takeIf { !it.isNaN() } ?: box?.get(3) ?: 0.0
        val v = box ?: listOf(0.0, 0.0, width, height)
        val translate = Affine2D.translation(-v[0], -v[1])
        if (v[2] == 0.0 || v[3] == 0.0) return translate
        var sx = width / v[2]
        var sy = height / v[3]
        val aspect = root["preserveAspectRatio"] ?: "xMidYMid meet"
        if (aspect != "none") { val s = if ("slice" in aspect) max(sx, sy) else min(sx, sy); sx = s; sy = s }
        return translate * Affine2D.scale(sx, sy)
    }

    /** svg.path.get_node_transform: own and group/link ancestors' transforms, then the viewBox. */
    fun nodeTransform(node: SvgNode): Affine2D {
        var m = Affine2D.IDENTITY
        if (node.parent != null) {
            var n: SvgNode? = node
            while (n != null) {
                m = transformOf(n) * m
                n = n.parent?.takeIf { it.tag == "g" || it.tag == "a" }
            }
        }
        return viewBoxTransform(node.root()) * m
    }

    /** inkex composed_transform: every ancestor's transform, without the viewBox. */
    fun composedTransform(node: SvgNode): Affine2D =
        (node.parent?.let(::composedTransform) ?: Affine2D.IDENTITY) * transformOf(node)

    /** Parsed `transform` attribute of [node]. */
    fun ownTransform(node: SvgNode): Affine2D = transformOf(node)

    private val INHERITED = setOf("fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity",
        "stroke-dasharray", "stroke-linejoin", "stroke-linecap", "visibility", "color", "marker-start", "marker-mid", "marker-end")
    private val PRESENTATION = INHERITED + setOf("display", "opacity")

    /** The `style` attribute alone, as inkex `node.style`. */
    fun ownStyle(node: SvgNode): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        node["style"]?.split(';')?.forEach { part ->
            val i = part.indexOf(':')
            if (i > 0) out[part.substring(0, i).trim()] = part.substring(i + 1).trim().removeSuffix("!important").trim()
        }
        return out
    }

    fun styleString(style: Map<String, String>): String = style.entries.joinToString(";") { "${it.key}:${it.value}" }

    /** inkex specified_style: inherited properties of the ancestors, then presentation attributes, then `style`. */
    fun specifiedStyle(node: SvgNode): Map<String, String> {
        val out = HashMap<String, String>()
        node.parent?.let { p -> specifiedStyle(p).forEach { (k, v) -> if (k in INHERITED) out[k] = v } }
        for (name in PRESENTATION) node[name]?.let { out[name] = it.trim() }
        out.putAll(ownStyle(node))
        // "inherit" takes the parent's value.
        for ((k, v) in out.entries.toList()) if (v == "inherit") node.parent?.let { specifiedStyle(it)[k] }?.let { out[k] = it } ?: out.remove(k)
        return out
    }

    /** svg.units.convert_length to px; null when the unit is unknown. */
    fun lengthPx(text: String): Double? {
        val m = Regex("^\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*([a-zA-Z%]*)\\s*$").find(text) ?: return null
        val v = m.groupValues[1].toDouble()
        return when (m.groupValues[2].lowercase()) {
            "", "px" -> v
            "mm" -> v * 96 / 25.4
            "cm" -> v * 960 / 25.4
            "in" -> v * 96
            "pt" -> v * 96 / 72
            "pc" -> v * 16
            "q" -> v * 96 / 101.6
            else -> null
        }
    }

    /**
     * Path data of an embroiderable element: `d` of a path, or the outline inkex gives a shape.
     * Null for other tags and for shapes without area or length.
     */
    fun pathData(node: SvgNode): String? {
        fun num(name: String, default: Double = 0.0) = node[name]?.let(::lengthPx) ?: default
        return when (node.tag) {
            "path" -> node["d"]?.takeIf { it.isNotBlank() }
            "rect" -> {
                val x = num("x"); val y = num("y"); val w = num("width"); val h = num("height")
                if (w <= 0 || h <= 0) return null
                var rx = node["rx"]?.let(::lengthPx); var ry = node["ry"]?.let(::lengthPx)
                if (rx == null) rx = ry; if (ry == null) ry = rx
                val cx = min(rx ?: 0.0, w / 2); val cy = min(ry ?: 0.0, h / 2)
                if (cx <= 0 || cy <= 0) "M $x $y h $w v $h h ${-w} z"
                else "M ${x + cx} $y H ${x + w - cx} A $cx $cy 0 0 1 ${x + w} ${y + cy} V ${y + h - cy} " +
                    "A $cx $cy 0 0 1 ${x + w - cx} ${y + h} H ${x + cx} A $cx $cy 0 0 1 $x ${y + h - cy} V ${y + cy} " +
                    "A $cx $cy 0 0 1 ${x + cx} $y z"
            }
            "circle", "ellipse" -> {
                val cx = num("cx"); val cy = num("cy")
                val rx = if (node.tag == "circle") num("r") else num("rx", node["ry"]?.let(::lengthPx) ?: 0.0)
                val ry = if (node.tag == "circle") num("r") else num("ry", node["rx"]?.let(::lengthPx) ?: 0.0)
                if (rx <= 0 || ry <= 0) return null
                "M ${cx + rx} $cy A $rx $ry 0 0 1 $cx ${cy + ry} A $rx $ry 0 0 1 ${cx - rx} $cy " +
                    "A $rx $ry 0 0 1 $cx ${cy - ry} A $rx $ry 0 0 1 ${cx + rx} $cy z"
            }
            "line" -> "M ${num("x1")} ${num("y1")} L ${num("x2")} ${num("y2")}"
            "polyline", "polygon" -> {
                val values = node["points"]?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
                if (values.size < 4) return null
                val pairs = values.chunked(2).filter { it.size == 2 }
                "M " + pairs.joinToString(" L ") { "${it[0]} ${it[1]}" } + if (node.tag == "polygon") " z" else ""
            }
            else -> null
        }
    }

    /** 0xAARRGGBB of a paint, null for none. Gradients take their first stop; unknown paints are black. */
    fun color(paint: String?, document: SvgDocument? = null, current: String? = null): Int? {
        val p = paint?.trim()?.lowercase() ?: return null
        if (p == "none" || p.isEmpty()) return null
        if (p == "currentcolor") return color(current ?: "black", document)
        if (p.startsWith("url(")) {
            val id = p.removePrefix("url(").substringBefore(')').trim().trim('\'', '"')
            val gradient = runCatching { document?.resolve(id) }.getOrNull() ?: return BLACK
            val stop = gradientStops(gradient, document).firstOrNull() ?: return BLACK
            return color(ownStyle(stop)["stop-color"] ?: stop["stop-color"] ?: "black", document)
        }
        if (p.startsWith("#")) {
            val hex = p.substring(1)
            if ((hex.length == 3 || hex.length == 6) && hex.all { it in "0123456789abcdef" }) {
                val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
                return (0xFF000000 or full.toLong(16)).toInt()
            }
            return BLACK
        }
        if (p.startsWith("rgb(")) {
            val parts = p.removePrefix("rgb(").substringBefore(')').split(',').map { it.trim() }
            if (parts.size == 3) {
                val c = parts.map { s -> (if (s.endsWith("%")) s.dropLast(1).toDoubleOrNull()?.times(2.55) else s.toDoubleOrNull())?.toInt()?.coerceIn(0, 255) ?: 0 }
                return (0xFF000000 or (c[0].toLong() shl 16) or (c[1].toLong() shl 8) or c[2].toLong()).toInt()
            }
        }
        return NAMED[p] ?: BLACK
    }

    private fun gradientStops(gradient: SvgNode, document: SvgDocument?, depth: Int = 0): List<SvgNode> {
        val own = gradient.children.filter { it.tag == "stop" }
        if (own.isNotEmpty() || depth > 8) return own
        val linked = runCatching { document?.resolve(gradient.href) }.getOrNull() ?: return own
        return gradientStops(linked, document, depth + 1)
    }

    private const val BLACK = 0xFF000000.toInt()
    /** CSS named colors. */
    private val NAMED: Map<String, Int> = """
        aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc bisque ffe4c4
        black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887
        cadetblue 5f9ea0 chartreuse 7fff00 chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc
        crimson dc143c cyan 00ffff darkblue 00008b darkcyan 008b8b darkgoldenrod b8860b darkgray a9a9a9
        darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b darkmagenta 8b008b darkolivegreen 556b2f darkorange ff8c00
        darkorchid 9932cc darkred 8b0000 darksalmon e9967a darkseagreen 8fbc8f darkslateblue 483d8b
        darkslategray 2f4f4f darkslategrey 2f4f4f darkturquoise 00ced1 darkviolet 9400d3 deeppink ff1493
        deepskyblue 00bfff dimgray 696969 dimgrey 696969 dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0
        forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff gold ffd700 goldenrod daa520 gray 808080
        green 008000 greenyellow adff2f grey 808080 honeydew f0fff0 hotpink ff69b4 indianred cd5c5c indigo 4b0082
        ivory fffff0 khaki f0e68c lavender e6e6fa lavenderblush fff0f5 lawngreen 7cfc00 lemonchiffon fffacd
        lightblue add8e6 lightcoral f08080 lightcyan e0ffff lightgoldenrodyellow fafad2 lightgray d3d3d3
        lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1 lightsalmon ffa07a lightseagreen 20b2aa
        lightskyblue 87cefa lightslategray 778899 lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0
        lime 00ff00 limegreen 32cd32 linen faf0e6 magenta ff00ff maroon 800000 mediumaquamarine 66cdaa
        mediumblue 0000cd mediumorchid ba55d3 mediumpurple 9370db mediumseagreen 3cb371 mediumslateblue 7b68ee
        mediumspringgreen 00fa9a mediumturquoise 48d1cc mediumvioletred c71585 midnightblue 191970 mintcream f5fffa
        mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead navy 000080 oldlace fdf5e6 olive 808000 olivedrab 6b8e23
        orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa palegreen 98fb98 paleturquoise afeeee
        palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 peru cd853f pink ffc0cb plum dda0dd powderblue b0e0e6
        purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f royalblue 4169e1 saddlebrown 8b4513
        salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee sienna a0522d silver c0c0c0 skyblue 87ceeb
        slateblue 6a5acd slategray 708090 slategrey 708090 snow fffafa springgreen 00ff7f steelblue 4682b4 tan d2b48c
        teal 008080 thistle d8bfd8 tomato ff6347 turquoise 40e0d0 violet ee82ee wheat f5deb3 white ffffff
        whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32
    """.trim().split(Regex("\\s+")).chunked(2).associate { (name, hex) -> name to (0xFF000000 or hex.toLong(16)).toInt() }
}
