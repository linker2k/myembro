// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linker2k.myembro.BuildConfig
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.format.dimensions
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.ui.machines.FrameOption
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.theme.MyembroLayout
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
    onNewProjectFrame: ((HoopSpec) -> Unit)? = null,
    onPhoto: (() -> Unit)? = null,
    onVersion: () -> Unit = onSettings,
    onEmoji: (() -> Unit)? = null,
    onAiPicture: (() -> Unit)? = null,
) {
    val vm: HomeViewModel = viewModel()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    MyembroTheme {
        HomeContent(vm.recent, vm.samples, vm.loading, vm.failed, vm::refresh,
            onNewProject, onOpenRecent, onImport, onLettering, onOpenLibrary, onSettings, modifier,
            vm.settings.availableViewerHoops, vm.settings.defaultHoop, onCreateFromDesign, letteringAvailable,
            onFreeDesigns, vm.settings.defaultFrame, onNewProjectFrame, onPhoto, BuildConfig.VERSION_NAME, onVersion, onEmoji, onAiPicture)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    recent: List<HomeProject>, samples: List<HomeProject>, loading: Boolean, failed: Boolean,
    onRefresh: () -> Unit, onNewProject: (Hoop) -> Unit, onOpenRecent: (File) -> Unit,
    onImport: () -> Unit, onLettering: () -> Unit, onOpenLibrary: () -> Unit,
    onSettings: () -> Unit, modifier: Modifier = Modifier,
    supportedHoops: List<Hoop> = listOf(Hoop.H100), defaultHoop: Hoop = Hoop.H100,
    onCreateFromDesign: () -> Unit = onOpenLibrary, letteringAvailable: Boolean = false,
    onFreeDesigns: (() -> Unit)? = null,
    defaultFrame: FrameOption? = null, onNewProjectFrame: ((HoopSpec) -> Unit)? = null,
    onPhoto: (() -> Unit)? = null,
    version: String? = null, onVersion: () -> Unit = onSettings,
    onEmoji: (() -> Unit)? = null,
    onAiPicture: (() -> Unit)? = null,
) {
    var showNewProject by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.myembro_splash_icon), null, Modifier.size(40.dp))
                        Text(stringResource(R.string.app_name), Modifier.alignByBaseline(), style = MaterialTheme.typography.titleLarge)
                        // Small version next to the name; the full version + commit stays at the bottom of settings.
                        if (version != null) {
                            val description = stringResource(R.string.home_version, version)
                            Text(version, Modifier.alignByBaseline().clip(MyembroShapes.Chip)
                                .clickable(role = Role.Button, onClick = onVersion)
                                .semantics { contentDescription = description }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, stringResource(R.string.home_settings))
                } },
            )
        },
        bottomBar = { HomeTabs({ showNewProject = true }, onOpenLibrary, onSettings) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val expanded = maxWidth >= MyembroLayout.ExpandedWidth
            val columns = if (expanded) MyembroLayout.designColumns(maxWidth) else 1
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "greeting", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.home_workshop), style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.home_welcome), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "new_project", span = { GridItemSpan(if (columns == 3) 2 else 1) }) {
                    NewProjectCard(defaultHoop, defaultFrame) { showNewProject = true }
                }
                if (expanded) item(key = "quick_start") { QuickStart(onImport, onLettering, letteringAvailable, onFreeDesigns, stacked = true, onPhoto = onPhoto) }
                item(key = "recent_heading", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(stringResource(R.string.home_recent), onOpenLibrary)
                }
                when {
                    recent.isEmpty() -> item(key = "recent_empty", span = { GridItemSpan(maxLineSpan) }) {
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else RecentEmpty(failed, if (failed) onRefresh else onImport)
                    }
                    expanded -> gridItems(recent, key = { "recent:${it.file.path}" }) { project ->
                        ProjectCard(project, project.file.nameWithoutExtension,
                            project.file.extension.uppercase(Locale.ROOT), Modifier.fillMaxWidth()) { onOpenRecent(project.file) }
                    }
                    else -> item(key = "recent_row") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(recent, key = { it.file.path }) { project ->
                                ProjectCard(project, project.file.nameWithoutExtension,
                                    project.file.extension.uppercase(Locale.ROOT)) { onOpenRecent(project.file) }
                            }
                        }
                    }
                }
                item(key = "samples_heading", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(stringResource(R.string.home_gallery))
                }
                if (expanded) gridItems(samples, key = { "sample:${it.file.path}" }) { project ->
                    ProjectCard(project, sampleTitle(project), stringResource(R.string.home_sample),
                        Modifier.fillMaxWidth()) { onOpenRecent(project.file) }
                } else item(key = "samples_row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(samples, key = { it.file.path }) { project ->
                            ProjectCard(project, sampleTitle(project), stringResource(R.string.home_sample)) { onOpenRecent(project.file) }
                        }
                    }
                }
                if (!expanded) item(key = "quick_start") { QuickStart(onImport, onLettering, letteringAvailable, onFreeDesigns, onPhoto = onPhoto) }
            }
        }
    }

    if (showNewProject) NewProjectSheet(
        onDismissRequest = { showNewProject = false }, onBlank = onNewProject,
        onFromDesign = onCreateFromDesign, onFromText = onLettering,
        hoops = supportedHoops, letteringAvailable = letteringAvailable, onBlankFrame = onNewProjectFrame, onFromPhoto = onPhoto, onFromEmoji = onEmoji, onAiPicture = onAiPicture,
    )
}

@Composable
private fun NewProjectCard(hoop: Hoop, frame: FrameOption? = null, onCreate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = MyembroShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val artSize = if (maxWidth < 340.dp) 72.dp else 108.dp
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_new_project), Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall)
                    Image(painterResource(R.drawable.empty_state), null, Modifier.size(artSize))
                }
                Text(stringResource(R.string.home_new_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.new_project_default_hoop, frame?.let { if (it.preset == null) "${it.spec.name} · ${it.dimensions}" else it.dimensions } ?: hoop.dimensions()),
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
}

private fun sampleTitle(project: HomeProject): String =
    project.file.nameWithoutExtension.removePrefix("sample_").replace('_', ' ')

@Composable
private fun RecentEmpty(failed: Boolean, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MyembroShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(if (failed) R.string.home_load_failed else R.string.home_recent_empty),
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAction) {
                Text(stringResource(if (failed) R.string.home_retry else R.string.home_import))
            }
        }
    }
}

@Composable
private fun QuickStart(
    onImport: () -> Unit,
    onLettering: () -> Unit,
    letteringAvailable: Boolean,
    onFreeDesigns: (() -> Unit)?,
    stacked: Boolean = false,
    onPhoto: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.home_quick_start))
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(Icons.Default.FileOpen, stringResource(R.string.home_import), Modifier.fillMaxWidth(), compact = true,
                onClick = onImport)
            if (letteringAvailable) QuickAction(Icons.Default.TextFields, stringResource(R.string.home_lettering),
                Modifier.fillMaxWidth(), compact = true, onClick = onLettering)
            if (onFreeDesigns != null) QuickAction(Icons.Default.TravelExplore, stringResource(R.string.sources_title),
                Modifier.fillMaxWidth(), compact = true, onClick = onFreeDesigns)
            QuickAction(Icons.Default.AddPhotoAlternate, stringResource(R.string.home_photo), Modifier.fillMaxWidth(),
                enabled = onPhoto != null, compact = true, onClick = { onPhoto?.invoke() })
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.Default.FileOpen, stringResource(R.string.home_import), Modifier.weight(1f), onClick = onImport)
            if (letteringAvailable) QuickAction(Icons.Default.TextFields, stringResource(R.string.home_lettering),
                Modifier.weight(1f), onClick = onLettering)
            if (onFreeDesigns != null) QuickAction(Icons.Default.TravelExplore, stringResource(R.string.sources_title),
                Modifier.weight(1f), onClick = onFreeDesigns)
            QuickAction(Icons.Default.AddPhotoAlternate, stringResource(R.string.home_photo), Modifier.weight(1f),
                enabled = onPhoto != null, onClick = { onPhoto?.invoke() })
        }
    }
}

@Composable
private fun SectionTitle(title: String, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (onMore != null) TextButton(onClick = onMore) { Text(stringResource(R.string.home_view_all)) }
    }
}

@Composable
private fun ProjectCard(project: HomeProject, title: String, subtitle: String, modifier: Modifier = Modifier.width(164.dp), onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = MyembroShapes.Card,
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
private fun QuickAction(
    icon: ImageVector, title: String, modifier: Modifier, enabled: Boolean = true,
    compact: Boolean = false, onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = if (compact) 64.dp else 142.dp),
        shape = MyembroShapes.Button, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
        if (compact) Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                if (!enabled) Text(stringResource(R.string.home_coming_soon), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(25.dp), tint = tint)
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
    MyembroTheme(darkTheme = false) { HomeContent(emptyList(), emptyList(), false, false, {}, {}, {}, {}, {}, {}, {}) }
}

@Preview(name = "Craft dark", showBackground = true, locale = "ko")
@Composable
private fun HomeDarkPreview() {
    MyembroTheme(darkTheme = true) { HomeContent(emptyList(), emptyList(), false, false, {}, {}, {}, {}, {}, {}, {}) }
}
