// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.DesignObject
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.ShapeObject
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.edit.commands.CommandResult
import io.github.linker2k.myembro.engine.edit.commands.ProjectCommand
import io.github.linker2k.myembro.engine.edit.commands.apply
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.ui.editor.block.BlockApply
import io.github.linker2k.myembro.ui.editor.block.BlockStitchDraft
import io.github.linker2k.myembro.ui.editor.block.BlockTexture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeStitchGuardTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect x="0" y="0" width="100" height="100" fill="#3366CC"/>""" +
        """<circle cx="50" cy="50" r="20" fill="#FFCC00"/></svg>"""
    private val shape = ShapeObject.fromSvg("o", "two", svg, 40.0)
    /** The fake generator: ART rejecting a JTS class, as seen on 5560. */
    private val verifyError: (DesignObject) -> EmbPattern = { throw VerifyError("Verifier rejected class org.locationtech.jts.operation.relateng.RelatePointLocator") }
    @After fun reset() = ShapeStitchGuard.clear()

    @Test fun aVerifyErrorShowsTheLastGoodStitchesAndIsListed() {
        val good = ShapeStitchGuard.local(shape)
        assertNull(good.failure); assertTrue(good.pattern.stitchCount > 0)
        val shown = ShapeStitchGuard.local(shape, verifyError)
        assertTrue(shown.failure!!.startsWith("java.lang.VerifyError"))
        assertEquals(good.pattern, shown.pattern)
        assertEquals(setOf("o"), ShapeStitchGuard.failedIds())
        ShapeStitchGuard.local(shape)                       // generates again: no longer listed
        assertEquals(emptySet<String>(), ShapeStitchGuard.failedIds())
    }

    @Test fun withoutALastGoodResultTheDefaultSettingsStitchesAreShown() {
        val project = Project(hoop = HoopSpec.H100).add(shape)
        val region = shape.regions.first().id
        val changed = apply(project, ProjectCommand.SetBlockStitch("o", setOf(region),
            BlockApply.patch(BlockStitchDraft(texture = BlockTexture.CONTOUR)))) as CommandResult.Applied
        val edited = changed.project.find("o") as ShapeObject
        // Only the edited (override) generation fails; the default-settings source still generates.
        val shown = ShapeStitchGuard.local(edited) { o -> if ((o as ShapeObject).overrides.isNotEmpty()) throw VerifyError("x") else o.localPattern() }
        assertTrue(shown.failure != null)
        assertEquals(shape.localPattern().stitches, shown.pattern.stitches)
    }

    @Test fun nothingToFallBackToShowsNoStitchesButStillWarns() {
        val shown = ShapeStitchGuard.local(shape, verifyError)
        assertEquals(0, shown.pattern.stitchCount); assertEquals(setOf("o"), ShapeStitchGuard.failedIds())
    }

    @Test fun outOfMemoryIsNotCaught() {
        assertThrows(OutOfMemoryError::class.java) { ShapeStitchGuard.local(shape) { throw OutOfMemoryError("big") } }
    }

    @Test fun theFallbackIsPlacedWithTheObjectsPose() {
        val good = ShapeStitchGuard.local(shape).pattern
        val moved = shape.withTransform(Transform(x = 100.0, y = -50.0))
        val placed = ShapeStitchGuard.placed(moved, verifyError).pattern
        assertEquals(good.stitches.first().x + 100, placed.stitches.first().x)
        assertEquals(good.stitches.first().y - 50, placed.stitches.first().y)
    }

    @Test fun displayFlattenFallsBackButExportStyleFlattenStillFails() {
        val project = Project(hoop = HoopSpec.H100).add(shape)
        ShapeStitchGuard.local(shape)
        val shown = ShapeStitchGuard.displayFlatten(project) { throw VerifyError("x") }
        assertTrue(shown.stitchCount > 0)
        // Export paths call flatten directly: the Error reaches them (EditorViewModel refuses to write).
        assertThrows(VerifyError::class.java) { (verifyError)(shape) }
    }
}
