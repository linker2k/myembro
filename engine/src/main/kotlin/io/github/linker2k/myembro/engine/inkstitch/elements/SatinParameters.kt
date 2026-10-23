// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/satin_column.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
@file:Suppress("PropertyName")
package io.github.linker2k.myembro.engine.inkstitch.elements

/** Names are inkstitch:* attributes. Coordinates/lengths are mm; angles and percentages are unscaled. */
data class SatinParameters(
    val satin_method: String = "satin_column",
    val zigzag_spacing_mm: Double = 0.4,
    val pull_compensation_mm: Pair<Double,Double> = 0.0 to 0.0,
    val pull_compensation_percent: Pair<Double,Double> = 0.0 to 0.0,
    val push_compensation_mm: Pair<Double,Double> = 0.0 to 0.0,
    val reverse_rails: String = "automatic",
    val swap_satin_rails: Boolean = false,
    val short_stitch_inset: List<Double> = listOf(15.0),
    val short_stitch_distance_mm: Double = 0.25,
    val split_method: String = "default",
    val max_stitch_length_mm: Double? = null,
    val split_staggers: Double = 4.0,
    val random_split_jitter_percent: Double = 0.0,
    val random_split_phase: Boolean = false,
    val min_random_split_length_mm: Double? = null,
    val random_width_decrease_percent: Pair<Double,Double> = 0.0 to 0.0,
    val random_width_increase_percent: Pair<Double,Double> = 0.0 to 0.0,
    val random_zigzag_spacing_percent: Double = 0.0,
    val random_seed: String = "",
    val running_stitch_length_mm: Double = 2.5,
    // Upstream decorator displays 0.1; the actual property getter defaults to 0.2.
    val running_stitch_tolerance_mm: Double = 0.2,
    val running_stitch_position: Double = 50.0,
    val start_at_nearest_point: Boolean = true,
    val end_at_nearest_point: Boolean = true,
    val center_walk_underlay: Boolean = false,
    val center_walk_underlay_stitch_length_mm: Double = 3.0,
    val center_walk_underlay_stitch_tolerance_mm: Double = 0.2,
    val center_walk_underlay_repeats: Int = 2,
    val center_walk_underlay_position: Double = 50.0,
    val contour_underlay: Boolean = false,
    val contour_underlay_stitch_length_mm: Double = 3.0,
    val contour_underlay_stitch_tolerance_mm: Double = 0.2,
    val contour_underlay_inset_mm: Pair<Double,Double> = 0.4 to 0.4,
    val contour_underlay_inset_percent: Pair<Double,Double> = 0.0 to 0.0,
    val zigzag_underlay: Boolean = false,
    val zigzag_underlay_spacing_mm: Double = 3.0,
    val zigzag_underlay_inset_mm: Pair<Double,Double>? = null,
    val zigzag_underlay_inset_percent: Pair<Double,Double>? = null,
    val zigzag_underlay_max_stitch_length_mm: Double? = null,
    val min_stitch_length_mm: Double = 0.1,
    val min_jump_stitch_length_mm: Double = 3.0,
) {
    init {
        require(satin_method in setOf("satin_column","e_stitch","s_stitch","zigzag"))
        require(reverse_rails in setOf("automatic","none","first","second","both"))
        require(split_method in setOf("default","simple","staggered"))
        val finite = listOf(zigzag_spacing_mm,short_stitch_distance_mm,split_staggers,random_split_jitter_percent,
            random_zigzag_spacing_percent,running_stitch_length_mm,running_stitch_tolerance_mm,running_stitch_position,
            center_walk_underlay_stitch_length_mm,center_walk_underlay_stitch_tolerance_mm,center_walk_underlay_position,
            contour_underlay_stitch_length_mm,contour_underlay_stitch_tolerance_mm,zigzag_underlay_spacing_mm,min_stitch_length_mm,min_jump_stitch_length_mm) + short_stitch_inset +
            listOfNotNull(max_stitch_length_mm,min_random_split_length_mm,zigzag_underlay_max_stitch_length_mm) +
            listOfNotNull(pull_compensation_mm,pull_compensation_percent,push_compensation_mm,random_width_decrease_percent,
                random_width_increase_percent,contour_underlay_inset_mm,contour_underlay_inset_percent,
                zigzag_underlay_inset_mm,zigzag_underlay_inset_percent).flatMap { listOf(it.first,it.second) }
        require(finite.all(Double::isFinite))
        require(split_staggers > 0 && center_walk_underlay_repeats in 1..100)
        require(listOfNotNull(max_stitch_length_mm,min_random_split_length_mm,zigzag_underlay_max_stitch_length_mm).all { it > 0 })
    }
}
