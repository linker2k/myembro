// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.io.PecThreads

@Composable
fun MyThreadsSection(vm: SettingsViewModel) {
    val palette = vm.settings.myThreads
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.my_threads_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.my_threads_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(palette.enabled, { vm.setMyThreads(palette.copy(enabled = it)) })
        }
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.my_threads_choose, palette.owned.size)) }
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.my_threads_title)) },
            text = {
                Column {
                    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.my_threads_search)) }, singleLine = true)
                    TextButton(onClick = { vm.setMyThreads(palette.copy(indices = emptySet())) }) { Text(stringResource(R.string.my_threads_clear)) }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items((1 until PecThreads.SET.size).filter {
                            val t = PecThreads.forIndex(it)
                            query.isBlank() || "${t.catalogNumber} ${t.description}".contains(query.trim(), ignoreCase = true)
                        }, key = { it }) { index ->
                            val thread = PecThreads.forIndex(index)
                            val checked = index in palette.indices
                            Row(Modifier.fillMaxWidth().clickable {
                                vm.setMyThreads(palette.copy(indices = if(checked) palette.indices-index else palette.indices+index))
                            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, onCheckedChange = null)
                                Box(Modifier.size(20.dp).background(Color(thread.color), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text("${thread.catalogNumber} · ${thread.description}")
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) } })
    }
}
