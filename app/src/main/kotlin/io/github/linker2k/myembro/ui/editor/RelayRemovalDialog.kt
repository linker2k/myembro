// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.relay.RelayConfirmation

/** AI connection: the user's yes or no for a chatbot's remove_objects, by object name. Dismissing is a no. */
@Composable
internal fun RelayRemovalDialog(confirmation: RelayConfirmation) {
    val request by confirmation.request.collectAsState()
    val r = request ?: return
    AlertDialog(onDismissRequest = { confirmation.answer(r, false) },
        title = { Text(stringResource(R.string.relay_remove_title)) },
        text = { Text(stringResource(R.string.relay_remove_body, r.names.joinToString(", "))) },
        confirmButton = { TextButton(onClick = { confirmation.answer(r, true) }) { Text(stringResource(R.string.relay_remove_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmation.answer(r, false) }) { Text(stringResource(R.string.relay_remove_cancel)) } })
}
