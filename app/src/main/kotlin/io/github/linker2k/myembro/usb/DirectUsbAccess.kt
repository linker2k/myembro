// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import androidx.core.content.ContextCompat
import io.github.linker2k.myembro.engine.fat12.Fat12Volume
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.Closeable
import java.io.IOException
import java.util.UUID

data class DirectUsbTarget(val device: UsbDevice, val usbInterface: UsbInterface,
    val input: UsbEndpoint, val output: UsbEndpoint) {
    val id: String get() = "${device.deviceName}:${device.deviceId}:${usbInterface.id}"
}

/** Discovery and permission do no disk I/O. Only an explicit export opens the BOT/SCSI driver. */
internal class DirectUsbAccess(private val context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)
    fun devices(): List<DirectUsbTarget> = manager.deviceList.values.flatMap { device ->
        (0 until device.interfaceCount).mapNotNull { i ->
            val face = device.getInterface(i)
            if (face.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE || face.interfaceSubclass != 6 || face.interfaceProtocol != 0x50) return@mapNotNull null
            val endpoints = (0 until face.endpointCount).map(face::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return@mapNotNull null
            val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return@mapNotNull null
            DirectUsbTarget(device, face, input, output)
        }
    }
    fun hasPermission(target: DirectUsbTarget): Boolean = manager.hasPermission(target.device)

    fun requestPermission(target: DirectUsbTarget, onResult: (Boolean) -> Unit): () -> Unit {
        val action = "${context.packageName}.SE400_USB_PERMISSION.${UUID.randomUUID()}"
        var registered = true
        lateinit var receiver: BroadcastReceiver
        fun dispose() { if (registered) { registered = false; context.unregisterReceiver(receiver) } }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != action) return
                dispose()
                // Query the system grant instead of trusting broadcast extras (also works with immutable PI).
                onResult(devices().any { it.id == target.id } && manager.hasPermission(target.device))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pending = PendingIntent.getBroadcast(context, target.device.deviceId,
            Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        try { manager.requestPermission(target.device, pending) } catch (e: Exception) { dispose(); pending.cancel(); throw e }
        return { dispose(); pending.cancel() }
    }

    /** Blocking, LUN 0 only. Never invokes UsbMassStorageDevice.init()/Partition/FS factories. */
    fun open(target: DirectUsbTarget): Session {
        if (devices().none { it.id == target.id }) throw IOException("USB device disconnected")
        if (!hasPermission(target)) throw IOException("USB permission missing")
        val capture = UsbDiagnosticCapture()
        val description = "USB VID=0x%04X PID=0x%04X".format(target.device.vendorId, target.device.productId) +
            "; manufacturer=${target.device.manufacturerName}; product=${target.device.productName}"
        val communication = DiagnosticUsbCommunication(UsbCommunicationFactory.createUsbCommunication(
            manager, target.device, target.usbInterface, target.output, target.input), capture)
        try {
            val driver = BlockDeviceDriverFactory.createBlockDevice(communication, 0)
            driver.init()
            val block = LibaumsBlockDevice(driver)
            capture.blocks = block.sectorCount; capture.blockSize = block.sectorSize
            val volume = Fat12Volume.mount(block, capture::bootSector)
            capture.warnings = volume.warnings
            return Session(volume, communication) { error -> DirectUsbFailure(error, capture.report(description, error)) }
        } catch (e: Exception) {
            runCatching { communication.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw DirectUsbFailure(e, capture.report(description, e))
        }
    }
    class Session(val volume: Fat12Volume, private val transport: Closeable,
        val failure: (Exception) -> DirectUsbFailure) : Closeable {
        override fun close() {
            try { transport.close() } catch (e: Exception) { throw failure(e) }
        }
    }
}
