// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.theme.MyembroTheme

/**
 * An AI change came in while the user is busy on another screen or in a dialog: a banner at the top with
 * [View] (the editor on the AI work project) instead of switching screens under them. It is its own window,
 * added last, so it shows above an open dialog or bottom sheet (an activity Popup stays under them, seen on
 * 5558); it is not focusable and not touch-modal, so whatever is underneath keeps working.
 */
@Composable
fun AiChangeBanner(headline: String, onView: () -> Unit, onDismiss: () -> Unit) {
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    val text = rememberUpdatedState(headline)
    val view = rememberUpdatedState(onView)
    val dismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(activity) {
        val banner = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setContent { MyembroTheme { BannerCard(text.value, { view.value() }, { dismiss.value() }) } }
        }
        val params = android.view.WindowManager.LayoutParams(android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = android.view.Gravity.TOP
            // The activity's own token (a TYPE_APPLICATION window), not the decor view's sub-window token.
            token = activity.window.attributes.token
            title = "myembro AI change"
        }
        // A finishing activity has no valid token: then there is no banner, never a crash.
        val added = try { activity.windowManager.addView(banner, params); true }
            catch (_: android.view.WindowManager.BadTokenException) { false }
        onDispose { if (added) activity.windowManager.removeViewImmediate(banner) }
    }
}

@Composable
private fun BannerCard(headline: String, onView: () -> Unit, onDismiss: () -> Unit) {
    Surface(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 560.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(headline, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            TextButton(onClick = onView, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)) {
                Text(stringResource(R.string.relay_change_view))
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.relay_change_dismiss)) }
        }
    }
}
