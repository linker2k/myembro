// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

/** References are synthetic. Commercial fonts/patterns are deliberately excluded. */
class AutomaticFillTest {
    private fun box(w: Double, h: Double) = listOf(-w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2, -w / 2 to -h / 2)
    private val plain = FillOptions(pullCompensationMm = 0.0, underlay = false, border = FillBorder.NONE)
    private fun moves(p: EmbPattern) = p.stitches.zipWithNext().filter { (a, b) -> a.command == Command.STITCH && b.command == Command.STITCH }
    @Test fun smallTextRelaxesDensityAtTheSevenMillimetreBoundaryUnlessManuallySet() {
        assertEquals(0.6, plain.densityForHeight(6.0))
        assertEquals(0.6, plain.densityForHeight(6.999))
        assertEquals(0.4, plain.densityForHeight(7.0))
        assertEquals(0.4, plain.densityForHeight(10.0))
        assertEquals(0.4, plain.copy(autoDensity = false).densityForHeight(6.0))
        assertEquals(0.8, plain.copy(densityMm = 0.8).densityForHeight(6.0))
        val relaxed = OutlineFill.generate(listOf(box(1.5, 6.0)), plain)
        val manual = OutlineFill.generate(listOf(box(1.5, 6.0)), plain.copy(autoDensity = false))
        assertTrue(relaxed.stitchCount < manual.stitchCount)
    }
    @Test fun narrowAndWideTtfRectanglesCannotBypassAutoSatinDisable() {
        for (width in listOf(1.5,6.5,12.0)) {
            val shape=listOf(box(width,30.0))
            val fallback=OutlineFill.generate(shape,plain,satin=true)
            val fill=OutlineFill.generate(shape,plain,satin=false)
            assertTrue(fallback.warnings.any { "TTF" in it })
            assertEquals(fill.stitches,fallback.stitches)
            assertSewingLengths(fallback)
            val restored=PesReader.read(PesWriter.write(fallback))
            assertEquals(PesWriter.prepareForOutput(fallback).stitchCount,restored.stitchCount)
            assertEquals(PesWriter.prepareForOutput(fallback).bounds,restored.bounds)
        }
    }
    @Test fun shortTJunctionSpursDoNotCreateFullLetterWidthCrossings() {
        val t = listOf(-2.5 to -4.0, 2.5 to -4.0, 2.5 to -2.8, 0.6 to -2.8, 0.6 to 4.0,
            -0.6 to 4.0, -0.6 to -2.8, -2.5 to -2.8, -2.5 to -4.0)
        val p = OutlineFill.generate(listOf(t), plain)
        assertTrue(moves(p).all { (a, b) -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= 40.0 })
    }
    @Test fun satinUnderlayFollowsCenterAndWideColumnsAlsoUseAnEdgeRun() {
        val narrow = OutlineFill.generate(listOf(box(1.5, 10.0)), plain.copy(underlay = true))
        assertTrue(narrow.stitches.filter { it.command == Command.STITCH }.take(3).all { abs(it.x) <= 1 })
        val wide = OutlineFill.generate(listOf(box(12.0, 30.0)), plain.copy(underlay = true))
        val beginning = wide.stitches.filter { it.command == Command.STITCH }.take(15)
        assertTrue(beginning.any { abs(it.x) > 40 })
        assertTrue(wide.stitchCount > OutlineFill.generate(listOf(box(12.0, 30.0)), plain).stitchCount)
    }
    @Test fun unverifiedRingSatinFallsBackToRoutedFillWithoutSewingAcrossItsHole() {
        val outer = (0..96).map { val a = it * PI / 48; 12 * cos(a) to 12 * sin(a) }
        val inner = outer.map { (x, y) -> x * 2 / 3 to y * 2 / 3 }
        val p = OutlineFill.generate(listOf(outer, inner), plain, satin = true)
        val crossings = moves(p).filter { (a, b) -> hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) > 10.0 }
        val radial = crossings.count { (a, b) ->
            val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble()
            val mx = (a.x + b.x) / 2.0; val my = (a.y + b.y) / 2.0
            abs(dx * mx + dy * my) / (hypot(dx, dy) * hypot(mx, my)) > 0.9
        }
        assertTrue(p.warnings.any { "검증" in it })
        assertTrue(p.stitches.filter { it.command == Command.STITCH }.all { hypot(it.x + (p.metadata["geometry.centerXUnits"]?.toDouble() ?: 0.0),it.y + (p.metadata["geometry.centerYUnits"]?.toDouble() ?: 0.0)) >= 79.0 })
        assertSewingLengths(p)
    }
    @Test fun automaticModePersistsAndLegacyTatamiDefaultsPreserveExistingPatterns() {
        val obj = TextObject("auto", text = "I", fontKind = FontKind.OUTLINE, contours = listOf(box(1.5, 6.0)),
            contourSourceHeight = 6.0, heightMm = 6.0, stitchStyle = StitchStyle.FILL)
        val project = Project(objects = listOf(obj))
        val back = ProjectJson.decode(project.toJson())
        assertEquals(project, back)
        assertEquals(project.flatten().stitches, back.flatten().stitches)
        val encoded = TextObjectCodec.encode(obj)
        val legacyOptions = JsonObject(encoded.getValue("fillOptions").jsonObject.filterKeys { it != "strategy" && it != "autoDensity" })
        val old = TextObjectCodec.decode(JsonObject(encoded + ("fillOptions" to legacyOptions))) as TextObject
        assertEquals(FillStrategy.TATAMI, old.fillOptions.strategy)
        assertFalse(old.fillOptions.autoDensity)
        assertEquals(TatamiLettering.generateGlyphs(listOf(OutlineGlyph(listOf(box(1.5,6.0)),old.thread,6.0))).stitches,
            old.localPattern().stitches)
    }
}
