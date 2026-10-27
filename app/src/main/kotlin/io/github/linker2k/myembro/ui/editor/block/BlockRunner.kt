// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import io.github.linker2k.myembro.engine.edit.BlockGenerationKind
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
    data class Blocked(val failure: BlockFailure, val applyFailed: Boolean,
        val code: io.github.linker2k.myembro.engine.edit.commands.CommandError? = null,
        val kind: io.github.linker2k.myembro.engine.edit.BlockGenerationKind? = null) : BlockStatus
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
    /** The engine reducer and the badge check; replaceable in tests only. */
    private val reduce: (Project, ProjectCommand) -> CommandResult = ::apply,
    private val checker: (Project) -> String? = BlockApply::check,
) {
    var status by mutableStateOf<BlockStatus>(BlockStatus.Idle)
        private set
    private var job: Job? = null

    /** [current] is the editor's project when the result arrives; [commit] returns false if the editor refused it. */
    fun apply(base: Project, command: ProjectCommand, current: () -> Project, commit: (Project) -> Boolean) {
        job?.cancel()
        status = BlockStatus.Generating(clock())
        job = scope.launch {
            val result = withContext(worker) { runInterruptible { guarded { reduce(base, command) } } }
            status = when (result) {
                is Guarded.Failed -> BlockStatus.Blocked(result.failure, applyFailed = true)
                is Guarded.Done -> applied(result.value, base, current, commit)
            }
        }
    }

    private fun applied(result: CommandResult, base: Project, current: () -> Project, commit: (Project) -> Boolean): BlockStatus =
            when {
                result is CommandResult.Rejected -> BlockApply.rejectedStatus(result)
                // Something else changed the design meanwhile: never publish a result built on an old project.
                current() !== base -> BlockStatus.Blocked(BlockFailure(BlockFailure.Kind.DESIGN_CHANGED, "STALE_PROJECT"), applyFailed = true)
                commit(result.project) -> BlockStatus.Passed
                else -> BlockStatus.Blocked(BlockFailure(BlockFailure.Kind.EDITOR_BUSY, "EDITOR_BUSY"), applyFailed = true)
            }

    /** The whole design's hoop and sewing check, for the badge. */
    fun check(project: Project) {
        job?.cancel()
        status = BlockStatus.Checking
        job = scope.launch {
            status = when (val problem = withContext(worker) { runInterruptible { guarded { checker(project) } } }) {
                is Guarded.Failed -> BlockStatus.Blocked(problem.failure, applyFailed = false)
                is Guarded.Done -> problem.value?.let { BlockStatus.Blocked(BlockFailure.sewout(it), applyFailed = false) } ?: BlockStatus.Passed
            }
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

    private sealed interface Guarded<out T> {
        data class Done<T>(val value: T) : Guarded<T>
        data class Failed(val failure: BlockFailure) : Guarded<Nothing>
    }

    /**
     * Crash safety net around generation: the reducer turns Exceptions into rejections, but an Error would end
     * the app. Caught here, on the worker thread that did the work, so the previous settings stay:
     * - LinkageError (VerifyError, NoClassDefFoundError, ExceptionInInitializerError...): this device's runtime
     *   cannot run a generation library class (e.g. JTS RelateNG on ART).
     * - StackOverflowError: the stack is already unwound when it reaches here.
     * - OutOfMemoryError: everything the generation allocated becomes unreachable once unwound, and it is the
     *   generation (up to a million meander points) that grows; failing this one apply beats losing the app.
     * Other VirtualMachineErrors (InternalError, UnknownError) mean the VM itself is broken and are not caught.
     */
    private inline fun <T> guarded(block: () -> T): Guarded<T> = try { Guarded.Done(block()) } catch (e: Throwable) {
        Guarded.Failed(BlockFailure.device(e) ?: throw e)
    }
}

/**
 * Why an apply or check did not pass, classified for a short localized message from the engine's typed
 * result (CommandError + BlockGenerationKind), never from its message; [detail] keeps the engine's code and
 * original text for the details view only.
 */
data class BlockFailure(val kind: Kind, val detail: String, val sewoutCodes: List<String> = emptyList()) {
    enum class Kind {
        /** Generation (the settings cannot produce stitches for this region). */
        MEANDER_REGION_TOO_SMALL, MEANDER_FAILED, SATIN_UNSUPPORTED, SATIN_TOO_LONG, NO_STITCHES, UNSUPPORTED_METHOD, GENERATION_FAILED,
        /** The device's runtime could not run the generation (Error caught by BlockRunner's safety net). */
        DEVICE_FAILED, OUT_OF_MEMORY,
        /** Stitches were made but the hoop/sewing check blocks them. */
        SEWOUT_BLOCKED,
        SETTINGS_INVALID, DESIGN_CHANGED, EDITOR_BUSY,
    }

    companion object {
        /** The Errors BlockRunner survives; null = rethrow. */
        fun device(e: Throwable): BlockFailure? {
            val detail = "${e.javaClass.name}: ${e.message}"
            return when (e) {
                is OutOfMemoryError -> BlockFailure(Kind.OUT_OF_MEMORY, detail)
                is LinkageError, is StackOverflowError -> BlockFailure(Kind.DEVICE_FAILED, detail)
                else -> null
            }
        }

        fun of(code: CommandError, message: String, generation: BlockGenerationKind? = null): BlockFailure {
            val detail = listOfNotNull(code.name, generation?.name).joinToString("/") + ": " + message
            return when (code) {
                CommandError.INVALID_BLOCK_SETTINGS -> BlockFailure(Kind.SETTINGS_INVALID, detail)
                CommandError.SEWOUT_BLOCKED -> sewout(message, detail)
                CommandError.GENERATION_FAILED -> BlockFailure(when (generation) {
                    BlockGenerationKind.MEANDER_GRAPH -> Kind.MEANDER_REGION_TOO_SMALL
                    BlockGenerationKind.MEANDER_GENERATION -> Kind.MEANDER_FAILED
                    BlockGenerationKind.SATIN_GEOMETRY_UNSUPPORTED, BlockGenerationKind.SATIN_GENERATION_FAILED -> Kind.SATIN_UNSUPPORTED
                    BlockGenerationKind.SATIN_STITCH_TOO_LONG -> Kind.SATIN_TOO_LONG
                    BlockGenerationKind.EMPTY_REGION, BlockGenerationKind.REGION_SET_CHANGED -> Kind.NO_STITCHES
                    BlockGenerationKind.UNSUPPORTED_FILL_BACKEND -> Kind.UNSUPPORTED_METHOD
                    BlockGenerationKind.REGION_GENERATION, BlockGenerationKind.REGION_ORDER, BlockGenerationKind.OUTPUT_ENCODING, null -> Kind.GENERATION_FAILED
                }, detail)
                else -> BlockFailure(Kind.GENERATION_FAILED, detail)
            }
        }

        /**
         * A sewing-check block. The engine joins its BLOCKER findings as "FindingCode: message; ..." (BlockCommandCheck,
         * PesExport, SewoutLimits); only the leading code of each entry is read, and only known codes are kept.
         */
        fun sewout(message: String, detail: String = message) = BlockFailure(Kind.SEWOUT_BLOCKED, detail,
            message.split("; ").map { it.substringBefore(':').trim() }.filter { it in SEWOUT_CODES }.distinct())

        /** Finding codes the summary knows; others fall under "other check". */
        val SEWOUT_CODES = setOf("HOOP_SAFETY_MARGIN", "HOOP_EXCEEDED", "MACHINE_AREA_EXCEEDED", "DENSITY_HOTSPOT",
            "LONG_STITCH", "MACHINE_STITCH_LENGTH_EXCEEDED", "PEC_DELTA_EXCEEDED", "REPEATED_PENETRATION", "ZERO_LENGTH_MOTION",
            "MISSING_LOCK_STITCH", "EMPTY_DESIGN", "ANALYSIS_LIMIT", "ANALYSIS_INCOMPLETE", "INVALID_PES", "PEC_ORIGIN")
    }
}
