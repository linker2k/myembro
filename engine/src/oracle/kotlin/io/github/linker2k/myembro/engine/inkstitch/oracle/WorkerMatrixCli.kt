// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/svg/units.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// SVG boundary also follows lib/svg/path.py and lib/elements/stroke.py at the same commit.
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.inkstitch.elements.ElementCommands
import io.github.linker2k.myembro.engine.inkstitch.elements.Stroke
import io.github.linker2k.myembro.engine.inkstitch.elements.StrokeParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.round

/** Replays only our Kotlin engine. Never launches/imports the original or official Ink/Stitch code. */
object WorkerMatrixCli {
    private const val SOURCE_COMMIT = "b0edd96311ece82ee48816dc93466274b12c2a9c"
    private const val SVG = "http://www.w3.org/2000/svg"
    private const val INK = "http://inkstitch.org/namespace"
    private val json = Json { prettyPrint = true }
    private class Unsupported(val code: String, message: String) : IllegalArgumentException(message)
    private data class SvgResult(val groups: List<StitchGroup>, val collapseMm: Double, val minimumMm: Double, val elements: Int)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 3) { "Usage: WorkerMatrixCli MATRIX ORACLE_ROOT OUTPUT [FEATURES] [ONLY_ID]" }
        val matrixFile = File(args[0]).canonicalFile
        val oracleRoot = File(args[1]).canonicalFile
        val output = File(args[2]).canonicalFile.apply { mkdirs() }
        val features = args.getOrNull(3)?.split(',')?.toSet() ?: setOf("stitch_plan", "ties", "stroke")
        require(features.isNotEmpty() && features.all { it in setOf("stitch_plan", "ties", "stroke") })
        val onlyId = args.getOrNull(4).orEmpty()
        val matrixBytes = matrixFile.readBytes()
        val matrixHash = sha(matrixBytes)
        val matrix = json.parseToJsonElement(matrixBytes.decodeToString()).jsonObject
        val frozenFile = File(oracleRoot, "oracle-freeze.json")
        val frozen = json.parseToJsonElement(frozenFile.readText()).jsonObject
        require(frozen.getValue("matrix_sha256").jsonPrimitive.content == matrixHash) { "Frozen matrix SHA256 mismatch" }
        val selected = matrix.getValue("cases").jsonArray.map { it.jsonObject }.filter {
            it.getValue("feature").jsonPrimitive.content in features && (onlyId.isEmpty() || it.getValue("id").jsonPrimitive.content == onlyId)
        }
        require(selected.isNotEmpty()) { "No selected matrix cases" }
        val results = mutableListOf<JsonObject>()
        val identityLines = mutableListOf<String>()
        for (case in selected) {
            val id = case.getValue("id").jsonPrimitive.content
            val feature = case.getValue("feature").jsonPrimitive.content
            require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Unsafe matrix case ID" }
            val original = File(case.getValue("input").jsonPrimitive.content).canonicalFile
            val svg = File(oracleRoot, "inputs/$id.svg")
            val record = File(oracleRoot, "out/$feature/$id.oracle.json")
            val destination = File(output, "$feature/$id.pes").apply { parentFile.mkdirs() }
            if (destination.exists()) check(destination.delete()) { "Cannot remove stale output $destination" }
            val row = linkedMapOf<String, JsonElement>(
                "id" to JsonPrimitive(id), "feature" to JsonPrimitive(feature), "input" to JsonPrimitive(original.path),
                "input_sha256" to case.getValue("input_sha256"), "materialized_input" to JsonPrimitive(svg.path),
                "expected" to case.getValue("expected"),
            )
            try {
                val originalHash = sha(original.readBytes())
                require(originalHash == case.getValue("input_sha256").jsonPrimitive.content) { "Matrix input SHA256 mismatch" }
                val official = json.parseToJsonElement(record.readText()).jsonObject
                val mapping = official.getValue("mapping").jsonObject
                require(official.getValue("matrix_sha256").jsonPrimitive.content == matrixHash)
                require(mapping.getValue("source_sha256").jsonPrimitive.content == originalHash)
                val inputBytes = svg.readBytes()
                val derivedHash = sha(inputBytes)
                require(mapping.getValue("materialized_sha256").jsonPrimitive.content == derivedHash) { "Materialized SVG SHA256 mismatch" }
                row["materialized_sha256"] = JsonPrimitive(derivedHash)
                row["oracle_status"] = official.getValue("status")
                identityLines += "$id\t$originalHash\t$derivedHash"
                val source = parseSvg(inputBytes)
                if (source.groups.isEmpty()) throw Unsupported("NO_STITCHABLE_STROKE", "SVG has no visible supported stroke")
                // Typed SVG groups enter the shared source plan directly; the app geometry bridge is not needed here.
                val plan = StitchPlanBuilder.fromGroups(source.groups, source.collapseMm, source.minimumMm)
                val converted = SourceOutputBoundary.convert(plan)
                val pattern = converted.pattern
                row["source_output_origin_svg_px"] = JsonArray(listOf(JsonPrimitive(converted.origin.x),JsonPrimitive(converted.origin.y)))
                val bytes = PesWriter.write(pattern, PesWriteSettings(inkstitchCommands = true))
                destination.writeBytes(bytes)
                row["status"] = JsonPrimitive("GENERATED")
                row["path"] = JsonPrimitive(destination.path)
                row["sha256"] = JsonPrimitive(sha(bytes))
                row["bytes"] = JsonPrimitive(bytes.size)
                row["elements"] = JsonPrimitive(source.elements)
                row["groups"] = JsonPrimitive(source.groups.size)
                row["stitch_count"] = JsonPrimitive(pattern.stitchCount)
                row["collapse_len_mm"] = JsonPrimitive(source.collapseMm)
                row["min_stitch_len_mm"] = JsonPrimitive(source.minimumMm)
            } catch (e: Unsupported) {
                row["status"] = JsonPrimitive("X")
                row["error_code"] = JsonPrimitive(e.code)
                row["error"] = JsonPrimitive(e.message.orEmpty())
            } catch (e: Exception) {
                row["status"] = JsonPrimitive("ERROR")
                row["error_code"] = JsonPrimitive(e.javaClass.simpleName)
                row["error"] = JsonPrimitive(e.message.orEmpty())
            }
            results += JsonObject(row)
        }
        val inputList = "id\tsource_sha256\tmaterialized_sha256\n" + identityLines.joinToString("\n") + "\n"
        val commit = command("git", "rev-parse", "HEAD")
        val dirty = command("git", "status", "--porcelain").isNotEmpty()
        val manifest = buildJsonObject {
            put("schema_version", 1); put("owner", "embro-worker"); put("branch", command("git", "branch", "--show-current"))
            put("matrix", matrixFile.path); put("matrix_sha256", matrixHash)
            put("input_list_sha256", sha(inputList.toByteArray()))
            put("commit", commit); put("working_tree_dirty", dirty); put("source_commit", SOURCE_COMMIT)
            put("matrix_source_commit", matrix.getValue("source").jsonObject.getValue("commit"))
            put("oracle_freeze_sha256", sha(frozenFile.readBytes())); put("created_at", Instant.now().toString())
            put("pipeline", "materialized SVG -> transformed paths -> Stroke/LockStitches -> StitchPlanBuilder -> source output bbox-center/scale/bankers-round -> EmbPatternAdapter -> PesWriter v1 inkstitchCommands=true")
            put("curve_flattening", "pinned Inkex 1.4.1 CSP controls; running: transformed cspsubdiv at 0.1 SVG px; manual: original CSP anchors")
            put("inkex_source_commit", "2044255c7a79a0d0b5fb20cb8b0f573ed1ae7892")
            put("pec_viewport_prefix_policy", "pystitch PecWriter.write_pec_block serializes JUMP(-minX,-minY) outside pec_encode state; writer profile owns this prefix, not the CLI or plan")
            put("output_origin_policy", "lib/output.py default all-plan-stitch bbox center before scale and native rounding; explicit global origin/stop_position commands require SVG command resolution and remain unsupported")
            put("original_or_official_code_executed", false)
            put("counts", buildJsonObject { results.groupingBy { it.getValue("status").jsonPrimitive.content }.eachCount().forEach { (k,v) -> put(k,v) } })
            put("cases", JsonArray(results))
        }
        val manifestFile = File(output, "manifest.json")
        File(output, "inputs.tsv").writeText(inputList)
        manifestFile.writeText(json.encodeToString(JsonObject.serializer(), manifest) + "\n")
        println("${manifest.getValue("counts")} ${manifestFile.path}")
    }

    private fun parseSvg(bytes: ByteArray): SvgResult {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val root = factory.newDocumentBuilder().parse(bytes.inputStream()).documentElement
        require(root.namespaceURI == SVG && root.localName == "svg") { "Not an SVG document" }
        if (root.getElementsByTagNameNS(SVG, "style").length != 0) throw Unsupported("UNSUPPORTED_CSS", "Stylesheet selectors require the SVG import layer")
        val documentTransform = viewBox(root)
        val groups = mutableListOf<StitchGroup>()
        var elements = 0
        fun visit(node: Element, parentTransform: Affine2D, inherited: Map<String, String>) {
            if (node.namespaceURI != SVG) return
            val tag = node.localName
            if (tag in setOf("metadata", "defs", "title", "desc", "namedview")) return
            if (tag !in setOf("svg", "g", "a", "path")) throw Unsupported("UNSUPPORTED_SVG_ELEMENT", "Unsupported SVG element $tag")
            val style = inherited.toMutableMap()
            for (key in listOf("fill", "stroke", "stroke-width", "display", "visibility", "color", "opacity", "stroke-opacity", "fill-opacity", "stroke-dasharray", "clip-path", "mask", "filter")) {
                if (node.hasAttribute(key)) style[key] = node.getAttribute(key)
            }
            node.getAttribute("style").split(';').filter { it.contains(':') }.forEach {
                val (key,value) = it.split(':', limit=2); style[key.trim()] = value.trim()
            }
            if (style["display"] == "none" || style["opacity"]?.toDoubleOrNull() == 0.0) return
            if (style.keys.any { it in setOf("clip-path", "mask", "filter") && style[it] !in setOf(null, "none", "") }) throw Unsupported("UNSUPPORTED_SVG_EFFECT", "Clipping/masking/filtering must be resolved by the SVG import layer")
            val ownTransform = if (node.hasAttribute("transform")) Affine2D.parse(node.getAttribute("transform")) else Affine2D.IDENTITY
            val transform = parentTransform * ownTransform
            if (tag == "path" && style["visibility"] !in setOf("hidden", "collapse")) {
                val fill = style["fill"] ?: "black"
                if (fill != "none" && style["fill-opacity"]?.toDoubleOrNull() != 0.0) throw Unsupported("UNSUPPORTED_FILL", "This runner does not substitute running stitches for a fill element")
                val stroke = style["stroke"] ?: "none"
                if (stroke != "none" && style["stroke-opacity"]?.toDoubleOrNull() != 0.0) {
                    if (style["stroke-dasharray"] !in setOf(null, "none", "")) throw Unsupported("UNSUPPORTED_DASH", "Dashed-stroke conversion is not part of this runner")
                    val attrs = (0 until node.attributes.length).associate { index -> node.attributes.item(index).let { it.nodeName to it.nodeValue } }
                    val p = StrokeParameters.fromAttributes(attrs)
                    val pathData = node.getAttribute("d")
                    if (p.stroke_method == "ripple_stitch") throw Unsupported("UNSUPPORTED_RIPPLE_S1", "Ripple is outside S1")
                    val paths = if (p.stroke_method == "manual_stitch") ManualCspPath.parse(pathData,transform)
                        else ManualCspPath.flatten(pathData,transform,tolerance=0.1)
                    val lockParameters = LockStitchParameters(
                        ties = ink(node, "ties")?.toIntOrNull() ?: 0, force_lock_stitches = p.force_lock_stitches,
                        lock_start = ink(node,"lock_start") ?: "half_stitch", lock_end = ink(node,"lock_end") ?: "half_stitch",
                        lock_custom_start = ink(node,"lock_custom_start").orEmpty(), lock_custom_end = ink(node,"lock_custom_end").orEmpty(),
                        lock_start_scale_mm = ink(node,"lock_start_scale_mm")?.toDoubleOrNull() ?: 0.7,
                        lock_end_scale_mm = ink(node,"lock_end_scale_mm")?.toDoubleOrNull() ?: 0.7,
                        lock_start_scale_percent = ink(node,"lock_start_scale_percent")?.toDoubleOrNull() ?: 100.0,
                        lock_end_scale_percent = ink(node,"lock_end_scale_percent")?.toDoubleOrNull() ?: 100.0,
                    )
                    val locks = LockStitches.fromParameters(lockParameters)
                    val width = length(style["stroke-width"] ?: "1") * kotlin.math.sqrt(abs(transform.a * transform.d - transform.b * transform.c))
                    val commands = ElementCommands(ignore_object = ink(node,"ignore_object")?.lowercase() in setOf("true","yes","1","y","t"))
                    val previous = groups.lastOrNull()?.stitches?.lastOrNull()
                    groups += Stroke(paths, EmbThread(color(if (stroke == "currentColor") style["color"] ?: "black" else stroke)), p,
                        strokeWidth = width, elementId = node.getAttribute("id"), lockStart = locks.start, lockEnd = locks.end,
                        commands = commands).toStitchGroups(previous)
                    elements++
                }
            }
            for (i in 0 until node.childNodes.length) (node.childNodes.item(i) as? Element)?.let { visit(it, transform, style) }
        }
        visit(root, documentTransform, emptyMap())
        fun metadata(name: String, default: Double): Double {
            val nodes = root.getElementsByTagNameNS(INK, name)
            return if (nodes.length == 0) default else nodes.item(0).textContent.toDouble().also { require(it.isFinite()) }
        }
        return SvgResult(groups, metadata("collapse_len_mm",3.0), metadata("min_stitch_len_mm",0.1), elements)
    }

    /** Mirrors the pinned Ink/Stitch get_viewbox_transform composition and six-place scale formatting. */
    private fun viewBox(root: Element): Affine2D {
        if (!root.hasAttribute("viewBox")) return Affine2D.IDENTITY
        val box = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).map(String::toDouble)
        require(box.size == 4 && box[2] > 0 && box[3] > 0)
        var sx = length(root.getAttribute("width")) / box[2]
        var sy = length(root.getAttribute("height")) / box[3]
        val aspect = root.getAttribute("preserveAspectRatio").ifEmpty { "xMidYMid meet" }
        if (aspect != "none") { val uniform = if ("slice" in aspect) max(sx,sy) else min(sx,sy); sx=uniform; sy=uniform }
        fun six(value: Double) = round(value * 1_000_000) / 1_000_000
        return Affine2D.translation(six(-box[0]),six(-box[1])) * Affine2D.scale(six(sx),six(sy))
    }

    private fun length(text: String): Double {
        val found = Regex("^\\s*([-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)\\s*(mm|cm|in|px|pt|pc)?\\s*$").matchEntire(text)
            ?: throw Unsupported("UNSUPPORTED_SVG_LENGTH", "Unsupported SVG length '$text'")
        val value = found.groupValues[1].toDouble()
        return value * when(found.groupValues[2]) { "mm" -> Units.PIXELS_PER_MM; "cm" -> 10 * Units.PIXELS_PER_MM; "in" -> 96.0; "pt" -> 96.0/72; "pc" -> 16.0; else -> 1.0 }
    }
    private fun ink(node: Element, name: String): String? = node.getAttributeNS(INK,name).takeIf { it.isNotEmpty() }
    private fun color(text: String): Int {
        val value = when {
            text.matches(Regex("#[0-9a-fA-F]{6}")) -> text.drop(1).toInt(16)
            text.matches(Regex("#[0-9a-fA-F]{3}")) -> text.drop(1).flatMap { listOf(it,it) }.joinToString("").toInt(16)
            text == "black" -> 0
            else -> throw Unsupported("UNSUPPORTED_COLOR", "Unsupported CSS color '$text'")
        }
        return value or 0xff000000.toInt()
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { "Command failed: ${args.joinToString(" ")}" }
        return output
    }
}
