// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import io.github.linker2k.myembro.ui.LibraryScreen
import io.github.linker2k.myembro.ui.LibraryViewModel
import io.github.linker2k.myembro.ui.ViewerScreen
import io.github.linker2k.myembro.ui.ViewerViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewer: ViewerViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()

    /** Path of the design open in the viewer, or null while the list is showing. */
    private var openPath by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPath = savedInstanceState?.getString(KEY_OPEN)
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
                uri?.let(::importAndView)
            }
            MaterialTheme(colorScheme = colors) {
                if (openPath == null) {
                    LibraryScreen(library, onOpen = { file ->
                        openPath = file.path
                        viewer.openFile(file)
                    })
                } else {
                    BackHandler { openPath = null }
                    // PES files have no registered MIME type, so allow any file and let the reader decide.
                    ViewerScreen(viewer, onOpenFile = { openDocument.launch(arrayOf("*/*")) })
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_OPEN, openPath)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(::importAndView)
    }

    /** Files opened from outside go into the library first, so they can be exported later. */
    private fun importAndView(uri: android.net.Uri) {
        lifecycleScope.launch {
            runCatching { library.importForViewing(uri) }
                .onSuccess { openPath = it.path; viewer.openFile(it) }
                .onFailure { viewer.open(uri) }
        }
    }

    private companion object {
        const val KEY_OPEN = "open_path"
    }
}
