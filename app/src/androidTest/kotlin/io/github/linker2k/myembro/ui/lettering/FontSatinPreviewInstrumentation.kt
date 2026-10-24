// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.app.Instrumentation
import android.os.Bundle
import io.github.linker2k.myembro.backup.BackupPreferences
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.lettering.HangulSatin
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.fonts.*
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import java.io.File

/** Install the three pinned OFL catalog TTFs and their licenses before running. */
class FontSatinPreviewInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            val output = File(targetContext.filesDir, "font-satin-preview-qa").apply { mkdirs() }
            val report = StringBuilder("font\theightMm\tminColumnMm\tmaxColumnMm\twidth1to6\tstitches\tstatus\n")
            val repo = FontRepository(targetContext)
            kotlinx.coroutines.runBlocking {
                for (id in listOf("gowun-dodum", "do-hyeon", "nanum-gothic")) {
                    val font = repo.find(id)!!
                    check(repo.isInstalled(font)) { "Missing installed OFL font: $id" }
                    val cache = FontStitchPreviewCache(targetContext)
                    val initial = cache.pattern(font)
                    check(initial.metadata["myembro.lettering.backend"] == HangulSatin.BACKEND)
                    val cached = cache.pattern(font)
                    check(initial.stitches == cached.stitches) { "Cache changed stitches: $id" }
                    for (height in listOf(10.0, 15.0, 20.0, 25.0)) {
                        try {
                            val state = automaticLetteringState(targetContext, id, FontStitchPreviewCache.SAMPLE, height)
                            val settings = LetteringSettings(FontStitchPreviewCache.SAMPLE, id, height, 1.0,
                                LetteringAlignment.CENTER, false, LetteringCurve.STRAIGHT, 0.0, stitchStyle = state.style)
                            val result = renderLettering(targetContext, settings, EmbThread(0xff000000.toInt(), "Black 40wt"), HoopSpec.H100)
                            check(!result.unsupported)
                            val obj = result.textObject
                            val widths = obj.centerlineSatin!!.widths.values.map { it * height / requireNotNull(obj.contourSourceHeight) }
                            val min = widths.min(); val max = widths.max()
                            File(output, "$id-${height.toInt()}.PES").writeBytes(PesWriter.write(result.pattern))
                            report.append("$id\t$height\t$min\t$max\t${min >= 1.0 && max <= 6.0}\t${result.pattern.stitchCount}\tGENERATED\n")
                        } catch (e: Exception) {
                            report.append("$id\t$height\t-\t-\tfalse\t0\t${e.message}\n")
                        }
                    }
                }
            }
            // Real settings snapshot/restore, not a second fake preference store.
            val preferences = SettingsPreferences(targetContext)
            val before = BackupPreferences.snapshot(targetContext)
            val id = "gowun-dodum"
            preferences.toggleFavoriteFont(id)
            val changed = preferences.read().favoriteFontIds
            val snapshot = BackupPreferences.snapshot(targetContext)
            BackupPreferences.validate(snapshot)
            preferences.toggleFavoriteFont(id)
            BackupPreferences.restore(targetContext, snapshot)
            check(SettingsPreferences(targetContext).read().favoriteFontIds == changed)
            BackupPreferences.restore(targetContext, before)
            File(output, "sweep.tsv").writeText(report.toString())
            File(output, "result.txt").writeText("PASS: 3 installed catalog fonts generate actual satin previews; disk cache stitches exact; favorite settings backup/restore exact.\n")
            finish(-1, Bundle().apply { putString("result", "PASS files/font-satin-preview-qa") })
        } catch (e: Throwable) {
            finish(0, Bundle().apply { putString("error", e.stackTraceToString()) })
        }
    }
}
