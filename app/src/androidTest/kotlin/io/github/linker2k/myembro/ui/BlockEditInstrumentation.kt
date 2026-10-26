// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.editor.block.BlockEditScreen
import io.github.linker2k.myembro.ui.editor.block.BlockEditState
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import java.io.File

/**
 * Block editing UI draft on 5558 (fake regions, no personal data): two petals selected, Fill + Pattern
 * (meander tiles) + manual angle, then Apply; one screenshot named by the argument "shot".
 */
class BlockEditInstrumentation : Instrumentation() {
    private var name = "block-edit.png"
    override fun onCreate(arguments: Bundle?) { name = arguments?.getString("shot") ?: name; super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val state = BlockEditState.sample().apply { tap(0.30f, 0.35f, false); tap(0.70f, 0.35f, true) }
            runOnMainSync { activity.setContent { MyembroTheme { BlockEditScreen("꽃 (가짜 영역)", onClose = {}, state = state) } } }
            fun nodes(): List<AccessibilityNodeInfo> {
                fun all(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::all).orEmpty() }
                return uiAutomation.rootInActiveWindow?.let(::all).orEmpty()
            }
            fun click(text: String) {
                val end = SystemClock.uptimeMillis() + 10_000
                var node: AccessibilityNodeInfo? = null
                while (node == null) { check(SystemClock.uptimeMillis() < end) { "Missing $text" }; node = nodes().firstOrNull { it.text?.toString() == text }; Thread.sleep(100) }
                while (!node!!.isClickable) node = node.parent
                // The folded sheet scrolls: scroll it once and look again if the control is below the fold.
                if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    waitForIdleSync(); Thread.sleep(500)
                    node = checkNotNull(nodes().firstOrNull { it.text?.toString() == text }) { "Missing $text after scroll" }
                    while (!node!!.isClickable) node = node.parent
                    check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $text" }
                }
                waitForIdleSync()
            }
            click(targetContext.getString(R.string.block_edit_texture_pattern))
            click(targetContext.getString(R.string.block_edit_direction_manual))
            click(targetContext.getString(R.string.block_edit_apply))
            check(state.undoSteps == 1) { "undo=${state.undoSteps}" }
            val out = File(targetContext.filesDir, "block-edit").apply { mkdirs() }
            waitForIdleSync(); uiAutomation.waitForIdle(250, 5000); SystemClock.sleep(700)
            checkNotNull(uiAutomation.takeScreenshot()).let { image ->
                File(out, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
            }
            runOnMainSync { activity.finish() }
            finish(0, Bundle().apply { putString("result", "BLOCK_EDIT_PASS: 2 regions, pattern + manual angle applied as 1 undo step -> $name") })
        } catch (failure: Throwable) {
            finish(1, Bundle().apply { putString("error", failure.stackTraceToString()) })
        }
    }
}
