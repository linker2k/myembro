// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.ShapeObject
import io.github.linker2k.myembro.ui.editor.AiWorkspace
import io.github.linker2k.myembro.ui.editor.block.BlockCanvasFit
import io.github.linker2k.myembro.ui.editor.block.BlockEditState
import java.io.File

/**
 * Block editing on a bundled emoji as a ShapeObject (5558, no personal data): editor -> Edit blocks ->
 * tap two real regions with multi-select (screen taps, topmost-wins hit test) -> Pattern with the real
 * meander tiles -> manual direction; one screenshot named by the argument "shot".
 */
class BlockEditInstrumentation : Instrumentation() {
    private var name = "block-edit.png"
    private var emoji = "butterfly"
    override fun onCreate(arguments: Bundle?) {
        name = arguments?.getString("shot") ?: name; emoji = arguments?.getString("emoji") ?: emoji
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val log = mutableListOf<String>()
        try {
            val app = targetContext.applicationContext as Application
            File(app.filesDir, "drafts/editor.myembro").delete()
            val svg = targetContext.assets.open("emoji/bundled/$emoji.svg").use { it.readBytes().decodeToString() }
            val shape = ShapeObject.fromSvg("emoji-$emoji", emoji, svg, 60.0)
            // The app's own way into the AI work project's editor (AiWorkspace.ACTION_OPEN).
            runOnMainSync { AiWorkspace.editor(app).startWith(shape, HoopSpec.H100) }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .setAction(AiWorkspace.ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            fun nodes(): List<AccessibilityNodeInfo> {
                fun all(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::all).orEmpty() }
                return uiAutomation.rootInActiveWindow?.let(::all).orEmpty()
            }
            fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            fun await(text: String): AccessibilityNodeInfo {
                val end = SystemClock.uptimeMillis() + 15_000
                while (true) { find(text)?.let { return it }; check(SystemClock.uptimeMillis() < end) { "Missing $text" }; Thread.sleep(100) }
            }
            fun click(text: String) {
                var node: AccessibilityNodeInfo? = await(text)
                while (!node!!.isClickable) node = node.parent
                if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    // The folded sheet scrolls: scroll once and look again if the control is below the fold.
                    nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    waitForIdleSync(); Thread.sleep(500)
                    node = await(text); while (!node!!.isClickable) node = node.parent
                    check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $text" }
                }
                waitForIdleSync(); Thread.sleep(200)
            }
            fun tap(x: Float, y: Float) {
                val t = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                    val e = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    check(uiAutomation.injectInputEvent(e, true)); e.recycle()
                }
                waitForIdleSync(); Thread.sleep(300)
            }

            click(targetContext.getString(R.string.block_edit_open))
            val state = BlockEditState.of(shape)
            /** A screen point where [id] is the topmost region (grid search over the shape bounds). The canvas
             * shrinks once the settings sheet opens, so its bounds are read again for every tap. */
            fun pointOf(id: String): Pair<Float, Float> {
                // The accessibility cache keeps the old bounds after the resize; refresh the node first.
                val node = await(targetContext.getString(R.string.block_edit_canvas)).apply { refresh() }
                val box = Rect().also(node::getBoundsInScreen)
                val fit = BlockCanvasFit(state.bounds, box.width().toFloat(), box.height().toFloat())
                val b = checkNotNull(state.bounds)
                for (i in 1 until 60) for (j in 1 until 60) {
                    val x = b.minX + (b.maxX - b.minX) * i / 60; val y = b.minY + (b.maxY - b.minY) * j / 60
                    if (state.regions.lastOrNull { it.hit(x, y, 0.0) }?.id == id &&
                        listOf(-0.8 to 0.0, 0.8 to 0.0, 0.0 to -0.8, 0.0 to 0.8).all { (dx, dy) -> state.regions.lastOrNull { it.hit(x + dx, y + dy, 0.0) }?.id == id })
                        return (box.left + fit.x(x) to box.top + fit.y(y))
                }
                error("No tappable point for $id")
            }
            // The two largest fill regions by bounds area.
            val targets = state.regions.filter { r -> r.paths.any { it.closed } }.sortedByDescending { r ->
                r.paths.sumOf { (it.xs.max() - it.xs.min()) * (it.ys.max() - it.ys.min()) } }.take(2).map { it.id }
            click(targetContext.getString(R.string.block_edit_multi))
            targets.forEach { id -> pointOf(id).let { (x, y) -> tap(x, y) } }
            await(targetContext.getString(R.string.block_edit_selected, 2))
            log += "TAP PASS: ${state.regions.size} regions from ShapeObject($emoji); screen taps selected ${targets.joinToString()}"
            click(targetContext.getString(R.string.block_edit_direction_manual))
            click(targetContext.getString(R.string.block_edit_texture_pattern))
            await("N3-11a")
            // On the device itself (Android XML parser): every bundled tile loads through the engine's loader.
            val tiles = io.github.linker2k.myembro.ui.editor.block.MeanderThumbnails.ids()
            check(tiles.size == 75 && tiles.all { io.github.linker2k.myembro.ui.editor.block.MeanderThumbnails.lines(it).coords.size >= 4 })
            log += "TILES PASS: ${tiles.size} engine meander tiles load on Android; grid shown (N3-11a)"
            val out = File(targetContext.filesDir, "block-edit").apply { mkdirs() }
            waitForIdleSync(); uiAutomation.waitForIdle(250, 5000); SystemClock.sleep(1200)
            checkNotNull(uiAutomation.takeScreenshot()).let { image ->
                File(out, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
            }
            runOnMainSync { activity.finish(); AiWorkspace.viewModelStore.clear() }
            Thread.sleep(500); File(app.filesDir, "drafts/editor.myembro").delete()
            finish(0, Bundle().apply { putString("result", log.joinToString("\n") + "\nBLOCK_EDIT_PASS -> $name") })
        } catch (failure: Throwable) {
            runCatching { uiAutomation.takeScreenshot()?.let { image ->
                File(targetContext.filesDir, "block-edit/failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
            finish(1, Bundle().apply { putString("error", log.joinToString("\n") + "\n" + failure.stackTraceToString()) })
        }
    }
}
