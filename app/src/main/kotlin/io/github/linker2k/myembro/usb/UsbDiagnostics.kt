// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.Fat12Diagnostics
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class DirectUsbFailure(cause: Exception, val diagnostics: String) : IOException(cause.message, cause)

/** Observes libaums' existing INQUIRY/READ CAPACITY data phases, without issuing extra commands.
 * libaums 0.10.0 does not expose vendor/product from its private INQUIRY response.
 * https://repo.maven.apache.org/maven2/me/jahnen/libaums/core/0.10.0/core-0.10.0-sources.jar
 */
internal class UsbDiagnosticCapture {
    private var opcode = -1
    private var expected = 0
    private var received = ByteArray(0)
    private var inquiry: ByteArray? = null
    private var capacity: ByteArray? = null
    var sector0: ByteArray? = null
    var blocks: Long? = null
    var blockSize: Int? = null
    var warnings: List<String> = emptyList()

    fun outgoing(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() != 31 || b.getInt(b.position()) != 0x43425355) return
        val start = b.position()
        opcode = b.get(start + 15).toInt() and 255
        val length = b.getInt(start + 8)
        expected = if (opcode == 0x12 && length in 1..255 || opcode == 0x25 && length == 8) length else 0
        received = ByteArray(0)
    }
    fun incoming(buffer: ByteBuffer, offset: Int, count: Int) {
        if (expected == 0 || count <= 0 || offset !in 0..buffer.limit()) return
        val length = minOf(count, expected - received.size, buffer.limit() - offset)
        val part = ByteArray(length)
        buffer.duplicate().apply { position(offset); get(part) }
        received += part
        if (received.size == expected) {
            if (opcode == 0x12) inquiry = received.copyOf() else capacity = received.copyOf()
            expected = 0
        }
    }
    fun report(device: String, error: Exception): String = buildString {
        appendLine("myembro SE400 USB/FAT12 diagnostics v1")
        appendLine(device)
        appendLine("Failure: ${error.javaClass.simpleName}: ${error.message}")
        val response = inquiry
        if (response != null && response.size >= 36) {
            fun field(offset: Int, size: Int) = String(response, offset, size, Charsets.US_ASCII).trim { it <= ' ' }
            appendLine("INQUIRY vendor=${field(8, 8)}; product=${field(16, 16)}; revision=${field(32, 4)}")
            appendLine("INQUIRY hex: ${Fat12Diagnostics.hex(response)}")
        } else appendLine("INQUIRY vendor/product: unavailable (미확인)")
        val cap = capacity
        if (cap != null && cap.size == 8) {
            val b = ByteBuffer.wrap(cap).order(ByteOrder.BIG_ENDIAN)
            val last = b.int.toLong() and 0xFFFF_FFFFL
            val size = b.int.toLong() and 0xFFFF_FFFFL
            appendLine("READ CAPACITY lastLBA=$last; blockCount=${last + 1}; blockSize=$size; bytes=${(last + 1) * size}")
            appendLine("READ CAPACITY hex: ${Fat12Diagnostics.hex(cap)}")
        } else appendLine("READ CAPACITY: unavailable (미확인)")
        appendLine("Block adapter: blockCount=${blocks ?: "unavailable"}; blockSize=${blockSize ?: "unavailable"}")
        warnings.forEach { appendLine("Warning: $it") }
        sector0?.let { append(Fat12Diagnostics.bootSector(it)) }
            ?: appendLine("sector0 / BPB: unavailable (미확인; read failed before capture)")
    }
}

/** Delegation preserves transport behavior and buffer positions; only copies diagnostic bytes. */
internal class DiagnosticUsbCommunication(private val delegate: UsbCommunication, private val capture: UsbDiagnosticCapture) : UsbCommunication by delegate {
    override fun bulkOutTransfer(src: ByteBuffer): Int {
        capture.outgoing(src)
        return delegate.bulkOutTransfer(src)
    }
    override fun bulkInTransfer(dest: ByteBuffer): Int {
        val offset = dest.position()
        val count = delegate.bulkInTransfer(dest)
        capture.incoming(dest, offset, count)
        return count
    }
}
