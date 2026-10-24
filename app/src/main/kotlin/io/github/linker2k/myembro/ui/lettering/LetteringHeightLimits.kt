// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.lettering.*
import kotlinx.serialization.json.*

/** App restriction data, independent of the engine's wider generation/oracle input range. */
internal class LetteringHeightLimits private constructor(private val profiles: Map<String, Profile>) {
    private data class Profile(val sha256: String, val range: ValidatedHeightRange)

    fun outlineRanges(fontId: String, revision: String): List<ValidatedHeightRange> {
        val profile = profiles[fontId]?.takeIf { it.sha256 == revision.substringBefore(':') }
            ?: return emptyList()
        return capRanges(listOf(profile.range))
    }

    companion object {
        const val MAX_MM = 60.0
        const val ASSET = "lettering/ttf-fill-height-ranges.json"

        fun capRanges(ranges: List<ValidatedHeightRange>): List<ValidatedHeightRange> =
            ranges.filter { it.minMm <= MAX_MM }.map { ValidatedHeightRange(it.minMm, minOf(it.maxMm, MAX_MM)) }

        fun decode(data: String): LetteringHeightLimits {
            val root = Json.parseToJsonElement(data).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
            require(root.getValue("appMaxHeightMm").jsonPrimitive.double == MAX_MM)
            val profiles = mutableMapOf<String, Profile>()
            for (entry in root.getValue("profiles").jsonArray) {
                val p = entry.jsonObject
                val id = p.getValue("fontId").jsonPrimitive.content
                val sha = p.getValue("fontSha256").jsonPrimitive.content
                val range = ValidatedHeightRange(p.getValue("minMm").jsonPrimitive.double, p.getValue("maxMm").jsonPrimitive.double)
                require(id.isNotBlank() && sha.matches(Regex("[0-9a-f]{64}")) && range.maxMm <= MAX_MM)
                require(profiles.put(id, Profile(sha, range)) == null) { "Duplicate font: $id" }
            }
            return LetteringHeightLimits(profiles)
        }
    }
}

/** The display, slider and Add guard all consume this capped range, including restored/legacy state. */
internal data class AutomaticLetteringState(
    val fontName: String,
    val style: StitchStyle?,
    private val sourceRanges: List<ValidatedHeightRange>,
    val centerlineSatin: Boolean = false,
    val singleLineWeightEnabled: Boolean = false,
) {
    val heightRanges: List<ValidatedHeightRange> = LetteringHeightLimits.capRanges(sourceRanges)
}

/** Pure counterpart called directly by the Android font-loading entry point. */
internal fun automaticLetteringState(
    fontName: String, fontId: String, revision: String, text: String, heightMm: Double,
    geometry: LetteringGeometry, limits: LetteringHeightLimits,
    inkRange: ClosedFloatingPointRange<Double>? = null,
    policy: AutomaticLetteringPolicy = AutomaticLetteringPolicy.Shared,
): AutomaticLetteringState {
    inkRange?.takeIf { it.start > 0 && it.endInclusive.isFinite() }?.let {
        return AutomaticLetteringState(fontName, StitchStyle.SATIN, listOf(ValidatedHeightRange(it.start, it.endInclusive)))
    }
    if(geometry==LetteringGeometry.OUTLINE && HangulSatin.applies(text))
        return AutomaticLetteringState(fontName,StitchStyle.SATIN,emptyList(),centerlineSatin=true)
    val candidates = policy.candidates(fontId, revision, geometry, text)
    val ranges = if (geometry == LetteringGeometry.OUTLINE) limits.outlineRanges(fontId, revision)
        else candidates.map { it.heights }.distinct()
    // Fill needs a swept height range; an outline font without one (most library TTFs) falls back to the
    // automatic running baseline, which the screen permits without a range, instead of producing nothing.
    val style = policy.method(candidates, heightMm, geometry)
        .let { if (it == StitchStyle.FILL && ranges.isEmpty()) StitchStyle.RUNNING else it }
    return AutomaticLetteringState(fontName, style, ranges)
}

/** Whether the screen may generate at [height]: a validated range must hold it, or the style is the range-free running baseline. */
internal fun letteringHeightPermitted(automatic: AutomaticLetteringState?, height: Double): Boolean {
    val ranges = automatic?.heightRanges.orEmpty()
    return height <= LetteringHeightLimits.MAX_MM && when {
        ranges.isEmpty() -> automatic?.style == StitchStyle.RUNNING || automatic?.centerlineSatin==true
        else -> automatic?.style in listOf(StitchStyle.RUNNING, StitchStyle.SATIN, StitchStyle.FILL) && ranges.any { it.contains(height) }
    }
}

/** A digitized embroidery font owns its repeats; it must never receive another bean layer. */
internal fun singleLineWeightEnabled(singleLine: Boolean, predigitized: Boolean, fontId: String): Boolean =
    singleLine && !predigitized && !fontId.contains("bean", ignoreCase = true)

internal fun letteringStyleForWeight(automatic: AutomaticLetteringState?, bold: Boolean): StitchStyle =
    if (automatic?.singleLineWeightEnabled == true && automatic.style == StitchStyle.RUNNING && bold) StitchStyle.BEAN
    else automatic?.style ?: StitchStyle.RUNNING
