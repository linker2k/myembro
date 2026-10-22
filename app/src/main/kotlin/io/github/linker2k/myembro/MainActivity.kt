// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro

import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.github.linker2k.myembro.ui.ViewerScreen
import io.github.linker2k.myembro.ui.ViewerViewModel

class MainActivity : ComponentActivity() {
    private val vm: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let(vm::open)
            }
            val saveDocument = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri -> vm.save(uri) }
            val shareUri = vm.shareUri
            LaunchedEffect(shareUri) {
                if (shareUri != null) {
                    vm.consumeShare(shareUri)
                    try {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            clipData = ClipData.newRawUri(context.getString(R.string.share), shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_pes)))
                    } catch (error: Exception) {
                        vm.reportExportFailure(error)
                    }
                }
            }
            MaterialTheme(colorScheme = colors) {
                // PES files have no registered MIME type, so allow any file and let the reader decide.
                ViewerScreen(
                    vm,
                    onOpenFile = { openDocument.launch(arrayOf("*/*")) },
                    onSaveFile = {
                        vm.prepareSave()?.let { name ->
                            try {
                                saveDocument.launch(name)
                            } catch (error: Exception) {
                                vm.savePickerFailed(error)
                            }
                        }
                    },
                    onShareFile = vm::share,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(vm::open)
    }
}
