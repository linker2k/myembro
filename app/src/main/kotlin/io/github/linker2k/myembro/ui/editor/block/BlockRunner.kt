// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.commands.CommandError
import io.github.linker2k.myembro.engine.edit.commands.CommandResult
import io.github.linker2k.myembro.engine.edit.commands.ProjectCommand
import io.github.linker2k.myembro.engine.edit.commands.apply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Where the block editor's generation stands; the badge and the sheet show it. */
sealed interface BlockStatus {
    data object Idle : BlockStatus
    data object Checking : BlockStatus
    /** [startedAtMs] on the runner's clock, for the elapsed time shown next to Cancel. */
    data class Generating(val startedAtMs: Long) : BlockStatus
    /** Whole project inside the hoop and SewoutCheck has no blocker. */
    data object Passed : BlockStatus
    /** The user cancelled a generation; nothing was committed, the previous settings stay. */
    data object Cancelled : BlockStatus
    /** [applyFailed]: an Apply was refused and the previous settings stay; otherwise the current design is blocked. */
    data class Blocked(val failure: BlockFailure, val applyFailed: Boolean) : BlockStatus
}

/**
 * Runs SetBlockStitch (generation, hoop and SewoutCheck) and the badge check off the main thread, one at a
 * time and interruptible: a newer apply, Cancel or leaving the block editor stops the previous one (meander
 * checks the thread's interrupt, as upstream). Only the reducer's Applied project is handed to [commit].
 */
class BlockRunner(
    private val scope: CoroutineScope,
    private val worker: CoroutineContext = Dispatchers.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    var status by mutableStateOf<BlockStatus>(BlockStatus.Idle)
        private set
    private var job: Job? = null

    /** [current] is the editor's project when the result arrives; [commit] returns false if the editor refused it. */
    fun apply(base: Project, command: ProjectCommand, current: () -> Project, commit: (Project) -> Boolean) {
        job?.cancel()
        status = BlockStatus.Generating(clock())
        job = scope.launch {
            val result = withContext(worker) { runInterruptible { apply(base, command) } }
            status = when {
                result is CommandResult.Rejected -> BlockStatus.Blocked(BlockFailure.of(result.code, result.message), applyFailed = true)
                // Something else changed the design meanwhile: never publish a result built on an old project.
                current() !== base -> BlockStatus.Blocked(BlockFailure(BlockFailure.Kind.DESIGN_CHANGED, "STALE_PROJECT"), applyFailed = true)
                commit(result.project) -> BlockStatus.Passed
                else -> BlockStatus.Blocked(BlockFailure(BlockFailure.Kind.EDITOR_BUSY, "EDITOR_BUSY"), applyFailed = true)
            }
        }
    }

    /** The whole design's hoop and sewing check, for the badge. */
    fun check(project: Project) {
        job?.cancel()
        status = BlockStatus.Checking
        job = scope.launch {
            val problem = withContext(worker) { runInterruptible { BlockApply.check(project) } }
            status = if (problem == null) BlockStatus.Passed else BlockStatus.Blocked(BlockFailure.sewout(problem), applyFailed = false)
        }
    }

    /** Cancel button: stops a running generation; nothing is committed. */
    fun cancelApply() {
        if (status !is BlockStatus.Generating) return
        job?.cancel(); job = null
        status = BlockStatus.Cancelled
    }

    /** Leaving the block editor. */
    fun stop() { job?.cancel(); job = null; status = BlockStatus.Idle }

    fun elapsedMs(s: BlockStatus.Generating) = clock() - s.startedAtMs
}

/**
 * Why an apply or check did not pass, classified for a short localized message; [detail] keeps the engine's
 * code and original text for the details view only.
 */
data class BlockFailure(val kind: Kind, val detail: String, val sewoutCodes: List<String> = emptyList()) {
    enum class Kind {
        /** Generation (the settings cannot produce stitches for this region). */
        MEANDER_REGION_TOO_SMALL, MEANDER_TOO_MANY_REPEATS, SATIN_UNSUPPORTED, NO_STITCHES, UNSUPPORTED_METHOD, GENERATION_FAILED,
        /** Stitches were made but the hoop/sewing check blocks them. */
        SEWOUT_BLOCKED,
        SETTINGS_INVALID, DESIGN_CHANGED, EDITOR_BUSY,
    }

    companion object {
        /**
         * The engine reports generation failures and sewing-check blocks under the same SEWOUT_BLOCKED code, so
         * the message decides: generation messages (MeanderFill, ShapeBlockRenderer, ShapeObject) are matched
         * first; everything else is a sewing-check block whose finding codes are kept.
         */
        fun of(code: CommandError, message: String): BlockFailure {
            val detail = "$code: $message"
            if (code == CommandError.INVALID_BLOCK_SETTINGS) return BlockFailure(Kind.SETTINGS_INVALID, detail)
            if (code != CommandError.SEWOUT_BLOCKED) return BlockFailure(Kind.GENERATION_FAILED, detail)
            val kind = when {
                "Could not build graph for meander" in message || "Meander graph is disconnected" in message -> Kind.MEANDER_REGION_TOO_SMALL
                "Meander tile repetition resource limit" in message || "Meander node resource limit" in message ||
                    "Meander clipping resource limit" in message -> Kind.MEANDER_TOO_MANY_REPEATS
                "SATIN_GEOMETRY_UNSUPPORTED" in message -> Kind.SATIN_UNSUPPORTED
                "EMPTY_REGION" in message || "REGION_SET_CHANGED" in message -> Kind.NO_STITCHES
                "UNSUPPORTED_FILL_BACKEND" in message || "LINEAR_GRADIENT_REQUIRES_COLOR_STOPS" in message -> Kind.UNSUPPORTED_METHOD
                "Meander" in message || "meander" in message -> Kind.GENERATION_FAILED
                else -> return sewout(message, detail)
            }
            return BlockFailure(kind, detail)
        }

        /** A sewing-check block from SewoutLimits / PecViewportCheck / PesExport text ("CODE: message; ..."). */
        fun sewout(message: String, detail: String = message) = BlockFailure(Kind.SEWOUT_BLOCKED, detail,
            CODE.findAll(message).map { it.groupValues[1] }.filter { it in SEWOUT_CODES }.distinct().toList())

        /** Finding codes the summary knows; others fall under "other check". */
        val SEWOUT_CODES = setOf("HOOP_SAFETY_MARGIN", "HOOP_EXCEEDED", "MACHINE_AREA_EXCEEDED", "DENSITY_HOTSPOT",
            "LONG_STITCH", "MACHINE_STITCH_LENGTH_EXCEEDED", "PEC_DELTA_EXCEEDED", "REPEATED_PENETRATION", "ZERO_LENGTH_MOTION",
            "MISSING_LOCK_STITCH", "EMPTY_DESIGN", "ANALYSIS_LIMIT", "ANALYSIS_INCOMPLETE", "INVALID_PES", "PEC_ORIGIN")
        private val CODE = Regex("\\b([A-Z][A-Z0-9_]{3,})\\b")
    }
}
