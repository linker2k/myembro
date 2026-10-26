// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.core.content.FileProvider
import io.github.linker2k.myembro.engine.agent.ToolDispatcher
import io.github.linker2k.myembro.engine.edit.commands.EditSession
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.Transform
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.roundToInt

/** Repository synthetic robot only; exercises the real Android picker capability/codec and engine. */
class RelayPhotoInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val log = mutableListOf<String>()
        try { runBlocking {
            val folder = File(targetContext.cacheDir, "pes_exports/relay-photo-test").apply { mkdirs() }
            val robot = File(folder, "robot.png")
            context.assets.open("image-to-design/robot.png").use { input -> robot.outputStream().use { input.copyTo(it) } }
            fun uri(file: File) = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file).toString()
            val photos = RelayPhotoAttachments(targetContext)
            val session = EditSession()
            val controller = ToolDispatcher(attachmentStore = photos).bind(session)
            var editable = true
            val tools = RelayPhotoTools(photos, controller, { json("""{"machineId":"se700","frameId":"H100","pesVersion":"V6"}""") },
                publish = { it() }, canEdit = { editable })
            suspend fun call(name: String, body: String = "{}") = tools.call(name, json(body))
            fun output(result: RelayResult) = Json.parseToJsonElement(result.content.single().getValue("text").jsonPrimitive.content).jsonObject
            suspend fun missing(id: String) {
                val result = call("view_attachment", """{"attachmentId":"$id","maxEdgePx":768}""")
                check(result.isError && output(result)["code"] == JsonPrimitive("ATTACHMENT_NOT_FOUND"))
            }
            check(photos.handoff(uri(robot), consentGiven = false) == null)
            check(output(call("list_attachments")).getValue("attachments").jsonArray.isEmpty())
            missing(uri(robot)); missing(robot.path); missing("not-issued")
            val denied = call("prepare_photo", """{"attachmentId":"${uri(robot)}","mode":"outline","colors":2,"widthMm":30,"filter":"original"}""")
            check(denied.isError && output(denied)["code"] == JsonPrimitive("ATTACHMENT_NOT_FOUND"))
            check(session.snapshot().undoCount == 0)
            log += "CONSENT PASS: declined/unissued URI/path not listed, viewed or prepared"

            // Upscale only the repo's synthetic fixture so both accepted caps are exercised exactly.
            val bitmap = requireNotNull(BitmapFactory.decodeFile(robot.path))
            val enlarged = Bitmap.createScaledBitmap(bitmap, 1200, 900, true)
            val large = File(folder, "robot-large.png")
            large.outputStream().use { check(enlarged.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            enlarged.recycle(); bitmap.recycle()
            val largeId = checkNotNull(photos.handoff(uri(large), true))
            for (edge in listOf(767, 768)) {
                val result = call("view_attachment", """{"attachmentId":"$largeId","maxEdgePx":$edge}""")
                check(!result.isError)
                val image = result.content.single(); check(image["mimeType"] == JsonPrimitive("image/png"))
                val bytes = Base64.decode(image.getValue("data").jsonPrimitive.content, Base64.NO_WRAP)
                val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                check(decoded.width == edge && decoded.height == (edge * .75).roundToInt())
                decoded.recycle()
                log += "VIEW $edge PASS: PNG decoded, long side=$edge, aspect preserved"
            }
            for (body in listOf("""{"attachmentId":"$largeId","maxEdgePx":769}""",
                """{"attachmentId":"$largeId","maxEdgePx":768,"consent":true}""",
                """{"attachmentId":"$largeId","maxEdgePx":"768"}""")) {
                check(output(call("view_attachment", body))["code"] == JsonPrimitive("INVALID_ARGUMENTS"))
            }
            log += "VIEW 769 / forged consent / noninteger PASS: rejected before decode"
            photos.revoke(largeId); missing(largeId)
            log += "REVOKE PASS: preview capability removed"

            val id = checkNotNull(photos.handoff(uri(robot), true))
            val before = session.snapshot()
            val request = """{"attachmentId":"$id","mode":"outline","colors":2,"widthMm":30,"filter":"original"}"""
            editable = false
            check(output(call("prepare_photo", request))["code"] == JsonPrimitive("OPEN_EDITOR"))
            editable = true
            val prepared = call("prepare_photo", request)
            check(!prepared.isError) { output(prepared).toString() }
            check(session.snapshot() == before)
            val resultId = output(prepared).getValue("resultId").jsonPrimitive.content
            check(output(prepared)["verdict"] == JsonPrimitive("PASS"))
            log += "PREPARE PASS: real PhotoService pipeline, sewout PASS, session unchanged"
            val add = """{"transactionId":"relay-robot","resultId":"$resultId","objectId":"robot","name":"Synthetic robot"}"""
            val added = call("add_generated_art", add)
            val result = output(added)
            check(!added.isError) { result.toString() }
            check(result.getValue("sewout").jsonObject["verdict"] == JsonPrimitive("PASS"))
            check(result.getValue("sewout").jsonObject["findings"] is JsonArray)
            check(result.getValue("project").jsonObject.isNotEmpty() && result["revision"] == JsonPrimitive("1"))
            check(session.project.objects.single().id == "robot" && session.snapshot().undoCount == 1)
            check(session.project.objects.single().placedBoundsForTest())
            val generated = session.project.objects.single()
            session.undo(session.revision)
            check(session.project == before.project && session.snapshot().undoCount == 0)
            log += "ADD PASS: sewout PASS + findings + summary/revision; exactly 1 undo restores project"
            val blockedSession = EditSession(Project(objects = listOf(generated.withId("outside").withTransform(Transform(x = 1000.0)))))
            val blockedController = ToolDispatcher(attachmentStore = photos).bind(blockedSession)
            val blockedTools = RelayPhotoTools(photos, blockedController,
                { json("""{"machineId":"se700","frameId":"H100","pesVersion":"V6"}""") },
                publish = { it() }, canEdit = { true })
            val blockedBefore = blockedSession.snapshot()
            val blockedPrepared = output(blockedTools.call("prepare_photo", json(request)))
            check(blockedPrepared["ok"] == JsonPrimitive(true))
            val blockedArgs = json(add).toMutableMap().apply {
                put("transactionId", JsonPrimitive("blocked"))
                put("resultId", blockedPrepared.getValue("resultId"))
            }
            val blockedResult = blockedTools.call("add_generated_art", JsonObject(blockedArgs))
            val blocked = output(blockedResult)
            check(blockedResult.isError && blocked["code"] == JsonPrimitive("SEWOUT_BLOCKED")) { blocked.toString() }
            check(blocked.getValue("sewout").jsonObject["verdict"] == JsonPrimitive("BLOCK"))
            check(blocked.getValue("sewout").jsonObject.getValue("findings").jsonArray.isNotEmpty())
            check(blockedSession.snapshot() == blockedBefore)
            log += "HOOP_BLOCK PASS: out-of-hoop candidate rejected; findings included; no partial edit/history"
            // A receipt/result is not permission to keep using a photo after it is withdrawn.
            photos.revoke(id)
            check(output(call("add_generated_art", add.replace("relay-robot", "revoked")))["code"] == JsonPrimitive("ATTACHMENT_NOT_FOUND"))
            check(session.project == before.project)
            log += "REVOKED_RESULT PASS: no edit after withdrawal"
            photos.clear(); check(photos.ids().isEmpty())
            folder.deleteRecursively()
        }
            finish(0, Bundle().apply { putString("result", log.joinToString("\n") + "\nRELAY_PHOTO_PASS") })
        } catch (failure: Throwable) {
            finish(1, Bundle().apply { putString("error", log.joinToString("\n") + "\n" + failure.stackTraceToString()) })
        }
    }
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun io.github.linker2k.myembro.engine.edit.DesignObject.placedBoundsForTest(): Boolean {
        val pattern = placedPattern()
        val bounds = checkNotNull(pattern.bounds)
        return bounds.minX >= -500 && bounds.maxX <= 499 && bounds.minY >= -500 && bounds.maxY <= 499
    }
}
