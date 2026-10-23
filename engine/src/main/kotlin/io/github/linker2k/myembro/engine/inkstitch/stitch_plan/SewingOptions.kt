// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/element.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.model.EmbPattern
import kotlinx.serialization.json.*

/** Explicit source-compatible sewing settings; public length attributes are millimetres. */
data class SewingOptions(
    val locks: LockStitchParameters = LockStitchParameters(),
    val min_stitch_length_mm: Double = 0.1,
    val min_jump_stitch_length_mm: Double = 3.0,
    val trim_after: Boolean = false,
) {
    init {
        require(min_stitch_length_mm.isFinite() && min_stitch_length_mm >= 0)
        require(min_jump_stitch_length_mm.isFinite() && min_jump_stitch_length_mm >= 0)
        require(locks.ties in 0..3)
        require(listOf(locks.lock_start_scale_mm, locks.lock_end_scale_mm, locks.lock_start_scale_percent, locks.lock_end_scale_percent).all { it.isFinite() })
    }
    fun toJson(): String = buildJsonObject {
        put("min_stitch_length_mm", min_stitch_length_mm); put("min_jump_stitch_length_mm", min_jump_stitch_length_mm); put("trim_after", trim_after)
        put("ties", locks.ties); put("force_lock_stitches", locks.force_lock_stitches)
        put("lock_start", locks.lock_start); put("lock_end", locks.lock_end)
        put("lock_custom_start", locks.lock_custom_start); put("lock_custom_end", locks.lock_custom_end)
        put("lock_start_scale_mm", locks.lock_start_scale_mm); put("lock_end_scale_mm", locks.lock_end_scale_mm)
        put("lock_start_scale_percent", locks.lock_start_scale_percent); put("lock_end_scale_percent", locks.lock_end_scale_percent)
    }.toString()

    companion object {
        fun fromJson(text: String): SewingOptions {
            val value = Json.parseToJsonElement(text).jsonObject
            fun str(key: String, fallback: String) = value[key]?.jsonPrimitive?.content ?: fallback
            fun num(key: String, fallback: Double) = value[key]?.jsonPrimitive?.double ?: fallback
            fun bool(key: String, fallback: Boolean) = value[key]?.jsonPrimitive?.boolean ?: fallback
            return SewingOptions(
                LockStitchParameters(
                    ties = value["ties"]?.jsonPrimitive?.int ?: 0, force_lock_stitches = bool("force_lock_stitches", false),
                    lock_start = str("lock_start", "half_stitch"), lock_end = str("lock_end", "half_stitch"),
                    lock_custom_start = str("lock_custom_start", ""), lock_custom_end = str("lock_custom_end", ""),
                    lock_start_scale_mm = num("lock_start_scale_mm", 0.7), lock_end_scale_mm = num("lock_end_scale_mm", 0.7),
                    lock_start_scale_percent = num("lock_start_scale_percent", 100.0), lock_end_scale_percent = num("lock_end_scale_percent", 100.0),
                ), num("min_stitch_length_mm", 0.1), num("min_jump_stitch_length_mm", 3.0), bool("trim_after", false),
            )
        }
    }
}

/** Counts actual applied lock patterns (start/end), never individual lock needle penetrations. */
data class ProcessingSummary(val lockLocations: Int = 0, val shortStitchesRemoved: Int = 0) {
    init { require(lockLocations >= 0 && shortStitchesRemoved >= 0) }
    operator fun plus(other: ProcessingSummary) = ProcessingSummary(lockLocations + other.lockLocations, shortStitchesRemoved + other.shortStitchesRemoved)
    internal fun metadata(): Map<String, String> = mapOf(LOCKS_KEY to lockLocations.toString(), REMOVED_KEY to shortStitchesRemoved.toString())
    companion object {
        const val COMPOSITE_KEY = "myembro.inkstitch.composite"
        private const val LOCKS_KEY = "myembro.inkstitch.summary.lock_locations"
        private const val REMOVED_KEY = "myembro.inkstitch.summary.short_stitches_removed"
        fun fromPattern(pattern: EmbPattern): ProcessingSummary? {
            if (pattern.metadata[GeneratedPatternProcessor.FINISHED_KEY] != "v1" && pattern.metadata[COMPOSITE_KEY] != "v1") return null
            val locks = pattern.metadata[LOCKS_KEY]?.toIntOrNull() ?: return null
            val removed = pattern.metadata[REMOVED_KEY]?.toIntOrNull() ?: return null
            return if (locks >= 0 && removed >= 0) ProcessingSummary(locks, removed) else null
        }
    }
}
