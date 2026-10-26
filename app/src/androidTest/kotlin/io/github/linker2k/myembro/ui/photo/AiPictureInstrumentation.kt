// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import java.io.File

/** Development emulator-5558; repository robot only. Opens choosers, never sends to a third party. */
class AiPictureInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val log = mutableListOf<String>()
        try {
            val out = File(targetContext.filesDir, "ai-picture-smoke").apply { mkdirs() }
            val input = File(targetContext.cacheDir, "pes_exports/ai-picture-robot.png").apply { parentFile.mkdirs() }
            context.assets.open("image-to-design/robot.png").use { stream -> input.outputStream().use { stream.copyTo(it) } }
            val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", input)
            val received = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setClass(targetContext, MainActivity::class.java).apply { clipData = ClipData.newRawUri("robot", uri) }
            val activity = startActivitySync(received) as MainActivity
            fun await(label: String, condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 20_000
                while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }; Thread.sleep(100) }
                waitForIdleSync()
            }
            fun nodes(): List<AccessibilityNodeInfo> {
                fun all(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::all).orEmpty() }
                return uiAutomation.rootInActiveWindow?.let(::all).orEmpty()
            }
            fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            fun click(text: String) {
                await(text) { find(text) != null }
                var node = checkNotNull(find(text))
                while (!node.isClickable) node = checkNotNull(node.parent)
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); waitForIdleSync()
            }
            fun shot(name: String) { waitForIdleSync(); uiAutomation.waitForIdle(250, 5000); SystemClock.sleep(1000); checkNotNull(uiAutomation.takeScreenshot()).let { bitmap ->
                File(out, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
            } }
            val getPhoto = MainActivity::class.java.getDeclaredMethod("getPhoto").apply { isAccessible = true }
            lateinit var vm: PhotoViewModel
            runOnMainSync { vm = getPhoto.invoke(activity) as PhotoViewModel }
            await("shared image decoded") { !vm.busy && vm.source != null }
            check(vm.step == 0 && vm.error == null)
            await("photo screen") { find(targetContext.getString(R.string.photo_crop_title)) != null }
            shot("01-shared-image.png")
            log += "INCOMING PNG PASS: ACTION_SEND -> existing photo.load -> choose/crop screen"
            // MIME registration includes JPEG/WebP as image/*; run both through onNewIntent.
            for ((mime, format) in listOf("image/jpeg" to Bitmap.CompressFormat.JPEG, "image/webp" to Bitmap.CompressFormat.WEBP_LOSSLESS)) {
                val file = File(input.parentFile, "robot-${format.name.lowercase()}.${if(mime.endsWith("jpeg")) "jpg" else "webp"}")
                file.outputStream().use { check(vm.source!!.compress(format, 100, it)) }
                val imageUri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file)
                runOnMainSync { activity.startActivity(Intent(received).setType(mime).putExtra(Intent.EXTRA_STREAM, imageUri).apply { clipData = ClipData.newRawUri("robot", imageUri) }) }
                Thread.sleep(150); await("$mime decode") { !vm.busy && vm.source != null }
                check(vm.step == 0 && vm.error == null); file.delete()
                log += "INCOMING $mime PASS: warm activity photo screen"
            }
            val source = checkNotNull(vm.source)
            val prompt = "Synthetic robot, exact text 코코"
            val shareFolder = File(targetContext.cacheDir, "ai_picture_shares")
            val before = shareFolder.listFiles()?.map { it.name }?.toSet().orEmpty()
            repeat(2) {
                val textOnly = aiPictureShareIntent(targetContext, prompt, source, includePhoto = false)
                check(textOnly.type == "text/plain" && !textOnly.hasExtra(Intent.EXTRA_STREAM) && textOnly.clipData == null)
                check(textOnly.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0)
                check(before == shareFolder.listFiles()?.map { it.name }?.toSet().orEmpty())
            }
            val withPhoto = aiPictureShareIntent(targetContext, prompt, source, includePhoto = true)
            val sharedUri = checkNotNull(withPhoto.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
            val decoded = targetContext.contentResolver.openInputStream(sharedUri).use { BitmapFactory.decodeStream(it) }
            check(decoded != null && decoded.width == source.width && decoded.height == source.height); decoded.recycle()
            check(withPhoto.type == "image/png" && withPhoto.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            log += "CONSENT TRANSPORT PASS: text-only has no stream/grant/file; approved PNG decodes without original URI/EXIF"
            click(targetContext.getString(R.string.ai_picture_title))
            await("request field") { nodes().any { it.isEditable } }
            check(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "웃는 강아지 얼굴, 아래에 코코")
            }))
            click(targetContext.getString(R.string.ai_picture_share))
            await("per-send disclosure") { find(targetContext.getString(R.string.ai_picture_disclosure_photo)) != null }
            shot("02-photo-consent.png")
            click(targetContext.getString(R.string.ai_picture_text_only))
            await("text chooser") { uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it == "android" || it.contains("intentresolver") } == true }
            shot("03-text-share-sheet.png")
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("return to request") { find(targetContext.getString(R.string.ai_picture_share)) != null }
            click(targetContext.getString(R.string.ai_picture_share))
            await("fresh per-send disclosure") { find(targetContext.getString(R.string.ai_picture_disclosure_photo)) != null }
            log += "CONSENT UI PASS: choosing text-only never remembered; second share asks again"
            click(targetContext.getString(R.string.ai_picture_send))
            await("photo chooser") { uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it == "android" || it.contains("intentresolver") } == true }
            shot("04-photo-share-sheet.png")
            log += "CHOOSER PASS: text and photo share sheets visible; no external app selected"
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            click(targetContext.getString(android.R.string.cancel))
            // A small non-2000px image is deliberately accepted through the unchanged pipeline.
            val smile = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(smile)
            val paint = android.graphics.Paint().apply { isAntiAlias = false }
            canvas.drawColor(android.graphics.Color.WHITE)
            paint.color = 0xffffcc00.toInt(); canvas.drawCircle(160f, 160f, 110f, paint)
            paint.color = android.graphics.Color.BLACK
            canvas.drawCircle(120f, 135f, 10f, paint); canvas.drawCircle(200f, 135f, 10f, paint)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 8f
            canvas.drawArc(android.graphics.RectF(110f, 140f, 210f, 220f), 0f, 180f, false, paint)
            val smileFile = File(input.parentFile, "two-color-smile.png")
            smileFile.outputStream().use { check(smile.compress(Bitmap.CompressFormat.PNG, 100, it)) }; smile.recycle()
            val smileUri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", smileFile)
            runOnMainSync { activity.startActivity(Intent(received).setType("image/png").putExtra(Intent.EXTRA_STREAM, smileUri)
                .apply { clipData = ClipData.newRawUri("smile", smileUri) }) }
            Thread.sleep(150); await("smile load") { !vm.busy && vm.source != null }
            check(vm.source!!.width == 320)
            runOnMainSync {
                vm.configure(io.github.linker2k.myembro.ui.settings.EmbroiderySettings())
                vm.changeColorCount(2); vm.changeStyle(PhotoMode.COLOR)
                if (vm.step == 0) vm.next()
            }
            await("2-color trace") { !vm.busy && vm.outline != null }
            runOnMainSync { vm.changeSize(30.0) }
            await("sized smile trace") { !vm.busy && vm.outline != null }
            runOnMainSync { vm.next() }
            await("smile sewout") { !vm.busy && vm.report != null }
            check(vm.passed) { "Smile blocked: ${vm.error} ${vm.report}" }
            val colors = vm.render!!.pattern.colorBlocks.map { it.thread.color }.distinct().size
            check(colors == 2) { "Expected 2 colors, got $colors" }
            shot("05-two-color-smile-pass.png")
            log += "SMILE PASS: generated 320x320 two-color PNG -> shared photo screen -> existing color pipeline, colors=$colors, stitches=${vm.report!!.statistics.stitchCount}, sewout PASS"
            finish(0, Bundle().apply { putString("result", log.joinToString("\n") + "\nAI_PICTURE_PASS") })
        } catch (failure: Throwable) { finish(1, Bundle().apply { putString("error", log.joinToString("\n") + "\n" + failure.stackTraceToString()) }) }
    }
}
