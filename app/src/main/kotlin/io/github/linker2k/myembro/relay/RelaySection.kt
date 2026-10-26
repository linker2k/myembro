// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.linker2k.myembro.R

/** Settings card "AI connection (experimental)": relay address, APP_KEY and on/off. Fields lock while on. */
@Composable
fun RelaySection() {
    val context = LocalContext.current
    val prefs = remember { RelayPreferences(context) }
    var config by remember { mutableStateOf(prefs.read()) }
    val state by RelayService.connection.collectAsStateWithLifecycle()
    fun edit(next: RelayConfig) { config = next; prefs.write(next) }
    fun turn(on: Boolean) { edit(config.copy(enabled = on)); RelayService.sync(context) }
    // The notification is optional: the service runs either way.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { turn(true) }
    val valid = RelayProtocol.appUrl(config.address, config.key) != null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.relay_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = config.enabled, enabled = config.enabled || valid, onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else turn(on)
                })
            }
            Text(stringResource(R.string.relay_description), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(config.address, { edit(config.copy(address = it)) }, Modifier.fillMaxWidth(), enabled = !config.enabled,
                singleLine = true, label = { Text(stringResource(R.string.relay_address)) },
                placeholder = { Text("wss://…") })
            OutlinedTextField(config.key, { edit(config.copy(key = it)) }, Modifier.fillMaxWidth(), enabled = !config.enabled,
                singleLine = true, label = { Text(stringResource(R.string.relay_key)) }, visualTransformation = PasswordVisualTransformation())
            if (!valid && config.address.isNotBlank()) Text(stringResource(R.string.relay_invalid), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            if (config.enabled) Text(stringResource(when (state) {
                RelayClient.State.CONNECTED -> R.string.relay_connected
                RelayClient.State.WAITING -> R.string.relay_waiting
                else -> R.string.relay_connecting
            }), style = MaterialTheme.typography.bodySmall)
        }
    }
}
