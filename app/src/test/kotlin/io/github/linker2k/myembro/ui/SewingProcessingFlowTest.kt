// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.model.Stitch as NativeStitch
import io.github.linker2k.myembro.library.DesignFormats
import io.github.linker2k.myembro.ui.settings.withSewingDefaults
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import java.nio.file.Files
import kotlin.test.*

class SewingProcessingFlowTest {
    private val withoutLocks = SewingOptions(locks = LockStitchParameters(ties = 3))
    private val custom = SewingOptions()
    private val imported = EmbPattern(listOf(NativeStitch(0, 0, Command.JUMP), NativeStitch(0, 0, Command.STITCH),
        NativeStitch(1, 0, Command.STITCH), NativeStitch(50, 0, Command.STITCH), NativeStitch(0, 0, Command.STITCH), NativeStitch(50, 0, Command.END)),
        listOf(EmbThread(0xFF123456.toInt(), "Original")))

    @Test fun `automatic app policy uses source defaults without a user setting`() {
        assertEquals(SewingOptions(), EmbroiderySettings().sewing)
        val bytes = ProjectJson.encode(Project(objects = listOf(TextObject("text", text = "I")))).toByteArray()
        val implicit = readViewerDesign(bytes, "old.myembro")
        val explicit = readViewerDesign(bytes, "old.myembro", sewingOptions = SewingOptions())
        assertEquals(explicit.pattern.stitches, implicit.pattern.stitches)
        assertContentEquals(explicit.source, implicit.source)
        assertTrue(ProcessingSummary.fromPattern(implicit.pattern)!!.lockLocations > 0)
    }

    @Test fun `PES bytes and imported stitches survive all default changes`() {
        val bytes = PesWriter.write(imported)
        val original = PatternReader.read(bytes, "original.pes")
        for (type in LockStitches.types) {
            val options = custom.copy(locks = custom.locks.copy(lock_start = type, lock_end = type))
            val opened = readViewerDesign(bytes, "original.pes", sewingOptions = options)
            assertSame(bytes, opened.source)
            assertEquals(original.stitches, opened.pattern.stitches)
            assertNull(opened.projectSource)
            assertNull(ProcessingSummary.fromPattern(opened.pattern))
            assertSame(opened.pattern, opened.pattern.withSewingDefaults(options))
        }
    }

    @Test fun `old project text regenerates using current defaults without rewriting its source`() {
        val project = Project(objects = listOf(TextObject("text", text = "I")))
        val bytes = ProjectJson.encode(project).toByteArray()
        val before = bytes.copyOf()
        val disabled = project.flatten(withoutLocks)
        val disabledViewer=readViewerDesign(bytes,"old.myembro",sewingOptions=withoutLocks)
        val enabled = readViewerDesign(bytes, "old.myembro", sewingOptions = custom)
        assertEquals(0, ProcessingSummary.fromPattern(disabled)!!.lockLocations)
        assertTrue(ProcessingSummary.fromPattern(enabled.pattern)!!.lockLocations > 0)
        assertFalse(disabledViewer.source.contentEquals(enabled.source))
        assertSame(bytes, enabled.projectSource)
        assertContentEquals(before, bytes)
        assertEquals(custom, enabled.sewingOptions)
        assertEquals(project.flatten(custom).stitches, enabled.pattern.stitches)
        assertEquals(io.github.linker2k.myembro.engine.io.PesWriter.prepareForOutput(enabled.pattern).stitchCount, PatternReader.read(enabled.source, enabled.sourceName).stitchCount)
    }

    @Test fun `mixed generated project with unsafe raw boundaries is blocked before export`() {
        val project=Project(objects=listOf(TextObject("text",text="I"),StitchObject("raw","raw",imported,transform=Transform(x=200.0))))
        assertFailsWith<IllegalArgumentException> { readViewerDesign(ProjectJson.encode(project).toByteArray(),"mixed.myembro") }
    }

    @Test fun `USB project preparation uses the same defaults as the viewer and raw PES passes through`() {
        val directory = Files.createTempDirectory("myembro-sewing-flow-").toFile()
        try {
            val file = directory.resolve("letters.myembro")
            val bytes = ProjectJson.encode(Project(objects = listOf(TextObject("t", text = "AB")))).toByteArray()
            file.writeBytes(bytes)
            val viewer = readViewerDesign(bytes, file.name, sewingOptions = custom)
            val prepared = DesignFormats.preparePes(file, PesWriteSettings(), custom)
            assertContentEquals(viewer.source, prepared.bytes)
            val pes = directory.resolve("original.pes").apply { writeBytes(PesWriter.write(imported)) }
            assertContentEquals(pes.readBytes(), DesignFormats.preparePes(pes, PesWriteSettings(PesVersion.V6), custom).bytes)
        } finally { directory.deleteRecursively() }
    }
}
