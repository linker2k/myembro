// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

/** Lossless SVG-name attribute codec, shared by project persistence, UI state and oracle input. */
object SatinParametersCodec {
    private val defaults=SatinParameters()
    fun encode(p:SatinParameters):Map<String,String> = buildMap {
        p.satin_method.toString().let { value -> put("satin_method",value) }
        p.zigzag_spacing_mm.toString().let { value -> put("zigzag_spacing_mm",value) }
        p.pull_compensation_mm.let { "${it.first} ${it.second}" }.let { value -> put("pull_compensation_mm",value) }
        p.pull_compensation_percent.let { "${it.first} ${it.second}" }.let { value -> put("pull_compensation_percent",value) }
        p.push_compensation_mm.let { "${it.first} ${it.second}" }.let { value -> put("push_compensation_mm",value) }
        p.reverse_rails.toString().let { value -> put("reverse_rails",value) }
        p.swap_satin_rails.toString().let { value -> put("swap_satin_rails",value) }
        p.short_stitch_inset.joinToString(" ").let { value -> put("short_stitch_inset",value) }
        p.short_stitch_distance_mm.toString().let { value -> put("short_stitch_distance_mm",value) }
        p.split_method.toString().let { value -> put("split_method",value) }
        p.max_stitch_length_mm?.toString().let { value -> if(value!=null) put("max_stitch_length_mm",value) }
        p.split_staggers.toString().let { value -> put("split_staggers",value) }
        p.random_split_jitter_percent.toString().let { value -> put("random_split_jitter_percent",value) }
        p.random_split_phase.toString().let { value -> put("random_split_phase",value) }
        p.min_random_split_length_mm?.toString().let { value -> if(value!=null) put("min_random_split_length_mm",value) }
        p.random_width_decrease_percent.let { "${it.first} ${it.second}" }.let { value -> put("random_width_decrease_percent",value) }
        p.random_width_increase_percent.let { "${it.first} ${it.second}" }.let { value -> put("random_width_increase_percent",value) }
        p.random_zigzag_spacing_percent.toString().let { value -> put("random_zigzag_spacing_percent",value) }
        p.random_seed.toString().let { value -> put("random_seed",value) }
        p.running_stitch_length_mm.toString().let { value -> put("running_stitch_length_mm",value) }
        p.running_stitch_tolerance_mm.toString().let { value -> put("running_stitch_tolerance_mm",value) }
        p.running_stitch_position.toString().let { value -> put("running_stitch_position",value) }
        p.start_at_nearest_point.toString().let { value -> put("start_at_nearest_point",value) }
        p.end_at_nearest_point.toString().let { value -> put("end_at_nearest_point",value) }
        p.center_walk_underlay.toString().let { value -> put("center_walk_underlay",value) }
        p.center_walk_underlay_stitch_length_mm.toString().let { value -> put("center_walk_underlay_stitch_length_mm",value) }
        p.center_walk_underlay_stitch_tolerance_mm.toString().let { value -> put("center_walk_underlay_stitch_tolerance_mm",value) }
        p.center_walk_underlay_repeats.toString().let { value -> put("center_walk_underlay_repeats",value) }
        p.center_walk_underlay_position.toString().let { value -> put("center_walk_underlay_position",value) }
        p.contour_underlay.toString().let { value -> put("contour_underlay",value) }
        p.contour_underlay_stitch_length_mm.toString().let { value -> put("contour_underlay_stitch_length_mm",value) }
        p.contour_underlay_stitch_tolerance_mm.toString().let { value -> put("contour_underlay_stitch_tolerance_mm",value) }
        p.contour_underlay_inset_mm.let { "${it.first} ${it.second}" }.let { value -> put("contour_underlay_inset_mm",value) }
        p.contour_underlay_inset_percent.let { "${it.first} ${it.second}" }.let { value -> put("contour_underlay_inset_percent",value) }
        p.zigzag_underlay.toString().let { value -> put("zigzag_underlay",value) }
        p.zigzag_underlay_spacing_mm.toString().let { value -> put("zigzag_underlay_spacing_mm",value) }
        p.zigzag_underlay_inset_mm?.let { "${it.first} ${it.second}" }.let { value -> if(value!=null) put("zigzag_underlay_inset_mm",value) }
        p.zigzag_underlay_inset_percent?.let { "${it.first} ${it.second}" }.let { value -> if(value!=null) put("zigzag_underlay_inset_percent",value) }
        p.zigzag_underlay_max_stitch_length_mm?.toString().let { value -> if(value!=null) put("zigzag_underlay_max_stitch_length_mm",value) }
        p.min_stitch_length_mm?.toString().let { value -> if(value!=null) put("min_stitch_length_mm",value) }
        p.min_jump_stitch_length_mm?.toString().let { value -> if(value!=null) put("min_jump_stitch_length_mm",value) }
    }
    fun decode(attributes:Map<String,String>):SatinParameters {
        fun text(name:String)=attributes[name]
        fun numbers(value:String)=value.trim().split(Regex("[\\s,]+")).filter(String::isNotBlank).map(String::toDouble)
        fun pair(value:String):Pair<Double,Double> = numbers(value).let { require(it.size in 1..2);it.first() to it.last() }
        return SatinParameters(
            satin_method=text("satin_method") ?: defaults.satin_method,
            zigzag_spacing_mm=text("zigzag_spacing_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.zigzag_spacing_mm,
            pull_compensation_mm=text("pull_compensation_mm")?.let(::pair) ?: defaults.pull_compensation_mm,
            pull_compensation_percent=text("pull_compensation_percent")?.let(::pair) ?: defaults.pull_compensation_percent,
            push_compensation_mm=text("push_compensation_mm")?.let(::pair) ?: defaults.push_compensation_mm,
            reverse_rails=text("reverse_rails") ?: defaults.reverse_rails,
            swap_satin_rails=text("swap_satin_rails")?.toBooleanStrict() ?: defaults.swap_satin_rails,
            short_stitch_inset=text("short_stitch_inset")?.let(::numbers) ?: defaults.short_stitch_inset,
            short_stitch_distance_mm=text("short_stitch_distance_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.short_stitch_distance_mm,
            split_method=text("split_method") ?: defaults.split_method,
            max_stitch_length_mm=text("max_stitch_length_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.max_stitch_length_mm,
            split_staggers=text("split_staggers")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.split_staggers,
            random_split_jitter_percent=text("random_split_jitter_percent")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.random_split_jitter_percent,
            random_split_phase=text("random_split_phase")?.toBooleanStrict() ?: defaults.random_split_phase,
            min_random_split_length_mm=text("min_random_split_length_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.min_random_split_length_mm,
            random_width_decrease_percent=text("random_width_decrease_percent")?.let(::pair) ?: defaults.random_width_decrease_percent,
            random_width_increase_percent=text("random_width_increase_percent")?.let(::pair) ?: defaults.random_width_increase_percent,
            random_zigzag_spacing_percent=text("random_zigzag_spacing_percent")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.random_zigzag_spacing_percent,
            random_seed=text("random_seed") ?: defaults.random_seed,
            running_stitch_length_mm=text("running_stitch_length_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.running_stitch_length_mm,
            running_stitch_tolerance_mm=text("running_stitch_tolerance_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.running_stitch_tolerance_mm,
            running_stitch_position=text("running_stitch_position")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.running_stitch_position,
            start_at_nearest_point=text("start_at_nearest_point")?.toBooleanStrict() ?: defaults.start_at_nearest_point,
            end_at_nearest_point=text("end_at_nearest_point")?.toBooleanStrict() ?: defaults.end_at_nearest_point,
            center_walk_underlay=text("center_walk_underlay")?.toBooleanStrict() ?: defaults.center_walk_underlay,
            center_walk_underlay_stitch_length_mm=text("center_walk_underlay_stitch_length_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.center_walk_underlay_stitch_length_mm,
            center_walk_underlay_stitch_tolerance_mm=text("center_walk_underlay_stitch_tolerance_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.center_walk_underlay_stitch_tolerance_mm,
            center_walk_underlay_repeats=text("center_walk_underlay_repeats")?.toInt() ?: defaults.center_walk_underlay_repeats,
            center_walk_underlay_position=text("center_walk_underlay_position")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.center_walk_underlay_position,
            contour_underlay=text("contour_underlay")?.toBooleanStrict() ?: defaults.contour_underlay,
            contour_underlay_stitch_length_mm=text("contour_underlay_stitch_length_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.contour_underlay_stitch_length_mm,
            contour_underlay_stitch_tolerance_mm=text("contour_underlay_stitch_tolerance_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.contour_underlay_stitch_tolerance_mm,
            contour_underlay_inset_mm=text("contour_underlay_inset_mm")?.let(::pair) ?: defaults.contour_underlay_inset_mm,
            contour_underlay_inset_percent=text("contour_underlay_inset_percent")?.let(::pair) ?: defaults.contour_underlay_inset_percent,
            zigzag_underlay=text("zigzag_underlay")?.toBooleanStrict() ?: defaults.zigzag_underlay,
            zigzag_underlay_spacing_mm=text("zigzag_underlay_spacing_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.zigzag_underlay_spacing_mm,
            zigzag_underlay_inset_mm=text("zigzag_underlay_inset_mm")?.let(::pair) ?: defaults.zigzag_underlay_inset_mm,
            zigzag_underlay_inset_percent=text("zigzag_underlay_inset_percent")?.let(::pair) ?: defaults.zigzag_underlay_inset_percent,
            zigzag_underlay_max_stitch_length_mm=text("zigzag_underlay_max_stitch_length_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.zigzag_underlay_max_stitch_length_mm,
            min_stitch_length_mm=text("min_stitch_length_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.min_stitch_length_mm,
            min_jump_stitch_length_mm=text("min_jump_stitch_length_mm")?.takeIf(String::isNotBlank)?.toDouble() ?: defaults.min_jump_stitch_length_mm,
        )
    }
}
