// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.Fat12Volume
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.io.PesExport
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.sewout.DesignOrigin
import io.github.linker2k.myembro.library.DesignFormats
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import java.io.File
import java.io.IOException

internal class DirectUsbExporter(private val access: DirectUsbAccess) {
    data class Payload(val source: String, val name: String, val bytes: ByteArray, val converted: Boolean, val designLabel: String? = null, val automaticJumpTrim: Boolean? = null, val machineName: String? = null, val hoop: HoopSpec = HoopSpec.H100, val origin: DesignOrigin = DesignOrigin.IMPORTED)
    data class SpacePlan(val fingerprint: String, val candidates: List<Fat12Volume.Entry>,
        val payloads: List<Payload>, val failures: List<Pair<String, String>>, val freeBytes: Long,
        val requiredBytes: Long, val freeSlots: Set<Int>, val clusterBytes: Int, val requiredSlots: List<Int>,
        val rootEntryCount: Int, val diagnostics: String = "") {
        val freeEntries: Int get() = freeSlots.size
        val requiredEntries: Int get() = requiredSlots.sum()
        fun canProceed(names: Set<String>): Boolean {
            if (names.isEmpty() || names.any { name -> candidates.none { it.name == name } }) return false
            val selected = candidates.filter { it.name in names }
            val reclaimed = selected.sumOf { ((it.size + clusterBytes - 1) / clusterBytes) * clusterBytes }
            val free = freeSlots + selected.flatMap { it.longNameSlots + it.rootIndex }
            // Same contiguous first-fit check Fat12Volume.checkSpace runs after the deletions.
            return freeBytes + reclaimed >= requiredBytes &&
                Fat12Volume.fitsRootSlots(BooleanArray(rootEntryCount) { it in free }, requiredSlots)
        }
    }
    sealed interface Result {
        data class Complete(val report: UsbExporter.Report, val failure: DirectUsbFailure? = null) : Result
        data class NeedSpace(val plan: SpacePlan) : Result
    }

    fun export(target: DirectUsbTarget, designs: List<File>, settings: PesWriteSettings, sewingOptions: SewingOptions? = null, machine: io.github.linker2k.myembro.engine.sewout.SewoutMachine? = null): Result {
        val failures = mutableListOf<Pair<String, String>>()
        val payloads = designs.mapNotNull { file -> runCatching {
            val prepared = DesignFormats.preparePes(file, settings, sewingOptions)
            // Only parse a content label if the actual filename contains no meaningful design name.
            val label = if (UsbDesignNames.needsDesignLabel(file.name))
                runCatching { DesignFormats.read(file).metadata["name"] }.getOrNull() else null
            Payload(file.name, prepared.fileName, prepared.bytes, prepared.converted, label, machine?.automaticJumpTrim?.value, machine?.name, prepared.hoop, prepared.origin)
        }.onFailure { failures += file.name to (it.message ?: it.javaClass.simpleName) }.getOrNull() }
        if (payloads.isEmpty()) return Result.Complete(UsbExporter.Report(emptyList(), 0, failures))
        return access.open(target).use { session ->
            try {
                val volume = session.volume
                try { volume.checkSpace(payloads.map { it.bytes.size }, names(volume.listRoot(), payloads).map { it.second }) }
                catch (e: Fat12Volume.NoSpace) { return@use Result.NeedSpace(plan(volume, payloads, failures, e, session.diagnostics())) }
                write(session, payloads, failures)
            } catch (e: Exception) { throw session.failure(e) }
        }
    }

    fun resume(target: DirectUsbTarget, plan: SpacePlan, deleteNames: Set<String>): Result {
        // Validate a frozen copy before opening the device, especially before deleting anything.
        val payloads = checkedPayloads(plan.payloads)
        return access.open(target).use { session ->
            try {
                val fs = session.volume
                // No deletion if the device/volume changed after the user saw the exact list.
                if (fs.fingerprint() != plan.fingerprint) throw IOException("USB contents changed; reopen export before deleting")
                require(plan.canProceed(deleteNames)) { "Select enough existing PES files to free space/root entries" }
                for (name in deleteNames) {
                    val current = fs.listRoot().singleOrNull { it.name == name }
                    require(current != null && current in plan.candidates && !current.isDirectory && !current.isReadOnly && current.name.endsWith(".PES", true))
                }
                for (name in deleteNames) fs.deleteFile(name)
                fs.checkSpace(payloads.map { it.bytes.size }, names(fs.listRoot(), payloads).map { it.second })
                write(session, payloads, plan.failures)
            } catch (e: Exception) { throw session.failure(e) }
        }
    }

    private fun plan(fs: Fat12Volume, payloads: List<Payload>, failures: List<Pair<String, String>>, e: Fat12Volume.NoSpace, diagnostics: String) =
        SpacePlan(fs.fingerprint(), fs.listRoot().filter { !it.isDirectory && !it.isReadOnly && it.name.endsWith(".PES", true) },
            payloads, failures, fs.freeBytes, e.requiredBytes, fs.freeRootSlots, fs.geometry.clusterBytes,
            names(fs.listRoot(), payloads).map { Fat12Volume.entrySlots(it.second) }, fs.geometry.rootEntryCount, diagnostics)

    companion object {
        /** (8.3 alias, long name or null when identical) for each payload, in write order, so the
         * space check and the writes allocate exactly the same names and root slots. */
        internal fun names(existing: List<Fat12Volume.Entry>, payloads: List<Payload>): List<Pair<String, String?>> {
            val taken = existing.flatMap { listOfNotNull(it.name, it.longName) }.toMutableList()
            return payloads.map { payload ->
                val long = UsbDesignNames.longName(payload.name, taken)
                val alias = UsbDesignNames.unique(payload.source, taken, payload.designLabel)
                taken += long; taken += alias
                alias to long.takeIf { it != alias }
            }
        }

        private fun checkedPayloads(payloads: List<Payload>): List<Payload> = payloads.map {
            it.copy(bytes = it.bytes.copyOf())
        }.also { frozen ->
            frozen.forEach { PesExport.requireSafe(it.bytes, it.hoop, it.origin) }
        }

        internal fun write(session: DirectUsbAccess.Session, payloads: List<Payload>, initialFailures: List<Pair<String, String>>): Result {
            // Reject the entire batch before any filesystem mutation. Keep validation failures
            // separate from I/O failures: validation has not poisoned the volume.
            val frozen = try { checkedPayloads(payloads) } catch (e: Exception) {
                return Result.Complete(UsbExporter.Report(emptyList(), 0,
                    initialFailures + payloads.map { it.source to (e.message ?: e.javaClass.simpleName) }))
            }
            val fs = session.volume
            val exported = mutableListOf<String>()
            val written = mutableListOf<UsbTransferredFile>()
            val notices = mutableSetOf<UsbTransferNotice>()
            if (fs.warnings.any { !expectedSe400Note(it, fs.geometry) }) notices += UsbTransferNotice.FILESYSTEM
            var remaining: Long? = fs.freeBytes
            val failures = initialFailures.toMutableList()
            var converted = 0
            var failure: DirectUsbFailure? = null
            // Root listing was already validated at mount; this reads no device data.
            val planned = names(fs.listRoot(), frozen)
            for ((index, payload) in frozen.withIndex()) {
                try {
                    // Compare what the user sees: a changed alias alone (DESIGN~1.PES) is not a rename.
                    val preferred = names(emptyList(), listOf(payload)).single().let { it.second ?: it.first }
                    val (alias, long) = planned[index]
                    val entry = fs.writeFile(alias, payload.bytes, long)
                    val shown = entry.longName ?: entry.name
                    exported += shown
                    written += UsbTransferredFile(shown, entry.size,
                        usbAllocatedBytes(entry.size, fs.geometry.clusterBytes), fs.geometry.clusterBytes, sourceName = payload.source, automaticJumpTrim = payload.automaticJumpTrim, machineName = payload.machineName)
                    if (shown != preferred) notices += UsbTransferNotice.NAME_COLLISION
                    remaining = fs.freeBytes
                    if (payload.converted) converted++
                } catch (e: Exception) {
                    remaining = null // A failed mutation poisons the session; do not query it again.
                    failure = session.failure(e)
                    // Never retry against a potentially half-written filesystem or continue after detach.
                    frozen.drop(index).forEach { failures += it.source to (e.message ?: e.javaClass.simpleName) }
                    break
                }
            }
            return Result.Complete(UsbExporter.Report(exported, converted, failures, fs.warnings,
                written, remaining, notices, (failure?.diagnostics ?: session.diagnostics()) +
                    "\nUsable data bytes: ${fs.geometry.clusterCount} x ${fs.geometry.clusterBytes} = ${fs.geometry.clusterCount.toLong() * fs.geometry.clusterBytes}; free bytes=$remaining; used bytes=${remaining?.let { fs.geometry.clusterCount.toLong() * fs.geometry.clusterBytes - it } ?: "unknown"}"), failure)
        }
    }
}
