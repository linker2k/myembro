// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class UsbDiagnosticsTest {
    private fun cbw(opcode: Int, length: Int) = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0, 0x43425355); putInt(8, length); put(12, 0x80.toByte()); put(15, opcode.toByte())
    }
    private class Transport(vararg data: ByteArray) : UsbCommunication {
        val incoming = ArrayDeque(data.toList())
        var outputs = 0
        override val inEndpoint: UsbEndpoint get() = error("Unused")
        override val outEndpoint: UsbEndpoint get() = error("Unused")
        override val usbInterface: UsbInterface get() = error("Unused")
        override fun bulkOutTransfer(src: ByteBuffer): Int = src.remaining().also { outputs++; src.position(src.limit()) }
        override fun bulkInTransfer(dest: ByteBuffer): Int = incoming.removeFirst().let { dest.put(it); it.size }
        override fun close() {}
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int): Int = error("Unused")
        override fun resetDevice() = error("Unused")
        override fun clearFeatureHalt(endpoint: UsbEndpoint) = error("Unused")
    }
    @Test fun `captures real inquiry fields and unsigned capacity without extra commands or buffer changes`() {
        val inquiry = ByteArray(36).also {
            "BROTHER ".toByteArray().copyInto(it, 8)
            "SE400 RAM DISK  ".toByteArray().copyInto(it, 16)
            "1.00".toByteArray().copyInto(it, 32)
        }
        val capacity = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(951).putInt(512).array()
        val transport = Transport(inquiry.copyOfRange(0, 18), inquiry.copyOfRange(18, 36), ByteArray(13), capacity)
        val capture = UsbDiagnosticCapture()
        val communication = DiagnosticUsbCommunication(transport, capture)
        val command = cbw(0x12, 36)
        assertEquals(31, communication.bulkOutTransfer(command))
        assertEquals(31, command.position())
        val response = ByteBuffer.allocate(36)
        assertEquals(18, communication.bulkInTransfer(response))
        assertEquals(18, communication.bulkInTransfer(response))
        assertEquals(36, response.position())
        assertContentEquals(inquiry, response.array())
        communication.bulkInTransfer(ByteBuffer.allocate(13)) // CSW is not diagnostic data.
        communication.bulkOutTransfer(cbw(0x25, 8))
        communication.bulkInTransfer(ByteBuffer.allocate(8))
        assertEquals(2, transport.outputs)
        capture.sector0 = ByteArray(512)
        capture.blocks = 952; capture.blockSize = 512
        val report = capture.report("USB VID=0x04F9", IOException("Superfloppy failed: invalid BPB; MBR failed: Not an MBR"))
        assertTrue(report.contains("vendor=BROTHER; product=SE400 RAM DISK; revision=1.00"))
        assertTrue(report.contains("lastLBA=951; blockCount=952; blockSize=512; bytes=487424"))
        assertTrue(report.contains("01F0:"))
        assertTrue(report.contains("Superfloppy failed:"))
        assertTrue(report.contains("MBR failed:"))
    }
    @Test fun `unavailable diagnostics remain unknown and capture does not alter input views`() {
        val capture = UsbDiagnosticCapture()
        val command = cbw(0x25, 8)
        capture.outgoing(command)
        assertEquals(0, command.position())
        val bytes = ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(-1).putInt(512).array())
        bytes.position(8)
        capture.incoming(bytes, 0, 8)
        assertEquals(8, bytes.position())
        val report = capture.report("USB descriptor only", IOException("device gone"))
        assertTrue(report.contains("INQUIRY vendor/product: unavailable"))
        assertTrue(report.contains("blockCount=4294967296"))
        assertTrue(report.contains("sector0 / BPB: unavailable"))
    }

    @Test fun `failed partition BPB report includes LBA 32 bytes and separately labeled fields`() {
        val capture = UsbDiagnosticCapture()
        val zero = ByteArray(512).also {
            it[446] = 0x80.toByte(); it[450] = 1; it[454] = 0x20
            it[458] = 0xDB.toByte(); it[459] = 3; it[510] = 0x55; it[511] = 0xAA.toByte()
        }
        val partition = ByteArray(512).also {
            it[11] = 0; it[12] = 2; it[13] = 1; it[14] = 1; it[16] = 2
            it[17] = 0xE0.toByte(); it[19] = 0xDB.toByte(); it[20] = 3; it[22] = 3
        } // Deliberately missing signature: capture must still survive parsing failure.
        capture.bootSector(0, zero); capture.bootSector(32, partition)
        partition[11] = 0x7F // Diagnostic snapshots do not alias the driver's read buffer.
        val report = capture.report("SE400", IOException("BPB signature failed"))
        assertTrue(report.contains("sector0 first 512 bytes (hex):"))
        assertTrue(report.contains("0: boot=0x80 type=0x01 start=32 sectors=987"))
        assertTrue(report.contains("partition start sector (LBA 32) first 512 bytes (hex):"))
        assertTrue(report.contains("BPB (partition start sector (LBA 32) interpreted as boot sector):"))
        assertTrue(report.contains("BytesPerSector=512"))
        assertTrue(report.contains("TotalSectors16=987"))
        assertEquals(2, report.lines().count { it.startsWith("01F0:") })
    }

    @Test fun `v3 diagnostics include separate FAT prefixes and the complete first root sector`() {
        val capture = UsbDiagnosticCapture()
        val first = ByteArray(512) { it.toByte() }
        val second = ByteArray(512) { (255 - it).toByte() }
        val root = ByteArray(32 * 512) { (it * 3).toByte() }
        capture.fatCopy(1, 33, first)
        capture.fatCopy(2, 34, second)
        capture.rootDirectory(35, root)
        first.fill(0); second.fill(0); root.fill(0) // Snapshots must not alias device buffers.
        val report = capture.report("SE400", IOException("outside usable data clusters"))
        assertTrue(report.contains("diagnostics v3"))
        assertTrue(report.contains("FAT1 (LBA 33) first 64 bytes (hex):"))
        assertTrue(report.contains("00 01 02 03 04 05 06 07"))
        assertTrue(report.contains("FAT2 (LBA 34) first 64 bytes (hex):"))
        assertTrue(report.contains("FF FE FD FC FB FA F9 F8"))
        assertTrue(report.contains("Root directory first sector (LBA 35) 512 bytes (hex):"))
        val rows = report.substringAfter("Root directory first sector").lines().filter { it.matches(Regex("[0-9A-F]{4}: .*")) }
        assertEquals(32, rows.size)
        val restored = rows.flatMap { it.substringAfter(": ").split(' ') }.map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(ByteArray(512) { (it * 3).toByte() }, restored)
    }
}
