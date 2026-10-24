// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Ten public/synthetic byte-identical cases. Python is needed only to refresh snapshots. */
class GatePolicyParityTest {
    private fun pes(points: List<Pair<Int,Int>>): ByteArray = PesWriter.write(EmbPattern(
        points.map { (x,y) -> Stitch(x,y,Command.STITCH) }, listOf(EmbPattern.DEFAULT_PALETTE[0])))
    private fun resource(name: String) = javaClass.getResourceAsStream("/sewout/gate-v5/$name")!!.readBytes()
    private fun current(name: String) = javaClass.getResourceAsStream("/sewout/gate-v6/$name")!!.readBytes()
    private fun cases(): Map<String,ByteArray> {
        fun line(vararg xs: Int) = pes(xs.map { it to 0 })
        val valid=line(0,0,40,40)
        val pointer=(0..3).sumOf { (valid[8+it].toInt() and 255) shl (8*it) }
        return linkedMapOf(
            "sampler" to resource("S1SAMPL.PES"),
            "s23sampler" to current("S23SAMPL.PES"),
            "T1PEDS" to current("T1PEDS.PES"),
            "T2OURS" to current("T2OURS.PES"),
            "T3NOZRO" to current("T3NOZRO.PES"),
            "density30" to pes(List(30) { it%5 to (it/5)%5 }),
            "density31" to pes(List(31) { it%5 to (it/5)%5 }),
            "repeat3" to line(0,0,0,40,40),
            "long_first" to line(200,200,240,240),
            "invalid_length" to valid.copyOf().also { for (i in 0..2) it[pointer+514+i]=0x7f },
        )
    }
    @Test fun tenCasesMatchPythonV6AndOriginPolicy() {
        val cases=cases()
        System.getenv("SEWOUT_PARITY_EXPORT")?.let { directory ->
            File(directory).mkdirs()
            cases.forEach { (name,bytes) -> File(directory,"$name.PES").writeBytes(bytes) }
            return // Explicit maintainer-only snapshot export, never set in CI.
        }
        val snapshot=Json.parseToJsonElement(current("reference.json").decodeToString()).jsonObject
        val expected=snapshot.getValue("cases").jsonObject
        assertEquals(10,cases.size)
        assertEquals(cases.keys,expected.keys)
        cases.forEach { (name,bytes) ->
            val e=expected.getValue(name).jsonObject
            val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(e.getValue("sha256").jsonPrimitive.content,sha,"$name input identity")
            for (origin in listOf(DesignOrigin.APP_GENERATED,DesignOrigin.IMPORTED)) {
                val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=origin))
                val verdict=e.getValue(if(origin==DesignOrigin.IMPORTED) "imported_policy_verdict" else "verdict").jsonPrimitive.content
                assertEquals(verdict,report.verdict.name,"$name $origin: ${report.findings}")
                e["metrics"]?.jsonObject?.let { m ->
                    val s=report.statistics
                    fun int(key: String,actual: Int) = assertEquals(m.getValue(key).jsonPrimitive.int,actual,"$name $key")
                    int("stitch_count",s.stitchCount)
                    int("cell_peak_05mm",s.peakCellPenetrations)
                    int("max_same_point_consecutive",s.maximumConsecutivePositions)
                    int("max_same_point_needle_only",s.maximumConsecutiveNeedles)
                    int("start_lock_missing",s.missingStartLocks)
                    int("end_lock_missing",s.missingEndLocks)
                    assertEquals(m.getValue("maximum_stitch_mm").jsonPrimitive.double,s.maximumStitchMm,1e-9,"$name maximum stitch")
                }
            }
        }
    }
}
