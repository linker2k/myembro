// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/stroke.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import kotlin.math.max

/** Wire names, defaults and public units match inkstitch:* SVG attributes; geometry is SVG px. */
@Suppress("PropertyName")
data class StrokeParameters(
    val stroke_method: String = "running_stitch",
    val repeats: Int = 1,
    val bean_stitch_repeats: List<Int> = listOf(0),
    val running_stitch_length_mm: List<Double> = listOf(2.5),
    val running_stitch_tolerance_mm: Double = 0.2,
    val enable_random_stitch_length: Boolean = false,
    val random_stitch_length_jitter_percent: Double = 10.0,
    val random_seed: String = "",
    val max_stitch_length_mm: Double? = null,
    val zigzag_spacing_mm: List<Double> = listOf(0.4),
    val stroke_pull_compensation_mm: Pair<Double, Double> = 0.0 to 0.0,
    val zigzag_angle: Double = 0.0,
    val force_lock_stitches: Boolean = false,
    val min_stitch_length_mm: Double? = null,
    val min_jump_stitch_length_mm: Double? = null,
    val trim_after: Boolean = false,
    val stop_after: Boolean = false,
) {
    init {
        require(running_stitch_length_mm.isNotEmpty() && running_stitch_length_mm.all { it.isFinite() })
        require(bean_stitch_repeats.isNotEmpty())
        require(zigzag_spacing_mm.isNotEmpty() && zigzag_spacing_mm.all { it.isFinite() })
        require(listOf(running_stitch_tolerance_mm, random_stitch_length_jitter_percent, zigzag_angle,
            stroke_pull_compensation_mm.first, stroke_pull_compensation_mm.second).all { it.isFinite() })
        require(listOfNotNull(max_stitch_length_mm, min_stitch_length_mm, min_jump_stitch_length_mm).all { it.isFinite() })
    }
    val repeatCount: Int get() = max(1, repeats)
    // Upstream clamps after mm -> px conversion, so this lower bound is 0.01px, not 0.01mm.
    val runningStitchLength: List<Double> get() = running_stitch_length_mm.map { max(Units.mmToPixels(it), 0.01) }
    val runningStitchTolerance: Double get() = max(Units.mmToPixels(running_stitch_tolerance_mm), 0.01)
    val randomStitchLengthJitter: Double get() = max(random_stitch_length_jitter_percent, 0.0) / 100
    val maxStitchLength: Double? get() = max_stitch_length_mm?.takeIf { it > 0 }?.let(Units::mmToPixels)
    val zigzagSpacing: List<Double> get() = zigzag_spacing_mm.map { max(Units.mmToPixels(it), 0.01) }
    val pullCompensation: Pair<Double, Double> get() = Units.mmToPixels(stroke_pull_compensation_mm.first) to Units.mmToPixels(stroke_pull_compensation_mm.second)
    val zigzagAngle: Double get() = zigzag_angle.coerceIn(-89.0, 89.0)

    companion object {
        /** Accept expanded namespace, inkstitch:-prefixed or local names, never arbitrary suffixes. */
        fun fromAttributes(attributes: Map<String, String>): StrokeParameters {
            fun raw(name: String): String? = attributes["{http://inkstitch.org/namespace}$name"]
                ?: attributes["inkstitch:$name"] ?: attributes[name]
            fun float(name: String, default: Double?): Double? = raw(name)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: default
            fun int(name: String, default: Int): Int = raw(name)?.toIntOrNull() ?: default
            fun bool(name: String, default: Boolean = false): Boolean = raw(name)?.lowercase()?.let { it in setOf("yes", "y", "true", "t", "1") } ?: default
            fun floats(name: String, default: List<Double>): List<Double> = raw(name)?.let { text ->
                val values = text.split(' ').filter { it.isNotEmpty() }.map { it.toDoubleOrNull()?.takeIf(Double::isFinite) }
                if (values.isEmpty() || values.any { it == null }) default else values.filterNotNull()
            } ?: default
            fun ints(name: String, default: List<Int>): List<Int> = raw(name)?.let { text ->
                val values = text.split(' ').filter { it.isNotEmpty() }.map(String::toIntOrNull)
                if (values.isEmpty() || values.any { it == null }) default else values.filterNotNull()
            } ?: default
            val pull = raw("stroke_pull_compensation_mm")?.trim()?.split(Regex("\\s+"))?.take(2)
                ?.map { it.toDoubleOrNull()?.takeIf(Double::isFinite) }
            val compensation = if (pull.isNullOrEmpty() || pull.any { it == null }) 0.0 to 0.0
                else pull[0]!! to (pull.getOrNull(1) ?: pull[0])!!
            return StrokeParameters(
                stroke_method = raw("stroke_method") ?: "running_stitch",
                repeats = int("repeats", 1), bean_stitch_repeats = ints("bean_stitch_repeats", listOf(0)),
                running_stitch_length_mm = floats("running_stitch_length_mm", listOf(2.5)),
                running_stitch_tolerance_mm = float("running_stitch_tolerance_mm", 0.2)!!,
                enable_random_stitch_length = bool("enable_random_stitch_length"),
                random_stitch_length_jitter_percent = float("random_stitch_length_jitter_percent", 10.0)!!,
                random_seed = raw("random_seed") ?: "", max_stitch_length_mm = float("max_stitch_length_mm", null),
                zigzag_spacing_mm = floats("zigzag_spacing_mm", listOf(0.4)),
                stroke_pull_compensation_mm = compensation, zigzag_angle = float("zigzag_angle", 0.0)!!,
                force_lock_stitches = bool("force_lock_stitches"),
                min_stitch_length_mm = float("min_stitch_length_mm", null),
                min_jump_stitch_length_mm = float("min_jump_stitch_length_mm", null),
                trim_after = bool("trim_after"), stop_after = bool("stop_after"),
            )
        }
    }
}
