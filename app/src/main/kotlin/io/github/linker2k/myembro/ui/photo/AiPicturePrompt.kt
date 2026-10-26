// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import java.util.Locale

/** Pure transport text, not a stitch algorithm or a sewing guarantee. */
object AiPicturePrompt {
    const val DEFAULT_COLORS = 2
    fun build(request: String, hasPhoto: Boolean, hoop: HoopSpec, targetWidthMm: Double,
        colors: Int = DEFAULT_COLORS, palette: List<Int> = emptyList()): String {
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
            appendLine("Output: square 2000×2000 px PNG. Compose the artwork within hoop aspect ratio ${hoop.width}:${hoop.height}; keep all artwork inside the canvas.")
            appendLine("Use exactly $colors solid foreground colors (+ white background), with clean separate regions and bold outlines.")
            if (palette.isNotEmpty()) appendLine("Use foreground colors ONLY from this thread palette: " + palette.distinct().joinToString(", ") { "#%06X".format(Locale.ROOT, it and 0xffffff) } + ". White is the unstitched background.")
            appendLine("No antialiasing, gradients, shadows, shading, photographic detail, texture, dithering or tiny isolated specks.")
            appendLine("Minimum line/detail thickness: ${n(line)} mm at target size = ${n(line / targetWidthMm * 100)}% of artwork width. This is an image-design guide, not a sewing guarantee.")
            appendLine("If the request includes lettering, reproduce those characters exactly, without translation or extra text, in a bold Gothic/sans-serif font.")
            appendLine("USER REQUEST (preserve requested lettering exactly):")
            appendLine(request)
            appendLine("Return a normal PNG picture, not SVG. JPEG is also accepted on import. Share the resulting image to myembro to choose/crop it and run its embroidery checks.")
        }
    }
}
