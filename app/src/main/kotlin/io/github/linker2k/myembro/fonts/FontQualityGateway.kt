// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import io.github.linker2k.myembro.engine.lettering.FontQuality
import io.github.linker2k.myembro.engine.lettering.FontQualityReport
import io.github.linker2k.myembro.engine.lettering.Grade

/*
 * App-side entry to the engine's FontQuality (docs/lettering.md, "글꼴 품질").
 * Coordinates are mm, normalized to the requested height; one inner list per glyph.
 */

typealias QualityGrade = Grade
typealias QualityReport = FontQualityReport

/** Reason keys the app adds itself; the engine's reasons are display sentences shown as-is. */
object QualityReasons {
    const val MISSING_HANGUL = "missing_hangul"
    const val MISSING_LATIN = "missing_latin"
}

object FontQualityGateway {
    /** The fixed analysis sample of a font: Hangul when it has Hangul, otherwise Latin. */
    fun sample(hangul: Boolean): String = FontQuality.sample(hangul)

    fun analyze(
        glyphContours: List<List<List<Pair<Double, Double>>>>, heightMm: Double, outline: Boolean, hangul: Boolean,
    ): QualityReport = FontQuality.analyze(
        glyphContours, heightMm, outline,
        detailFloorMm = if (hangul) FontQuality.HANGUL_DETAIL_MM else FontQuality.LATIN_DETAIL_MM,
    )
}
