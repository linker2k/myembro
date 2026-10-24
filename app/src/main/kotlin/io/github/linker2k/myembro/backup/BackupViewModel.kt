// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.backup

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import io.github.linker2k.myembro.ui.theme.applyPlatformThemeMode
import kotlinx.coroutines.*
import java.io.File

class BackupViewModel(app: Application): AndroidViewModel(app) {
    var busy by mutableStateOf(false);private set
    var progress by mutableStateOf("");private set
    var result by mutableStateOf<String?>(null);private set
    var pending by mutableStateOf<BackupArchive.Plan?>(null);private set
    var duplicates by mutableIntStateOf(0);private set
    private val app get()=getApplication<Application>()
    private fun work(block: suspend ()->Unit) {
        if(busy)return
        busy=true
        viewModelScope.launch {
            try { block() }
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {result=app.getString(R.string.backup_failure,e.message ?: e.javaClass.simpleName)}
            finally {busy=false;progress=""}
        }
    }
    fun backup(uri: Uri)=work {
        withContext(Dispatchers.IO) {
            val temp=File.createTempFile("backup-", ".zip",app.cacheDir)
            try {
                temp.outputStream().use { BackupArchive.write(app.filesDir,BackupPreferences.snapshot(app),it) { n,total ->
                    progress=app.getString(R.string.backup_progress,n,total)
                } }
                // Detect a file changing while backup was being created before publishing success.
                temp.inputStream().use { BackupArchive.read(it,app.cacheDir).close() }
                app.contentResolver.openOutputStream(uri,"wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                    ?: error("Cannot open backup destination")
                result=app.getString(R.string.backup_saved)
            } finally {temp.delete()}
        }
    }
    fun open(uri: Uri)=work {
        val plan=withContext(Dispatchers.IO) {
            val stream=app.contentResolver.openInputStream(uri) ?: error("Cannot open backup")
            stream.use { BackupArchive.read(it,app.cacheDir) }.also {
                try {BackupPreferences.validate(it.preferences)}catch(e:Exception){it.close();throw e}
            }
        }
        pending?.close();duplicates=plan.duplicateCount(app.filesDir);pending=plan
    }
    fun cancelRestore(){pending?.close();pending=null}
    fun restore(mode: BackupArchive.Duplicates) {
        val plan=pending ?: return
        pending=null
        work {
            try {
                val restored=withContext(Dispatchers.IO) {plan.restore(app.filesDir,mode) {BackupPreferences.restore(app,it)}}
                app.applyPlatformThemeMode(SettingsPreferences(app).read().themeMode)
                result=app.getString(R.string.backup_restored,restored.added,restored.skipped)
            } finally {plan.close()}
        }
    }
    fun dismissResult(){result=null}
    override fun onCleared(){pending?.close()}
}
