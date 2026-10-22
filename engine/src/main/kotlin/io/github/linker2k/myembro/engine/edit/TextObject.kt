// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.lettering.HersheyFont
import io.github.linker2k.myembro.engine.lettering.LetteringEngine
import io.github.linker2k.myembro.engine.lettering.TextAlignment
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread

enum class FontKind { HERSHEY, OUTLINE }

/** Editable text. Height and extra character spacing are in mm. */
data class TextObject(
    override val id: String,
    override val name: String = "Text",
    val text: String = "",
    val fontId: String = HersheyFont.DEFAULT_ID,
    val heightMm: Double = 10.0,
    val spacing: Double = 1.0,
    val arcDeg: Double = 0.0,
    val thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
    override val transform: Transform = Transform(),
    override val visible: Boolean = true,
    val fontKind: FontKind = FontKind.HERSHEY,
    /** Precomputed +y-down outlines; Android types never enter the engine. */
    val contours: List<List<Pair<Double, Double>>> = emptyList(),
    val contourBaseline: Double = 0.0,
    val contourSourceHeight: Double? = null,
    val lineSpacing: Double = 1.5,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val arcRadiusMm: Double? = null,
    /** Code-point indices in NFC/normalized text, including spaces and newlines. */
    val characterThreads: Map<Int, EmbThread> = emptyMap(),
    val contourCharacters: List<Int> = emptyList(),
    /** Source-unit baseline for each contour, allowing independent multiline arcs. */
    val contourBaselines: List<Double> = emptyList(),
    /** Also true for imported single-line SVGs stored as precomputed contours. */
    val singleLine: Boolean = fontKind == FontKind.HERSHEY,
    val stitchStyle: StitchStyle = if (singleLine) StitchStyle.BEAN else StitchStyle.RUNNING,
) : DesignObject {
    init {
        require(lineSpacing.isFinite() && lineSpacing >= 1.0)
        require(arcRadiusMm == null || arcRadiusMm.isFinite() && arcRadiusMm > 0)
        require(characterThreads.keys.all { it >= 0 })
        require(contourCharacters.isEmpty() || contourCharacters.size == contours.size)
        require(contourCharacters.all { it >= 0 })
        require(contourBaselines.isEmpty() || contourBaselines.size == contours.size)
        require(contourBaselines.all { it.isFinite() })
        require(fontKind != FontKind.OUTLINE || characterThreads.isEmpty() || contourCharacters.size == contours.size)
    }
    private val pattern: EmbPattern by lazy {
        when (fontKind) {
            FontKind.HERSHEY -> LetteringEngine.generate(text, fontId, heightMm, spacing, arcDeg, thread,
                lineSpacing = lineSpacing, alignment = alignment, arcRadiusMm = arcRadiusMm, characterThreads = characterThreads, stitchStyle = stitchStyle)
            FontKind.OUTLINE -> LetteringEngine.fromContours(
                contours, heightMm, arcDeg = arcDeg, thread = thread,
                baseline = contourBaseline, sourceHeight = contourSourceHeight, arcRadiusMm = arcRadiusMm,
                contourThreads = if (contourCharacters.isEmpty()) emptyList() else contourCharacters.map { characterThreads[it] ?: thread },
                contourBaselines = contourBaselines, stitchStyle = stitchStyle,
            )
        }
    }

    override fun localPattern(): EmbPattern = pattern
    override fun withId(id: String): TextObject = copy(id = id)
    override fun withTransform(t: Transform): TextObject = copy(transform = t)
    override fun withVisible(v: Boolean): TextObject = copy(visible = v)
}
