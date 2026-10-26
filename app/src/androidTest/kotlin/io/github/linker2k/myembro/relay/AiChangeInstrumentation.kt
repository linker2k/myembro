// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.editor.AiWorkspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

/**
 * How an AI connection change reaches the user (5558 only, synthetic SVG and text): myembro on screen at rest
 * switches to the editor; with the Create sheet open it offers a banner instead; in the background it posts
 * the notification. Calls go through the service's own provider (AiWorkspace.relayDocument).
 */
class AiChangeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val log = mutableListOf<String>()
        try {
            val app = targetContext.applicationContext as Application
            // The banner is a separate, non-focusable window: read every window, not only the active one.
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            File(app.filesDir, "drafts/editor.myembro").delete()
            val tools = RelayToolbox({ AiWorkspace.relayDocument(app) })
            fun call(tool: String, args: JsonObject) = runBlocking { tools.call(tool, args) }
            fun ok(result: RelayResult) = Json.parseToJsonElement(result.content.single().getValue("text").jsonPrimitive.content)
                .jsonObject["ok"] == JsonPrimitive(true)
            fun await(label: String, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 20_000
                while (!condition()) { check(SystemClock.uptimeMillis() < end) { "Timed out: $label" }; Thread.sleep(100) }
                waitForIdleSync()
            }
            fun nodes(): List<AccessibilityNodeInfo> {
                fun all(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::all).orEmpty() }
                // The active window (fresh) plus the others, e.g. the non-focusable banner popup.
                return (listOfNotNull(uiAutomation.rootInActiveWindow) + uiAutomation.windows.mapNotNull { it.root }).flatMap(::all)
            }
            fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            fun contains(text: String) = nodes().any { it.text?.toString()?.contains(text) == true }
            fun click(text: String) {
                await(text) { find(text) != null }
                var node = checkNotNull(find(text)); while (!node.isClickable) node = checkNotNull(node.parent)
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); waitForIdleSync()
            }
            val out = File(targetContext.filesDir, "ai-change-smoke").apply { mkdirs() }
            fun shot(name: String) {
                waitForIdleSync(); uiAutomation.waitForIdle(250, 5000); SystemClock.sleep(700)
                val image = checkNotNull(uiAutomation.takeScreenshot())
                File(out, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
            }
            val notifications = targetContext.getSystemService(android.app.NotificationManager::class.java)
            fun notified() = notifications.activeNotifications.any { it.id == 7103 }
            val objects = { n: Int -> targetContext.getString(R.string.editor_object_count, n) }

            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            @Suppress("UNCHECKED_CAST")
            val stack = MainActivity::class.java.getDeclaredField("stack").apply { isAccessible = true }.get(activity) as MutableList<Any>
            await("home") { find(targetContext.getString(R.string.home_tab_create)) != null }

            // 1. On screen, at rest (home): the editor opens by itself, with a short message.
            val smile = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="45" fill="#FFCC00"/>""" +
                """<circle cx="35" cy="40" r="6" fill="#000000"/><circle cx="65" cy="40" r="6" fill="#000000"/></svg>"""
            check(ok(call("import_picture", buildJsonObject { put("format", "svg"); put("data", smile); put("title", "Smile") })))
            await("switched to the editor") { find(objects(1)) != null }
            await("message") { contains(targetContext.getString(R.string.relay_change_picture, "Smile")) }
            check(!notified())
            shot("01-home-switches-to-editor.png")
            log += "VISIBLE PASS: home at rest -> editor opened by itself (1 object) with a message; no notification"

            // 2. Busy (the Create sheet open on home): no switch; a banner with View.
            runOnMainSync { stack.clear() }
            click(targetContext.getString(R.string.home_tab_create))
            await("create sheet") { find(targetContext.getString(R.string.new_project_blank)) != null }
            check(ok(call("add_text", Json.parseToJsonElement("""{"text":"hi","heightMm":10,"xMm":0,"yMm":38}""").jsonObject)))
            await("banner") { find(targetContext.getString(R.string.relay_change_view)) != null }
            check(stack.isEmpty() && find(targetContext.getString(R.string.new_project_blank)) != null) { "switched under the sheet" }
            check(!notified())
            shot("02-sheet-open-banner.png")
            click(targetContext.getString(R.string.relay_change_view))
            await("editor from the banner") { find(objects(2)) != null }
            log += "BUSY PASS: Create sheet open -> no switch, banner with View; View opens the editor (2 objects)"

            // 3. In the background: Android does not let the app come forward; the notification instead.
            runOnMainSync { stack.clear(); activity.moveTaskToBack(true) }
            await("stopped") { !AiWorkspace.uiStarted }
            check(ok(call("add_text", Json.parseToJsonElement("""{"text":"bg","heightMm":10,"xMm":0,"yMm":-38}""").jsonObject)))
            await("notification") { notified() }
            log += "BACKGROUND PASS: app behind -> change notification posted"
            notifications.cancel(7103)

            runOnMainSync { activity.finish(); AiWorkspace.viewModelStore.clear() }
            Thread.sleep(500); File(app.filesDir, "drafts/editor.myembro").delete()
            finish(0, Bundle().apply { putString("result", log.joinToString("\n") + "\nAI_CHANGE_PASS") })
        } catch (failure: Throwable) {
            runCatching { uiAutomation.takeScreenshot()?.let { image ->
                File(targetContext.filesDir, "ai-change-smoke/failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
            runCatching { log += uiAutomation.windows.joinToString { "${it.type}:${it.title}" } }
            finish(1, Bundle().apply { putString("error", log.joinToString("\n") + "\n" + failure.stackTraceToString()) })
        }
    }
}
