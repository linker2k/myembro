// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

internal object ReaderComparison {
    fun json(p: EmbPattern) = buildJsonObject {
        put("stitches", buildJsonArray { p.stitches.forEach { s -> add(buildJsonArray { add(s.x); add(s.y); add(s.command.name) }) } })
        put("threads", buildJsonArray { p.threads.forEach { t -> add(buildJsonObject {
            put("color", t.color and 0xffffff); put("description", t.description); put("catalog", t.catalogNumber); put("brand", t.brand); put("chart", t.chart)
        }) } })
    }
    fun write(ext: String, p: EmbPattern) = when(ext) {
        "jef" -> JefWriter.write(p, "20000102030405")
        "exp" -> ExpWriter.write(p)
        else -> Vp3Writer.write(p)
    }
}

/** Explicit full sweep. No Python invocation or fixture updates in tests. */
object FormatReaderSampleCli {
    fun files(repo: File): List<File> {
        val golden = File(repo, "engine/src/test/resources/fixtures")
        // Twelve existing PES/PEC originals: six PES versions, three PEC, three cross-writer PES.
        return listOf("flower", "long_moves", "stops").flatMap { n -> listOf("v1.pes", "v6.pes", "pec").map { File(golden, "$n.$it") } } +
            File(golden, "embroidepy").listFiles()!!.filter { it.extension == "pes" }.sortedBy { it.name } +
            File(repo, "app/src/main/assets/samples").listFiles()!!.filter { it.extension == "pes" }.sortedBy { it.name }
    }
    @JvmStatic fun main(args: Array<String>) {
        val repo = File(args[0]); val root = File(args[1]).apply { mkdirs() }
        val files = files(repo)
        check(files.size == 148) { "Expected 12 goldens + 136 samples, got ${files.size}" }
        val manifest = buildJsonArray {
            files.forEachIndexed { i, file ->
                val id = "%03d-%s".format(i, file.name)
                val source = PesReader.read(file.readBytes())
                File(root, "$id.source.json").writeText(ReaderComparison.json(source).toString())
                for (ext in listOf("jef", "exp", "vp3")) {
                    val bytes = ReaderComparison.write(ext, source)
                    File(root, "$id.$ext").writeBytes(bytes)
                    val result = PatternReader.read(bytes, "$id.$ext")
                    File(root, "$id.$ext.json").writeText(ReaderComparison.json(result).toString())
                }
                add(id)
            }
        }
        File(root, "manifest.json").writeText(manifest.toString())
        println("FORMAT_READER_EXPORT golden=12 samples=136 formats=3 files=444")
    }
}

class FormatReaderTest {
    @Test fun frozenPythonResultsMatchAllCommandsAndThreadIdentity() {
        val root = File("src/test/resources/format-readers")
        val manifest = kotlinx.serialization.json.Json.parseToJsonElement(File(root,"manifest.json").readText()).jsonArray
        for (entry in manifest) {
            val name = entry.jsonPrimitive.content
            val expected = kotlinx.serialization.json.Json.parseToJsonElement(File(root,"$name.json").readText())
            val sourceFile = FormatReaderSampleCli.files(File("..")).single { it.name == name.substring(4).substringBeforeLast('.') }
            assertContentEquals(File(root,name).readBytes(),ReaderComparison.write(name.substringAfterLast('.'),PesReader.read(sourceFile.readBytes())),name)

            assertEquals(expected, ReaderComparison.json(PatternReader.read(File(root,name).readBytes(),name)), name)
        }
    }
    @Test fun sourceRoundTripDifferencesRemainExplicit() {
        val root = File("src/test/resources/format-readers")
        val rows = kotlinx.serialization.json.Json.parseToJsonElement(File(root,"source-differences.json").readText()).jsonArray
        val repo = File("..").canonicalFile
        val files = FormatReaderSampleCli.files(repo)
        assertEquals(148, files.size)
        for ((i,file) in files.withIndex()) {
            val source = PesReader.read(file.readBytes())
            for ((j,ext) in listOf("jef","exp","vp3").withIndex()) {
                val result = PatternReader.read(ReaderComparison.write(ext,source),"file.$ext")
                val expected = rows[i*3+j].jsonObject
                assertEquals(expected["name"]!!.jsonPrimitive.content,"%03d-%s".format(i,file.name))
                assertEquals(expected["format"]!!.jsonPrimitive.content,ext)
                val a = source.stitches; val b = result.stitches
                val count = maxOf(a.size,b.size)
                val boundariesA = a.withIndex().filter { it.value.command in setOf(Command.STOP,Command.COLOR_CHANGE) }
                val boundariesB = b.withIndex().filter { it.value.command in setOf(Command.STOP,Command.COLOR_CHANGE) }
                val actual = mapOf(
                    "coordinates" to (0 until count).count { k -> a.getOrNull(k)?.let { it.x to it.y } != b.getOrNull(k)?.let { it.x to it.y } },
                    "commands" to (0 until count).count { k -> a.getOrNull(k)?.command != b.getOrNull(k)?.command },
                    "colorBoundaries" to (0 until maxOf(boundariesA.size,boundariesB.size)).count { k -> boundariesA.getOrNull(k) != boundariesB.getOrNull(k) })
                for ((key,value) in actual) assertEquals(expected["source"]!!.jsonObject[key]!!.jsonPrimitive.int,value,"${file.name} $ext $key")
            }
        }
    }
    @Test fun malformedHeadersAndBlocksAreRejected() {
        for (name in listOf("bad.jef", "bad.vp3")) assertFailsWith<EmbFormatException> { PatternReader.read(ByteArray(16), name) }
        val p = EmbPattern(listOf(Stitch(10,20,Command.STITCH),Stitch(30,40,Command.END)),listOf(EmbThread(0xffff0000.toInt())))
        val bytes = Vp3Writer.write(p)
        assertFailsWith<EmbFormatException> { Vp3Reader.read(bytes.copyOf(bytes.size-10)) }
    }
    @Test fun importedDensityPolicyRemainsThirtyAllowedThirtyOneBlocked() {
        for (ext in listOf("jef", "exp", "vp3")) for (count in listOf(16,30,31)) {
            val p = EmbPattern(List(count) { i -> Stitch(10+i%2,20+(i/2)%2,Command.STITCH) },listOf(EmbThread(0xff000000.toInt())))
            val imported = PatternReader.read(ReaderComparison.write(ext,p), "dense.$ext")
            assertEquals(DesignOrigin.IMPORTED,SewoutCheck.originOf(imported))
            val report = SewoutCheck.check(imported)
            assertEquals(count > 30, report.hasBlockingIssues, "$ext count=$count")
        }
    }
}
