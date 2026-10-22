// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

/** Statistics for visible, sewn output; estimates use the same units/defaults as EmbPattern. */
data class ProjectStatistics(
    val stitchCount: Int,
    val jumpCount: Int,
    val trimCount: Int,
    val colorChangeCount: Int,
    val stopCount: Int,
    val estimatedSeconds: Int,
    val exceedsHoop: Boolean,
)

/** Counts exactly the commands returned by flatten, including object-boundary jumps and trims. */
fun Project.statistics(
    stitchesPerMinute: Int = 650,
    secondsPerTrim: Int = 4,
    secondsPerColorChange: Int = 30,
): ProjectStatistics {
    require(stitchesPerMinute > 0) { "Sewing speed must be positive" }
    require(secondsPerTrim >= 0 && secondsPerColorChange >= 0) { "Command times must be nonnegative" }
    val pattern = flatten()
    return ProjectStatistics(
        pattern.stitchCount, pattern.jumpCount, pattern.trimCount, pattern.colorChangeCount,
        pattern.stopCount, pattern.estimatedSeconds(stitchesPerMinute, secondsPerTrim, secondsPerColorChange),
        pattern.bounds?.let { !hoop.contains(it) } ?: false,
    )
}
