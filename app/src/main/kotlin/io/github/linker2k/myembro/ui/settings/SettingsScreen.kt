// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.pm.PackageInfoCompat
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.theme.HoopPreview
import io.github.linker2k.myembro.ui.theme.ThemeMode
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import io.github.linker2k.myembro.ui.EmbroideryTheme
import io.github.linker2k.myembro.ui.format.Dimensions
import io.github.linker2k.myembro.ui.format.dimensions
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.machines.MachineProfile
import io.github.linker2k.myembro.ui.machines.MachineProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.linker2k.myembro.engine.io.PesVersion

private val machinePresets = MachineProfiles.all.map { it.displayName }

/** Standalone settings destination; the host supplies navigation and owns its ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var machineDialog by rememberSaveable { mutableStateOf(false) }
    var brandDialog by rememberSaveable { mutableStateOf(false) }
    var pesDialog by rememberSaveable { mutableStateOf(false) }
    var themeDialog by rememberSaveable { mutableStateOf(false) }
    var licenseAsset by rememberSaveable { mutableStateOf<String?>(null) }
    val settings = vm.settings
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }

    EmbroideryTheme {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        if (onBack != null) IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card(
                    shape = MyembroShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.myembro_splash_icon), null, Modifier.size(72.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_studio), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.settings_studio_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                SectionHeading(Icons.Default.Straighten, stringResource(R.string.settings_defaults_title))
                Card(
                    shape = MyembroShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    SettingRow(
                        Icons.Default.Settings, stringResource(R.string.settings_machine),
                        if (settings.machineProfile?.id == "free") stringResource(R.string.settings_machine_free) else settings.machineModel.ifBlank { stringResource(R.string.settings_machine_unset) },
                    ) { machineDialog = true }
                    MachineDetails(settings.machineProfile)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(stringResource(R.string.settings_default_hoop), Modifier.padding(start = 16.dp, top = 16.dp),
                        style = MaterialTheme.typography.titleSmall)
                    LazyRow(
                        modifier = Modifier.selectableGroup(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(Hoop.entries, key = { it.name }) { hoop ->
                            val selected = settings.defaultFrame.preset == hoop
                            val supported = settings.machineProfile?.supports(hoop) == true
                            Surface(
                                Modifier.width(180.dp).selectable(selected, role = Role.RadioButton,
                                    onClick = { vm.setDefaultHoop(hoop) }),
                                shape = MyembroShapes.Button,
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box {
                                        HoopPreview(hoop, Modifier.size(76.dp))
                                        if (selected) Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp).align(Alignment.TopEnd),
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Text((if (hoop.name.startsWith("SA")) hoop.name + " · " else "") + hoop.dimensions(),
                                        style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                    if (!supported) Text(stringResource(R.string.machine_hoop_unsupported),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                CustomFramesSection(vm)
                SectionHeading(Icons.Default.Palette, stringResource(R.string.settings_threads_title))
                Card(
                    shape = MyembroShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    SettingRow(Icons.Default.Palette, stringResource(R.string.settings_thread_brand),
                        stringResource(settings.threadBrand.labelRes)) { brandDialog = true }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(Icons.Default.Description, stringResource(R.string.settings_pes_version),
                        pesVersionLabel(settings.pesVersion)) { pesDialog = true }
                }
                Text(stringResource(R.string.settings_defaults_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(shape = MyembroShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    SettingRow(Icons.Default.Brightness6, stringResource(R.string.settings_theme),
                        stringResource(themeLabel(settings.themeMode))) { themeDialog = true }
                }
                SectionHeading(Icons.Default.Info, stringResource(R.string.settings_about_title))
                Card(
                    shape = MyembroShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.settings_version, version.versionName.orEmpty(),
                                PackageInfoCompat.getLongVersionCode(version)), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.settings_app_description), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(Icons.Default.Description, stringResource(R.string.settings_app_license),
                        stringResource(R.string.settings_gpl)) { licenseAsset = "LICENSE" }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(Icons.Default.Description, stringResource(R.string.settings_open_source),
                        stringResource(R.string.settings_notices_description)) { licenseAsset = "THIRD_PARTY_NOTICES.md" }
                }
            }
        }
        if (machineDialog) MachineDialog(settings.machineModel, onDismiss = { machineDialog = false }) {
            vm.setMachineModel(it)
            machineDialog = false
        }
        if (brandDialog) ChoiceDialog(
            title = stringResource(R.string.settings_thread_brand),
            options = ThreadBrand.entries,
            selected = settings.threadBrand,
            label = { stringResource(it.labelRes) },
            onDismiss = { brandDialog = false },
        ) { vm.setThreadBrand(it); brandDialog = false }
        if (pesDialog) ChoiceDialog(
            title = stringResource(R.string.settings_pes_version),
            options = PesVersion.entries,
            selected = settings.pesVersion,
            label = { pesVersionLabel(it) },
            onDismiss = { pesDialog = false },
        ) { vm.setPesVersion(it); pesDialog = false }
        if (themeDialog) ChoiceDialog(
            title = stringResource(R.string.settings_theme), options = ThemeMode.entries,
            selected = settings.themeMode, label = { stringResource(themeLabel(it)) },
            onDismiss = { themeDialog = false },
        ) { themeDialog = false; vm.setThemeMode(it) }
        licenseAsset?.let { LicenseDialog(it) { licenseAsset = null } }
    }
}

@Composable
private fun MachineDetails(profile: MachineProfile?) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (profile == null || profile.id == "free") {
            Text(stringResource(if (profile?.id == "free") R.string.machine_free_hint else R.string.machine_unverified), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.machine_area_format, Dimensions.hoopSize(profile.maximumArea.width / 10, profile.maximumArea.height / 10), profile.exportFormats.joinToString()),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(when (profile.id) {
                "se400" -> R.string.machine_transfer_se400
                "se600" -> R.string.machine_transfer_se600
                else -> R.string.machine_transfer_se700
            }), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.machine_optional_hoops_repositionable),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(when (profile.id) {
                "se400" -> R.string.machine_limits_se400
                "se600" -> R.string.machine_limits_se600
                else -> R.string.machine_limits_se700
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.machine_filename_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeading(icon: ImageVector, title: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MyembroShapes.Chip, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Icon(icon, null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MachineDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(current.takeIf { it in machinePresets }) }
    var custom by rememberSaveable { mutableStateOf(current.isNotBlank() && current !in machinePresets) }
    var text by rememberSaveable { mutableStateOf(if (custom) current else "") }
    val value = if (custom) text.trim() else selected.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_machine)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                machinePresets.forEach { model ->
                    RadioOption(if (model == MachineProfiles.FREE.displayName) stringResource(R.string.settings_machine_free) else model, !custom && selected == model) { selected = model; custom = false }
                }
                RadioOption(stringResource(R.string.settings_machine_custom), custom) { custom = true; selected = null }
                if (custom) OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(120) },
                    label = { Text(stringResource(R.string.settings_machine_model)) },
                    placeholder = { Text(stringResource(R.string.settings_machine_example)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
            Text(stringResource(R.string.settings_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun pesVersionLabel(version: PesVersion): String = stringResource(
    if (version == PesVersion.V6) R.string.settings_pes_v6 else R.string.settings_pes_v1,
)

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                options.forEach { option -> RadioOption(label(option), selected == option) { onSelect(option) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
    )
}

@Composable
private fun RadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LicenseDialog(asset: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val result by produceState<Result<String>?>(null, asset) {
        value = try {
            Result.success(withContext(Dispatchers.IO) { context.assets.open(asset).bufferedReader().use { it.readText() } })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(20.dp), shape = MyembroShapes.Card, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (asset == "LICENSE") R.string.settings_app_license else R.string.settings_open_source),
                    style = MaterialTheme.typography.titleLarge)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val loaded = result
                    when {
                        loaded == null -> CircularProgressIndicator()
                        loaded.isFailure -> Text(stringResource(R.string.settings_license_error))
                        else -> SelectionContainer {
                            Text(loaded.getOrThrow(), Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.settings_close))
                }
            }
        }
    }
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.SYSTEM -> R.string.settings_theme_system
}
