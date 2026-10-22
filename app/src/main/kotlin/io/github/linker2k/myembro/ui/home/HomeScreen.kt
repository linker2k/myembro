// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import java.io.File
import java.util.Locale

/**
 * Standalone home destination. The host owns navigation and converts the selected UI [Hoop]
 * to its editor/project model. Recent and sample callbacks always receive existing local files.
 */
@Composable
fun HomeScreen(
    onNewProject: (Hoop) -> Unit,
    onOpenRecent: (File) -> Unit,
    onImport: () -> Unit,
    onLettering: () -> Unit,
    onOpenLibrary: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onCreateFromDesign: () -> Unit = onOpenLibrary,
    letteringAvailable: Boolean = false,
    onFreeDesigns: (() -> Unit)? = null,
) {
    val vm: HomeViewModel = viewModel()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    MyembroTheme {
        HomeContent(vm.recent, vm.sample, vm.loading, vm.failed, vm::refresh,
            onNewProject, onOpenRecent, onImport, onLettering, onOpenLibrary, onSettings, modifier,
            vm.settings.availableViewerHoops, vm.settings.defaultHoop, onCreateFromDesign, letteringAvailable,
            onFreeDesigns)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    recent: List<HomeProject>, sample: HomeProject?, loading: Boolean, failed: Boolean,
    onRefresh: () -> Unit, onNewProject: (Hoop) -> Unit, onOpenRecent: (File) -> Unit,
    onImport: () -> Unit, onLettering: () -> Unit, onOpenLibrary: () -> Unit,
    onSettings: () -> Unit, modifier: Modifier = Modifier,
    supportedHoops: List<Hoop> = listOf(Hoop.H100), defaultHoop: Hoop = Hoop.H100,
    onCreateFromDesign: () -> Unit = onOpenLibrary, letteringAvailable: Boolean = false,
    onFreeDesigns: (() -> Unit)? = null,
) {
    var showNewProject by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.myembro_splash_icon), null, Modifier.size(40.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = { IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, stringResource(R.string.home_settings))
                } },
            )
        },
        bottomBar = { HomeTabs({ showNewProject = true }, onOpenLibrary, onSettings) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.home_workshop), style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.home_welcome), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { NewProjectCard(defaultHoop) { showNewProject = true } }
            item {
                SectionTitle(stringResource(R.string.home_recent), onOpenLibrary)
                when {
                    loading && recent.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                    recent.isEmpty() -> Card(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = MyembroShapes.Card,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(stringResource(if (failed) R.string.home_load_failed else R.string.home_recent_empty),
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = if (failed) onRefresh else onImport) {
                                Text(stringResource(if (failed) R.string.home_retry else R.string.home_import))
                            }
                        }
                    }
                    else -> LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recent, key = { it.file.path }) { project ->
                            ProjectCard(project, project.file.nameWithoutExtension,
                                project.file.extension.uppercase(Locale.ROOT)) { onOpenRecent(project.file) }
                        }
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.home_gallery))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    sample?.let { project -> item {
                        ProjectCard(project, stringResource(R.string.home_flower_sample),
                            stringResource(R.string.home_sample)) { onOpenRecent(project.file) }
                    } }
                }
            }
            item {
                SectionTitle(stringResource(R.string.home_quick_start))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction(Icons.Default.FileOpen, stringResource(R.string.home_import), Modifier.weight(1f), onClick = onImport)
                    if (letteringAvailable) QuickAction(Icons.Default.TextFields, stringResource(R.string.home_lettering), Modifier.weight(1f), onClick = onLettering)
                    if (onFreeDesigns != null) QuickAction(Icons.Default.TravelExplore, stringResource(R.string.sources_title), Modifier.weight(1f), onClick = onFreeDesigns)
                    QuickAction(Icons.Default.AddPhotoAlternate, stringResource(R.string.home_photo), Modifier.weight(1f),
                        enabled = false, onClick = {})
                }
            }
        }
    }
    if (showNewProject) NewProjectSheet(
        onDismissRequest = { showNewProject = false }, onBlank = onNewProject,
        onFromDesign = onCreateFromDesign, onFromText = onLettering,
        hoops = supportedHoops, letteringAvailable = letteringAvailable,
    )
}

@Composable
private fun NewProjectCard(hoop: Hoop, onCreate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = MyembroShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_new_project), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.home_new_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Image(painterResource(R.drawable.empty_state), null, Modifier.size(108.dp))
            }
            Text(stringResource(R.string.new_project_default_hoop, hoop.label),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth(), shape = MyembroShapes.Button,
                contentPadding = PaddingValues(14.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_create))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (onMore != null) TextButton(onClick = onMore) { Text(stringResource(R.string.home_view_all)) }
    }
}

@Composable
private fun ProjectCard(project: HomeProject, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(164.dp), shape = MyembroShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        val thumbnail = project.thumbnail
        if (thumbnail != null) Image(thumbnail.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(1.2f))
        else HoopDrawing(Hoop.H100, Modifier.fillMaxWidth().aspectRatio(1.2f).padding(28.dp))
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HoopDrawing(hoop: Hoop, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val cloth = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val ratio = hoop.widthMm.toFloat() / hoop.heightMm
        val h = size.height * 0.78f
        val w = (h * ratio).coerceAtMost(size.width * 0.78f)
        val origin = Offset((size.width - w) / 2, (size.height - h) / 2 + 2.dp.toPx())
        val radius = CornerRadius(w * 0.22f, w * 0.22f)
        drawRoundRect(cloth, origin, Size(w, h), radius)
        drawRoundRect(outline, origin, Size(w, h), radius, style = Stroke(2.dp.toPx()))
        drawLine(outline, Offset(size.width / 2, origin.y - 4.dp.toPx()),
            Offset(size.width / 2, origin.y), strokeWidth = 3.dp.toPx())
    }
}

@Composable
private fun QuickAction(icon: ImageVector, title: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.height(142.dp), shape = MyembroShapes.Button,
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(25.dp), tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Text(title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            if (!enabled) Text(stringResource(R.string.home_coming_soon), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeTabs(onCreate: () -> Unit, onLibrary: () -> Unit, onSettings: () -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            val colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer)
            NavigationBarItem(selected = true, onClick = {}, colors = colors,
                icon = { Icon(Icons.Default.Home, null) }, label = { Text(stringResource(R.string.home_tab_home)) })
            NavigationBarItem(selected = false, onClick = onLibrary, colors = colors,
                icon = { Icon(Icons.Default.CollectionsBookmark, null) }, label = { Text(stringResource(R.string.home_tab_library)) })
            NavigationBarItem(selected = false, onClick = onCreate, colors = colors,
                icon = { Icon(Icons.Default.AutoAwesome, null) }, label = { Text(stringResource(R.string.home_tab_create)) })
            NavigationBarItem(selected = false, onClick = onSettings, colors = colors,
                icon = { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.home_settings)) })
        }
    }
}

@Preview(name = "Craft light", showBackground = true, locale = "ko")
@Composable
private fun HomeLightPreview() {
    MyembroTheme(darkTheme = false) { HomeContent(emptyList(), null, false, false, {}, {}, {}, {}, {}, {}, {}) }
}

@Preview(name = "Craft dark", showBackground = true, locale = "ko")
@Composable
private fun HomeDarkPreview() {
    MyembroTheme(darkTheme = true) { HomeContent(emptyList(), null, false, false, {}, {}, {}, {}, {}, {}, {}) }
}
