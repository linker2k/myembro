// SPDX-License-Identifier: GPL-3.0-or-later
// Original myembro automatic image classification and SVG handoff, not a source port.
// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/stroke.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/satin_column.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Only SVG parameter names/defaults are transcribed; tracing/classification is original.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import org.locationtech.jts.algorithm.MinimumDiameter
import org.locationtech.jts.geom.Polygon
import java.util.Locale
import kotlin.math.*

enum class ImageStitchType { FILL, SATIN, RUNNING }

/** A geometry request, not machine stitches. Consumers must apply their StitchGroup/plan finisher. */
data class ImageElement(
    val region: ImageVectorRegion,
    val type: ImageStitchType,
    val centerLine: Polyline? = null,
    val rails: Pair<Polyline, Polyline>? = null,
    /** Keys omit only the `inkstitch:` namespace prefix. Length values are mm, angle is degrees. */
    val parameters: Map<String, String>,
)

data class ImageEmbroideryDraft(val vector: VectorizedImage, val elements: List<ImageElement>) {
    /** Integration hook until the shared EmbroideryElement factory is available on main. */
    fun <T> toElements(factory: (ImageElement) -> T): List<T> = elements.map(factory)

    /** Valid SVG handoff, one path per element. viewBox makes one user unit exactly one mm. */
    fun toSvg(): String = buildString {
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:inkstitch=\"http://inkstitch.org/namespace\"")
        append(" width=\"${number(vector.widthMm)}mm\" height=\"${number(vector.heightMm)}mm\"")
        append(" viewBox=\"0 0 ${number(vector.widthMm)} ${number(vector.heightMm)}\">\n")
        for (element in elements) {
            val color = String.format(Locale.ROOT, "#%06X", element.region.color.rgb)
            val paths = when (element.type) {
                ImageStitchType.FILL -> element.region.geometry.boundaries()
                ImageStitchType.SATIN -> listOf(element.rails!!.first, element.rails.second)
                ImageStitchType.RUNNING -> listOf(element.centerLine!!)
            }
            val d = paths.joinToString(" ") { path ->
                path.points.mapIndexed { i, p -> "${if (i == 0) "M" else "L"}${number(p.x / 10)},${number(p.y / 10)}" }
                    .joinToString(" ") + if (path.closed) " Z" else ""
            }
            append("<path id=\"image-region-${element.region.id}\" d=\"$d\"")
            if (element.type == ImageStitchType.FILL) append(" fill=\"$color\" fill-rule=\"evenodd\" stroke=\"none\"")
            else append(" fill=\"none\" stroke=\"$color\" stroke-width=\"0.1\"")
            element.parameters.toSortedMap().forEach { (name, value) ->
                require(name.matches(Regex("[a-z][a-z0-9_]*"))) { "Invalid Ink/Stitch parameter name" }
                val escaped = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
                append(" inkstitch:$name=\"$escaped\"")
            }
            append("/>\n")
        }
        append("</svg>\n")
    }

    companion object {
        // Avoid locale-dependent decimal commas and millimeter rounding of sub-pixel geometry.
        private fun number(value: Double) = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}

object ImagePipeline {
    fun trace(image: RasterImage, options: ImageTraceOptions): ImageEmbroideryDraft {
        val vector = BitmapVectorizer.vectorize(ImageQuantizer.quantize(image, options), options)
        val notices = vector.notices.toMutableSet()
        val elements = vector.regions.map { region ->
            val column = straightColumn(region, options)
            if (column != null) column else {
                val bounds = region.geometry.geometry.envelopeInternal
                if (min(bounds.width, bounds.height) / 10 <= options.satinMaxWidthMm)
                    notices += ImageTraceNotice.COLUMN_REQUIRES_REVIEW
                ImageElement(region, ImageStitchType.FILL, parameters = mapOf(
                    "fill_method" to "tatami_fill", "row_spacing_mm" to "0.25", "max_stitch_length_mm" to "4.0",
                    "staggers" to "4", "angle" to "0"))
            }
        }
        return ImageEmbroideryDraft(vector.copy(notices = notices.toSet()), elements)
    }

    /** Only hole-free, almost rectangular columns with covered centerline/rails are automatic.
     * Curved/branching shapes need a real column skeleton/rail solver; guessing risks bad stitches.
     */
    private fun straightColumn(region: ImageVectorRegion, options: ImageTraceOptions): ImageElement? {
        val g = region.geometry.geometry
        if (g.numGeometries != 1) return null
        val polygon = g.getGeometryN(0) as Polygon
        if (polygon.numInteriorRing != 0) return null
        val rect = MinimumDiameter.getMinimumRectangle(g) as? Polygon ?: return null
        val corners = rect.exteriorRing.coordinates.take(4).map { Point(it.x, it.y) }
        if (corners.size != 4) return null
        val lengths = corners.indices.map { corners[it].distance(corners[(it + 1) % 4]) }
        val short = lengths.indices.minBy { lengths[it] }
        val width = lengths[short]; val length = lengths[(short + 1) % 4]
        if (width <= 0 || length / width < options.minimumColumnAspect || width / 10 > options.satinMaxWidthMm) return null
        if (g.area / (width * length) < 0.98) return null
        val a = corners[short]; val b = corners[(short + 1) % 4]
        val c = corners[(short + 2) % 4]; val d = corners[(short + 3) % 4]
        val start = a.interpolate(b, 0.5); val end = d.interpolate(c, 0.5)
        if (!region.geometry.coversSegment(start, end)) return null
        val line = Polyline(listOf(start, end))
        if (width / 10 <= options.runningMaxWidthMm) return ImageElement(region, ImageStitchType.RUNNING,
            centerLine = line, parameters = mapOf("stroke_method" to "running_stitch", "running_stitch_length_mm" to "2.5"))
        // Require both complete rails inside the source; a nearly rectangular notch must not be crossed.
        if (!region.geometry.coversSegment(a, d) || !region.geometry.coversSegment(b, c)) return null
        return ImageElement(region, ImageStitchType.SATIN, centerLine = line,
            rails = Polyline(listOf(a, d)) to Polyline(listOf(b, c)),
            parameters = mapOf("satin_column" to "true", "zigzag_spacing_mm" to "0.4", "running_stitch_length_mm" to "2.5"))
    }
}
