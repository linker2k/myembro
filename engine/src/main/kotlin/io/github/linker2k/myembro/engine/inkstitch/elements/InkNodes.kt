// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/utils/nodes.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/clone.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/element.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/commands.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/marker.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgDocument
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgGeometry
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgImportException
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgNode
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgRejection
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round

/** Document-wide settings of `<metadata>` (Ink/Stitch InkStitchMetadata), with Ink/Stitch's defaults. */
data class InkStitchMetadata(
    val minStitchLenMm: Double = 0.1,
    val collapseLenMm: Double = 3.0,
    val minSatinStrokeWidthMm: Double = 1.0,
) {
    companion object {
        fun of(root: SvgNode): InkStitchMetadata {
            val items = root.children.filter { it.tag == "metadata" }.flatMap { it.children }
                .filter { it.tag.startsWith("inkstitch:") }.associate { it.tag.removePrefix("inkstitch:") to it.text.trim().trim('"') }
            fun mm(name: String, default: Double) = items[name]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: default
            return InkStitchMetadata(mm("min_stitch_len_mm", 0.1), mm("collapse_len_mm", 3.0), mm("min_satin_stroke_width_mm", 1.0))
        }
    }
}

/** What a document's elements are, in sewing order, and what was left out (by kind). */
data class InkStitchElements(
    val elements: List<InkStitchElement>,
    val unsupported: Map<String, Int>,
    val metadata: InkStitchMetadata,
    /** Whether any element carries `inkstitch:*` parameters; otherwise every element uses Ink/Stitch's defaults. */
    val parameterized: Boolean = false,
)

/**
 * Turns SVG nodes into embroidery elements the way Ink/Stitch's `iterate_nodes` and
 * `node_to_elements` do: a post-order walk that skips hidden, ignored, defs/mask/clipPath and
 * command content; fills, satin columns and strokes by style and `inkstitch:*` parameters; clones
 * resolved as an unlinked copy with their fill angles rotated. Geometry is flattened to SVG px.
 */
class InkNodes(private val document: SvgDocument) {
    val metadata = InkStitchMetadata.of(document.root)
    private val unsupported = sortedMapOf<String, Int>()
    private val commands: Map<String, List<String>> = connectedCommands()

    val unsupportedCounts: Map<String, Int> get() = unsupported.toMap()

    fun note(kind: String) { unsupported[kind] = (unsupported[kind] ?: 0) + 1 }

    /** Every element of the document. */
    fun read(): InkStitchElements = elementsOf(document.root).copy(
        parameterized = document.root.descendants().any { n -> !n.tag.startsWith("inkstitch:") && n.attributes.keys.any { it.startsWith("inkstitch:") } })

    /** Elements of [node] and its descendants (used by fonts on each glyph layer). */
    fun elementsOf(node: SvgNode): InkStitchElements =
        InkStitchElements(iterate(node).flatMap { toElements(it, 0) }, unsupported.toMap(), metadata)

    /** nodes.iterate_nodes without a selection. */
    fun iterate(node: SvgNode): List<SvgNode> {
        val out = ArrayList<SvgNode>()
        fun walk(n: SvgNode) {
            if ("ignore_object" in commandsOf(n) || isIgnoredLayer(n)) return
            if ((n.tag in EMBROIDERABLE || n.tag == "g") && SvgGeometry.specifiedStyle(n)["display"] == "none") return
            if (n.tag == "defs" || n.tag == "mask" || n.tag == "clipPath") return
            if (isCommand(n) || n["inkscape:connector-type"] != null) return
            n.children.toList().forEach(::walk)
            if (n.tag == "g") return
            if ((n.tag in EMBROIDERABLE || isClone(n)) && !hasMarker(n)) out += n
            else if (n.tag == "text" || n.tag == "image") note(n.tag)
        }
        walk(node)
        return out
    }

    /** nodes.node_to_elements. */
    private fun toElements(node: SvgNode, depth: Int): List<InkStitchElement> {
        if (SvgGeometry.specifiedStyle(node)["display"] == "none") return emptyList()
        if (isClone(node)) return clone(node, depth)
        val d = SvgGeometry.pathData(node) ?: run { note("empty:${node.tag}"); return emptyList() }
        if (hasMarker(node)) return emptyList()
        val transform = SvgGeometry.nodeTransform(node)
        val paths = runCatching { SvgPath.flatten(d, transform, TOLERANCE_PX) }.getOrElse {
            throw SvgImportException(SvgRejection.INVALID_GEOMETRY, "unreadable path data in $node")
        }
        if (paths.any { p -> p.points.any { !it.x.isFinite() || !it.y.isFinite() } }) throw SvgImportException(SvgRejection.INVALID_GEOMETRY, "non-finite coordinates in $node")
        val style = SvgGeometry.specifiedStyle(node)
        var params = InkStitchParams(node.attributes.filterKeys { it.startsWith("inkstitch:") }.mapKeys { it.key.removePrefix("inkstitch:") })
        for (c in commandsOf(node)) when (c) {
            "trim" -> params = params.plus("trim_after", "true")
            "stop" -> params = params.plus("stop_after", "true")
            else -> note("command:$c")
        }
        val made = ArrayList<InkStitchElement>()
        val fill = SvgGeometry.color(none(style["fill"] ?: "black"), document, style["color"])
        if (fill != null && style["fill-opacity"] != "0") {
            val method = params["fill_method"] ?: "tatami_fill"
            if (method in FILL_METHODS) {
                made += InkFill(paths, if (style["fill-rule"] == "evenodd") FillRule.EVEN_ODD else FillRule.NON_ZERO, fill, params, node.id)
            } else note("fill:$method")
        }
        val stroke = SvgGeometry.color(none(style["stroke"]), document, style["color"])
        if (stroke != null) {
            val width = (SvgGeometry.lengthPx(style["stroke-width"] ?: "1.0") ?: 1.0) * strokeScale(transform)
            val satin = params.bool("satin_column", false) &&
                (paths.size > 1 || width > metadata.minSatinStrokeWidthMm * PX_PER_MM)
            when {
                satin && (params["satin_method"] ?: "satin_column") !in SATIN_DEFAULT -> note("satin:${params["satin_method"]}")
                satin && params.bool("e_stitch", false) -> note("satin:e_stitch")
                satin -> {
                    val nodes = runCatching { SvgPath.flatten(d, transform, NODES_ONLY) }.getOrNull().orEmpty()
                    InkSatinRails.select(paths, nodes, width, params, stroke, node.id)?.let { made += it } ?: note("satin:unreadable")
                }
                isCommand(node) -> Unit
                (params["stroke_method"] ?: "running_stitch") !in RUNNING_DEFAULT -> note("stroke:${params["stroke_method"]}")
                params.bool("manual_stitch", false) -> note("stroke:manual_stitch")
                else -> paths.filter { it.points.size > 1 }.takeIf { it.isNotEmpty() }?.let { made += InkRun(it, stroke, params, node.id) }
            }
        }
        if (params.bool("stroke_first", false)) made.reverse()
        return made
    }

    /** Clone.to_stitch_groups: the unlinked copy's own elements, or nothing when `inkstitch:clone` is off. */
    private fun clone(use: SvgNode, depth: Int): List<InkStitchElement> {
        if (use["inkstitch:clone"]?.trim()?.lowercase().let { it == "false" || it == "0" || it == "no" }) return emptyList()
        val resolved = resolveClone(use, emptySet())
        return iterate(resolved).flatMap { toElements(it, depth + 1) }
    }

    /**
     * Clone.resolve_clone: a copy of the referenced node placed beside the `<use>`, its transform
     * and style merged with the clone's, inner clones resolved as if at the source, and fill
     * angles rotated by the clone's transform (or replaced by its own `inkstitch:angle`).
     */
    private fun resolveClone(use: SvgNode, visiting: Set<SvgNode>): SvgNode {
        val parent = use.parent ?: throw SvgImportException(SvgRejection.INVALID_INPUT, "clone without parent")
        val source = document.resolve(use.href) ?: throw SvgImportException(SvgRejection.MISSING_REFERENCE, "clone target ${use.href} not found")
        if (source in visiting || visiting.size > MAX_CLONE_DEPTH || source === use || use.ancestors().any { it === source }) {
            throw SvgImportException(SvgRejection.CYCLIC_REFERENCE, "clone ${use.id ?: use.href} refers to itself")
        }
        val sourceParent = source.parent ?: throw SvgImportException(SvgRejection.INVALID_INPUT, "clone target is the document root")
        var cloned = source.copy()
        cloned.attributes.remove("id")
        // Resolve inner clones as if this copy were where its source is.
        cloned.parent = sourceParent
        if (isClone(cloned)) {
            cloned = resolveClone(cloned, visiting + source)
        } else {
            for (inner in cloned.descendants().filter(::isClone).toList()) {
                val replacement = resolveClone(inner, visiting + source)
                val holder = inner.parent!!
                holder.children[holder.children.indexOf(inner)] = replacement
                replacement.parent = holder
            }
        }
        cloned.parent = parent
        val x = use["x"]?.let(SvgGeometry::lengthPx) ?: 0.0
        val y = use["y"]?.let(SvgGeometry::lengthPx) ?: 0.0
        val outer = SvgGeometry.ownTransform(use) * Affine2D.translation(x, y)
        if (cloned.tag == "symbol") cloned.children.forEach { it["transform"] = matrix(outer * SvgGeometry.ownTransform(it)) }
        else cloned["transform"] = matrix(outer * SvgGeometry.ownTransform(cloned))
        // The source's style applies on top of the clone's.
        val style = SvgGeometry.ownStyle(use).apply { putAll(SvgGeometry.ownStyle(cloned)) }
        if (style.isNotEmpty()) cloned["style"] = SvgGeometry.styleString(style)
        val sourceTransform = SvgGeometry.composedTransform(if (cloned.tag == "symbol") parent else sourceParent)
        val angleTransform = SvgGeometry.composedTransform(use) * inverse(sourceTransform)
        applyAngles(use, cloned, angleTransform)
        return cloned
    }

    /** Clone.apply_angles. */
    private fun applyAngles(use: SvgNode, cloned: SvgNode, transform: Affine2D) {
        val custom = use["inkstitch:angle"]?.trim()?.toDoubleOrNull()
        val flip = use["inkstitch:flip_angle"]?.trim()?.lowercase().let { it == "true" || it == "1" || it == "yes" }
        val linear = Affine2D(transform.a, transform.b, transform.c, transform.d)
        for (node in sequenceOf(cloned) + cloned.descendants()) {
            if (node.tag !in EMBROIDERABLE) continue
            val style = SvgGeometry.specifiedStyle(node)
            if (none(style["fill"] ?: "black") == null || style["fill-opacity"] == "0") continue
            var angle = if (custom == null) {
                val own = node["inkstitch:angle"]?.trim()?.toDoubleOrNull() ?: 0.0
                // SVG rotation is clockwise on screen, Ink/Stitch angles counter-clockwise.
                val v = (linear * Affine2D.rotation(-own)).apply(Point(1.0, 0.0))
                -Math.toDegrees(if (v.x == 0.0 && v.y == 0.0) 0.0 else atan2(v.y, v.x))
            } else custom
            if (flip) angle = -angle
            node["inkstitch:angle"] = (round(angle * 1e6) / 1e6).toString()
        }
    }

    private fun inverse(m: Affine2D): Affine2D {
        val det = m.a * m.d - m.b * m.c
        if (det == 0.0) throw SvgImportException(SvgRejection.INVALID_GEOMETRY, "singular transform")
        val a = m.d / det; val b = -m.b / det; val c = -m.c / det; val d = m.a / det
        return Affine2D(a, b, c, d, -(a * m.e + c * m.f), -(b * m.e + d * m.f))
    }

    private fun matrix(m: Affine2D) = "matrix(${m.a},${m.b},${m.c},${m.d},${m.e},${m.f})"

    /** element.stroke_scale: mean length of the transformed unit vectors. */
    private fun strokeScale(m: Affine2D) = (hypot(m.a, m.b) + hypot(m.c, m.d)) / 2

    /** clone.is_clone: a `<use>` that is not a command symbol. */
    private fun isClone(n: SvgNode) = n.tag == "use" && n.href != null && !isCommandSymbol(n)

    private fun isCommandSymbol(n: SvgNode) = n["xlink:href"]?.startsWith("#inkstitch_") == true &&
        n["xlink:href"]!!.substring(11).let { it in COMMANDS }

    /** commands.is_command: connectors carry connection endpoints. */
    private fun isCommand(n: SvgNode) = n["inkscape:connection-start"] != null || n["inkscape:connection-end"] != null

    /** marker.has_marker: guide/pattern paths marked by Ink/Stitch's markers are not sewn. */
    private fun hasMarker(n: SvgNode) = MARKERS.any { "marker-start:url(#inkstitch-$it-marker" in (n["style"] ?: "") }

    private fun commandsOf(n: SvgNode): List<String> = n.id?.let { commands[it] }.orEmpty()

    /** A layer holding a standalone `ignore_layer` symbol. */
    private fun isIgnoredLayer(n: SvgNode) = n.tag == "g" && n["inkscape:groupmode"] == "layer" &&
        n.descendants().any { it.tag == "use" && commandName(it) == "ignore_layer" }

    /** Command name of a `<use>` pointing at an Ink/Stitch command symbol, e.g. "trim". */
    private fun commandName(use: SvgNode): String? {
        val symbol = runCatching { document.resolve(use["xlink:href"] ?: use["href"]) }.getOrNull() ?: return null
        if (symbol.tag != "symbol") return null
        val id = symbol.id ?: return null
        if (!id.startsWith("inkstitch_")) return null
        return id.substring(10).substringBefore('-')
    }

    /** commands.find_commands for every element: connectors between a command symbol and an object. */
    private fun connectedCommands(): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for (connector in document.root.descendants()) {
            val start = connector["inkscape:connection-start"] ?: continue
            val end = connector["inkscape:connection-end"] ?: continue
            val a = runCatching { document.resolve(start) }.getOrNull() ?: continue
            val b = runCatching { document.resolve(end) }.getOrNull() ?: continue
            val (use, target) = if (a.tag == "use") a to b else if (b.tag == "use") b to a else continue
            val name = commandName(use) ?: continue
            target.id?.let { out.getOrPut(it) { ArrayList() } += name }
        }
        return out
    }

    companion object {
        val EMBROIDERABLE = setOf("path", "line", "polyline", "polygon", "rect", "ellipse", "circle")
        private val COMMANDS = setOf("starting_point", "ending_point", "target_point", "autoroute_start", "autoroute_end",
            "stop", "trim", "ignore_object", "satin_cut_point", "ignore_layer", "origin", "stop_position")
        private val MARKERS = listOf("anchor-line", "pattern", "guide-line")
        private const val PX_PER_MM = 96 / 25.4
        private const val TOLERANCE_PX = 0.1
        /** Flatten tolerance so large that curves collapse to their end nodes: the path's node list. */
        private const val NODES_ONLY = 1e12
        private const val MAX_CLONE_DEPTH = 32
        // Old files store a combo's index instead of its name; 0 is the default method.
        private val FILL_METHODS = setOf("tatami_fill", "auto_fill", "legacy_fill", "0")
        private val SATIN_DEFAULT = setOf("satin_column", "0")
        private val RUNNING_DEFAULT = setOf("running_stitch", "0")

        private fun none(paint: String?): String? = paint?.takeUnless { it.trim().equals("none", ignoreCase = true) }
    }
}
