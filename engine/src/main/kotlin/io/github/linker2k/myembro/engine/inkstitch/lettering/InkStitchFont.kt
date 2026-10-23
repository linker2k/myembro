// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/lettering/font_variant.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/lettering/glyph.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/svg/guides.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/svg/path.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.inkstitch.elements.InkFill
import io.github.linker2k.myembro.engine.inkstitch.elements.InkNodes
import io.github.linker2k.myembro.engine.inkstitch.elements.InkRun
import io.github.linker2k.myembro.engine.inkstitch.elements.InkSatin
import io.github.linker2k.myembro.engine.inkstitch.elements.InkStitchElement
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgDocument
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgGeometry
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgNode
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.SvgPath as GeometrySvgPath
import java.io.InputStream
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min
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
        private const val TOLERANCE_PX = 0.1

        fun parse(svg: InputStream, fontJson: String): InkStitchFont = parse(svg, InkStitchFontInfo.parse(fontJson))

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
                val document = SvgDocument.parse(svg)
                if (guide == null) guide = baselineGuide(document.root)
                val nodes = InkNodes(document)
                for (layer in document.root.descendants().filter { it.tag == "g" && it["inkscape:label"]?.startsWith("GlyphLayer-") == true }.toList()) {
                    // font_variant._clean_group: glyph layers are often hidden in the font file.
                    layer.attributes.remove("display")
                    SvgGeometry.ownStyle(layer).let { st -> if (st.remove("display") != null) layer["style"] = SvgGeometry.styleString(st) }
                    layer.attributes.remove("inkscape:groupmode")
                    setStyle(layer)
                    val name = Normalizer.normalize(layer["inkscape:label"]!!.removePrefix("GlyphLayer-"), Normalizer.Form.NFC)
                    val elements = runCatching { nodes.elementsOf(layer).elements }
                        .onFailure { nodes.note("error:${it.javaClass.simpleName}") }.getOrNull() ?: continue
                    glyph(name, layer, elements)?.let { glyphs[name] = it }
                }
                nodes.unsupportedCounts.forEach { (k, v) -> unsupported[k] = (unsupported[k] ?: 0) + v }
            }
            val probe = InkStitchFont(info, glyphs, 0.0, unsupported)
            // Ink/Stitch aligns by the guide. All glyphs share it, so only a missing guide needs a stand-in.
            val baseline = guide ?: probe.referenceGlyph?.inkBottom ?: 0.0
            return InkStitchFont(info, glyphs, baseline, unsupported, guide)
        }

        /**
         * Font._set_style, which lettering applies to every rendered glyph: unfilled paths get a
         * 1px stroke, so only multi-rail satins stay satins.
         */
        private fun setStyle(layer: SvgNode) {
            for (node in layer.descendants()) {
                if (node.tag != "path") continue
                val style = SvgGeometry.ownStyle(node)
                if (style["fill"] != "none") continue
                style["stroke-width"] = "1px"
                val dashed = style["stroke-dasharray"]?.let { it != "none" } == true
                if (dashed) style["stroke-dasharray"] = "3, 1"
                node["style"] = SvgGeometry.styleString(style)
            }
        }

        /** glyph.Glyph: extents of every path in the layer except command connectors; ink extents of what is sewn. */
        private fun glyph(name: String, layer: SvgNode, elements: List<InkStitchElement>): InkStitchGlyph? {
            var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
            for (node in layer.descendants()) {
                if (node.tag != "path" || node["inkscape:connection-end"] != null) continue
                val d = node["d"] ?: continue
                val paths = runCatching { GeometrySvgPath.flatten(d, SvgGeometry.nodeTransform(node), TOLERANCE_PX) }.getOrNull() ?: continue
                for (p in paths) for (q in p.points) { minX = min(minX, q.x); maxX = max(maxX, q.x) }
            }
            if (maxX < minX) return null
            var top = Double.POSITIVE_INFINITY; var bottom = Double.NEGATIVE_INFINITY
            fun ink(p: Point) { top = min(top, p.y); bottom = max(bottom, p.y) }
            for (e in elements) when (e) {
                is InkFill -> e.paths.forEach { it.points.forEach(::ink) }
                is InkRun -> e.paths.forEach { it.points.forEach(::ink) }
                is InkSatin -> e.rails.forEach { it.forEach(::ink) }
            }
            return InkStitchGlyph(name, elements, minX, maxX, top.takeIf { it.isFinite() } ?: 0.0, bottom.takeIf { it.isFinite() } ?: 0.0)
        }

        /** svg.guides: the "baseline" guide, stored y-up from the page bottom, in px. */
        private fun baselineGuide(root: SvgNode): Double? {
            val view = root.children.firstOrNull { it.tag == "sodipodi:namedview" } ?: return null
            val guide = view.children.firstOrNull { it.tag == "sodipodi:guide" && it["inkscape:label"] == "baseline" } ?: return null
            val y = guide["position"]?.split(',')?.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
            val box = root["viewBox"]?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }
            val pageHeight = box?.get(3) ?: root["height"]?.let(SvgGeometry::lengthPx) ?: return null
            return SvgGeometry.viewBoxTransform(root).apply(Point(0.0, pageHeight - y)).y
        }
    }
}
