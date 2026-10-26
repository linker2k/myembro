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
import io.github.linker2k.myembro.ui.editor.block.BlockStatus
import io.github.linker2k.myembro.ui.editor.block.BlockType
import java.io.File

/**
 * Block editing on a bundled emoji as a ShapeObject (5558, no personal data): editor -> Edit blocks ->
 * tap the largest region (screen tap, topmost-wins hit test) -> manual 135° -> Pattern (meander N3-11a) ->
 * Apply runs SetBlockStitch in the background; the badge must reach the real sewing-check pass. Before and
 * after screenshots of the editor's stitches plus the block screen after apply.
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
                // Compose updates (scroll, new controls) can be missing from the accessibility cache (API 34+).
                runCatching { uiAutomation.clearCache() }
                return uiAutomation.rootInActiveWindow?.let(::all).orEmpty()
            }
            fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            fun await(text: String): AccessibilityNodeInfo {
                val end = SystemClock.uptimeMillis() + 15_000
                while (true) { find(text)?.let { return it }; check(SystemClock.uptimeMillis() < end) { "Missing $text" }; Thread.sleep(100) }
            }
            fun click(text: String) {
                fun clickable(): AccessibilityNodeInfo? = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
                    ?.let { n -> generateSequence(n) { it.parent }.firstOrNull { it.isClickable } }
                // The folded sheet scrolls (off-screen controls are not in the tree): try as is, then scrolled
                // down, then scrolled back up; each position gets a few seconds to appear.
                for (action in listOf(0, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    if (action != 0) { nodes().filter { it.isScrollable }.forEach { it.performAction(action) }; waitForIdleSync(); Thread.sleep(400) }
                    val end = SystemClock.uptimeMillis() + (if (action == 0) 8_000 else 2_000)
                    while (SystemClock.uptimeMillis() < end) {
                        if (clickable()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { waitForIdleSync(); Thread.sleep(200); return }
                        Thread.sleep(150)
                    }
                }
                error("Cannot click $text")
            }
            fun tap(x: Float, y: Float) {
                val t = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                    val e = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    check(uiAutomation.injectInputEvent(e, true)); e.recycle()
                }
                waitForIdleSync(); Thread.sleep(300)
            }

            val out = File(targetContext.filesDir, "block-edit").apply { mkdirs() }
            fun shot(file: String) {
                waitForIdleSync(); uiAutomation.waitForIdle(250, 5000); SystemClock.sleep(1200)
                checkNotNull(uiAutomation.takeScreenshot()).let { image ->
                    File(out, file).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
                }
            }
            val vm = AiWorkspace.editor(app)
            await(targetContext.getString(R.string.editor_object_count, 1))
            shot("04-before-editor.png")
            val beforeStitches = vm.project.flatten().stitches.size
            val undoBefore = vm.undoSteps

            click(targetContext.getString(R.string.block_edit_open))
            await(targetContext.getString(R.string.block_edit_sewout_pass))
            val state = BlockEditState.of(shape)
            /** A screen point where [id] is the topmost region (grid search over the shape bounds). */
            fun pointOf(id: String): Pair<Float, Float> {
                val node = await(targetContext.getString(R.string.block_edit_canvas)).apply { refresh() }
                val box = Rect().also(node::getBoundsInScreen)
                val fit = BlockCanvasFit(state.bounds, box.width().toFloat(), box.height().toFloat())
                val b = checkNotNull(state.bounds)
                for (i in 1 until 60) for (j in 1 until 60) {
                    val x = b.minX + (b.maxX - b.minX) * i / 60; val y = b.minY + (b.maxY - b.minY) * j / 60
                    if (listOf(0.0 to 0.0, -0.8 to 0.0, 0.8 to 0.0, 0.0 to -0.8, 0.0 to 0.8).all { (dx, dy) ->
                            state.regions.lastOrNull { it.hit(x + dx, y + dy, 0.0) }?.id == id })
                        return box.left + fit.x(x) to box.top + fit.y(y)
                }
                error("No tappable point for $id")
            }
            // The largest fill region the engine refuses as satin (its disabled option is shown with a reason).
            val engineOptions = shape.regions.associate { it.id to io.github.linker2k.myembro.ui.editor.block.BlockApply.options(it, shape.transform) }
            val target = state.regions.filter { r -> r.paths.any { it.closed } && BlockType.SATIN !in engineOptions.getValue(r.id).types }
                .maxBy { r -> r.paths.sumOf { (it.xs.max() - it.xs.min()) * (it.ys.max() - it.ys.min()) } }.id
            log += "TARGET $target satin=${engineOptions.getValue(target).unavailable[BlockType.SATIN]}"
            pointOf(target).let { (x, y) -> tap(x, y) }
            await(targetContext.getString(R.string.block_edit_selected, 1))
            // Engine prechecks for this region: an unavailable type is disabled with a short reason.
            val satinLabel = targetContext.getString(R.string.block_edit_type_satin)
            val reasonEnd = SystemClock.uptimeMillis() + 60_000
            var reason: String? = null
            while (reason == null && SystemClock.uptimeMillis() < reasonEnd) {
                reason = nodes().mapNotNull { it.text?.toString() }.firstOrNull { it.startsWith("$satinLabel: ") }; if (reason == null) Thread.sleep(200) }
            val satinButton = nodes().first { it.text?.toString() == satinLabel }.let { n -> generateSequence(n) { it.parent }.first { it.isClickable || it.isCheckable } }
            log += "OPTIONS PASS: reason=$reason; satin button enabled=${satinButton.isEnabled}"
            check(reason != null && !satinButton.isEnabled) { "Satin should be disabled with a reason" }
            shot("07-disabled-option.png")
            /** Manual 135°, Pattern (meander N3-11a), Apply; waits for the engine's verdict. */
            fun pattern135AndApply(): BlockStatus {
                click(targetContext.getString(R.string.block_edit_direction_manual))
                val angleLabel = targetContext.getString(R.string.block_edit_angle_label)
                fun isSlider(n: AccessibilityNodeInfo) = n.contentDescription?.toString() == angleLabel || n.rangeInfo != null
                var slider = nodes().firstOrNull(::isSlider)
                repeat(3) {
                    if (slider == null) {   // below the fold of the folded sheet
                        nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                        waitForIdleSync(); Thread.sleep(400); slider = nodes().firstOrNull(::isSlider)
                    }
                }
                checkNotNull(slider) { "No angle slider" }
                check(slider!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 135f) }))
                await(targetContext.getString(R.string.block_edit_angle, 135))
                click(targetContext.getString(R.string.block_edit_texture_pattern))
                click(targetContext.getString(R.string.block_edit_apply))
                val end = SystemClock.uptimeMillis() + 120_000
                while (vm.blockStatus !is BlockStatus.Passed && vm.blockStatus !is BlockStatus.Blocked) {
                    check(SystemClock.uptimeMillis() < end) { "Generation timed out" }; Thread.sleep(200) }
                return vm.blockStatus
            }
            fun stitchOf(id: String) = (vm.project.find(shape.id) as ShapeObject).regions.first { it.id == id }.stitch

            // 1. The small spot: meander cannot build its graph there. Refused, previous settings kept, reason shown.
            val spotBefore = stitchOf(target)
            val refused = pattern135AndApply()
            check(refused is BlockStatus.Blocked && refused.applyFailed &&
                refused.failure.kind == io.github.linker2k.myembro.ui.editor.block.BlockFailure.Kind.MEANDER_REGION_TOO_SMALL) { "Expected a refusal: $refused" }
            check(stitchOf(target) == spotBefore && vm.undoSteps == undoBefore) { "A refused apply changed the design" }
            // Short localized text only; the engine's original text stays behind Details.
            await(targetContext.getString(R.string.block_edit_kept, targetContext.getString(R.string.block_fail_meander_small)))
            check(nodes().none { it.text?.toString()?.contains("SEWOUT_BLOCKED") == true }) { "Engine code shown outside details" }
            log += "REFUSED PASS: $target kept its settings, undo unchanged; ${refused.failure.kind} (${refused.failure.detail})"
            shot("08-refused-kept.png")

            // 2. The largest region (a wing): applied.
            val wing = state.regions.filter { r -> r.paths.any { it.closed } }.maxBy { r ->
                r.paths.sumOf { (it.xs.max() - it.xs.min()) * (it.ys.max() - it.ys.min()) } }.id
            pointOf(wing).let { (x, y) -> tap(x, y) }
            val status = pattern135AndApply()
            check(status is BlockStatus.Passed) { "Apply refused: $status" }
            val applied = stitchOf(wing)
            check(applied.fill.fill_method == "meander_fill" && applied.meander_pattern == "N3-11a" && applied.fill.angle == 135.0 && !applied.automatic_angle)
            check(vm.undoSteps == undoBefore + 1) { "undo ${vm.undoSteps} vs $undoBefore" }
            log += "APPLY PASS: $wing -> meander_fill N3-11a, angle 135, one undo step; badge ${vm.blockStatus}"
            shot("05-block-applied.png")
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await(targetContext.getString(R.string.editor_object_count, 1))
            val afterStitches = vm.project.flatten().stitches.size
            shot("06-after-editor.png")
            log += "STITCHES: before $beforeStitches, after $afterStitches"
            runOnMainSync { activity.finish(); AiWorkspace.viewModelStore.clear() }
            Thread.sleep(500); File(app.filesDir, "drafts/editor.myembro").delete()
            finish(0, Bundle().apply { putString("result", log.joinToString("\n") + "\nBLOCK_APPLY_PASS") })
        } catch (failure: Throwable) {
            runCatching { uiAutomation.takeScreenshot()?.let { image ->
                File(targetContext.filesDir, "block-edit/failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
            finish(1, Bundle().apply { putString("error", log.joinToString("\n") + "\n" + failure.stackTraceToString()) })
        }
    }
}
