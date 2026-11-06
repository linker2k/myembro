// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

/** User-requested one-minute decision interval; elapsed time is monotonic, not an estimate. */
internal const val PHOTO_GENERATION_TIMEOUT_MS = 60_000L
internal fun photoGenerationTimedOut(startMs: Long, nowMs: Long,
    allowanceMs: Long = PHOTO_GENERATION_TIMEOUT_MS) = nowMs - startMs >= allowanceMs

internal fun photoExtendedAllowance(elapsedMs: Long, allowanceMs: Long): Long =
    maxOf(elapsedMs, allowanceMs) + PHOTO_GENERATION_TIMEOUT_MS

internal data class PhotoGenerationProgress(val phase: String = "stitches",
    val completedRegions: Int? = null, val totalRegions: Int? = null, val stitches: Int? = null,
    /** Stage, then path steps, inside the region being sewn (one fill pass); cleared when a region completes. */
    val regionStage: String? = null, val regionStep: Int? = null, val regionSteps: Int? = null) {
    fun checkpoint(value: String): PhotoGenerationProgress {
        if (value.startsWith("regions=")) {
            val parts = value.removePrefix("regions=").split(';', '/')
            return copy(completedRegions = parts[0].toInt(), totalRegions = parts[1].toInt(),
                stitches = parts[2].removePrefix("stitches=").toInt(), regionStage = null, regionStep = null, regionSteps = null)
        }
        if (value.startsWith("region_stage="))
            return copy(regionStage = value.removePrefix("region_stage="), regionStep = null, regionSteps = null)
        if (value.startsWith("region_step=")) {
            val parts = value.removePrefix("region_step=").split('/')
            return copy(regionStep = parts[0].toInt(), regionSteps = parts[1].toInt())
        }
        return if (value.startsWith("stitches_ready=")) copy(stitches = value.substringAfter('=').toInt())
            else copy(phase = value)
    }
}
