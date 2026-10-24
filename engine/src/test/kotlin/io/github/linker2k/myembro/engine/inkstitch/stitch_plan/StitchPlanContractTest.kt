// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.elements.EmbroideryElement
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch as MachineStitch
import kotlin.test.*

class StitchPlanContractTest {
    @Test fun `raw adapter retains every command coordinate metadata warning and full thread identity`() {
        val threads = listOf(EmbThread(0xFF123456.toInt(), "A", "1", "Brand", "Chart"),
            EmbThread(0xFF123456.toInt(), "Different catalog", "2", "Other brand", "Other chart"))
        val commands = Command.entries.mapIndexed { i, command -> MachineStitch(-213 + i * 17, 19 - i * 23, command) }
        // Also retain records after END; the adapter is not a normalizer.
        val source = EmbPattern(commands + MachineStitch(123, 456, Command.STITCH), threads,
            mapOf("name" to "한글", "author" to "A"), listOf("reader warning"))
        val result = EmbPatternAdapter.toEmbPattern(EmbPatternAdapter.fromEmbPattern(source))
        assertEquals(source.stitches, result.stitches)
        assertEquals(source.threads, result.threads)
        assertEquals(source.metadata, result.metadata)
        assertEquals(source.warnings, result.warnings)
    }

    @Test fun `empty patterns and absent palettes are not populated by the raw adapter`() {
        for (stitches in listOf(emptyList(), listOf(MachineStitch(0, 0, Command.END)),
            listOf(MachineStitch(0, 0, Command.COLOR_CHANGE), MachineStitch(0, 0, Command.COLOR_CHANGE)))) {
            val source = EmbPattern(stitches, emptyList())
            val result = EmbPatternAdapter.toEmbPattern(EmbPatternAdapter.fromEmbPattern(source))
            assertEquals(stitches, result.stitches)
            assertTrue(result.threads.isEmpty())
        }
    }

    @Test fun `native integer coordinates including limits round trip through 96dpi pixels`() {
        val values = listOf(Int.MIN_VALUE, Int.MAX_VALUE, -10001, -13, -1, 0, 1, 13, 10001)
        for (value in values) assertEquals(value, Units.pixelsToNative(Units.nativeToPixels(value)))
        assertEquals(96.0, Units.mmToPixels(25.4), 1e-12)
        assertEquals(25.4, Units.pixelsToMm(96.0), 1e-12)
        assertFailsWith<IllegalArgumentException> { Units.pixelsToNative(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Units.pixelsToNative(1e20) }
    }

    @Test fun `generated blocks quantize only at adapter boundary and preserve compound commands`() {
        val blue = EmbThread(0xFF0000FF.toInt())
        val red = EmbThread(0xFFFF0000.toInt())
        val plan = StitchPlan(listOf(
            ColorBlock(blue, listOf(Stitch(0.0, 0.0, tags = setOf("jump")), Stitch(Units.mmToPixels(2.0), 0.0))),
            ColorBlock(red, listOf(Stitch(Units.mmToPixels(2.0), 0.0, trim = true, stop = true), Stitch(Units.mmToPixels(3.0), 0.0))),
        ))
        val result = EmbPatternAdapter.toEmbPattern(plan)
        assertEquals(listOf(Command.JUMP, Command.STITCH, Command.COLOR_CHANGE, Command.TRIM, Command.STOP,
            Command.STITCH, Command.END), result.stitches.map { it.command })
        assertEquals(listOf(blue, red), result.threads)
        assertEquals(20, result.stitches[1].x)
        assertEquals(30, result.stitches.last().x)
    }

    @Test fun `group is a passive contract and lock callback obeys disable and short-run rules`() {
        val calls = mutableListOf<LockPosition>()
        val lock = LockStitch { stitches, position -> calls += position; listOf(stitches.first().copy(tags = setOf("lock_stitch"))) }
        val body = listOf(Stitch(0.0, 0.0), Stitch(10.0, 0.0))
        val element = EmbroideryElement { previous ->
            assertEquals(Stitch(-1.0, 0.0), previous)
            listOf(StitchGroup(stitches = body, lockStart = lock, lockEnd = lock, minStitchLengthMm = 0.1))
        }
        val group = element.toStitchGroups(Stitch(-1.0, 0.0)).single()
        assertEquals(body, group.stitches)
        assertTrue(calls.isEmpty())
        assertTrue(group.lockStitches(LockPosition.START).single().isLock)
        assertEquals(listOf(LockPosition.START), calls)
        assertTrue(group.lockStitches(LockPosition.END, disableTies = true).isEmpty())
        assertTrue(group.copy(stitches = body.take(1)).lockStitches(LockPosition.END).isEmpty())
    }

    @Test fun `existing reader fixtures produce byte-identical PES after passive adapter round trip`() {
        for (name in listOf("flower.v1.pes", "flower.v6.pes", "stops.v1.pes", "long_moves.v6.pes", "flower.pec", "stops.dst")) {
            val bytes = javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }
            val source = PatternReader.read(bytes, name)
            val result = EmbPatternAdapter.toEmbPattern(EmbPatternAdapter.fromEmbPattern(source))
            assertEquals(source.stitches, result.stitches, name)
            assertEquals(source.threads, result.threads, name)
            assertContentEquals(io.github.linker2k.myembro.engine.PolicyFixtureWriter.write(source), io.github.linker2k.myembro.engine.PolicyFixtureWriter.write(result), name)
        }
    }
    @Test fun `fixed manual SVG contract exports a deterministic PES for independent quality review`() {
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(javaClass.getResourceAsStream("/inkstitch/contract.svg"))
        val data = (xml.getElementsByTagName("path").item(0) as org.w3c.dom.Element).getAttribute("d")
        // SVG viewBox maps one user unit to 1mm. Parser output is explicitly scaled to 96dpi px.
        val points = io.github.linker2k.myembro.engine.geometry.SvgPath.flatten(data,
            io.github.linker2k.myembro.engine.geometry.Affine2D.scale(Units.PIXELS_PER_MM)).single().points
        val body = points.map { Stitch(it.x, it.y) }
        val plan = StitchPlan(listOf(ColorBlock(EmbThread(0xFF000000.toInt()),
            listOf(body.first().copy(jump = true)) + body + body.last().copy(trim = true))))
        val pattern = EmbPatternAdapter.toEmbPattern(plan)
        val bytes = io.github.linker2k.myembro.engine.PolicyFixtureWriter.write(pattern)
        assertContentEquals(bytes, io.github.linker2k.myembro.engine.PolicyFixtureWriter.write(EmbPatternAdapter.toEmbPattern(plan)))
        val output = java.io.File("build/inkstitch-contract/contract.pes")
        output.parentFile.mkdirs(); output.writeBytes(bytes)
    }

}
