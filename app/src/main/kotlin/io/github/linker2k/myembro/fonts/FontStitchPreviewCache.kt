// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import android.content.Context
import io.github.linker2k.myembro.engine.lettering.HangulSatin
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.ui.lettering.LetteringFontPreview
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Actual lettering stitches, not Typeface outlines. Cache lives in Android's evictable cacheDir. */
internal class FontStitchPreviewCache(private val context: Context) {
    suspend fun pattern(font: CatalogFont): EmbPattern = workers.withPermit {
        val file = FontRepository(context).fontFile(font)
        val revision = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex()
        val sewing = SettingsPreferences(context).read().sewing
        val sample = if (font.korean) SAMPLE else "Abc"
        val backend = if (font.korean) HangulSatin.BACKEND else "outline-running-v1"
        val key = MessageDigest.getInstance("SHA-256").digest(
            "$revision:$backend:preview-v1:$sample:$HEIGHT_MM:$sewing".toByteArray()).hex()
        val cache = File(context.cacheDir, "font-stitch-previews/$key.bin")
        runCatching {
            cache.inputStream().buffered().use { raw -> java.io.DataInputStream(raw).use { input ->
                check(input.readInt() == 1)
                val count = input.readInt().also { require(it in 1..100_000) }
                val stitches = List(count) { Stitch(input.readInt(), input.readInt(), Command.entries[input.readInt()]) }
                EmbPattern(stitches, listOf(THREAD), if (font.korean) mapOf("myembro.lettering.backend" to backend) else emptyMap())
            } }
        }.getOrNull()?.let { return@withPermit it }
        val pattern = LetteringFontPreview.pattern(context, font.id, sample, HEIGHT_MM, THREAD)
        check(pattern.stitchCount > 0)
        if (font.korean) check(pattern.metadata["myembro.lettering.backend"] == HangulSatin.BACKEND)
        cache.parentFile!!.mkdirs()
        // Cache failures never hide an otherwise valid preview (e.g. Android cleared cacheDir).
        runCatching {
            val temporary = File(cache.path + ".tmp")
            temporary.outputStream().buffered().use { raw -> java.io.DataOutputStream(raw).use { output ->
                output.writeInt(1); output.writeInt(pattern.stitches.size)
                pattern.stitches.forEach { output.writeInt(it.x); output.writeInt(it.y); output.writeInt(it.command.ordinal) }
            } }
            check(temporary.renameTo(cache))
        }
        pattern
    }

    companion object {
        const val SAMPLE = "자수"
        const val HEIGHT_MM = 15.0
        private val THREAD = EmbThread(0xff000000.toInt(), "Black 40wt")
        private val workers = Semaphore(2)
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
