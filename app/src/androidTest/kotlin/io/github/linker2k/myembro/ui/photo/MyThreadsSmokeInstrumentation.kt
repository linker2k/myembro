// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.TimeoutException
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.engine.io.MyThreads
import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import io.github.linker2k.myembro.ui.settings.SettingsScreen
import io.github.linker2k.myembro.ui.settings.SettingsViewModel
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import java.io.File

/** Actual screen checks using only the repository's public face.png fixture. Opt-in runner. */
class MyThreadsSmokeInstrumentation : Instrumentation() {
    private var expectFresh = false
    override fun onCreate(arguments: Bundle?) {
        expectFresh = arguments?.getString("expectFresh") == "true"
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val preferences = SettingsPreferences(targetContext)
        val before = preferences.read().myThreads
        try {
            if (expectFresh) {
                check(before == MyThreads()) { "Fresh install must have default thread settings" }
                check(!File(targetContext.filesDir, "mythreads-smoke").exists()) { "Not a fresh installation" }
            }
            val automation = uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            val out = File(targetContext.filesDir, "mythreads-smoke").apply { mkdirs() }
            val app = targetContext.applicationContext as Application
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            // Wait on actual accessibility changes, with a monotonic deadline; never sleep
            // for an assumed render duration (first-install font/layout work can be slower).
            fun awaitCondition(label: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + timeoutMs
                while (true) {
                    waitForIdleSync()
                    if (condition()) return
                    val remaining = deadline - SystemClock.uptimeMillis()
                    check(remaining > 0) { "Timed out waiting for $label" }
                    try {
                        automation.executeAndWaitForEvent({}, { true }, minOf(remaining, 250))
                    } catch (_: TimeoutException) { /* Recheck the condition, including VM completion. */ }
                }
            }
            fun settle() {
                waitForIdleSync()
                automation.waitForIdle(100, 5_000)
            }
            fun nodes(): List<AccessibilityNodeInfo> {
                fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
                    (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
                return uiAutomation.rootInActiveWindow?.let(::visit).orEmpty()
            }
            fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text }
            fun verticalScroller() = nodes().firstOrNull {
                val bounds = android.graphics.Rect().also(it::getBoundsInScreen)
                it.isScrollable && bounds.height() > bounds.width()
            }
            fun reveal(text: String) {
                val deadline = SystemClock.uptimeMillis() + 30_000
                awaitCondition("screen/scroll container for $text") { find(text) != null || verticalScroller() != null }
                while (find(text) == null) {
                    check(SystemClock.uptimeMillis() < deadline) {
                        "Missing $text: ${nodes().mapNotNull { it.text }}"
                    }
                    val scroll = verticalScroller()
                    if (scroll == null) {
                        awaitCondition("scroll container for $text") { find(text) != null || verticalScroller() != null }
                        continue
                    }
                    try {
                        automation.executeAndWaitForEvent({
                            scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }, { it.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                            it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }, 1_000)
                    } catch (_: TimeoutException) { /* No event at the end; deadline diagnoses missing text. */ }
                    settle()
                }
            }
            fun click(text: String) {
                awaitCondition("clickable text $text") { find(text) != null }
                var n = requireNotNull(find(text)) { "Missing $text: ${nodes().mapNotNull { it.text }}" }
                while (!n.isClickable) n = requireNotNull(n.parent)
                check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)); settle()
            }
            fun screenshot(name: String) {
                settle()
                out.resolve(name).outputStream().use { stream ->
                    check(uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,stream))
                }
            }
            runOnMainSync { preferences.setMyThreads(MyThreads(true)) }
            lateinit var settingsVm: SettingsViewModel
            runOnMainSync {
                settingsVm = SettingsViewModel(app)
                activity.setContent { MyembroTheme { SettingsScreen(settingsVm) } }
            }
            settle()
            val choose = activity.getString(io.github.linker2k.myembro.R.string.my_threads_choose,0)
            reveal(choose)
            click(choose)
            awaitCondition("thread search input") { nodes().any { it.isEditable } }
            val field = nodes().first { it.isEditable }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Blue")
            }))
            click("2 · Blue")
            awaitCondition("saved Brother 2 selection") { preferences.read().myThreads == MyThreads(true,setOf(2)) }
            check(preferences.read().myThreads == MyThreads(true,setOf(2)))
            screenshot("01-settings-search-checked.png")

            val input = File(out,"repository-face.png")
            context.assets.open("image-to-design/face.png").use { source -> input.outputStream().use { source.copyTo(it) } }
            lateinit var photo: PhotoViewModel
            fun ready() {
                awaitCondition("photo generation", 60_000) {
                    var done = false
                    runOnMainSync { done = !photo.busy }
                    done
                }
                settle()
            }
            fun photoResult(palette: MyThreads) {
                runOnMainSync {
                    preferences.setMyThreads(palette)
                    val settings = preferences.read()
                    photo = PhotoViewModel(app)
                    photo.configure(settings)
                    activity.setContent { MyembroTheme { PhotoScreen(photo,settings,{}, {}, {}) } }
                    photo.load(Uri.fromFile(input))
                }
                ready(); check(photo.source!=null)
                runOnMainSync { photo.changeStyle(PhotoMode.COLOR); photo.next() }
                ready(); check(photo.colorPreview!=null) { "trace error=${photo.error}" }
                runOnMainSync { photo.changeSize(20.0) }; ready()
                runOnMainSync { photo.next() }; ready()
                check(photo.render!=null) { "result error=${photo.error}" }
                check(photo.requiredThreads.isNotEmpty())
            }
            photoResult(MyThreads(true,setOf(2)))
            check(photo.requiredThreads.all { it.sameColor(PecThreads.forIndex(2)) })
            screenshot("02-color-owned-only.png")
            val ownedCount = photo.requiredThreads.size
            photoResult(MyThreads(false,setOf(2)))
            check(photo.requiredThreads.any { photo.missingThread(it) })
            val missing = activity.getString(io.github.linker2k.myembro.R.string.my_threads_missing)
            reveal(missing)
            check(find(missing)!=null) { "Missing ownership label not visible" }
            screenshot("03-required-missing.png")
            val summary = "MY_THREADS_UI_PASS fresh_install=$expectFresh search=Blue checked=2 owned_only_threads=$ownedCount missing_label=true input=engine/src/test/resources/image-to-design/face.png"
            out.resolve("result.txt").writeText(summary)
            finish(-1, Bundle().apply { putString("result",summary) })
        } catch(failure: Throwable) {
            finish(0,Bundle().apply { putString("error",failure.stackTraceToString()) })
        } finally {
            runOnMainSync { preferences.setMyThreads(before) }
        }
    }
}
