// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.library.DesignFormats
import java.nio.file.Files
import kotlin.test.*

/** Exercises the payload preparers used by USB BOT/SAF, viewer save/share and editor export. */
class PesOutputRoutesTest {
    private val pattern=EmbPattern(listOf(100 to -50,100 to 50,-100 to 50,-100 to -50,100 to -50)
        .map { (x,y)->Stitch(x,y,Command.STITCH) },listOf(PecThreads.forIndex(5)))
    @Test fun generatedAndConvertedPayloadsHaveTopLeftCoordinatesAcrossExportRoutes() {
        val directory=Files.createTempDirectory("pes-output-routes-").toFile()
        try {
            val project=Project(objects=listOf(StitchObject("rectangle","rectangle",pattern)))
            val file=directory.resolve("rectangle.myembro").apply { writeText(ProjectJson.encode(project)) }
            for(v in PesVersion.entries) {
                val settings=PesWriteSettings(v)
                // EditorViewModel export writes the flattened project through this API.
                val editor=PesWriter.write(project.flatten(),settings)
                // DirectUsbExporter.export and UsbExporter.export both use this preparer.
                val usb=DesignFormats.preparePes(file,settings).bytes
                val viewer=readViewerDesign(file.readBytes(),file.name,writeSettings=settings)
                // Both SAF save and cache-file share call ViewerViewModel's common export(),
                // which runs PesExport.prepare on this viewer source.
                val save=PesExport.prepare(viewer.source!!,viewer.sourceName,settings).bytes
                val share=PesExport.prepare(viewer.source!!,viewer.sourceName,settings).bytes
                for(payload in listOf(editor,usb,save,share)) {
                    assertContentEquals(editor,payload)
                    val back=PesReader.read(payload)
                    assertEquals(0,back.stitches.minOf { it.x });assertEquals(0,back.stitches.minOf { it.y })
                    assertEquals(200,back.stitches.maxOf { it.x });assertEquals(100,back.stitches.maxOf { it.y })
                }
                assertTrue(viewer.pattern.stitches.any { it.x<0 }) // Editor/preview placement is unchanged.
                val pec=directory.resolve("rectangle.pec").apply { writeBytes(PecWriter.write(project.flatten())) }
                val converted=DesignFormats.preparePes(pec,settings)
                assertTrue(converted.converted)
                assertEquals(PesReader.read(editor).stitches,PesReader.read(converted.bytes).stitches)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun importedPesIsStillCopiedWithoutReencodingOrLosingEditorData() {
        val original=PesWriter.writeV6(pattern)
        val opened=readViewerDesign(original,"external.pes")
        assertSame(original,opened.source)
        for(v in PesVersion.entries)assertSame(original,PesExport.prepare(opened.source!!,opened.sourceName,PesWriteSettings(v)).bytes)
    }
    @Test fun everyCatalogMachineAndCustomHoopUsesItsOwnIntegerBoundary() {
        val custom=io.github.linker2k.myembro.ui.machines.CustomFrame("test","custom",731,1247)
        val frames=io.github.linker2k.myembro.ui.machines.FrameCatalog.options(listOf(custom)).map { it.spec } +
            io.github.linker2k.myembro.ui.machines.MachineProfiles.all.flatMap { it.projectHoops }
        for(hoop in frames)for(axis in 0..1)for(delta in -1..1) {
            val x=if(axis==0)io.github.linker2k.myembro.engine.sewout.SewoutLimits.maxDesignWidthUnits0p1mm(hoop)+delta else 20
            val y=if(axis==1)io.github.linker2k.myembro.engine.sewout.SewoutLimits.maxDesignHeightUnits0p1mm(hoop)+delta else 20
            val p=EmbPattern(listOf(Stitch(x,0,Command.STITCH),Stitch(0,y,Command.STITCH),Stitch(x,0,Command.STITCH)),pattern.threads)
            for(version in PesVersion.entries) {
                val settings=PesWriteSettings(version,hoop=hoop)
                if(delta>0)assertFailsWith<IllegalArgumentException> { PesWriter.write(p,settings) }
                else assertTrue(io.github.linker2k.myembro.engine.sewout.PecViewportCheck.check(PesWriter.write(p,settings),hoop).valid)
            }
        }
    }
    @Test fun invalidExistingPesCannotBypassTheSaveShareOrUsbPreparer() {
        val bytes=PesWriter.write(pattern)
        bytes[22+520]=(bytes[22+520]+1).toByte()
        assertFailsWith<IllegalArgumentException> { PesExport.prepare(bytes,"invalid.pes") }
    }

}
