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
    val candidates = policy.candidates(fontId, revision, geometry, text)
    val ranges = if (geometry == LetteringGeometry.OUTLINE) limits.outlineRanges(fontId, revision)
        else candidates.map { it.heights }.distinct()
    return AutomaticLetteringState(fontName, policy.method(candidates, heightMm, geometry), ranges)
}
