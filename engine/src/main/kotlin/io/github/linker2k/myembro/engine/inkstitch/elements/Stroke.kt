// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/stroke.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.RunningStitch
import io.github.linker2k.myembro.engine.inkstitch.stitches.RippleInput
import io.github.linker2k.myembro.engine.inkstitch.stitches.RippleParameters
import io.github.linker2k.myembro.engine.inkstitch.stitches.RippleStitch
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.math.ceil

/** Already transformed/clipped SVG paths. Stroke width and all path coordinates are SVG px. */
class Stroke(
    val paths: List<List<Point>>,
    val color: EmbThread? = null,
    val parameters: StrokeParameters = StrokeParameters(),
    val strokeWidth: Double = 1.0,
    val closed: Boolean = paths.size == 1 && paths[0].size >= 2 && paths[0].first().distance(paths[0].last()) < 0.05,
    val elementId: String = "",
    val lockStart: LockStitch? = InkLockStitch(),
    val lockEnd: LockStitch? = InkLockStitch(),
    val rippleInput: RippleInput? = null,
    val rippleParameters: RippleParameters? = null,
    val commands: ElementCommands = ElementCommands(),
    private val rippleGenerator: ((Stroke, Stitch?) -> List<StitchGroup>)? = null,
) : EmbroideryElement {
    init {
        require(strokeWidth.isFinite() && strokeWidth >= 0)
        require(paths.sumOf { it.size.toLong() } <= MAX_STITCHES) { "Too many stroke input points" }
    }

    override fun toStitchGroups(previousStitch: Stitch?): List<StitchGroup> {
        if (commands.ignored) return emptyList()
        // EmbroideryElement.embroider applies terminal commands once, after all subpaths.
        val generated = generateStitchGroups(previousStitch)
        requireOutputBudget(generated)
        return commands.applyTo(generated, parameters.trim_after, parameters.stop_after).map { group ->
            // The source facade sets these on every group/stitch, including cloned/custom groups.
            group.copy(
                minStitchLengthMm = parameters.min_stitch_length_mm,
                minJumpLengthMm = parameters.min_jump_stitch_length_mm,
                stitches = group.stitches.map { it.copy(minStitchLengthMm = parameters.min_stitch_length_mm) },
            )
        }
    }

    private fun generateStitchGroups(previousStitch: Stitch?): List<StitchGroup> {
        // _get_paths synthesizes a 1px horizontal segment for a one-node first path.
        val sourcePaths = if (paths.firstOrNull()?.size == 1) {
            val first = paths.first().first()
            listOf(listOf(first, first + Point(1.0, 0.0)))
        } else paths
        if (parameters.stroke_method == "ripple_stitch") {
            rippleGenerator?.let { return it(this, previousStitch) }
            val p = (rippleParameters ?: RippleParameters()).copy(
                running_stitch_length_mm = parameters.running_stitch_length_mm,
                running_stitch_tolerance_mm = parameters.running_stitch_tolerance_mm,
                repeats = parameters.repeatCount,
                bean_stitch_repeats = parameters.bean_stitch_repeats,
                enable_random_stitch_length = parameters.enable_random_stitch_length,
                random_stitch_length_jitter_percent = parameters.random_stitch_length_jitter_percent,
                random_seed = parameters.random_seed.ifEmpty { elementId },
            )
            return RippleStitch.generateGroups(rippleInput ?: RippleInput(sourcePaths), p, group(emptyList(), false))
        }
        val groups = mutableListOf<StitchGroup>()
        var remaining = MAX_STITCHES
        for (path in sourcePaths) {
            if (path.isEmpty()) continue
            require(remaining > 0) { "Stroke output exceeds $MAX_STITCHES stitches" }
            val manual = parameters.stroke_method == "manual_stitch"
            val stitches = when (parameters.stroke_method) {
                "manual_stitch" -> applyMaxStitchLength(path, remaining).map(::Stitch)
                "zigzag_stitch" -> simpleSatin(path, remaining)
                else -> repeated(RunningStitch.runningStitch(
                    path, parameters.runningStitchLength, parameters.runningStitchTolerance,
                    parameters.enable_random_stitch_length, parameters.randomStitchLengthJitter, parameters.random_seed.ifEmpty { elementId },
                ).map(::Stitch), skipJunction = false, budget = remaining)
            }
            val bean = if (parameters.bean_stitch_repeats.any { it != 0 }) {
                // Check before expanding, so the current path cannot consume earlier paths' budget.
                var expanded = if (stitches.isEmpty()) 0L else 1L
                for (i in 1 until stitches.size) {
                    expanded += 1L + 2L * parameters.bean_stitch_repeats[(i - 1) % parameters.bean_stitch_repeats.size].coerceAtLeast(0)
                    require(expanded <= remaining) { "Stroke output exceeds $MAX_STITCHES stitches" }
                }
                RunningStitch.beanStitch(stitches, parameters.bean_stitch_repeats)
            } else stitches
            require(bean.size <= remaining) { "Stroke output exceeds $MAX_STITCHES stitches" }
            groups += group(bean, manual)
            remaining -= bean.size
        }
        return groups
    }

    /** Also validate callback/ripple output before command/minimum propagation copies every stitch. */
    private fun requireOutputBudget(groups: List<StitchGroup>) {
        require(groups.size <= MAX_STITCHES) { "Too many stroke output groups" }
        var remaining = MAX_STITCHES.toLong()
        for (group in groups) {
            remaining -= group.stitches.size
            require(remaining >= 0) { "Stroke output exceeds $MAX_STITCHES stitches" }
        }
    }

    private fun group(stitches: List<Stitch>, manual: Boolean) = StitchGroup(
        color = color, stitches = stitches,
        lockStart = lockStart.takeUnless { manual && !parameters.force_lock_stitches },
        lockEnd = lockEnd.takeUnless { manual && !parameters.force_lock_stitches },
        forceLockStitches = parameters.force_lock_stitches,
        minStitchLengthMm = parameters.min_stitch_length_mm,
        minJumpLengthMm = parameters.min_jump_stitch_length_mm,
    )

    /** Upstream deliberately retains split endpoints twice; the common planner removes 0-length stitches. */
    private fun applyMaxStitchLength(path: List<Point>, budget: Int): List<Point> {
        require(path.size <= budget) { "Stroke output exceeds $MAX_STITCHES stitches" }
        val maximum = parameters.maxStitchLength ?: return path
        val result = mutableListOf(path.first())
        for ((a, b) in path.zipWithNext()) {
            val distance = a.distance(b)
            if (distance > maximum) {
                val count = ceil(distance / maximum)
                require(count <= budget - result.size) { "Manual stitch exceeds $MAX_STITCHES stitches" }
                val segments = count.toInt()
                for (i in 1..segments) result += a + (b - a) * (i.toDouble() / segments)
            }
            result += b
            require(result.size <= budget) { "Stroke output exceeds $MAX_STITCHES stitches" }
        }
        return result
    }

    private fun simpleSatin(path: List<Point>, budget: Int): List<Stitch> {
        val sampled = RunningStitch.runningStitch(path, parameters.zigzagSpacing.map { it / 2 }, parameters.runningStitchTolerance).map(::Stitch)
        val zigzag = RunningStitch.zigzagStitch(sampled, parameters.zigzagSpacing, strokeWidth, parameters.pullCompensation, parameters.zigzagAngle).toMutableList()
        if (closed && zigzag.size >= 2) zigzag[zigzag.lastIndex] = zigzag.last().copy(x = zigzag.first().x, y = zigzag.first().y)
        return repeated(zigzag, skipJunction = true, budget = budget)
    }

    private fun repeated(stitches: List<Stitch>, skipJunction: Boolean, budget: Int): List<Stitch> {
        val count = parameters.repeatCount
        require(stitches.size.toLong() * count <= budget) { "Stroke output exceeds $MAX_STITCHES stitches" }
        val result = mutableListOf<Stitch>()
        repeat(count) { pass ->
            val thisPass = if (pass % 2 == 1) stitches.asReversed() else stitches
            result += if (pass > 0 && skipJunction) thisPass.drop(1) else thisPass
        }
        return result
    }

    companion object { private const val MAX_STITCHES = 1_000_000 }
}
