// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.ProjectJson
import io.github.linker2k.myembro.ui.editor.AiWorkspace
import io.github.linker2k.myembro.ui.editor.EditorScreen
import io.github.linker2k.myembro.ui.editor.EditorViewModel
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

/**
 * The AI work project with the editor closed (5558 only, synthetic SVG and text, no personal image):
 * AI connection calls through the service's own provider (AiWorkspace.relayDocument) with no Activity,
 * then the editor shows the same project and undo history, and a restart recovers the autosaved draft.
 */
class AiWorkspaceInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val log = mutableListOf<String>()
        try {
            val app = targetContext.applicationContext as Application
            val draft = File(app.filesDir, "drafts/editor.myembro")
            // Fresh process: start from no draft, so no recovery is pending.
            draft.delete()
            val tools = RelayToolbox({ AiWorkspace.relayDocument(app) })
            fun call(tool: String, args: String) = runBlocking { tools.call(tool, Json.parseToJsonElement(args).jsonObject) }
            fun output(result: RelayResult) = Json.parseToJsonElement(result.content.single().getValue("text").jsonPrimitive.content).jsonObject
            fun editor(): EditorViewModel { lateinit var vm: EditorViewModel; runOnMainSync { vm = AiWorkspace.editor(app) }; return vm }
            fun await(label: String, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 20_000
                while (!condition()) { check(SystemClock.uptimeMillis() < end) { "Timed out: $label" }; Thread.sleep(100) }
            }

            val smile = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="45" fill="#FFCC00"/>""" +
                """<circle cx="35" cy="40" r="6" fill="#000000"/><circle cx="65" cy="40" r="6" fill="#000000"/>""" +
                """<path d="M30 62 Q50 82 70 62 L66 60 Q50 74 34 60 Z" fill="#000000"/></svg>"""
            val picture = call("import_picture", buildJsonObject { put("format", "svg"); put("data", smile); put("title", "Smile") }.toString())
            check(!picture.isError) { output(picture).toString() }
            check(output(picture)["added"] == JsonPrimitive(false))
            val text = call("add_text", """{"text":"안녕","heightMm":10,"xMm":0,"yMm":35}""")
            check(!text.isError) { output(text).toString() }
            check(output(text).getValue("sewout").jsonObject["verdict"] == JsonPrimitive("PASS"))
            val vm = editor()
            check(vm.project.objects.size == 1 && vm.undoSteps == 1) { "objects=${vm.project.objects.size} undo=${vm.undoSteps}" }
            log += "CLOSED PASS: picture pending in memory, no picture layer; add_text alone made 1 object/undo"
            // Editor not on screen: one notification, updated by the second change, that opens the AI project.
            val notifications = targetContext.getSystemService(android.app.NotificationManager::class.java)
            fun change() = notifications.activeNotifications.firstOrNull { it.id == 7103 }
            await("change notification") { change()?.notification?.number == 1 }
            val posted = checkNotNull(change()).notification
            check(posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString() ==
                targetContext.getString(R.string.relay_change_tap))
            log += "NOTIFY PASS: editor away -> one notification updated to 1 design change (tap checked on the device run)"

            // Open the app: the editor shows the same project and history; leaving the Activity keeps them.
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            fun pictureButton(): AccessibilityNodeInfo? {
                fun all(n:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap { n.getChild(it)?.let(::all).orEmpty() }
                return uiAutomation.rootInActiveWindow?.let(::all)?.firstOrNull { it.text?.toString()==targetContext.getString(R.string.photo_edit) }
            }
            await("picture input loaded") { pictureButton()!=null }
            check(vm.project.objects.size==1)
            var confirm=checkNotNull(pictureButton());while(!confirm.isClickable) confirm=checkNotNull(confirm.parent)
            check(confirm.isEnabled && confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("user confirmed picture layer") { vm.project.objects.size==2 }
            lateinit var shown: EditorViewModel
            runOnMainSync {
                shown = MainActivity::class.java.getDeclaredMethod("getEditor").apply { isAccessible = true }.invoke(activity) as EditorViewModel
                activity.setContent { MyembroTheme { EditorScreen(shown, onBack = {}) } }
            }
            check(shown === vm) { "The Activity got another editor" }
            fun nodes(): List<AccessibilityNodeInfo> {
                fun all(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::all).orEmpty() }
                return uiAutomation.rootInActiveWindow?.let(::all).orEmpty()
            }
            fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            await("object count") { find(targetContext.getString(R.string.editor_object_count, 2)) != null }
            var undo = checkNotNull(find(targetContext.getString(R.string.editor_undo)))
            while (!undo.isClickable) undo = checkNotNull(undo.parent)
            check(undo.isEnabled) { "Undo is disabled" }
            val out = File(targetContext.filesDir, "ai-workspace-smoke").apply { mkdirs() }
            waitForIdleSync(); uiAutomation.waitForIdle(250, 5000); SystemClock.sleep(700)
            checkNotNull(uiAutomation.takeScreenshot()).let { image ->
                File(out, "01-editor-after-closed-edits.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
            }
            check(undo.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("undo") { vm.project.objects.size == 1 }
            runOnMainSync { vm.redo() }
            check(vm.project.objects.size == 2 && vm.undoSteps == 2)
            log += "OPEN PASS: same EditorViewModel; screen shows 2 objects; on-screen undo removes the text, redo restores"
            await("notification cleared") { change() == null }
            // Editor on screen: no notification, a snackbar instead.
            check(output(call("add_text", """{"text":"on","heightMm":10,"xMm":30,"yMm":35}""")).getValue("ok") == JsonPrimitive(true))
            await("snackbar") { find(targetContext.getString(R.string.relay_change_snackbar)) != null }
            check(change() == null)
            runOnMainSync { vm.undo() }
            log += "SHOWN PASS: editor on screen clears the notification; a later AI change is a snackbar, no notification"
            runOnMainSync { activity.finish() }
            await("activity destroyed") { activity.isDestroyed }
            check(editor() === vm && vm.project.objects.size == 2 && vm.undoSteps == 2)
            log += "ACTIVITY_GONE PASS: Activity destroyed; AI work project and history kept"

            // Restart: the autosaved draft (existing EditorDraftStore) is what a new process finds.
            await("autosave") { draft.isFile && ProjectJson.decode(draft.readText()).objects.size == 2 }
            runOnMainSync { AiWorkspace.viewModelStore.clear() }
            val restarted = editor()
            check(restarted !== vm)
            val read = call("get_project", "{}")
            check(!read.isError) { output(read).toString() }
            val ids = output(read).getValue("project").jsonObject.getValue("objects").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            check(restarted.project.objects.size == 2 && restarted.recoveryProject == null && restarted.dirty) { "objects=${restarted.project.objects.size}" }
            check(output(call("add_text", """{"text":"hi","heightMm":10,"xMm":0,"yMm":-40}""")).getValue("ok") == JsonPrimitive(true))
            check(restarted.project.objects.size == 3)
            log += "RESTART PASS: new editor recovers the autosaved draft before the first call ($ids); tools keep working"

            runOnMainSync { AiWorkspace.viewModelStore.clear() }
            Thread.sleep(500); draft.delete()
            finish(0, Bundle().apply { putString("result", log.joinToString("\n") + "\nAI_WORKSPACE_PASS") })
        } catch (failure: Throwable) {
            finish(1, Bundle().apply { putString("error", log.joinToString("\n") + "\n" + failure.stackTraceToString()) })
        }
    }
}
