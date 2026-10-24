// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/element.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import kotlin.math.max

/** SVG attribute values retain their names/units; only the computed *Px properties convert to pixels. */
data class FillParameters(
    val fill: Boolean = true,
    val fill_method: String = "tatami_fill",
    val angle: Double = 0.0,
    val row_spacing_mm: Double = 0.25,
    val end_row_spacing_mm: Double? = null,
    val max_stitch_length_mm: Double = 4.0,
    val staggers: Double = 4.0,
    val skip_last: Boolean = false,
    val flip: Boolean = false,
    val reverse: Boolean = false,
    val expand_mm: Double = 0.0,
    val underpath: Boolean = true,
    val gap_fill_rows: Int = 0,
    val running_stitch_length_mm: Double = 2.5,
    // Upstream's getter defaults to 0.2mm despite the UI decorator showing 0.1mm.
    val running_stitch_tolerance_mm: Double = 0.2,
    val fill_underlay: Boolean = true,
    val fill_underlay_angle: String? = null,
    val fill_underlay_row_spacing_mm: Double? = null,
    val fill_underlay_max_stitch_length_mm: Double? = null,
    val fill_underlay_inset_mm: Double = 0.0,
    val fill_underlay_skip_last: Boolean = false,
    val underlay_underpath: Boolean = true,
    val pull_compensation_mm: List<Double> = listOf(0.0, 0.0),
    val pull_compensation_percent: List<Double> = listOf(0.0, 0.0),
    val enable_random_stitch_length: Boolean = false,
    val random_stitch_length_jitter_percent: Double = 10.0,
    val random_seed: String = "",
    val min_stitch_length_mm: Double? = null,
    val min_jump_stitch_length_mm: Double? = null,
    val ties: Int = 0,
    val force_lock_stitches: Boolean = false,
    val lock_start: String = "half_stitch",
    val lock_end: String = "half_stitch",
    val lock_start_scale_mm: Double = 0.7,
    val lock_end_scale_mm: Double = 0.7,
    val lock_start_scale_percent: Double = 100.0,
    val lock_end_scale_percent: Double = 100.0,
    val lock_custom_start: String? = null,
    val lock_custom_end: String? = null,
    val trim_after: Boolean = false,
    val stop_after: Boolean = false,
) {
    init {
        require(fill_method.isNotBlank() && lock_start.isNotBlank() && lock_end.isNotBlank())
        require(listOf(angle, row_spacing_mm, max_stitch_length_mm, staggers, expand_mm,
            running_stitch_length_mm, running_stitch_tolerance_mm, fill_underlay_inset_mm,
            random_stitch_length_jitter_percent, lock_start_scale_mm, lock_end_scale_mm,
            lock_start_scale_percent, lock_end_scale_percent).all { it.isFinite() })
        require(listOfNotNull(end_row_spacing_mm, fill_underlay_row_spacing_mm,
            fill_underlay_max_stitch_length_mm, min_stitch_length_mm,
            min_jump_stitch_length_mm).all { it.isFinite() })
        require(fill_underlay_row_spacing_mm == null || fill_underlay_row_spacing_mm >= 0)
        require(fill_underlay_max_stitch_length_mm == null || fill_underlay_max_stitch_length_mm >= 0)
        require(min_stitch_length_mm == null || min_stitch_length_mm >= 0)
        require(min_jump_stitch_length_mm == null || min_jump_stitch_length_mm >= 0)
        require(pull_compensation_mm.size in 1..2 && pull_compensation_mm.all { it.isFinite() })
        require(pull_compensation_percent.size in 1..2 && pull_compensation_percent.all { it.isFinite() })
        require(staggers >= 0 && gap_fill_rows >= 0 && ties in 0..3)
        require(lock_start_scale_mm >= 0 && lock_end_scale_mm >= 0 &&
            lock_start_scale_percent >= 0 && lock_end_scale_percent >= 0)
        require(!enable_random_stitch_length) { "Ink/Stitch seeded random stitch lengths are not ported" }
    }

    val angleRadians: Double get() = Math.toRadians(angle)
    val rowSpacingPx: Double get() = max(row_spacing_mm, 0.1) * PIXELS_PER_MM
    val endRowSpacingPx: Double? get() = end_row_spacing_mm?.takeIf { it != 0.0 }?.let { max(it, 0.0) * PIXELS_PER_MM }
    val maxStitchLengthPx: Double get() = max(max_stitch_length_mm, 0.1) * PIXELS_PER_MM
    // The source clamps these two to 0.01 pixels, not 0.01 millimetres.
    val runningLengthPx: Double get() = max(running_stitch_length_mm * PIXELS_PER_MM, 0.01)
    val runningTolerancePx: Double get() = max(running_stitch_tolerance_mm * PIXELS_PER_MM, 0.01)
    val expandPx: Double get() = expand_mm * PIXELS_PER_MM
    val underlayInsetPx: Double get() = fill_underlay_inset_mm * PIXELS_PER_MM
    val underlayRowSpacingPx: Double get() = fill_underlay_row_spacing_mm?.takeIf { it != 0.0 }
        ?.times(PIXELS_PER_MM) ?: (rowSpacingPx * 3)
    val underlayMaxStitchLengthPx: Double get() = fill_underlay_max_stitch_length_mm?.takeIf { it != 0.0 }
        ?.times(PIXELS_PER_MM) ?: maxStitchLengthPx
    val pullCompensationPx: List<Double> get() = pair(pull_compensation_mm).map { max(it, 0.0) * PIXELS_PER_MM }
    val pullCompensationRatio: List<Double> get() = pair(pull_compensation_percent).map { max(it, 0.0) / 100 }
    val randomStitchLengthJitter: Double get() = max(random_stitch_length_jitter_percent, 0.0) / 100

    /** Original space tokenizer, including backward-compatible trailing commas and invalid-list fallback. */
    fun resolvedUnderlayAnglesDegrees(gradientAngleDegrees: Double? = null): List<Double> {
        val fallback = listOf(angle + 90)
        val raw = fill_underlay_angle?.trim()?.takeIf { it.isNotEmpty() }
        if (raw != null) {
            val tokens = raw.split(' ').map { it.removeSuffix(",").toDoubleOrNull() }
            if (tokens.any { it == null || !it.isFinite() }) return fallback
            return tokens.map { it!! }
        }
        if (fill_method == "linear_gradient_fill" && gradientAngleDegrees != null) {
            require(gradientAngleDegrees.isFinite())
            return listOf(-gradientAngleDegrees)
        }
        return fallback
    }

    private fun pair(values: List<Double>) = if (values.size == 1) listOf(values[0], values[0]) else values

    companion object {
        /** Read unprefixed or inkstitch:* keys; malformed scalar values use the upstream defaults. */
        fun fromSvgAttributes(attributes: Map<String, String>): FillParameters {
            fun text(name: String): String? = (attributes["inkstitch:$name"] ?: attributes[name])?.trim()?.takeIf { it.isNotEmpty() }
            fun number(name: String, default: Double): Double = text(name)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: default
            fun optional(name: String): Double? = text(name)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            fun bool(name: String, default: Boolean): Boolean = text(name)?.lowercase()?.let { it in setOf("yes", "y", "true", "t", "1") } ?: default
            fun integer(name: String, default: Int): Int = text(name)?.toIntOrNull() ?: default
            fun pair(name: String): List<Double> {
                val tokens = text(name)?.split(Regex("\\s+")) ?: return listOf(0.0, 0.0)
                val first = tokens.firstOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return listOf(0.0, 0.0)
                val second = if (tokens.size == 1) first else tokens[1].toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: return listOf(0.0, 0.0)
                return listOf(first, second)
            }
            return FillParameters(
                fill = bool("fill", true), fill_method = text("fill_method") ?: "tatami_fill",
                angle = number("angle", 0.0), row_spacing_mm = number("row_spacing_mm", 0.25),
                end_row_spacing_mm = optional("end_row_spacing_mm"), max_stitch_length_mm = number("max_stitch_length_mm", 4.0),
                staggers = number("staggers", 4.0), skip_last = bool("skip_last", false),
                flip = bool("flip", false), reverse = bool("reverse", false), expand_mm = number("expand_mm", 0.0),
                underpath = bool("underpath", true), gap_fill_rows = integer("gap_fill_rows", 0),
                running_stitch_length_mm = number("running_stitch_length_mm", 2.5),
                running_stitch_tolerance_mm = number("running_stitch_tolerance_mm", 0.2),
                fill_underlay = bool("fill_underlay", true), fill_underlay_angle = text("fill_underlay_angle"),
                fill_underlay_row_spacing_mm = optional("fill_underlay_row_spacing_mm"),
                fill_underlay_max_stitch_length_mm = optional("fill_underlay_max_stitch_length_mm"),
                fill_underlay_inset_mm = number("fill_underlay_inset_mm", 0.0),
                fill_underlay_skip_last = bool("fill_underlay_skip_last", false),
                underlay_underpath = bool("underlay_underpath", true),
                pull_compensation_mm = pair("pull_compensation_mm"), pull_compensation_percent = pair("pull_compensation_percent"),
                enable_random_stitch_length = bool("enable_random_stitch_length", false),
                random_stitch_length_jitter_percent = number("random_stitch_length_jitter_percent", 10.0),
                random_seed = text("random_seed") ?: "", min_stitch_length_mm = optional("min_stitch_length_mm"),
                min_jump_stitch_length_mm = optional("min_jump_stitch_length_mm"), ties = integer("ties", 0),
                force_lock_stitches = bool("force_lock_stitches", false), lock_start = text("lock_start") ?: "half_stitch",
                lock_end = text("lock_end") ?: "half_stitch", lock_start_scale_mm = number("lock_start_scale_mm", 0.7),
                lock_end_scale_mm = number("lock_end_scale_mm", 0.7),
                lock_start_scale_percent = number("lock_start_scale_percent", 100.0),
                lock_end_scale_percent = number("lock_end_scale_percent", 100.0),
                lock_custom_start = text("lock_custom_start"), lock_custom_end = text("lock_custom_end"),
                trim_after = bool("trim_after", false), stop_after = bool("stop_after", false),
            )
        }
    }
}
