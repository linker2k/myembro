// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.sources

import android.content.ActivityNotFoundException
import android.net.Uri
import java.util.Locale
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host navigation supplies [onBack] and wraps this destination in MyembroTheme. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeDesignSourcesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val browserError = stringResource(R.string.sources_browser_missing)
    val colors = MaterialTheme.colorScheme
    val browser = remember(colors.surface) {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setColorScheme(if (colors.surface.luminance() < 0.5f) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(colors.surface.toArgb()).build())
            .build()
    }
    val open: (String) -> Unit = { url ->
        try {
            // CustomTabsIntent uses the default browser when no Custom Tabs provider is available.
            browser.launchUrl(context, Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(browserError) }
        }
    }
    var sources by remember { mutableStateOf<Result<List<FreeDesignSource>>?>(null) }
    LaunchedEffect(context) {
        sources = try {
            Result.success(withContext(Dispatchers.IO) {
                context.assets.open(FreeDesignSources.ASSET).bufferedReader().use { FreeDesignSources.parse(it.readText()) }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sources_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.sources_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(shape = MyembroShapes.Button, color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Download, null, Modifier.size(24.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.sources_import_hint), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.sources_link_only), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            when {
                sources == null -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                sources!!.isFailure -> item { Text(stringResource(R.string.sources_load_error), color = colors.error) }
                else -> items(sources!!.getOrThrow(), key = { it.id }) { source ->
                    SourceCard(source, language, onOpen = { open(source.url) }, onTerms = { open(source.termsUrls.first()) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(source: FreeDesignSource, language: String, onOpen: () -> Unit, onTerms: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onOpen,
        shape = MyembroShapes.Card,
        border = BorderStroke(1.dp, colors.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(source.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.sources_open), tint = colors.primary)
            }
            Text(source.description.localized(language), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceBadge(if (source.includesPes) stringResource(R.string.sources_pes_available) else stringResource(R.string.sources_no_pes))
                SourceBadge(stringResource(if (source.commercialUse == "not_allowed") R.string.sources_personal_only else R.string.sources_finished_sales))
                SourceBadge(stringResource(if (source.registration == "required") R.string.sources_account_required else R.string.sources_no_account))
            }
            Text(stringResource(R.string.sources_formats, source.formats.joinToString(" · ")),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(source.conditions.localized(language), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(stringResource(R.string.sources_verified, source.languages.joinToString(", ").uppercase(Locale.ROOT), source.verifiedOn),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            TextButton(onClick = onTerms, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sources_terms))
            }
        }
    }
}

@Composable
private fun SourceBadge(label: String) {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
    }
}
