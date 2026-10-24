// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.backup

import java.io.*
import java.nio.file.Files
import java.util.zip.*
import kotlinx.serialization.json.*
import kotlin.test.*

class BackupArchiveTest {
    private fun sandbox(block:(File)->Unit) { val dir=Files.createTempDirectory("backup-test-").toFile();try{block(dir)}finally{dir.deleteRecursively()} }
    private val prefs=buildJsonObject { put("myembro_settings",buildJsonObject { put("machine_model",buildJsonObject {put("type","string");put("value","Brother SE600")}) }) }
    private fun seed(root: File) {
        File(root,"designs").mkdirs();File(root,"projects").mkdirs();File(root,"fonts/user").mkdirs();File(root,"drafts").mkdirs()
        File(root,"designs/original.pes").writeBytes(File("../engine/src/test/resources/fixtures/stops.v6.pes").readBytes())
        File(root,"projects/project.myembro").writeText("{\"sourceBytes\":\"unchanged-test-content\"}")
        File(root,"fonts/test.svg").writeText("<svg/>");File(root,"fonts/test.LICENSE.txt").writeText("public test font")
        File(root,"fonts/user/user.ttf").writeBytes(byteArrayOf(0,1,0,0))
        File(root,"drafts/current.myembro").writeText("draft")
    }
    private fun snapshot(root:File)=root.walkTopDown().filter {it.isFile}.associate {it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList()}
    private fun backup(root: File)=ByteArrayOutputStream().also { BackupArchive.write(root,prefs,it) }.toByteArray()

    @Test fun backupDeleteAllDataAndRestorePreservesEveryByteAndCount()=sandbox { root ->
        val data=File(root,"data");seed(data);val before=snapshot(data);val zip=backup(data)
        data.deleteRecursively();data.mkdirs();var restoredPrefs=buildJsonObject {}
        BackupArchive.read(zip.inputStream(),File(root,"cache")).use {plan ->
            assertEquals(before.size,plan.fileCount);assertEquals(0,plan.duplicateCount(data))
            val result=plan.restore(data,BackupArchive.Duplicates.SKIP) {restoredPrefs=it}
            assertEquals(before.size,result.added);assertEquals(0,result.skipped)
        }
        assertEquals(before,snapshot(data));assertEquals(prefs,restoredPrefs)
    }
    @Test fun duplicatesSkipOrCopyNeverOverwriteOriginalDesignAndProjects()=sandbox { root ->
        val data=File(root,"data");seed(data);val zip=backup(data);val before=snapshot(data)
        BackupArchive.read(zip.inputStream(),File(root,"cache")).use {p ->
            assertEquals(before.size,p.duplicateCount(data));assertEquals(0,p.restore(data,BackupArchive.Duplicates.SKIP).added)
        }
        assertEquals(before,snapshot(data))
        BackupArchive.read(zip.inputStream(),File(root,"cache")).use {p -> p.restore(data,BackupArchive.Duplicates.COPY) }
        before.forEach { (path,bytes) -> assertEquals(bytes,File(data,path).readBytes().toList()) }
        assertEquals(before.getValue("designs/original.pes"),File(data,"designs/original (1).pes").readBytes().toList())
        assertTrue(File(data,"projects/project (1).myembro").isFile)
    }
    @Test fun cancelAfterValidationAndFailedSettingsCommitDoNotPublishFiles()=sandbox { root ->
        val data=File(root,"data");seed(data);val zip=backup(data);val dest=File(root,"empty").apply {mkdirs()}
        BackupArchive.read(zip.inputStream(),File(root,"cache")).close()
        assertTrue(snapshot(dest).isEmpty())
        BackupArchive.read(zip.inputStream(),File(root,"cache")).use {p ->
            assertFailsWith<IOException> {p.restore(dest,BackupArchive.Duplicates.SKIP) {throw IOException("settings failure")} }
        }
        assertTrue(snapshot(dest).isEmpty())
    }
    @Test fun traversalUnknownVersionsAndChecksumDamageFailBeforeRestoring()=sandbox {root ->
        fun zip(name:String,value:String)=ByteArrayOutputStream().also {out -> ZipOutputStream(out).use {z -> z.putNextEntry(ZipEntry(name));z.write(value.toByteArray());z.closeEntry()} }.toByteArray()
        for (bytes in listOf(zip("files/designs/../../escape.pes","bad"),zip("manifest.json","{\"format\":\"myembro-backup\",\"version\":99}")))
            assertFails {BackupArchive.read(bytes.inputStream(),File(root,"cache"))}
        val data=File(root,"data");seed(data);val valid=backup(data)
        val corrupted=ByteArrayOutputStream()
        ZipInputStream(valid.inputStream()).use {input -> ZipOutputStream(corrupted).use {output ->
            while(true) {val e=input.nextEntry ?: break;output.putNextEntry(ZipEntry(e.name));val b=input.readBytes();output.write(if(e.name.endsWith("original.pes"))byteArrayOf(1,2,3) else b);output.closeEntry()}
        } }
        assertFailsWith<IllegalArgumentException> {BackupArchive.read(corrupted.toByteArray().inputStream(),File(root,"cache"))}
        assertFalse(File(root,"escape.pes").exists())
    }
}
