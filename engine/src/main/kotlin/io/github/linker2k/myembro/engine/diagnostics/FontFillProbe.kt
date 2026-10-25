// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.diagnostics

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.FillTrace
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.engine.io.PesWriter
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Diagnostic replay of an actual app-prepared object; no font extraction or algorithm substitution. */
object FontFillProbe {
    fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun run(projectJson:String,repeats:Int=10):String=buildJsonObject {
        put("object_sha256",sha(projectJson.toByteArray(Charsets.UTF_8)))
        put("runs",buildJsonArray { repeat(repeats) { iteration->
            val stages=buildJsonArray {
                val pattern=FillTrace.observe({ name,payload->add(buildJsonObject {
                    put("stage",name);put("sha256",sha(payload.toByteArray(Charsets.UTF_8)));put("payload",payload)
                }) }) { ProjectJson.decode(projectJson).objects.single().localPattern() }
                val finished=GeneratedPatternProcessor.finish(pattern,options=SewingOptions())
                val pes=PesWriter.write(finished)
                add(buildJsonObject { put("stage","PES");put("sha256",sha(pes));put("bytes",pes.size)
                    put("stitches",pattern.stitchCount) })
            }
            add(buildJsonObject { put("iteration",iteration);put("stages",stages) })
        } })
    }.toString()
}
