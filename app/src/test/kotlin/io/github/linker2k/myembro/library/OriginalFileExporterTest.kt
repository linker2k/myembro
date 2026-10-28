// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.*

class OriginalFileExporterTest {
    private val directory=Files.createTempDirectory("myembro-original-export-").toFile()
    @AfterTest fun cleanup() { directory.deleteRecursively() }
    private fun source(name:String, bytes:ByteArray):File = directory.resolve(name).apply { parentFile?.mkdirs();writeBytes(bytes) }
    private class MemoryFolder(initial: Map<String,ByteArray> = emptyMap(),val failName:String?=null,
        val failDelete:Boolean=false, val failClose:Boolean=false): OriginalFileExporter.Folder {
        val files=initial.mapValues { (_,bytes) -> ByteArrayOutputStream().apply { write(bytes) } }.toMutableMap()
        val created=mutableListOf<String>()
        override fun names()=files.keys.toSet()
        override fun create(name:String):OriginalFileExporter.Document {
            check(name !in files)
            created+=name
            val bytes=ByteArrayOutputStream();files[name]=bytes
            return object:OriginalFileExporter.Document {
                override val name=name
                override fun open():OutputStream = object:FilterOutputStream(bytes) {
                    override fun write(b:ByteArray,off:Int,len:Int) {
                        if(name==failName) { out.write(b,off,minOf(2,len));throw IOException("destination full") }
                        out.write(b,off,len)
                    }
                    override fun close() { super.close();if(failClose) throw IOException("close failed") }
                }
                override fun delete():Boolean = if(failDelete) false else files.remove(name)!=null
            }
        }
    }
    private fun project(outside:Boolean): Project {
        val raw=EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(10,10,Command.STITCH),Stitch(10,10,Command.END)),
            listOf(EmbThread(0xff113355.toInt())))
        return Project(objects=listOf(StitchObject("synthetic","Synthetic",raw,transform=Transform(if(outside)2000.0 else 0.0,0.0))))
    }
    @Test fun pesProjectsAndOutsideHoopProjectAreByteIdenticalArchiveCopies() {
        val pes=File("../engine/src/test/resources/fixtures/flower.v6.pes").readBytes()
        val outside=project(true)
        assertTrue(outside.exceedsHoop())
        val inputs=listOf(source("archive.PES",pes),source("project.myembro",ProjectJson.encode(project(false)).toByteArray()),
            source("outside.myembro",ProjectJson.encode(outside).toByteArray()),
            source("original.pec","#PEC0001\u0000\u0001".toByteArray()),source("original.dst",ByteArray(512) { it.toByte() }))
        val original=inputs.associateWith { it.readBytes() }
        val folder=MemoryFolder()
        val report=OriginalFileExporter.export(inputs,folder)
        assertEquals(5,report.copied.size);assertTrue(report.failures.isEmpty())
        for(file in inputs) {
            assertContentEquals(original.getValue(file),folder.files.getValue(file.name).toByteArray())
            assertContentEquals(original.getValue(file),file.readBytes(),"Source is never changed")
        }
        println("ORIGINAL_EXPORT_BYTES_IDENTICAL PES/PEC/DST/PROJECT/OUTSIDE_PROJECT 5/5")
    }
    @Test fun collisionsPreserveExistingFilesAndUseSuffixBeforeTheOriginalExtension() {
        val a=source("one/도안.PES",byteArrayOf(1,2,3))
        val b=source("two/도안.PES",byteArrayOf(4,5,6))
        val folder=MemoryFolder(mapOf("도안.PES" to byteArrayOf(9),"도안 (1).PES" to byteArrayOf(8)))
        val report=OriginalFileExporter.export(listOf(a,b),folder)
        assertEquals(listOf("도안 (2).PES","도안 (3).PES"),report.copied.map { it.name })
        assertContentEquals(byteArrayOf(9),folder.files.getValue("도안.PES").toByteArray())
        assertContentEquals(a.readBytes(),folder.files.getValue("도안 (2).PES").toByteArray())
        assertContentEquals(b.readBytes(),folder.files.getValue("도안 (3).PES").toByteArray())
    }
    @Test fun caseInsensitiveAndExtensionlessCollisions() {
        val file=source("FOO.pes",byteArrayOf(1))
        val folder=MemoryFolder(mapOf("foo.PES" to byteArrayOf(9)))
        assertEquals("FOO (1).pes",OriginalFileExporter.export(listOf(file),folder).copied.single().name)
        assertEquals("name (1)",OriginalFileExporter.uniqueName("name") { it=="name" })
    }
    @Test fun missingSourceAndPartialWriteAreReportedAndOtherFilesContinue() {
        val missing=directory.resolve("missing.myembro")
        val bad=source("bad.pes",byteArrayOf(1,2,3,4))
        val good=source("good.dst",byteArrayOf(5,6,7))
        val folder=MemoryFolder(failName="bad.pes")
        val report=OriginalFileExporter.export(listOf(missing,bad,good),folder)
        assertEquals(listOf(good),report.copied.map { it.source })
        assertEquals(listOf(OriginalFileExporter.FailureReason.SOURCE_READ,OriginalFileExporter.FailureReason.COPY),report.failures.map {it.reason})
        assertTrue(report.failures.all { !it.detail.isNullOrBlank() })
        assertFalse("missing.myembro" in folder.files);assertFalse("bad.pes" in folder.files)
        assertContentEquals(good.readBytes(),folder.files.getValue("good.dst").toByteArray())
    }
    @Test fun failedCleanupAndOutputCloseFailureAreNotReportedAsSuccess() {
        val file=source("bad.pes",byteArrayOf(1,2,3,4))
        val partial=OriginalFileExporter.export(listOf(file),MemoryFolder(failName=file.name,failDelete=true))
        assertTrue(partial.copied.isEmpty());assertTrue(partial.failures.single().partialFileRemains)
        val folder=MemoryFolder(failClose=true)
        val close=OriginalFileExporter.export(listOf(file),folder)
        assertTrue(close.copied.isEmpty());assertEquals(1,close.failures.size);assertTrue(folder.files.isEmpty())
    }
    @Test fun inaccessibleFolderAndCreateFailureHaveSpecificReasons() {
        val file=source("a.pes",byteArrayOf(1))
        val folder=object:OriginalFileExporter.Folder {
            override fun names():Set<String> = throw SecurityException("folder denied")
            override fun create(name:String):OriginalFileExporter.Document = error("must not create")
        }
        assertEquals(OriginalFileExporter.FailureReason.FOLDER_ACCESS,OriginalFileExporter.export(listOf(file),folder).failures.single().reason)
        val noCreate=object:OriginalFileExporter.Folder {
            override fun names()=emptySet<String>()
            override fun create(name:String):OriginalFileExporter.Document = throw IOException("read only")
        }
        assertEquals(OriginalFileExporter.FailureReason.DESTINATION_CREATE,OriginalFileExporter.export(listOf(file),noCreate).failures.single().reason)
    }
    @Test fun singleDocumentAndSamplesLogoRemainOriginalBytes() {
        val folder=MemoryFolder()
        for(name in listOf("sample_synthetic.pes","myembro_logo.pes")) {
            val file=source(name,byteArrayOf(0,1,2,-1))
            val target=folder.create(name)
            val report=OriginalFileExporter.single(file,target)
            assertEquals(1,report.copied.size);assertTrue(report.failures.isEmpty())
            assertContentEquals(file.readBytes(),folder.files.getValue(name).toByteArray())
        }
    }
    @Test fun cancellationRemovesThePartialDocumentAndDoesNotStartLaterFiles() {
        val first=source("large.pes",ByteArray(32_768) { it.toByte() })
        val next=source("next.pes",byteArrayOf(7))
        val folder=MemoryFolder()
        var checkpoints=0
        assertFailsWith<CancellationException> {
            OriginalFileExporter.export(listOf(first,next),folder) { if(++checkpoints==4) throw CancellationException() }
        }
        assertTrue(folder.files.isEmpty());assertEquals(listOf("large.pes"),folder.created)
    }
}
