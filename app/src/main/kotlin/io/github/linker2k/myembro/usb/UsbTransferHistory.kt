// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** File bytes and verified FAT data-cluster allocation are separate; SAF/old logs may not know allocation. */
data class UsbTransferredFile(val name: String, val size: Long,
    val allocatedBytes: Long? = null, val allocationUnitBytes: Int? = null, val sourceName: String? = null, val manualJumpThreadCount: Int? = null)

enum class UsbTransferOutcome { SUCCESS, PARTIAL, FAILURE }
enum class UsbTransferNotice { NAME_COLLISION, FILESYSTEM }
enum class UsbTransferProblem { SPACE, VERIFY, PERMISSION, DISCONNECTED, NO_DEVICE, FOLDER, OTHER }

data class UsbTransferRecord(
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val drive: String,
    val files: List<UsbTransferredFile> = emptyList(),
    val failures: List<Pair<String, String>> = emptyList(),
    val remainingBytes: Long? = null,
    val notices: Set<UsbTransferNotice> = emptySet(),
    val technical: String = "",
    val problemHint: UsbTransferProblem? = null,
) {
    /** null for old records/unconfirmed machines, never silently converted to zero. */
    val manualJumpThreadCount: Int? get() = files.takeIf { it.isNotEmpty() && it.all { f -> f.manualJumpThreadCount != null } }
        ?.sumOf { it.manualJumpThreadCount!! }
    val allocatedBytes: Long? get() = files.takeIf { it.isNotEmpty() && it.all { f -> f.allocatedBytes != null } }
        ?.sumOf { it.allocatedBytes!! }
    val allocationUnitBytes: Int? get() = files.map { it.allocationUnitBytes }.distinct().singleOrNull()
    val outcome: UsbTransferOutcome get() = when {
        files.isEmpty() -> UsbTransferOutcome.FAILURE
        failures.isNotEmpty() -> UsbTransferOutcome.PARTIAL
        else -> UsbTransferOutcome.SUCCESS
    }
    val problem: UsbTransferProblem get() {
        problemHint?.let { return it }
        val text = failures.joinToString { it.second }.lowercase()
        return when {
            "space" in text || "root entr" in text -> UsbTransferProblem.SPACE
            "read-back" in text || "verification" in text || "verify" in text || "differs" in text -> UsbTransferProblem.VERIFY
            "permission" in text || "access" in text -> UsbTransferProblem.PERMISSION
            "disconnect" in text || "detach" in text -> UsbTransferProblem.DISCONNECTED
            else -> UsbTransferProblem.OTHER
        }
    }
    /** Raw reasons and mount snapshots stay here, never in the default user-facing summary. */
    fun diagnostics(): String = buildString {
        appendLine("myembro USB transfer $id")
        appendLine("Time (epoch ms): $time\nDrive: $drive\nResult: $outcome")
        files.forEach {
            appendLine("Sent: ${it.name}; ${it.size} bytes; allocated=${it.allocatedBytes ?: "unknown"} bytes; cluster=${it.allocationUnitBytes ?: "unknown"} bytes; source=${it.sourceName ?: "unknown"}")
        }
        appendLine("Size units: KB=1024 bytes; summaries rounded; allocation is summed per file")
        failures.forEach { appendLine("Failed: ${it.first}: ${it.second}") }
        appendLine("Remaining bytes: ${remainingBytes ?: "unknown"}")
        notices.forEach { appendLine("Notice: $it") }
        append(technical)
    }
}

/** Atomic, app-private last-20 log. The undismissed result survives activity/process recreation. */
internal class UsbTransferHistory(private val file: File) {
    data class State(val records: List<UsbTransferRecord> = emptyList(), val pendingId: String? = null) {
        val pending: UsbTransferRecord? get() = records.firstOrNull { it.id == pendingId }
    }
    @Synchronized fun read(): State {
        if (!file.exists()) return State()
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val records = root.getValue("records").jsonArray.take(20).map { element ->
            val r = element.jsonObject
            UsbTransferRecord(
                id = r.getValue("id").jsonPrimitive.content, time = r.getValue("time").jsonPrimitive.long,
                drive = r.getValue("drive").jsonPrimitive.content,
                files = r.getValue("files").jsonArray.map { f -> f.jsonObject.let {
                    UsbTransferredFile(it.getValue("name").jsonPrimitive.content, it.getValue("size").jsonPrimitive.long,
                        it["allocatedBytes"]?.jsonPrimitive?.longOrNull, it["allocationUnitBytes"]?.jsonPrimitive?.intOrNull,
                        it["sourceName"]?.jsonPrimitive?.contentOrNull, it["manualJumpThreadCount"]?.jsonPrimitive?.intOrNull)
                } },
                failures = r.getValue("failures").jsonArray.map { f -> f.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content } },
                remainingBytes = r["remainingBytes"]?.jsonPrimitive?.longOrNull,
                notices = r.getValue("notices").jsonArray.map { UsbTransferNotice.valueOf(it.jsonPrimitive.content) }.toSet(),
                technical = r.getValue("technical").jsonPrimitive.content,
                problemHint = r["problemHint"]?.jsonPrimitive?.contentOrNull?.let(UsbTransferProblem::valueOf),
            )
        }
        return State(records, root["pendingId"]?.jsonPrimitive?.contentOrNull)
    }
    @Synchronized fun add(record: UsbTransferRecord): State = State(
        (listOf(record) + read().records.filterNot { it.id == record.id }).take(20), record.id,
    ).also(::write)
    @Synchronized fun dismiss(id: String): State = read().let {
        if (it.pendingId == id) it.copy(pendingId = null) else it
    }.also(::write)

    private fun write(state: State) {
        val json = buildJsonObject {
            put("pendingId", state.pendingId?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("records") { state.records.forEach { r -> add(buildJsonObject {
                put("id", r.id); put("time", r.time); put("drive", r.drive)
                put("remainingBytes", r.remainingBytes?.let(::JsonPrimitive) ?: JsonNull)
                putJsonArray("files") { r.files.forEach { f -> add(buildJsonObject {
                    put("name", f.name); put("size", f.size)
                    put("allocatedBytes", f.allocatedBytes?.let(::JsonPrimitive) ?: JsonNull)
                    put("allocationUnitBytes", f.allocationUnitBytes?.let(::JsonPrimitive) ?: JsonNull)
                    put("sourceName", f.sourceName?.let(::JsonPrimitive) ?: JsonNull)
                    put("manualJumpThreadCount", f.manualJumpThreadCount?.let(::JsonPrimitive) ?: JsonNull)
                }) } }
                putJsonArray("failures") { r.failures.forEach { f -> add(buildJsonArray { add(f.first); add(f.second) }) } }
                putJsonArray("notices") { r.notices.forEach { add(it.name) } }
                put("technical", r.technical)
                put("problemHint", r.problemHint?.name?.let(::JsonPrimitive) ?: JsonNull)
            }) } }
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { stream -> stream.write(json.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** Only the exact firmware quirks confirmed on the SE400 are informational, not user warnings. */
internal fun expectedSe400Note(message: String, geometry: io.github.linker2k.myembro.engine.fat12.Fat12Volume.Geometry): Boolean {
    if (geometry.startSector != 32L || geometry.clusterCount != 115 || geometry.declaredClusterCount != 119 ||
        geometry.bytesPerSector != 512 || geometry.sectorsPerCluster != 8 || geometry.rootEntryCount != 512) return false
    return message == "BPB media byte differs from FAT[0]: BPB=0xF8 FAT=0x00 (unchanged)" ||
        message == "MBR partition sectors=987 clamped to device remainder=956 at start=32 (MBR unchanged)" ||
        message == "BPB total sectors=987 clamped to device/partition sectors=956 (boot sector unchanged)" ||
        message == "FAT12 data clusters: declared=119 usable=115; allocation limited to complete clusters within device bounds" ||
        message.matches(Regex("Nonstandard FAT[12] reserved entries: FAT\\[0]=0x000 FAT\\[1]=0x000 \\(unchanged\\)"))
}
