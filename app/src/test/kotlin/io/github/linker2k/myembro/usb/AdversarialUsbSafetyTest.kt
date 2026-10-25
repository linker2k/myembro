// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.sewout.*
import io.github.linker2k.myembro.library.DesignFormats
import io.github.linker2k.myembro.safety.SafetyFixtures
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AdversarialUsbSafetyTest {
    private class FakeUsb(val bytes:ByteArray):BlockDevice {
        override val sectorSize=512
        override val sectorCount get()=bytes.size/sectorSize.toLong()
        var writeCalls=0;var writtenBytes=0
        override fun readSectors(lba:Long,count:Int)=bytes.copyOfRange((lba*sectorSize).toInt(),((lba+count)*sectorSize).toInt())
        override fun writeSectors(lba:Long,data:ByteArray) { writeCalls++;writtenBytes+=data.size;data.copyInto(bytes,(lba*sectorSize).toInt()) }
    }
    @Test fun sewoutBlockedPesCannotReachTheRealDirectUsbWriteBoundary() {
        val dir=Files.createTempDirectory("safety-usb-").toFile()
        try {
            val image=File(dir,"disk.img")
            val mkfs=ProcessBuilder("/usr/sbin/mkfs.fat","-F","12","-C","-s","1","-f","2",image.path,"480").redirectErrorStream(true).start()
            assertTrue(mkfs.waitFor(15,TimeUnit.SECONDS));assertEquals(0,mkfs.exitValue(),mkfs.inputStream.bufferedReader().readText())
            val blocked=SafetyFixtures.densePes();assertTrue(PecViewportCheck.check(blocked,HoopSpec.H100).valid)
            val report=SewoutCheck.checkPes(blocked,options=SewoutOptions(origin=DesignOrigin.IMPORTED))
            assertTrue(report.hasBlockingIssues)
            val file=File(dir,"dense.pes").apply { writeBytes(blocked) }
            val fake=FakeUsb(image.readBytes());val volume=Fat12Volume.mount(fake)
            val prepared=runCatching { DesignFormats.preparePes(file,PesWriteSettings()) }.getOrNull()
            if(prepared!=null) {
                val session=DirectUsbAccess.Session(volume,Closeable {},{"fake USB"},{ DirectUsbFailure(it,"fake USB") })
                session.use { DirectUsbExporter.write(it,listOf(DirectUsbExporter.Payload("dense.pes",prepared.fileName,prepared.bytes,false)),emptyList()) }
            }
            SafetyFixtures.report("usb-blocked",listOf(buildJsonObject { put("inputVerdict",report.verdict.name);put("viewportValid",true);put("preparedBytes",prepared?.bytes?.size ?: 0);put("usbWriteCalls",fake.writeCalls);put("usbWrittenBytes",fake.writtenBytes) }))
            assertEquals(0,fake.writeCalls,"SewoutCheck BLOCK input was written to the actual DirectUsbExporter fake BlockDevice")
        } finally { dir.deleteRecursively() }
    }
}
