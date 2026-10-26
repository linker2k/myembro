// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.home

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.editor.EditorScreen
import io.github.linker2k.myembro.ui.editor.EditorViewModel
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import java.io.File

/** UI-only development smoke on 5558; no design generation, personal image or external share. */
class AiPictureMenuInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val log = mutableListOf<String>()
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val out = File(targetContext.filesDir, "ai-picture-menu-smoke").apply { mkdirs() }
            fun nodes(): List<AccessibilityNodeInfo> {
                fun all(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::all).orEmpty() }
                return uiAutomation.rootInActiveWindow?.let(::all).orEmpty()
            }
            fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            fun await(label: String, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 20_000
                while (!condition()) { check(SystemClock.uptimeMillis() < end) { "Timed out: $label" }; Thread.sleep(100) }
                waitForIdleSync()
            }
            fun click(id: Int) {
                val text = targetContext.getString(id); await(text) { find(text) != null }
                var node = checkNotNull(find(text)); while (!node.isClickable) node = checkNotNull(node.parent)
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); waitForIdleSync()
            }
            fun shot(name: String) {
                waitForIdleSync(); uiAutomation.waitForIdle(250, 5000); SystemClock.sleep(700)
                val image = checkNotNull(uiAutomation.takeScreenshot())
                File(out, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
            }
            await("home") { find(targetContext.getString(R.string.home_tab_create)) != null }
            check(find(targetContext.getString(R.string.ai_picture_title)) == null) { "AI action remains on home top bar" }
            log += "HOME PASS: AI picture entry absent before opening Create"
            click(R.string.home_tab_create)
            await("AI creation row") { find(targetContext.getString(R.string.ai_picture_title)) != null }
            val labels = nodes().mapNotNull { it.text?.toString() }
            check(labels.indexOf(targetContext.getString(R.string.photo_from_photo)) < labels.indexOf(targetContext.getString(R.string.ai_picture_title)))
            check(labels.indexOf(targetContext.getString(R.string.ai_picture_title)) < labels.indexOf(targetContext.getString(R.string.emoji_from)))
            shot("01-home-create-menu.png")
            click(R.string.ai_picture_title)
            await("request dialog") { nodes().any { it.isEditable } }
            check(find(targetContext.getString(R.string.ai_picture_no_photo)) != null)
            check(find(targetContext.getString(R.string.new_project_blank)) == null) { "Creation sheet was not dismissed" }
            log += "CREATE PASS: photo -> AI picture -> emoji row order; shared creation style; closes menu and opens description-only dialog"
            click(android.R.string.cancel)
            lateinit var editor: EditorViewModel
            var opened = 0
            runOnMainSync {
                editor = MainActivity::class.java.getDeclaredMethod("getEditor").apply { isAccessible = true }.invoke(activity) as EditorViewModel
                activity.setContent { MyembroTheme { EditorScreen(editor, onBack = {}, onAiPicture = { opened++ }) } }
            }
            click(R.string.editor_add)
            await("editor creation row") { find(targetContext.getString(R.string.ai_picture_title)) != null }
            shot("02-editor-add-menu.png")
            click(R.string.ai_picture_title)
            await("editor AI callback") { opened == 1 }
            await("closed editor Add") { find(targetContext.getString(R.string.ai_picture_title)) == null }
            log += "EDITOR PASS: AI picture uses Add sheet's shared creation row; callback once and sheet dismissed"
            finish(0, Bundle().apply { putString("result", log.joinToString("\n") + "\nAI_PICTURE_MENU_PASS") })
        } catch (failure: Throwable) { finish(1, Bundle().apply { putString("error", log.joinToString("\n") + "\n" + failure.stackTraceToString()) }) }
    }
}
