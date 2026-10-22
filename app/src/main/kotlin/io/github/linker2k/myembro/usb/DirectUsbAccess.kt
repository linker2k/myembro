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
        val communication = UsbCommunicationFactory.createUsbCommunication(manager, target.device, target.usbInterface, target.output, target.input)
        try {
            val driver = BlockDeviceDriverFactory.createBlockDevice(communication, 0)
            driver.init()
            return Session(Fat12Volume.mount(LibaumsBlockDevice(driver)), communication)
        } catch (e: Exception) { communication.close(); throw e }
    }
    class Session(val volume: Fat12Volume, private val transport: Closeable) : Closeable {
        override fun close() = transport.close()
    }
}
