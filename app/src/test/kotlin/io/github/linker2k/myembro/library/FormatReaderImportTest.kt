// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.ui.readViewerDesign
import io.github.linker2k.myembro.ui.photo.PictureIntake
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.*
import kotlin.test.*

class FormatReaderImportTest {
    @Test fun manifestAcceptsSpecificMimesForOpenAndShare() {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.File("src/main/AndroidManifest.xml"))
        val filters = document.getElementsByTagName("intent-filter")
        for (action in listOf("VIEW","SEND","SEND_MULTIPLE")) for (ext in listOf("jef","exp","vp3")) {
            assertTrue((0 until filters.length).any { i ->
                val filter = filters.item(i) as org.w3c.dom.Element
                val actions = filter.getElementsByTagName("action")
                val data = filter.getElementsByTagName("data")
                (0 until actions.length).any { (actions.item(it) as org.w3c.dom.Element).getAttribute("android:name") == "android.intent.action.$action" } &&
                    (0 until data.length).any { (data.item(it) as org.w3c.dom.Element).getAttribute("android:mimeType") == "application/x-$ext" }
            },"$action $ext")
        }
    }
    @Test fun newFormatsSurvivePickerZipLibraryAndViewer() {
        val p = EmbPattern(listOf(Stitch(10,20,Command.STITCH),Stitch(30,40,Command.STITCH),Stitch(10,40,Command.END)),listOf(EmbThread(0xff123456.toInt())))
        val files = mapOf("one.JEF" to JefWriter.write(p), "two.EXP" to ExpWriter.write(p), "three.VP3" to Vp3Writer.write(p))
        val root = Files.createTempDirectory("myembro-format-import-").toFile()
        try {
            val zip = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { stream ->
                files.forEach { (name,bytes) -> stream.putNextEntry(ZipEntry("nested/$name")); stream.write(bytes); stream.closeEntry() }
            } }.toByteArray()
            val result = DesignFileImporter(root).import(zip.inputStream(), "designs.zip")
            assertEquals(setOf("jef","exp","vp3"),result.map { it.extension }.toSet())
            files.forEach { (name,bytes) ->
                assertEquals(PictureIntake.Kind.DESIGN,PictureIntake.sniff(bytes.take(64).toByteArray(),name))
                assertTrue(DesignFormats.accepts(name))
                val actual = DesignFormats.read(bytes,name)
                assertTrue(actual.stitchCount > 0)
                assertEquals(actual.stitches,readViewerDesign(bytes,name).pattern.stitches)
                assertTrue("application/x-${name.substringAfterLast('.').lowercase()}" in DesignFormats.mimeTypes)
            }
            assertTrue(result.all { it.extension in DesignLibrary.SUPPORTED })
        } finally { root.deleteRecursively() }
    }
}
