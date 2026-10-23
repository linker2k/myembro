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
}
