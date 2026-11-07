// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json as KotlinJson
import java.security.MessageDigest
import kotlin.test.*

internal object FormatWriterFixtures {
    private fun bytes(name:String)=checkNotNull(javaClass.getResourceAsStream("/format-writers/$name")).use{it.readBytes()}
    private val manifest=KotlinJson.parseToJsonElement(bytes("provenance.json").decodeToString()).jsonObject.getValue("outputs").jsonObject
    val patterns=KotlinJson.parseToJsonElement(bytes("patterns.json").decodeToString()).jsonArray.map { value ->
        val row=value.jsonObject
        row.getValue("id").jsonPrimitive.content to EmbPattern(
            row.getValue("stitches").jsonArray.map { item -> val s=item.jsonArray
                Stitch(s[0].jsonPrimitive.int,s[1].jsonPrimitive.int,Command.valueOf(s[2].jsonPrimitive.content)) },
            row.getValue("threads").jsonArray.map { item -> val t=item.jsonObject
                EmbThread(0xff000000.toInt() or t.getValue("color").jsonPrimitive.int,
                    t["description"]?.jsonPrimitive?.content,t["catalog"]?.jsonPrimitive?.content) },
            row.getValue("metadata").jsonObject.mapValues{it.value.jsonPrimitive.content})
    }
    fun compare(name:String,actual:ByteArray) {
        val expected=bytes(name)
        val sha=MessageDigest.getInstance("SHA-256").digest(expected).joinToString(""){"%02x".format(it.toInt() and 255)}
        assertEquals(manifest.getValue(name).jsonObject.getValue("sha256").jsonPrimitive.content,sha,"oracle SHA $name")
        val first=(0 until minOf(expected.size,actual.size)).firstOrNull{expected[it]!=actual[it]}
            ?: if(expected.size!=actual.size)minOf(expected.size,actual.size) else -1
        assertContentEquals(expected,actual,"$name firstDifferentByteOffset=$first expectedSize=${expected.size} actualSize=${actual.size}")
        println("WRITER_PARITY $name PASS bytes=${actual.size} firstDifferentByteOffset=$first")
    }
}

class DstWriterTest {
    @Test fun threeBundledCc0Representatives(){FormatWriterSamples.compare("dst"){DstWriter.write(it)}}
    @Test fun defaultsMatchIsolatedPyembroidery151Oracle() {
        for((id,p)in FormatWriterFixtures.patterns)FormatWriterFixtures.compare("$id.dst",DstWriter.write(p))
    }
    @Test fun extendedHeaderMatchesIsolatedPyembroidery151Oracle() {
        for((id,p)in FormatWriterFixtures.patterns)FormatWriterFixtures.compare("$id.extended.dst",DstWriter.write(p,DstWriteSettings(extendedHeader=true)))
    }
    @Test fun writerDoesNotMutateSource() {
        for((_,p)in FormatWriterFixtures.patterns){
            val stitches=p.stitches.toList();val threads=p.threads.toList();val metadata=p.metadata.toMap()
            DstWriter.write(p);assertEquals(stitches,p.stitches);assertEquals(threads,p.threads);assertEquals(metadata,p.metadata)
        }
    }
}
