// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.Fat12Volume
import io.github.linker2k.myembro.engine.io.PesExport
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import java.io.File
import java.io.IOException

internal class DirectUsbExporter(private val access: DirectUsbAccess) {
    data class Payload(val source: String, val name: String, val bytes: ByteArray, val converted: Boolean)
    data class SpacePlan(val fingerprint: String, val candidates: List<Fat12Volume.Entry>,
        val payloads: List<Payload>, val failures: List<Pair<String, String>>, val freeBytes: Long,
        val requiredBytes: Long, val freeEntries: Int, val clusterBytes: Int) {
        fun canProceed(names: Set<String>): Boolean {
            if (names.isEmpty() || names.any { name -> candidates.none { it.name == name } }) return false
            val selected = candidates.filter { it.name in names }
            val reclaimed = selected.sumOf { ((it.size + clusterBytes - 1) / clusterBytes) * clusterBytes }
            val slots = selected.sumOf { 1 + it.longNameSlots.size }
            return freeBytes + reclaimed >= requiredBytes && freeEntries + slots >= payloads.size
        }
    }
    sealed interface Result {
        data class Complete(val report: UsbExporter.Report, val failure: DirectUsbFailure? = null) : Result
        data class NeedSpace(val plan: SpacePlan) : Result
    }

    fun export(target: DirectUsbTarget, designs: List<File>, settings: PesWriteSettings): Result {
        val failures = mutableListOf<Pair<String, String>>()
        val payloads = designs.mapNotNull { file -> runCatching {
            val prepared = PesExport.prepare(file.readBytes(), file.name, settings)
            Payload(file.name, prepared.fileName, prepared.bytes, prepared.converted)
        }.onFailure { failures += file.name to (it.message ?: it.javaClass.simpleName) }.getOrNull() }
        if (payloads.isEmpty()) return Result.Complete(UsbExporter.Report(emptyList(), 0, failures))
        return access.open(target).use { session ->
            try {
                val volume = session.volume
                try { volume.checkSpace(payloads.map { it.bytes.size }) }
                catch (e: Fat12Volume.NoSpace) { return@use Result.NeedSpace(plan(volume, payloads, failures, e)) }
                write(session, payloads, failures)
            } catch (e: Exception) { throw session.failure(e) }
        }
    }

    fun resume(target: DirectUsbTarget, plan: SpacePlan, deleteNames: Set<String>): Result = access.open(target).use { session ->
        try {
            val fs = session.volume
            // No deletion if the device/volume was changed since the exact list was shown to the user.
            if (fs.fingerprint() != plan.fingerprint) throw IOException("USB contents changed; reopen export before deleting")
            require(plan.canProceed(deleteNames)) { "Select enough existing PES files to free space/root entries" }
            for (name in deleteNames) {
                val current = fs.listRoot().singleOrNull { it.name == name }
                require(current != null && current in plan.candidates && !current.isDirectory && !current.isReadOnly && current.name.endsWith(".PES", true))
            }
            for (name in deleteNames) fs.deleteFile(name)
            fs.checkSpace(plan.payloads.map { it.bytes.size })
            write(session, plan.payloads, plan.failures)
        } catch (e: Exception) { throw session.failure(e) }
    }

    private fun plan(fs: Fat12Volume, payloads: List<Payload>, failures: List<Pair<String, String>>, e: Fat12Volume.NoSpace) =
        SpacePlan(fs.fingerprint(), fs.listRoot().filter { !it.isDirectory && !it.isReadOnly && it.name.endsWith(".PES", true) },
            payloads, failures, fs.freeBytes, e.requiredBytes, fs.freeRootEntries, fs.geometry.clusterBytes)

    private fun write(session: DirectUsbAccess.Session, payloads: List<Payload>, initialFailures: List<Pair<String, String>>): Result {
        val fs = session.volume
        val exported = mutableListOf<String>()
        val failures = initialFailures.toMutableList()
        var converted = 0
        var failure: DirectUsbFailure? = null
        for ((index, payload) in payloads.withIndex()) {
            try {
                exported += fs.writeFile(payload.name, payload.bytes).name
                if (payload.converted) converted++
            } catch (e: Exception) {
                failure = session.failure(e)
                // Never retry against a potentially half-written filesystem or continue after detach.
                payloads.drop(index).forEach { failures += it.source to (e.message ?: e.javaClass.simpleName) }
                break
            }
        }
        return Result.Complete(UsbExporter.Report(exported, converted, failures, fs.warnings), failure)
    }
}
