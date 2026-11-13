/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.android.axion.axthemestore.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.EmojiSupportMatch
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.axion.axthemestore.R
import com.android.axion.axthemestore.fonts.EmojiOption
import com.android.axion.axthemestore.fonts.EmojiRepository
import com.android.axion.axthemestore.ui.components.StoreModalBottomSheet
import com.android.axion.axthemestore.viewmodel.EmojiUiState
import com.android.axion.axthemestore.viewmodel.EmojiViewModel

@Composable
internal fun EmojiCustomizationSheet(
    viewModel: EmojiViewModel,
    state: EmojiUiState,
    onDismiss: () -> Unit,
) {
    var online by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf<EmojiOption?>(null) }
    var restoreStock by rememberSaveable { mutableStateOf(false) }
    var updateApps by rememberSaveable { mutableStateOf(false) }
    val busy = state.operation != null
    val context = LocalContext.current
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::inspect)
        }
    val stockName = stringResource(R.string.emoji_stock)
    val options =
        remember(
            state.options,
            online,
            query,
            stockName,
            state.currentTypeface,
            state.stockTypeface,
            state.selectedKey,
            state.currentLabel,
        ) {
            val local = buildList {
                add(EmojiOption(EmojiRepository.DEFAULT_KEY, stockName, state.stockTypeface))
                if (state.selectedKey == EmojiRepository.CUSTOM_KEY) {
                    add(
                        EmojiOption(
                            EmojiRepository.CUSTOM_KEY,
                            state.currentLabel,
                            state.currentTypeface,
                        )
                    )
                }
                addAll(state.options.filter { it.downloaded })
            }
            (if (online) state.options else local).filter { it.label.contains(query.trim(), true) }
        }
    LaunchedEffect(online, busy) { if (online && !busy) viewModel.loadCatalog() }
    StoreModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyVerticalGrid(
            columns =
                GridCells.Adaptive(148.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)),
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.font_emoji),
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                        )
                        Text(
                            stringResource(R.string.emoji_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        enabled = !busy && !state.catalogLoading,
                        onClick = {
                            viewModel.refresh()
                            if (online) viewModel.loadCatalog(force = true)
                        },
                    ) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                }
            }
            item(key = "current", span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.emoji_current),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            state.currentLabel.ifEmpty { stockName },
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        EmojiSample(state.currentTypeface)
                    }
                }
            }
            item(key = "import", span = { GridItemSpan(maxLineSpan) }) {
                FilledTonalButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    onClick = {
                        importer.launch(
                            arrayOf(
                                "font/ttf",
                                "font/sfnt",
                                "application/x-font-ttf",
                                "application/font-sfnt",
                                "application/octet-stream",
                            )
                        )
                    },
                ) {
                    Icon(Icons.Default.FileOpen, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.emoji_import))
                        Text(
                            stringResource(R.string.emoji_import_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(Icons.Default.Add, null)
                }
            }
            if (state.pendingRestart) {
                item(key = "restart", span = { GridItemSpan(maxLineSpan) }) {
                    EmojiAction(
                        stringResource(R.string.emoji_restart),
                        stringResource(R.string.emoji_restart_pending),
                        Icons.Default.RestartAlt,
                        enabled = !busy,
                        onClick = viewModel::showRestart,
                    )
                }
            }
            if (state.customInstalled) {
                item(key = "apps", span = { GridItemSpan(maxLineSpan) }) {
                    EmojiAction(
                        stringResource(R.string.emoji_update_apps),
                        stringResource(R.string.emoji_apps_description),
                        Icons.Default.Apps,
                        enabled = !busy,
                        onClick = { updateApps = true },
                    )
                }
            }
            if (busy) {
                item(key = "operation", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            checkNotNull(state.operation),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val progress = state.progress
                        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                    }
                }
            }
            item(key = "sources", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !online,
                            onClick = { online = false },
                            label = { Text(stringResource(R.string.font_bundled)) },
                            leadingIcon = {
                                Icon(Icons.Default.PhoneAndroid, null, Modifier.size(18.dp))
                            },
                        )
                        FilterChip(
                            selected = online,
                            onClick = { online = true },
                            label = { Text(stringResource(R.string.emoji_online)) },
                            leadingIcon = {
                                Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                            },
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.emoji_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                    )
                }
            }
            if (online && state.catalogLoading) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator(Modifier.size(40.dp))
                    }
                }
            }
            if (online && state.catalogError != null) {
                item(key = "catalogError", span = { GridItemSpan(maxLineSpan) }) {
                    EmojiAction(
                        state.catalogError,
                        stringResource(R.string.emoji_retry),
                        Icons.Default.CloudOff,
                        enabled = !busy && !state.catalogLoading,
                        onClick = { viewModel.loadCatalog(force = true) },
                    )
                }
            }
            items(options, key = { "emoji:${it.key}" }) { option ->
                EmojiCard(
                    option,
                    option.key == state.selectedKey,
                    !busy,
                    onClick = {
                        if (option.key == EmojiRepository.DEFAULT_KEY) {
                            if (state.customInstalled) restoreStock = true
                        } else if (option.entry != null) chosen = option
                    },
                )
            }
            if (options.isEmpty() && !state.catalogLoading) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.emoji_no_results),
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    chosen?.let { original ->
        val option = state.options.find { it.key == original.key } ?: original
        AlertDialog(
            onDismissRequest = { if (!busy) chosen = null },
            icon = { Icon(Icons.Default.EmojiEmotions, null) },
            title = { Text(option.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (option.typeface != null) EmojiSample(option.typeface)
                    else Text(stringResource(R.string.font_download_preview))
                    Text(Formatter.formatShortFileSize(context, option.sizeBytes))
                    Text(stringResource(R.string.emoji_apply_description))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && option.key != state.selectedKey,
                    onClick = {
                        chosen = null
                        viewModel.apply(option)
                    },
                ) {
                    Text(
                        stringResource(
                            if (option.downloaded) R.string.emoji_apply
                            else R.string.emoji_download_apply
                        )
                    )
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            chosen = null
                            if (option.downloaded) viewModel.deleteDownload(option)
                            else viewModel.download(option)
                        },
                    ) {
                        Text(
                            stringResource(
                                if (option.downloaded) R.string.emoji_delete_download
                                else R.string.emoji_download
                            )
                        )
                    }
                    TextButton(enabled = !busy, onClick = { chosen = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            },
        )
    }
    if (restoreStock)
        EmojiConfirmation(
            stringResource(R.string.emoji_restore_title),
            stringResource(R.string.emoji_restore_description),
            stringResource(R.string.emoji_restore),
            onDismiss = { restoreStock = false },
            onConfirm = {
                restoreStock = false
                viewModel.restoreStock()
            },
        )
    if (updateApps)
        EmojiConfirmation(
            stringResource(R.string.emoji_update_apps),
            stringResource(R.string.emoji_apps_confirmation),
            stringResource(R.string.emoji_update_apps),
            onDismiss = { updateApps = false },
            onConfirm = {
                updateApps = false
                viewModel.updateApps()
            },
        )
}

@Composable
internal fun EmojiDialogs(viewModel: EmojiViewModel, state: EmojiUiState) {
    val busy = state.operation != null
    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPreview,
            icon = { Icon(Icons.Default.EmojiEmotions, null) },
            title = { Text(preview.displayName ?: preview.postScriptName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    EmojiSample(preview.typeface)
                    Text(stringResource(R.string.emoji_apply_description))
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = viewModel::installPreview) {
                    Text(stringResource(R.string.emoji_apply))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = viewModel::dismissPreview) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (state.restartPrompt && !busy)
        EmojiConfirmation(
            stringResource(R.string.emoji_restart),
            stringResource(R.string.emoji_restart_description),
            stringResource(R.string.emoji_restart),
            onDismiss = viewModel::dismissRestart,
            onConfirm = viewModel::restart,
            dismissLabel = stringResource(R.string.font_later),
        )
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
private fun EmojiConfirmation(
    title: String,
    description: String,
    action: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismissLabel: String = stringResource(android.R.string.cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
private fun EmojiSample(typeface: Typeface?) {
    Text(
        stringResource(R.string.font_emoji_sample),
        style =
            MaterialTheme.typography.headlineLarge.copy(
                fontFamily = typeface?.let { FontFamily(it) } ?: FontFamily.Default,
                platformStyle = PlatformTextStyle(emojiSupportMatch = EmojiSupportMatch.None),
            ),
    )
}

@Composable
private fun EmojiCard(
    option: EmojiOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val preview =
        remember(option.preview) {
            option.preview?.let { file ->
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    var sampleSize = 1
                    while (
                        bounds.outWidth / sampleSize > 512 || bounds.outHeight / sampleSize > 512
                    ) sampleSize *= 2
                    BitmapFactory.decodeFile(
                            file.path,
                            BitmapFactory.Options().apply { inSampleSize = sampleSize },
                        )
                        ?.asImageBitmap()
                }
                    .getOrNull()
            }
        }
    Surface(
        onClick = onClick,
        enabled =
            enabled && option.key != EmojiRepository.CUSTOM_KEY && (!option.isDefault || !selected),
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
        shape = MaterialTheme.shapes.extraLarge,
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected)
                    Icon(
                        Icons.Default.CheckCircle,
                        stringResource(R.string.emoji_selected),
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
            }
            when {
                option.typeface != null || option.isDefault -> EmojiSample(option.typeface)
                preview != null ->
                    Image(
                        preview,
                        null,
                        Modifier.fillMaxWidth().height(72.dp),
                        contentScale = ContentScale.Fit,
                    )
                else ->
                    Icon(
                        Icons.Default.CloudDownload,
                        null,
                        Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
            }
            if (option.entry != null)
                Text(
                    stringResource(
                        if (option.downloaded) R.string.font_saved
                        else R.string.font_download_preview
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
private fun EmojiAction(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
