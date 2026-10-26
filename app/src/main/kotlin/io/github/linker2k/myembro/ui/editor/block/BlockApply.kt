// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import io.github.linker2k.myembro.engine.edit.BlockStitch
import io.github.linker2k.myembro.engine.edit.BlockStitchPatch
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.ShapeRegion
import io.github.linker2k.myembro.engine.edit.BlockStitchCapabilities
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import io.github.linker2k.myembro.engine.io.PesExport
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.DesignOrigin
import io.github.linker2k.myembro.engine.sewout.SewoutLimits
import kotlin.math.roundToInt

/** Where the block editor's generation stands; the badge shows it. */
sealed interface BlockStatus {
    data object Idle : BlockStatus
    data object Checking : BlockStatus
    data object Generating : BlockStatus
    /** Whole project inside the hoop and SewoutCheck has no blocker. */
    data object Passed : BlockStatus
    /** [applyFailed]: an Apply was refused and the previous settings stay; otherwise the current design is blocked. */
    data class Blocked(val reason: String, val applyFailed: Boolean,
        val code: io.github.linker2k.myembro.engine.edit.commands.CommandError? = null,
        val kind: io.github.linker2k.myembro.engine.edit.BlockGenerationKind? = null) : BlockStatus
}

/** Maps between the sheet's choices and the engine's upstream BlockStitch fields (docs/agent/block-stitch.md). */
object BlockApply {
    /** Typed command failure for presentation; callers never need to inspect the message. */
    fun rejectedStatus(result: io.github.linker2k.myembro.engine.edit.commands.CommandResult.Rejected) =
        BlockStatus.Blocked("${result.code}: ${result.message}",true,result.code,result.kind)

    /** Sheet texture -> Ink/Stitch fill_method (fill_stitch.py:166–184 option IDs). */
    val FILL_METHOD = mapOf(BlockTexture.BASIC to "tatami_fill", BlockTexture.PATTERN to "meander_fill",
        BlockTexture.CONTOUR to "contour_fill", BlockTexture.CIRCULAR to "circular_fill")

    /**
     * The engine's per-region prechecks (BlockStitchCapabilities, the same data list_blocks reports) at the
     * object's pose; scale changes satin stitch lengths. May run satin generation: call off the main thread.
     * These are prerequisites only; Apply still runs the full sewing check.
     */
    fun options(region: ShapeRegion, transform: Transform): BlockOptions {
        val c = BlockStitchCapabilities.forRegion(region, transform)
        val unavailable = listOf(BlockType.SATIN to c.satin, BlockType.RUNNING to c.running, BlockType.FILL to c.fill)
            .filter { !it.second.possible }.associate { (type, a) -> type to (a.reason ?: "UNAVAILABLE") }
        return BlockOptions(unavailable, FILL_METHOD.filterValues { it in BlockStitchCapabilities.fillMethods }.keys)
    }

    /**
     * Row spacing per density step. Only NORMAL has a basis: the upstream default row_spacing_mm 0.25
     * (fill_stitch.py:364–380). Upstream gives a minimum (0.1mm) but no dense/sparse presets, so those two
     * steps are unverified and not offered.
     */
    val ROW_SPACING_MM: Map<BlockDensity, Double?> = mapOf(BlockDensity.DENSE to null,
        BlockDensity.NORMAL to FillParameters().row_spacing_mm, BlockDensity.SPARSE to null)

    fun patch(d: BlockStitchDraft) = BlockStitchPatch(
        type = when (d.type) { BlockType.FILL -> BlockStitch.Type.FILL; BlockType.SATIN -> BlockStitch.Type.SATIN; BlockType.RUNNING -> BlockStitch.Type.RUNNING },
        fill_method = if (d.type == BlockType.FILL) FILL_METHOD.getValue(d.texture) else null,
        automatic_angle = d.autoAngle,
        angle = if (d.autoAngle) null else d.angleDeg.toDouble(),
        row_spacing_mm = ROW_SPACING_MM[d.density],
        meander_pattern = if (d.type == BlockType.FILL && d.texture == BlockTexture.PATTERN) d.tile else null,
    )

    fun draft(s: BlockStitch) = BlockStitchDraft(
        type = when (s.type) { BlockStitch.Type.FILL -> BlockType.FILL; BlockStitch.Type.SATIN -> BlockType.SATIN; BlockStitch.Type.RUNNING -> BlockType.RUNNING },
        texture = FILL_METHOD.entries.firstOrNull { it.value == s.fill.fill_method }?.key ?: BlockTexture.BASIC,
        tile = s.meander_pattern,
        autoAngle = s.automatic_angle,
        angleDeg = (((s.fill.angle.roundToInt() % 180) + 180) % 180),
        density = BlockDensity.NORMAL,
    )

    /** The same placed-hoop and released-PES checks SetBlockStitch runs before publishing; null = pass. */
    fun check(project: Project): String? = try {
        val pattern = project.flatten()
        SewoutLimits.requirePlaced(project.hoop, pattern.bounds)
        PesExport.requireSafe(PesWriter.write(pattern, PesWriteSettings(hoop = project.hoop)), project.hoop, DesignOrigin.APP_GENERATED)
        null
    } catch (e: InterruptedException) { throw e
    } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
}
