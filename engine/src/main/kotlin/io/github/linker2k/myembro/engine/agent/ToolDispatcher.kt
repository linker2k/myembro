// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import kotlin.math.*

/** Snapshot-in/snapshot-out kernel, NOT the proposed session/turn/history controller.
 * No implicit selection, filesystem, Android, network, UUID or global session state.
 * Host resolves frames/assets and consent; JSON cannot grant itself permission. */
class ToolDispatcher(
    private val schema: ToolSchema = ToolSchema.bundled(),
    frames: Map<String, HoopSpec> = mapOf("H100" to HoopSpec.H100, "H130x180" to HoopSpec.H130x180, "H160x260" to HoopSpec.H160x260),
    assets: Map<String, ByteArray> = emptyMap(),
) {
    private val frames = frames.mapValues { (_, h) -> h.copy(positions = h.positions.toList()) }
    private val assets = assets.mapValues { it.value.copyOf() }
    data class Result(val project: Project, val output: JsonObject, val pesBytes: ByteArray? = null,
        val preparedCommand: ProjectCommand? = null) {
        val ok get() = output.getValue("ok").jsonPrimitive.boolean
        val code get() = output["code"]?.jsonPrimitive?.content
    }
    fun dispatch(project: Project, revision: String, name: String, arguments: JsonElement, confirmed: Boolean = false): Result {
        fun failure(code: String, message: String, details: JsonObject = buildJsonObject {}) = Result(project, buildJsonObject {
            put("ok", false); put("code", code); put("message", message); put("details", details)
        })
        if (!schema.contains(name)) return failure("UNKNOWN_TOOL", name)
        schema.validate(name, arguments)?.let { return failure("INVALID_ARGUMENTS", it) }
        if (name !in SUPPORTED) return failure("NOT_IMPLEMENTED", name)
        if (schema.confirmation(name) && !confirmed) return failure("CONFIRMATION_REQUIRED", name)
        val a = arguments.jsonObject
        try {
            if (name == "get_project") return Result(project, buildJsonObject { put("ok", true); put("project", summary(project, revision)) })
            if (name == "sewout_check" || name == "prepare_pes") {
                val frame = frame(a.string("frameId"))
                val machine = SewoutMachines.all.firstOrNull { it.id == a.string("machineId") } ?: throw IllegalArgumentException("Unknown machine")
                if (name == "sewout_check") require(a.string("pesAssetId").isNotEmpty() != a.string("projectRevision").isNotEmpty()) { "Supply exactly one source" }
                if (name == "sewout_check" && a.string("pesAssetId").isNotEmpty()) {
                    require(a.string("projectRevision").isEmpty()) { "Supply exactly one source" }
                    val bytes = assets[a.string("pesAssetId")] ?: throw IllegalArgumentException("Unknown approved PES asset")
                    val report = SewoutCheck.checkPes(bytes, machine, SewoutOptions(hoop = frame, origin = DesignOrigin.IMPORTED))
                    val viewport = try { PecViewportCheck.check(bytes, frame) } catch (e: IllegalArgumentException) { return failure("INVALID_PES", e.message ?: "Malformed PES") }
                        catch (e: IndexOutOfBoundsException) { return failure("INVALID_PES", "Truncated PES") }
                    return Result(project, buildJsonObject { put("ok", true); put("report", reportJson(report)); put("viewport", viewportJson(viewport)) })
                }
                require(frame.width == project.hoop.width && frame.height == project.hoop.height && frame.positions == project.hoop.positions) { "Frame does not match current project" }
                if (a.string("projectRevision") != revision) return failure("STALE_REVISION", "Read current project revision")
                if (name == "sewout_check") require(a.string("pesAssetId").isEmpty()) { "Supply exactly one source" }
                require(frame.positions.isEmpty()) { "Coverage frame requires a confirmed split export" }
                val pattern = project.flatten()
                val origin = SewoutCheck.originOf(pattern)
                val options = SewoutOptions(hoop = frame, origin = origin)
                // Preserve centered placement validation BEFORE the writer normalizes to top-left.
                val placed = io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.forSewout(pattern)
                val placement = SewoutCheck.check(placed, machine, options.copy(placement = Placement.HOOP_COORDINATES))
                if (!placement.analysisComplete || placement.hasBlockingIssues) return failure("SEWOUT_BLOCKED", "Project placement/check blocked", buildJsonObject { put("placement", reportJson(placement)) })
                val version = if (name == "prepare_pes") PesVersion.valueOf(a.string("pesVersion")) else PesVersion.V1
                val bytes = try { PesWriter.write(pattern, PesWriteSettings(version, hoop = frame)) }
                    catch (e: IllegalArgumentException) { return failure("SEWOUT_BLOCKED", e.message ?: "Writer blocked output") }
                val report = SewoutCheck.checkPes(bytes, machine, options)
                val viewport = PecViewportCheck.check(bytes, frame)
                val details = buildJsonObject { put("report", reportJson(report)); put("viewport", viewportJson(viewport)); put("placement", reportJson(placement)) }
                if (!report.analysisComplete || report.verdict != SewoutVerdict.PASS || !viewport.valid) return failure("SEWOUT_BLOCKED", "Only complete PASS output may be released", details)
                return Result(project, buildJsonObject { put("ok", true); put("checks", details); put("byteCount", bytes.size) }, if (name == "prepare_pes") bytes.copyOf() else null)
            }
            require(a.string("transactionId").isNotBlank()) { "transactionId must not be blank" }
            val command = when (name) {
                "set_object_transforms" -> {
                    val rows = a.getValue("transforms").jsonArray.map { it.jsonObject }
                    require(rows.map { it.string("objectId") }.distinct().size == rows.size) { "Duplicate transform targets" }
                    ProjectCommand.SetTransforms(rows.associate { it.string("objectId") to transform(it, project.find(it.string("objectId"))?.transform) })
                }
                "transform_objects", "mirror_objects", "center_objects", "align_objects", "distribute_objects" -> {
                    val ids = a.ids(); require(ids.all { project.find(it) != null }) { "Missing object" }
                    val next = when(name) {
                        "mirror_objects" -> ids.fold(project) { p, id -> p.mirrorX(id) }
                        "align_objects" -> project.align(ids, Alignment.valueOf(a.string("alignment")))
                        "distribute_objects" -> project.distribute(ids, Axis.valueOf(a.string("axis")))
                        "center_objects" -> center(project, ids)
                        else -> group(project, ids, a.number("deltaXMm") * 10, a.number("deltaYMm") * 10, a.number("scaleFactor"), a.number("rotationDeg"))
                    }
                    ProjectCommand.SetTransforms(next.objects.filter { it.id in ids }.associate { it.id to TransformValues(it.transform) })
                }
                "duplicate_object" -> ProjectCommand.Duplicate(a.string("sourceId"), a.string("newObjectId"), transform(a.getValue("transform").jsonObject, project.find(a.string("sourceId"))?.transform))
                "reorder_object" -> ProjectCommand.Reorder(a.string("objectId"), a.integer("index"))
                "set_object_visibility" -> ProjectCommand.SetVisible(a.string("objectId"), a.getValue("visible").jsonPrimitive.boolean)
                "recolor_object" -> ProjectCommand.Recolor(a.string("objectId"), a.integer("threadIndex"), EmbThread(a.string("threadColor").removePrefix("#").toInt(16) or (-1 shl 24)))
                "remove_objects" -> ProjectCommand.Remove(a.ids())
                "set_project_frame" -> ProjectCommand.SetHoop(frame(a.string("frameId")))
                else -> error("Unreachable tool")
            }
            return when (val result = apply(project, command)) {
                is CommandResult.Rejected -> failure(result.code.name, result.message, buildJsonObject { put("objectIds", JsonArray(result.objectIds.map(::JsonPrimitive))) })
                is CommandResult.Applied -> Result(result.project, buildJsonObject {
                    put("ok", true); put("changed", result.changed); put("transactionId", a.string("transactionId")); put("changedIds", JsonArray(result.changedIds.map(::JsonPrimitive)))
                }, preparedCommand = command)
            }
        } catch (e: IllegalArgumentException) { return failure("INVALID_ARGUMENTS", e.message ?: "Invalid request") }
    }
    /** Bind to the same EditSession used by a host's UI/history. Keep this controller for the document lifetime. */
    fun bind(session: EditSession) = ToolTurnController(session, this)
    internal fun validateRequest(name: String, arguments: JsonElement): String? = schema.validate(name, arguments)
    internal fun knowsTool(name: String) = schema.contains(name)
    internal fun needsConfirmation(name: String) = schema.confirmation(name)
    private fun frame(id: String) = frames[id] ?: throw IllegalArgumentException("Unknown frame")
    companion object {
        val SUPPORTED = setOf("get_project", "set_object_transforms", "transform_objects", "mirror_objects", "center_objects", "align_objects", "distribute_objects", "duplicate_object", "reorder_object", "set_object_visibility", "recolor_object", "remove_objects", "set_project_frame", "sewout_check", "prepare_pes")
        fun summary(project: Project, revision: String) = buildJsonObject {
            put("revision", revision); put("hoop", buildJsonObject { put("name", project.hoop.name); put("widthMm", project.hoop.width / 10.0); put("heightMm", project.hoop.height / 10.0) })
            put("objects", JsonArray(project.objects.map { o -> buildJsonObject {
                put("id", o.id); put("name", o.name); put("visible", o.visible); put("type", if (o is TextObject) "text" else "stitch")
                val threads = when(o) { is StitchObject -> o.source.threads; is TextObject -> listOf(o.thread) }
                put("threadColors", JsonArray(threads.map { JsonPrimitive("#%06X".format(java.util.Locale.ROOT, it.color and 0xFFFFFF)) }))
                if (o is TextObject) { put("text", o.text); put("fontId", o.fontId); put("heightMm", o.heightMm) }
                put("transform", buildJsonObject { put("xMm", o.transform.x / 10); put("yMm", o.transform.y / 10); put("rotationDeg", o.transform.rotationDeg); put("scaleX", o.transform.scaleX); put("scaleY", o.transform.scaleY) })
            } }))
        }
        private fun transform(a: JsonObject, original: Transform?) = TransformValues(a.number("xMm") * 10, a.number("yMm") * 10,
            a.number("rotationDeg") % 360, a.number("scaleXPercent") / 100 * if ((original?.scaleX ?: 1.0) < 0) -1 else 1,
            a.number("scaleYPercent") / 100 * if ((original?.scaleY ?: 1.0) < 0) -1 else 1)
        private fun selectionBounds(p: Project, ids: Set<String>): io.github.linker2k.myembro.engine.model.Bounds? {
            val b = p.objects.filter { it.id in ids && it.visible }.mapNotNull { it.placedBounds() }
            return if (b.isEmpty()) null else io.github.linker2k.myembro.engine.model.Bounds(b.minOf { it.minX }, b.minOf { it.minY }, b.maxOf { it.maxX }, b.maxOf { it.maxY })
        }
        /** Exact existing EditorSelection.transformGroup math; uses final placed bounds, not UI preview boxes. */
        private fun group(p: Project, ids: Set<String>, dx: Double, dy: Double, factor: Double, degrees: Double): Project {
            require(factor.isFinite() && factor > 0) { "scaleFactor must be positive" }
            require(listOf(dx,dy,degrees).all { it.isFinite() })
            val selected = p.objects.filter { it.id in ids && it.visible }
            if (selected.isEmpty()) return p
            if (factor == 1.0 && degrees == 0.0) return p.copy(objects = p.objects.map { o -> if (o !in selected) o else o.withTransform(o.transform.copy(x = (o.transform.x + dx).coerceIn(-100000.0,100000.0), y = (o.transform.y + dy).coerceIn(-100000.0,100000.0))) })
            val b = selectionBounds(p, ids) ?: return p
            val cx = (b.minX.toDouble()+b.maxX)/2; val cy = (b.minY.toDouble()+b.maxY)/2
            val low = selected.maxOf { 0.05/minOf(abs(it.transform.scaleX),abs(it.transform.scaleY)) }
            val high = selected.minOf { 20.0/maxOf(abs(it.transform.scaleX),abs(it.transform.scaleY)) }
            val scale = if (factor == 1.0 || low > high) 1.0 else factor.coerceIn(low, high)
            val angle = Math.toRadians(degrees)
            return p.copy(objects = p.objects.map { o -> if (o !in selected) o else {
                val t = o.transform; val x = (t.x-cx)*scale; val y = (t.y-cy)*scale
                o.withTransform(t.copy(x=(cx+x*cos(angle)-y*sin(angle)+dx).coerceIn(-100000.0,100000.0), y=(cy+x*sin(angle)+y*cos(angle)+dy).coerceIn(-100000.0,100000.0), rotationDeg=(t.rotationDeg+degrees)%360, scaleX=t.scaleX*scale, scaleY=t.scaleY*scale))
            } })
        }
        private fun center(p: Project, ids: Set<String>): Project {
            if (ids.size == 1) return p.alignCenter(ids.single())
            val b = selectionBounds(p, ids) ?: return p
            return group(p, ids, -(b.minX.toDouble()+b.maxX)/2, -(b.minY.toDouble()+b.maxY)/2, 1.0, 0.0)
        }
        private fun reportJson(r: SewoutReport) = buildJsonObject {
            put("verdict", r.verdict.name); put("analysisComplete", r.analysisComplete); put("stitchCount", r.statistics.stitchCount)
            put("suppressedFindings", r.suppressedFindings)
            put("severityCounts", buildJsonObject { r.severityCounts.forEach { (key,value) -> put(key.name,value) } })
            put("findingSeverities", buildJsonObject { r.findingSeverities.forEach { (key,value) -> put(key.name,value.name) } })
            put("findings", JsonArray(r.findings.map { buildJsonObject { put("code",it.code.name); put("severity",it.severity.name); put("message",it.message) } }))
        }
        private fun viewportJson(r: PecViewportCheck.Result) = buildJsonObject {
            put("valid",r.valid); put("minXUnits",r.minX); put("minYUnits",r.minY); put("maxXUnits",r.maxX); put("maxYUnits",r.maxY); put("headerWidthUnits",r.headerWidth); put("headerHeightUnits",r.headerHeight); put("errors",JsonArray(r.errors.map(::JsonPrimitive)))
        }
    }
}
internal fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
internal fun JsonObject.integer(key: String): Int { val n=number(key); require(n >= Int.MIN_VALUE && n <= Int.MAX_VALUE); return n.toInt() }
internal fun JsonObject.ids(): Set<String> { val a=getValue("objectIds").jsonArray.map { it.jsonPrimitive.content }; require(a.distinct().size==a.size && a.all { it.isNotBlank() }) { "Blank or duplicate IDs" }; return a.toSet() }
