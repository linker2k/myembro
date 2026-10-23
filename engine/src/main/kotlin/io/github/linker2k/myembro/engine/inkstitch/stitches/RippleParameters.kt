// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/stroke.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import kotlin.math.abs

/** Names and public units match inkstitch:* SVG attributes. Algorithm geometry is SVG px. */
@Suppress("PropertyName")
data class RippleParameters(
    val running_stitch_length_mm: List<Double> = listOf(2.5),
    val running_stitch_tolerance_mm: Double = 0.2,
    val repeats: Int = 1,
    val bean_stitch_repeats: List<Int> = listOf(0),
    val manual_pattern_placement: Boolean = false,
    val enable_random_stitch_length: Boolean = false,
    val random_stitch_length_jitter_percent: Double = 10.0,
    val random_seed: String = "",
    val line_count: Int = 10,
    val min_line_dist_mm: Double? = null,
    // These two defaults follow Python getters, not the differing parameter-dialog defaults.
    val satin_guide_pattern_position: String = "line_count",
    val staggers: Double = 1.0,
    val skip_start: Int = 0,
    val skip_end: Int = 0,
    val flip_copies: Boolean = true,
    val exponent: Double = 1.0,
    val flip_exponent: Boolean = false,
    val reverse: Boolean = false,
    val reverse_rails: String = "automatic",
    val swap_satin_rails: Boolean = false,
    val grid_size_mm: Double = 0.0,
    val grid_first: Boolean = false,
    val scale_axis: Int = 0,
    val scale_start: Double = 100.0,
    val scale_end: Double = 0.0,
    val rotate_ripples: Boolean = true,
    val join_style: Int = 0,
) {
    init {
        require(running_stitch_length_mm.isNotEmpty()) { "Ripple requires a stitch length" }
        require((running_stitch_length_mm + listOf(running_stitch_tolerance_mm,
            random_stitch_length_jitter_percent, staggers, exponent, grid_size_mm, scale_start, scale_end)
            + listOfNotNull(min_line_dist_mm)).all { it.isFinite() }) { "Non-finite ripple parameter" }
        require(min_line_dist_mm == null || min_line_dist_mm >= 0.0) { "Negative minimum ripple line distance" }
        require(skip_start != Int.MIN_VALUE && skip_end != Int.MIN_VALUE)
        require(scale_axis in 0..3 && join_style in 0..1) { "Invalid ripple axis/join style" }
    }
    internal val lengthsPx get() = running_stitch_length_mm.map { Units.mmToPixels(it).coerceAtLeast(0.01) }
    internal val tolerancePx get() = Units.mmToPixels(running_stitch_tolerance_mm).coerceAtLeast(0.01)
    internal val minDistancePx get() = min_line_dist_mm?.let(Units::mmToPixels)?.takeUnless { it == 0.0 }
    internal val gridPx get() = abs(Units.mmToPixels(grid_size_mm))
    internal val jitter get() = random_stitch_length_jitter_percent.coerceAtLeast(0.0) / 100.0

    companion object {
        /** Element IDs are the upstream default seed when random_seed is empty. */
        fun fromAttributes(attributes: Map<String, String>, elementId: String = ""): RippleParameters {
            fun raw(key: String) = attributes["{http://inkstitch.org/namespace}$key"]
                ?: attributes["inkstitch:$key"] ?: attributes[key]
            fun text(key: String, default: String) = raw(key) ?: default
            fun number(key: String, default: Double) = raw(key)?.toDoubleOrNull() ?: default
            fun integer(key: String, default: Int) = raw(key)?.toIntOrNull() ?: default
            fun bool(key: String, default: Boolean) = raw(key)?.lowercase()?.let { it in setOf("yes", "y", "true", "t", "1") } ?: default
            fun numbers(key: String, default: Double): List<Double> = raw(key)?.split(' ')?.filter { it.isNotEmpty() }
                ?.map { it.toDoubleOrNull() ?: return listOf(default) } ?: listOf(default)
            fun ints(key: String, default: Int): List<Int> = raw(key)?.split(' ')?.filter { it.isNotEmpty() }
                ?.map { it.toIntOrNull() ?: return listOf(default) } ?: listOf(default)
            return RippleParameters(
                running_stitch_length_mm = numbers("running_stitch_length_mm", 2.5),
                running_stitch_tolerance_mm = number("running_stitch_tolerance_mm", 0.2),
                repeats = integer("repeats", 1).coerceAtLeast(1),
                bean_stitch_repeats = ints("bean_stitch_repeats", 0),
                manual_pattern_placement = bool("manual_pattern_placement", false),
                enable_random_stitch_length = bool("enable_random_stitch_length", false),
                random_stitch_length_jitter_percent = number("random_stitch_length_jitter_percent", 10.0),
                random_seed = text("random_seed", "").ifEmpty { elementId },
                line_count = integer("line_count", 10).coerceAtLeast(1),
                min_line_dist_mm = raw("min_line_dist_mm")?.toDoubleOrNull(),
                satin_guide_pattern_position = text("satin_guide_pattern_position", "line_count"),
                staggers = number("staggers", 1.0),
                skip_start = abs(integer("skip_start", 0)), skip_end = abs(integer("skip_end", 0)),
                flip_copies = bool("flip_copies", true), exponent = number("exponent", 1.0).coerceAtLeast(0.1),
                flip_exponent = bool("flip_exponent", false), reverse = bool("reverse", false),
                reverse_rails = text("reverse_rails", "automatic"), swap_satin_rails = bool("swap_satin_rails", false),
                grid_size_mm = abs(number("grid_size_mm", 0.0)), grid_first = bool("grid_first", false),
                scale_axis = integer("scale_axis", 0), scale_start = number("scale_start", 100.0),
                scale_end = number("scale_end", 0.0), rotate_ripples = bool("rotate_ripples", true),
                join_style = integer("join_style", 0),
            )
        }
    }
}
