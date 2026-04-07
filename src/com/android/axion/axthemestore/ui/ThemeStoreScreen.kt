/*
 * Copyright (C) 2025 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.axion.axthemestore.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.axion.axthemestore.R
import com.android.axion.axthemestore.data.model.Theme
import com.android.axion.axthemestore.data.model.ThemeCategory
import com.android.axion.axthemestore.data.model.ThemeInstallState
import com.android.axion.axthemestore.engine.ThemeEngineProxy
import com.android.axion.axthemestore.ui.components.AsyncNetworkImage
import com.android.axion.axthemestore.ui.components.BackGesturePreview
import com.android.axion.axthemestore.ui.components.BatteryStylePreview
import com.android.axion.axthemestore.ui.components.ThemeCard
import com.android.axion.axthemestore.ui.components.ImagePlaceholder
import com.android.axion.axthemestore.ui.components.ThemePackagePreview
import com.android.axion.axthemestore.viewmodel.ThemeStoreUiState
import com.android.axion.axthemestore.viewmodel.ThemeStoreViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeStoreScreen(
    viewModel: ThemeStoreViewModel,
    onThemeClick: (Theme) -> Unit,
    onNavigateToCategory: (String) -> Unit = {},
    onNavigateToInstalledComponents: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themeStates by viewModel.themeStates.collectAsStateWithLifecycle()
    
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isSearchActive) {
                SearchScreen(
                    viewModel = viewModel,
                    searchQuery = searchQuery,
                    onSearchChange = { 
                        searchQuery = it
                        viewModel.searchThemes(it)
                    },
                    onBack = { 
                        isSearchActive = false
                        searchQuery = ""
                        viewModel.searchThemes("")
                    },
                    themes = viewModel.getFilteredThemes(),
                    themeStates = themeStates,
                    onThemeClick = onThemeClick
                )
            } else {
                BrowseScreen(
                    uiState = uiState,
                    themeStates = themeStates,
                    onSearchClick = { isSearchActive = true },
                    onRefresh = { viewModel.loadThemes(forceRefresh = true) },
                    onThemeClick = onThemeClick,
                    onNavigateToCategory = onNavigateToCategory,

                    onNavigateToInstalledComponents = onNavigateToInstalledComponents
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryThemesScreen(
    categoryId: String,
    viewModel: ThemeStoreViewModel,
    onThemeClick: (Theme) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themeStates by viewModel.themeStates.collectAsStateWithLifecycle()
    
    val category = uiState.categories.find { it.id == categoryId }
    val categoryName = category?.name ?: "Themes"
    
    val categoryThemes = if (categoryId == "local") {
        uiState.themes.filter { it.isLocal }
    } else {
        uiState.themes.filter { it.category == categoryId }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = categoryName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    LoadingState()
                }
                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error!!,
                        onRetry = { viewModel.loadThemes(forceRefresh = true) }
                    )
                }
                categoryThemes.isEmpty() -> {
                    EmptyState(isSearching = false)
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(categoryThemes, key = { it.id }) { theme ->
                            ThemeCard(
                                theme = theme,
                                installState = themeStates[theme.id] ?: ThemeInstallState.NotInstalled,
                                onClick = { onThemeClick(theme) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    viewModel: ThemeStoreViewModel,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onBack: () -> Unit,
    themes: List<Theme>,
    themeStates: Map<String, ThemeInstallState>,
    onThemeClick: (Theme) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            
            TextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { 
                    Text(
                        stringResource(R.string.search_themes),
                        style = MaterialTheme.typography.bodyLarge
                    ) 
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
            
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear)
                    )
                }
            }
        }
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        
        if (themes.isEmpty() && searchQuery.isNotEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_results_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (searchQuery.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(themes, key = { it.id }) { theme ->
                    ThemeListItem(
                        theme = theme,
                        installState = themeStates[theme.id] ?: ThemeInstallState.NotInstalled,
                        onClick = { onThemeClick(theme) }
                    )
                }
            }
        } else {
            val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
            
            if (searchHistory.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = stringResource(R.string.no_recent_searches),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.recent_searches),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(onClick = { viewModel.clearSearchHistory() }) {
                            Text(stringResource(R.string.clear_all))
                        }
                    }
                    
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(searchHistory) { query ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSearchChange(query) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = query,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.removeSearchHistoryItem(query) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.remove),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseScreen(
    uiState: ThemeStoreUiState,
    themeStates: Map<String, ThemeInstallState>,
    onSearchClick: () -> Unit,
    onRefresh: () -> Unit,
    onThemeClick: (Theme) -> Unit,
    onNavigateToCategory: (String) -> Unit,

    onNavigateToInstalledComponents: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.themes),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            IconButton(onClick = onNavigateToInstalledComponents) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = stringResource(R.string.installed_components)
                )
            }
            
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.search)
                )
            }
            
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh)
                )
            }
        }
        
        when {
            uiState.isLoading -> {
                LoadingState()
            }
            uiState.error != null -> {
                ErrorState(
                    message = uiState.error!!,
                    onRetry = onRefresh
                )
            }
            else -> {
                if (uiState.themes.isEmpty()) {
                    EmptyState(isSearching = false)
                } else {
                    val themesByCategory = uiState.themes.groupBy { it.category }
                    
                    val installedThemes = uiState.themes.filter { theme ->
                        val state = themeStates[theme.id]
                        state is ThemeInstallState.Installed || 
                        state is ThemeInstallState.InstalledInactive ||
                        theme.isLocal
                    }
                    
                    val featuredThemes = remember(uiState.themes) { uiState.themes.shuffled().take(3) }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (featuredThemes.isNotEmpty()) {
                            item {
                                FeaturedCarousel(
                                    themes = featuredThemes,
                                    themeStates = themeStates,
                                    onThemeClick = onThemeClick
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        
                        if (installedThemes.isNotEmpty()) {
                            item {
                                ThemeSection(
                                    title = stringResource(R.string.installed),
                                    themes = installedThemes,
                                    themeStates = themeStates,
                                    onThemeClick = onThemeClick
                                )
                            }
                        }
                        
                        uiState.categories.forEach { category ->
                            val categoryThemes = themesByCategory[category.id] ?: emptyList()
                            if (categoryThemes.isNotEmpty()) {
                                item {
                                    ThemeSection(
                                        title = category.name,
                                        themes = categoryThemes,
                                        themeStates = themeStates,
                                        onThemeClick = onThemeClick
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSection(
    title: String,
    themes: List<Theme>,
    themeStates: Map<String, ThemeInstallState>,
    onThemeClick: (Theme) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        val chunkedThemes = themes.chunked(3)
        
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(chunkedThemes.size) { chunkIndex ->
                val chunk = chunkedThemes[chunkIndex]
                Column(
                    modifier = Modifier.width(280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    chunk.forEach { theme ->
                        ThemeListItem(
                            theme = theme,
                            installState = themeStates[theme.id] ?: ThemeInstallState.NotInstalled,
                            onClick = { onThemeClick(theme) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeListItem(
    theme: Theme,
    installState: ThemeInstallState,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.small)
            ) {
                val isInstalled = installState is ThemeInstallState.Installed ||
                                  installState is ThemeInstallState.InstalledInactive
                val packageName = theme.overlays.firstOrNull()?.packageName ?: ""
                val category = theme.category.ifEmpty { theme.overlays.firstOrNull()?.componentId ?: "" }
                val isBattery = packageName.contains("battery") || category.contains("battery")
                val isBackGesture = packageName.contains("back_gesture") || category.contains("back_gesture")
                val isChargingAnim = packageName.contains("charging_animation") || category.contains("charging_animation")

                run {
                    val previewResIds = getLocalPreviewResIds(LocalContext.current, packageName)
                    if (previewResIds.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(previewResIds.first()),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                colorFilter = ColorFilter.tint(
                                    MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    } else if (isBattery) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                            MaterialTheme.colorScheme.surfaceContainer
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            BatteryStylePreview(
                                packageName = packageName,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else if (isBackGesture) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                            MaterialTheme.colorScheme.surfaceContainer
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            BackGesturePreview(modifier = Modifier.fillMaxSize())
                        }
                    } else if (isChargingAnim) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                            MaterialTheme.colorScheme.surfaceContainer
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (isInstalled && packageName.isNotEmpty()) {
                        ThemePackagePreview(
                            packageName = packageName,
                            modifier = Modifier.fillMaxSize(),
                            showSingleIcon = true
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                            MaterialTheme.colorScheme.surfaceContainer
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = theme.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = theme.description.ifEmpty { theme.category },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                val stateText = when (installState) {
                    is ThemeInstallState.Installed -> "Active"
                    is ThemeInstallState.InstalledInactive -> "Installed"
                    else -> null
                }
                stateText?.let {
                    Text(
                        text = "✓ $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            if (theme.previewImages.size > 1) {
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 80.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                ) {
                    AsyncNetworkImage(
                        url = theme.previewImages[1],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactThemeCard(
    theme: Theme,
    installState: ThemeInstallState,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(140.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(MaterialTheme.shapes.medium)
            ) {
                val isInstalled = installState is ThemeInstallState.Installed || 
                                  installState is ThemeInstallState.InstalledInactive
                val packageName = theme.overlays.firstOrNull()?.packageName
                
                when {
                    theme.previewImages.isNotEmpty() -> {
                        AsyncNetworkImage(
                            url = theme.previewImages.first(),
                            contentDescription = theme.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            errorContent = {
                                if (isInstalled && packageName != null) {
                                    ThemePackagePreview(
                                        packageName = packageName,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    ImagePlaceholder(modifier = Modifier.fillMaxSize())
                                }
                            }
                        )
                    }
                    isInstalled && packageName != null -> {
                        ThemePackagePreview(
                            packageName = packageName,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                            MaterialTheme.colorScheme.surfaceContainer
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                val stateColor = when (installState) {
                    is ThemeInstallState.Installed -> MaterialTheme.colorScheme.primary
                    is ThemeInstallState.InstalledInactive -> MaterialTheme.colorScheme.secondary
                    else -> null
                }
                stateColor?.let { color ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
            
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = theme.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = theme.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun FeaturedCarousel(
    themes: List<Theme>,
    themeStates: Map<String, ThemeInstallState>,
    onThemeClick: (Theme) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { themes.size })
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.featured),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        )
        
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 16.dp,
            modifier = Modifier.height(200.dp)
        ) { page ->
            val theme = themes[page]
            FeaturedThemeCard(
                theme = theme, 
                installState = themeStates[theme.id] ?: ThemeInstallState.NotInstalled,
                onClick = { onThemeClick(theme) }
            )
        }
    }
}

@Composable
private fun FeaturedThemeCard(
    theme: Theme,
    installState: ThemeInstallState,
    onClick: () -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val scrimmedBg = lerp(containerColor, Color.Black, 0.6f)
    val textColor = if (scrimmedBg.luminance() < 0.4f) Color.White else Color.Black

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FeaturedPreviewContent(theme = theme, iconTint = textColor)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f)
                            ),
                            startY = 100f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = theme.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                Text(
                    text = theme.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun FeaturedPreviewContent(theme: Theme, iconTint: Color) {
    val context = LocalContext.current
    val previewMap = remember {
        val map = mutableMapOf<String, String>()
        try {
            val entries = context.resources.getStringArray(R.array.overlay_preview_map)
            for (entry in entries) {
                val parts = entry.split("|", limit = 2)
                if (parts.size == 2) map[parts[0]] = parts[1]
            }
        } catch (_: Exception) {}
        map
    }

    val packageName = theme.overlays.firstOrNull()?.packageName ?: ""
    val prefix = previewMap[packageName] ?: ""
    val resIds = if (prefix.isNotEmpty()) {
        (1..4).mapNotNull { i ->
            val id = context.resources.getIdentifier("${prefix}_$i", "drawable", context.packageName)
            if (id != 0) id else null
        }
    } else emptyList()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (resIds.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (resId in resIds) {
                    Image(
                        painter = painterResource(resId),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        colorFilter = ColorFilter.tint(iconTint)
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LoadingIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.loading_themes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.failed_to_load_themes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun EmptyState(isSearching: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isSearching) Icons.Default.SearchOff else Icons.Default.Inbox,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(if (isSearching) R.string.no_themes_found else R.string.no_themes_available),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (isSearching) R.string.try_different_search
                    else R.string.check_back_later
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val sPreviewMapCache = mutableMapOf<String, String>()

@Composable
private fun getLocalPreviewResIds(context: android.content.Context, packageName: String): List<Int> {
    if (sPreviewMapCache.isEmpty()) {
        try {
            val entries = context.resources.getStringArray(R.array.overlay_preview_map)
            for (entry in entries) {
                val parts = entry.split("|", limit = 2)
                if (parts.size == 2) sPreviewMapCache[parts[0]] = parts[1]
            }
        } catch (_: Exception) {}
    }
    val prefix = sPreviewMapCache[packageName] ?: return emptyList()
    return (1..4).mapNotNull { i ->
        val id = context.resources.getIdentifier("${prefix}_$i", "drawable", context.packageName)
        if (id != 0) id else null
    }
}
