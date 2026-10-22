// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.lettering

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.FontKind
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.model.EmbThread
import java.text.Normalizer

/** Android-only font outline extraction. The app's minSdk is 26 (Path.approximate). */
object AndroidGlyphOutlines {
    const val JUA_FONT_ID = "jua"

    fun jua(context: Context): Typeface = context.resources.getFont(R.font.jua_regular)

    /**
     * Returns closed glyph contours, including counters (holes), in source pixels.
     * First baseline is y=0, +y down. [spacing] is extra space in source pixels,
     * [lineSpacing] is a multiplier of [textSize]. NFC composes Hangul jamo first.
     * Lines are centered; tabs become four spaces. No system/network font download.
     */
    fun extract(
        text: String,
        typeface: Typeface,
        textSize: Float = 1000f,
        spacing: Float = 0f,
        acceptableError: Float = 0.5f,
        lineSpacing: Float = 1.5f,
    ): List<List<Pair<Double, Double>>> {
        require(textSize.isFinite() && textSize > 0)
        require(spacing.isFinite() && spacing >= 0)
        require(acceptableError.isFinite() && acceptableError > 0)
        require(lineSpacing.isFinite() && lineSpacing > 0)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.textSize = textSize
            letterSpacing = spacing / textSize
        }
        val lines = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')
        val result = mutableListOf<List<Pair<Double, Double>>>()
        lines.forEachIndexed { index, line ->
            if (line.isNotEmpty()) {
                val path = Path()
                paint.getTextPath(line, 0, line.length, -paint.measureText(line) / 2, index * textSize * lineSpacing, path)
                result += splitContours(path.approximate(acceptableError))
            }
        }
        return result.toList()
    }

    /** Equal path fractions mark moveTo, including transitions to inner glyph contours. */
    internal fun splitContours(approximation: FloatArray): List<List<Pair<Double, Double>>> {
        require(approximation.size % 3 == 0)
        val result = mutableListOf<List<Pair<Double, Double>>>()
        var current = mutableListOf<Pair<Double, Double>>()
        var previousFraction = -1f
        for (i in approximation.indices step 3) {
            val fraction = approximation[i]
            val point = approximation[i + 1].toDouble() to approximation[i + 2].toDouble()
            if (fraction == previousFraction) {
                if (current.size >= 2) result += current.toList()
                current = mutableListOf()
            }
            if (current.lastOrNull() != point) current += point
            previousFraction = fraction
        }
        if (current.size >= 2) result += current.toList()
        return result.toList()
    }

    /** Ready-to-add outline object using the bundled Jua font. Regenerate after text/spacing edits. */
    fun textObject(
        context: Context,
        id: String,
        text: String,
        heightMm: Double = 10.0,
        spacing: Double = 1.0,
        arcDeg: Double = 0.0,
        thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
    ): TextObject {
        require(heightMm.isFinite() && heightMm > 0)
        require(spacing.isFinite() && spacing >= 0)
        val face = jua(context)
        // Reference ink height keeps cap size stable for different strings/multiple lines.
        val reference = extract("자", face).flatten().let { points ->
            points.maxOf { it.second } - points.minOf { it.second }
        }
        val outlines = extract(text, face, spacing = (spacing / heightMm * reference).toFloat())
        return TextObject(
            id = id, name = text.take(32).ifBlank { "Text" }, text = text,
            fontId = JUA_FONT_ID, heightMm = heightMm, spacing = spacing, arcDeg = arcDeg,
            thread = thread, fontKind = FontKind.OUTLINE, contours = outlines,
            contourSourceHeight = reference,
        )
    }
}
