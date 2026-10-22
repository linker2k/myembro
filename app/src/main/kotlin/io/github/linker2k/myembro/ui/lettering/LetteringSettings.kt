// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.Transform

internal enum class LetteringAlignment { LEFT, CENTER, RIGHT }
internal enum class LetteringCurve { STRAIGHT, ARCH, CIRCLE }

internal data class LetteringSettings(
    val text: String,
    val fontId: String,
    val heightMm: Double,
    val spacingMm: Double,
    val alignment: LetteringAlignment,
    val curved: Boolean,
    val curve: LetteringCurve,
    val curvature: Double,
    val arcDirection: Double = 1.0,
    val arcOverride: Double? = null,
) {
    val arcDeg: Double get() = arcOverride ?: (when {
        !curved || curve == LetteringCurve.STRAIGHT -> 0.0
        curve == LetteringCurve.CIRCLE -> 180.0 + curvature.coerceIn(0.0, 100.0) * 1.8
        else -> curvature.coerceIn(0.0, 100.0) * 1.8
    }) * arcDirection
}

internal fun TextObject.letteringSettings(): LetteringSettings {
    val magnitude = kotlin.math.abs(arcDeg)
    val circle = magnitude > 180
    return LetteringSettings(text, fontId, heightMm, spacing, LetteringAlignment.CENTER, magnitude > 0,
        if (circle) LetteringCurve.CIRCLE else LetteringCurve.ARCH,
        (if (circle) (magnitude - 180) / 1.8 else magnitude / 1.8).coerceIn(0.0, 100.0),
        if (arcDeg < 0) -1.0 else 1.0, arcDeg)
}

/** Extra offset for lines shorter than the longest one; inputs share the same font units. */
internal fun lineOffset(width: Double, maximum: Double, alignment: LetteringAlignment): Double = when (alignment) {
    LetteringAlignment.LEFT -> -(maximum - width) / 2
    LetteringAlignment.CENTER -> 0.0
    LetteringAlignment.RIGHT -> (maximum - width) / 2
}

/** Align the final centered local pattern inside the hoop, keeping a 5 mm margin. */
internal fun placeLettering(text: TextObject, hoop: HoopSpec, alignment: LetteringAlignment): TextObject {
    val bounds = text.localPattern().bounds ?: return text
    val room = ((hoop.width - bounds.width) / 2.0 - 50.0).coerceAtLeast(0.0)
    val x = when (alignment) {
        LetteringAlignment.LEFT -> -room
        LetteringAlignment.CENTER -> 0.0
        LetteringAlignment.RIGHT -> room
    }
    return text.copy(transform = Transform(x = x))
}

/** Saved paths can be reused without loading a font when character layout is unchanged. */
internal fun reuseSavedLettering(original: TextObject, settings: LetteringSettings,
    thread: io.github.linker2k.myembro.engine.model.EmbThread): TextObject? {
    if (settings.text != original.text || settings.fontId != original.fontId ||
        settings.spacingMm != original.spacing || settings.alignment != LetteringAlignment.CENTER) return null
    if (settings.heightMm == original.heightMm && settings.arcDeg == original.arcDeg && thread == original.thread) return original
    return original.copy(heightMm = settings.heightMm, arcDeg = settings.arcDeg, thread = thread)
}
