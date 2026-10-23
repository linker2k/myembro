// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.lettering.ValidatedHeightRange
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
