// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import java.util.Locale

/** Pure transport text, not a stitch algorithm or a sewing guarantee. */
object AiPicturePrompt {
    const val DEFAULT_COLORS = 2
    enum class Format { PNG, SVG }
    fun build(request: String, hasPhoto: Boolean, hoop: HoopSpec, targetWidthMm: Double,
        colors: Int = DEFAULT_COLORS, palette: List<Int> = emptyList(), format: Format = Format.PNG): String {
        require(request.isNotBlank() && targetWidthMm.isFinite() && targetWidthMm > 0)
        require(colors in 2..6)
        val height = targetWidthMm * hoop.height / hoop.width
        require(targetWidthMm * 10 <= hoop.width - 1 && height * 10 <= hoop.height - 1)
        fun n(value: Double) = String.format(Locale.ROOT, "%.2f", value)
        val line = StitchTypeRule.SATIN_MIN_MM
        return buildString {
            appendLine("Create a flat illustration for machine embroidery, not a photo of embroidery or a stitched texture.")
            appendLine(if (hasPhoto) "Use the attached reference image and the request below." else "No reference image is attached. Create the picture from the request below.")
            appendLine("Hoop: ${n(hoop.width / 10.0)} x ${n(hoop.height / 10.0)} mm. Target artwork: ${n(targetWidthMm)} x ${n(height)} mm.")
            if (format == Format.PNG) appendLine("Output: square 2000×2000 px PNG. Compose the artwork within hoop aspect ratio ${hoop.width}:${hoop.height}; keep all artwork inside the canvas.")
            else appendLine("Output: a flat SVG picture with a square viewBox, only plain paths/basic shapes/groups and solid hex fills/strokes. Convert exact lettering to paths. No scripts, foreignObject, images, references, entities, gradients, filters, masks or stylesheets. Use few elements/simple paths; unsupported or overly complex SVG will be rejected. Compose for hoop aspect ratio ${hoop.width}:${hoop.height}.")
            appendLine("Use exactly $colors solid foreground colors (+ white background), with clean separate regions and bold outlines.")
            if (palette.isNotEmpty()) appendLine("Use foreground colors ONLY from this thread palette: " + palette.distinct().joinToString(", ") { "#%06X".format(Locale.ROOT, it and 0xffffff) } + ". White is the unstitched background.")
            appendLine("No antialiasing, gradients, shadows, shading, photographic detail, texture, dithering or tiny isolated specks.")
            appendLine("Minimum line/detail thickness: ${n(line)} mm at target size = ${n(line / targetWidthMm * 100)}% of artwork width. This is an image-design guide, not a sewing guarantee.")
            appendLine("Do not add any unrequested lettering, decoration, stars, frames or borders. If no lettering is requested, include no letters.")
            val quoted = Regex("\"([^\"]+)\"|“([^”]+)”|'([^']+)'").findAll(request)
                .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() } }.toList()
            if (quoted.isNotEmpty()) appendLine("Lettering must be exactly: " + quoted.joinToString("; ") { "\"$it\"" } + ". Add no other letters.")
            appendLine("If the request includes lettering, reproduce those characters exactly, without translation or extra text, in a bold Gothic/sans-serif font.")
            appendLine("USER REQUEST (preserve requested lettering exactly):")
            appendLine(request)
            appendLine(if (format == Format.PNG) "Return a normal PNG picture. JPEG is also accepted on import. Share the resulting image to myembro to choose/crop it and run its embroidery checks."
                else "Share the resulting SVG file to myembro for direct vector conversion and embroidery checks. If it is rejected or looks wrong, request PNG instead.")
        }
    }
}
