// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import io.github.linker2k.myembro.R

/** Mount beside the relay editor controls at integration. Picking alone never exposes a photo.
 * The per-photo dialog is deliberately separate from the system picker's file permission. */
@Composable
fun RelayPhotoHandoff(photos: RelayPhotoAttachments, onShared: (String) -> Unit = {},
    /** Null shows its own button; otherwise each new positive value opens the picker (a menu item's count). */
    launch: Int? = null) {
    var pending by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { pending = it }
    val open = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    if (launch == null) OutlinedButton(onClick = open) { Text(stringResource(R.string.relay_photo_send)) }
    else LaunchedEffect(launch) { if (launch > 0) open() }
    pending?.let { uri ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.relay_photo_send)) },
            text = { Text(stringResource(R.string.relay_photo_consent)) },
            confirmButton = { TextButton(onClick = {
                photos.handoff(uri.toString(), consentGiven = true)?.let(onShared)
                pending = null
            }) { Text(stringResource(R.string.relay_photo_confirm)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
