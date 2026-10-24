// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.backup

import android.app.Instrumentation
import android.os.Bundle
import android.content.Context
import java.io.File
import java.io.ByteArrayOutputStream

/** Opt-in isolated application ID only: never clears a user's installed app. */
class BackupInstrumentation: Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart() {
        try {
            check(targetContext.packageName.endsWith(".backupsmoke")) { "Use isolated smoke application ID" }
            val root=targetContext.filesDir
            File(root,"designs").mkdirs();File(root,"projects").mkdirs();File(root,"fonts").mkdirs()
            val sample=targetContext.assets.list("samples")!!.first {it.endsWith(".pes")}
            File(root,"designs/original.pes").writeBytes(targetContext.assets.open("samples/$sample").use {it.readBytes()})
            File(root,"projects/smoke.myembro").writeText("{\"test\":\"backup roundtrip\"}")
            File(root,"fonts/smoke.svg").writeText("<svg/>")
            File(root,"fonts/smoke.LICENSE.txt").writeText("smoke-only generated test data")
            val prefs=targetContext.getSharedPreferences("myembro_settings",Context.MODE_PRIVATE)
            check(prefs.edit().putString("machine_model","Brother SE600").putString("theme_mode","LIGHT")
                .putStringSet("test_set",setOf("a","한글")).putInt("test_int",3).putLong("test_long",1234567890123)
                .putFloat("test_float",1.5f).putBoolean("test_bool",true).commit())
            fun files()=root.walkTopDown().filter {it.isFile}.associate {it.relativeTo(root).path to it.readBytes().toList()}
            val before=files();val preferences=BackupPreferences.snapshot(targetContext)
            val zip=ByteArrayOutputStream().also {BackupArchive.write(root,preferences,it)}.toByteArray()
            root.deleteRecursively();root.mkdirs()
            for(name in preferences.keys)check(targetContext.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit())
            check(files().isEmpty())
            BackupArchive.read(zip.inputStream(),targetContext.cacheDir).use {plan ->
                BackupPreferences.validate(plan.preferences)
                val result=plan.restore(root,BackupArchive.Duplicates.SKIP){BackupPreferences.restore(targetContext,it)}
                check(result.added==before.size && result.skipped==0)
            }
            check(files()==before);check(BackupPreferences.snapshot(targetContext)==preferences)
            finish(-1,Bundle().apply {putString("result","BACKUP_DELETE_RESTORE_PASS files=${before.size} byte_equal=true typed_preferences_equal=true")})
        }catch(e:Throwable){finish(0,Bundle().apply {putString("error",e.stackTraceToString())})}
    }
}
