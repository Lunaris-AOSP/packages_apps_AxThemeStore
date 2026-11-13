/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.android.axion.axthemestore.ui

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.android.axion.axthemestore.R
import com.android.axion.axthemestore.fonts.*
import com.android.axion.axthemestore.ui.components.*
import com.android.axion.axthemestore.viewmodel.EmojiViewModel
import com.android.axion.axthemestore.viewmodel.FontUiState
import com.android.axion.axthemestore.viewmodel.FontViewModel
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel

@Composable
fun FontCustomizationScreen(viewModel: FontViewModel, bottomPadding: Dp = 100.dp) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val emojiViewModel: EmojiViewModel = composeViewModel()
    val emojiState by emojiViewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        emojiViewModel.refresh()
        onPauseOrDispose {}
    }
    var github by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showEmoji by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val bundledGridState = rememberLazyGridState()
    val githubGridState = rememberLazyGridState()
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importFont)
        }
    val busy = state.operation != null || emojiState.operation != null
    val defaultName = stringResource(R.string.font_default)
    val fonts =
        remember(state.bundled, query, defaultName) {
            state.bundled.filter {
                it.name.contains(query.trim(), true) ||
                    (it.packageName == "android" && defaultName.contains(query.trim(), true))
            }
        }
    val catalog =
        remember(state.catalog, query) {
            state.catalog.filter { it.name.contains(query.trim(), true) }
        }
    val savedFonts =
        remember(state.downloaded, query) {
            state.downloaded.filter { it.name.contains(query.trim(), true) }
        }
    val downloadedFileNames =
        remember(state.downloaded) { state.downloaded.map { it.fileName }.toSet() }
    LaunchedEffect(github) { if (github) viewModel.loadCatalog() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomPadding)) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StoreEmblem(Icons.Default.TextFields, Modifier.size(36.dp))
                Text(
                    stringResource(R.string.store_tab_fonts),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                IconButton(
                    enabled = !busy && !state.catalogLoading,
                    onClick = {
                        viewModel.refresh()
                        if (github) viewModel.loadCatalog(force = true)
                    },
                ) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                }
                FilledTonalIconButton(enabled = !busy, onClick = { showEmoji = true }) {
                    Icon(Icons.Default.EmojiEmotions, stringResource(R.string.font_emoji))
                }
            }
            LazyVerticalGrid(
                columns =
                    GridCells.Adaptive(148.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)),
                state = if (github) githubGridState else bundledGridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "intro", span = { GridItemSpan(maxLineSpan) }) { FontIntro(state) }
                item(key = "import", span = { GridItemSpan(maxLineSpan) }) {
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = {
                            keyboard?.hide()
                            importer.launch(
                                arrayOf(
                                    "font/ttf",
                                    "font/otf",
                                    "application/x-font-ttf",
                                    "application/x-font-otf",
                                    "application/octet-stream",
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Default.FileOpen, null)
                        Spacer(Modifier.width(12.dp))
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(R.string.font_import),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.font_import_description),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Icon(Icons.Default.Add, null)
                    }
                }
                item(key = "personalize", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FontActionRow(
                            stringResource(R.string.font_emoji),
                            emojiState.currentLabel.ifEmpty { stringResource(R.string.emoji_stock) },
                            Icons.Default.EmojiEmotions,
                            enabled = !busy,
                            onClick = { showEmoji = true },
                        )
                        if (
                            state.selection.customFontName.isNotBlank() ||
                                state.selection.overlayPackage != "android"
                        ) {
                            FontActionRow(
                                stringResource(R.string.font_reset),
                                null,
                                Icons.Default.Restore,
                                enabled = !busy,
                                onClick = { confirmReset = true },
                            )
                        }
                        if (
                            state.pendingRestart != null ||
                                state.selection.customFontName.isNotBlank()
                        ) {
                            FontActionRow(
                                stringResource(
                                    if (state.pendingRestart == FontRestart.SYSTEM_UI)
                                        R.string.font_restart_ui
                                    else R.string.font_reboot
                                ),
                                null,
                                Icons.Default.RestartAlt,
                                enabled = !busy,
                                onClick = viewModel::showRestart,
                            )
                        }
                    }
                }
                if (busy) {
                    item(key = "operation", span = { GridItemSpan(maxLineSpan) }) {
                        FontOperation(state)
                    }
                }
                item(key = "sources", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.selectableGroup(),
                        ) {
                            FilterChip(
                                selected = !github,
                                onClick = { github = false },
                                label = { Text(stringResource(R.string.font_bundled)) },
                                leadingIcon = {
                                    Icon(Icons.Default.PhoneAndroid, null, Modifier.size(18.dp))
                                },
                            )
                            FilterChip(
                                selected = github,
                                onClick = { github = true },
                                label = { Text(stringResource(R.string.font_github)) },
                                leadingIcon = {
                                    Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                                },
                            )
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                            placeholder = { Text(stringResource(R.string.font_search)) },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                if (query.isNotEmpty())
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Default.Close, stringResource(R.string.clear))
                                    }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions =
                                androidx.compose.foundation.text.KeyboardActions(
                                    onSearch = { keyboard?.hide() }
                                ),
                        )
                        Text(
                            if (github) stringResource(R.string.font_github_description)
                            else
                                pluralStringResource(
                                    R.plurals.font_collection_count,
                                    fonts.size + savedFonts.size,
                                    fonts.size + savedFonts.size,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                if (github && state.catalogError != null) {
                    item(key = "catalog_error", span = { GridItemSpan(maxLineSpan) }) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(
                                        if (catalog.isEmpty()) R.string.font_catalog_failed
                                        else R.string.font_offline
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    state.catalogError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(
                                    onClick = { viewModel.loadCatalog(force = true) },
                                    enabled = !state.catalogLoading,
                                ) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        }
                    }
                }
                if (state.loading || (github && state.catalogLoading)) {
                    item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            LoadingIndicator(Modifier.size(40.dp))
                            Text(stringResource(R.string.font_loading))
                        }
                    }
                }
                if (!github) {
                    items(fonts, key = { "bundled_" + it.packageName }) { font ->
                        val active =
                            state.selection.customFontName.isEmpty() &&
                                state.selection.overlayPackage == font.packageName
                        FontTile(
                            font.name.ifEmpty { stringResource(R.string.font_default) },
                            font.typeface,
                            stringResource(if (active) R.string.active else R.string.font_preview),
                            active,
                            enabled = !busy,
                            onClick = {
                                keyboard?.hide()
                                viewModel.previewBundled(font)
                            },
                        )
                    }
                }
                items(
                    if (github) catalog else savedFonts,
                    key = { "download_" + it.fileName },
                ) { font ->
                    val downloaded = font.fileName in downloadedFileNames
                    val typeface by
                        produceState<Typeface?>(null, font.fileName, downloaded) {
                            value = if (downloaded) viewModel.downloadedTypeface(font) else null
                        }
                    FontTile(
                        font.name,
                        typeface,
                        if (state.operation == font.name && state.progress != null)
                            stringResource(
                                R.string.download_progress,
                                ((state.progress ?: 0f) * 100).toInt(),
                            )
                        else
                            stringResource(
                                if (downloaded) R.string.font_saved
                                else R.string.font_download_preview
                            ),
                        enabled = !busy,
                        loading = state.operation == font.name,
                        progress = state.progress,
                        onClick = {
                            keyboard?.hide()
                            viewModel.previewDownload(font)
                        },
                    )
                }
                if (
                    !state.loading &&
                        !(github && state.catalogLoading) &&
                        (if (github) catalog.isEmpty() else fonts.isEmpty() && savedFonts.isEmpty())
                ) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StoreEmblem(Icons.Default.TextFields, Modifier.size(72.dp))
                            Text(
                                stringResource(
                                    if (query.isBlank()) R.string.font_catalog_empty
                                    else R.string.font_no_results
                                ),
                                style = MaterialTheme.typography.titleLargeEmphasized,
                            )
                            Text(
                                stringResource(R.string.font_no_results_hint),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    state.preview?.let { preview ->
        FontPreviewSheet(
            preview = preview,
            busy = busy,
            deleting = state.operation == stringResource(R.string.font_deleting),
            onDismiss = viewModel::dismissPreview,
            onApply = viewModel::applyPreview,
            onDelete = if (preview.downloadedFileName != null) viewModel::deletePreview else null,
        )
    }
    if (showEmoji) {
        EmojiCustomizationSheet(emojiViewModel, emojiState, onDismiss = { showEmoji = false })
    }
    EmojiDialogs(emojiViewModel, emojiState)
    LaunchedEffect(emojiState.message) {
        emojiState.message?.let {
            snackbar.showSnackbar(it)
            emojiViewModel.clearMessage()
        }
    }
    if (confirmReset)
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = { Icon(Icons.Default.Restore, null) },
            title = { Text(stringResource(R.string.font_reset_title)) },
            text = { Text(stringResource(R.string.font_reset_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        viewModel.reset()
                    }
                ) {
                    Text(stringResource(R.string.font_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    state.restartPrompt?.let { kind ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRestart,
            icon = { Icon(Icons.Default.RestartAlt, null) },
            title = { Text(stringResource(R.string.font_reboot_title)) },
            text = {
                Text(
                    stringResource(
                        if (kind == FontRestart.DEVICE) R.string.font_reboot_description
                        else R.string.font_restart_ui_description
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::restart) {
                    Text(
                        stringResource(
                            if (kind == FontRestart.DEVICE) R.string.font_reboot
                            else R.string.font_restart_ui
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestart) {
                    Text(stringResource(R.string.font_later))
                }
            },
        )
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            icon = { Icon(Icons.Default.ErrorOutline, null) },
            title = { Text(stringResource(R.string.font_action_failed)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) {
                    Text(stringResource(R.string.font_close))
                }
            },
        )
    }
}

@Composable
private fun FontIntro(state: FontUiState) {
    Column(
        Modifier.padding(top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.font_headline),
                style = MaterialTheme.typography.displayMediumEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.font_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.font_current),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        state.selection.customFontName.ifEmpty {
                            state.bundled
                                .find { it.packageName == state.selection.overlayPackage }
                                ?.name
                                ?.takeIf { it.isNotEmpty() }
                                ?: stringResource(R.string.font_default)
                        },
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                }
                if (LocalDensity.current.fontScale <= 1.3f) {
                    val typeface =
                        state.activeTypeface
                            ?: state.bundled
                                .find { it.packageName == state.selection.overlayPackage }
                                ?.typeface
                            ?: Typeface.create(
                                SystemFontController.EXTERNAL_FAMILY,
                                Typeface.NORMAL,
                            )
                    Text(
                        stringResource(R.string.font_sample_glyphs),
                        style = MaterialTheme.typography.displayLarge,
                        fontFamily = FontFamily(typeface),
                        modifier =
                            Modifier.clip(StoreFlowerShape)
                                .background(MaterialTheme.colorScheme.tertiary)
                                .padding(16.dp),
                        color = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FontActionRow(
    title: String,
    summary: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (summary != null)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FontTile(
    name: String,
    typeface: Typeface?,
    subtitle: String,
    active: Boolean = false,
    enabled: Boolean,
    loading: Boolean = false,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    StoreSurface(onClick = onClick, enabled = enabled) {
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            Box(
                Modifier.fillMaxWidth()
                    .height(108.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    if (progress == null) LoadingIndicator(Modifier.size(48.dp))
                    else LoadingIndicator(progress = { progress }, modifier = Modifier.size(48.dp))
                } else if (typeface != null)
                    Text(
                        stringResource(R.string.font_sample_glyphs),
                        fontFamily = FontFamily(typeface),
                        style = MaterialTheme.typography.displayLarge,
                        color =
                            if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                else
                    StoreEmblem(
                        Icons.Default.CloudDownload,
                        Modifier.size(64.dp),
                        MaterialTheme.colorScheme.secondary,
                        MaterialTheme.colorScheme.onSecondary,
                    )
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun FontOperation(state: FontUiState) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.operation.orEmpty(), style = MaterialTheme.typography.titleSmall)
        val progress = state.progress
        if (progress != null)
            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FontPreviewSheet(
    preview: FontPreview,
    busy: Boolean,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val latestBusy by rememberUpdatedState(busy)
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !latestBusy },
        )
    var sample by rememberSaveable(preview.name) { mutableStateOf("") }
    val family = remember(preview.typeface) { FontFamily(preview.typeface) }
    StoreModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding()) {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Text(
                        preview.name,
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.font_preview_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                stringResource(R.string.font_sample_title),
                                style = MaterialTheme.typography.headlineLarge,
                                fontFamily = family,
                            )
                            Text(
                                stringResource(R.string.font_sample_subtitle),
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = family,
                            )
                            Text(
                                stringResource(R.string.font_sample_body),
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = family,
                            )
                            Text(
                                stringResource(R.string.font_sample_numbers),
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = family,
                            )
                            Text(
                                stringResource(R.string.font_sample_symbols),
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = family,
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = sample,
                        onValueChange = { sample = it },
                        label = { Text(stringResource(R.string.font_try_text)) },
                        textStyle =
                            MaterialTheme.typography.headlineSmall.copy(fontFamily = family),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        minLines = 2,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                    ) {
                        if (deleting) LoadingIndicator(Modifier.size(24.dp))
                        else Icon(Icons.Default.DeleteOutline, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (deleting) R.string.font_deleting else R.string.font_delete
                            )
                        )
                    }
                }
                Button(
                    onClick = onApply,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) {
                    if (busy && !deleting) LoadingIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (busy && !deleting) R.string.font_applying else R.string.font_apply
                        )
                    )
                }
            }
        }
    }
}
