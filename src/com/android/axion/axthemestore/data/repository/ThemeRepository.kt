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

package com.android.axion.axthemestore.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.android.axion.axthemestore.data.model.Theme
import com.android.axion.axthemestore.data.model.ThemeCategory
import com.android.axion.axthemestore.data.model.ThemeComponent
import com.android.axion.axthemestore.data.model.ThemeOverlay
import com.android.axion.axthemestore.data.model.ThemesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.android.axion.axthemestore.data.model.IconPack
import android.content.Intent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ThemeRepository(private val context: Context) {

    companion object {
        private const val TAG = "ThemeRepository"
        private const val THEMES_JSON_URL = 
            "https://raw.githubusercontent.com/Lunaris-AOSP/AxThemeStore_themes_repository/16.2/themes.json"
        private const val CACHE_DURATION_MS = 0L
        private const val CACHE_FILE_NAME = "themes_cache.json"

        private val THEMEPICKER_CATEGORIES = setOf(
            "android.theme.customization.font",
            "android.theme.customization.adaptive_icon_shape",
            "android.theme.customization.icon_pack.android",
            "android.theme.customization.icon_pack.systemui",
            "android.theme.customization.icon_pack.settings",
            "android.theme.customization.icon_pack.launcher",
            "android.theme.customization.icon_pack.themepicker",
            "android.theme.customization.system_palette",
            "android.theme.customization.accent_color",
            "android.theme.customization.color_source",
            "android.theme.customization.lockscreen_clock_font",
            "android.theme.customization.settings",
            "android.theme.customization.qs_panel",
            "android.theme.customization.navbar",
            "android.theme.customization.hideclock",
            "android.theme.customization.smartspace",
            "android.theme.customization.smartspace_offset",
            "android.theme.customization.wallpaper",
        )
    }
    
    private var cachedResponse: ThemesResponse? = null
    private var lastFetchTime: Long = 0

    private val cacheFile: File
        get() = File(context.filesDir, CACHE_FILE_NAME)

    suspend fun fetchThemes(forceRefresh: Boolean = false): Result<ThemesResponse> {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!forceRefresh && cachedResponse != null &&
                (now - lastFetchTime) < CACHE_DURATION_MS) {
                return@withContext Result.success(cachedResponse!!)
            }

            try {
                val urlWithCacheBust = "$THEMES_JSON_URL?t=$now"
                val url = URL(urlWithCacheBust)
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext loadDiskCache()
                        ?.let { Result.success(it) }
                        ?: Result.failure(Exception("HTTP error: $responseCode"))
                }

                val jsonResponse = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val response = parseThemesResponse(jsonResponse)
                cachedResponse = response
                lastFetchTime = now

                runCatching { cacheFile.writeText(jsonResponse) }
                    .onFailure { Log.w(TAG, "Failed to persist themes cache", it) }

                Log.d(TAG, "Fetched ${response.themes.size} themes")

                Result.success(response)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch themes, trying disk cache", e)
                loadDiskCache()
                    ?.let { Result.success(it) }
                    ?: Result.failure(e)
            }
        }
    }

    private fun loadDiskCache(): ThemesResponse? {
        cachedResponse?.let { return it }
        val file = cacheFile
        if (!file.exists()) return null
        return runCatching {
            val response = parseThemesResponse(file.readText())
            cachedResponse = response
            Log.d(TAG, "Loaded ${response.themes.size} themes from disk cache")
            response
        }.getOrNull()
    }

    fun hasDiskCache(): Boolean = cachedResponse != null || cacheFile.exists()
    
    private fun parseThemesResponse(jsonStr: String): ThemesResponse {
        val json = JSONObject(jsonStr)
        val version = json.optInt("version", 0)
        val lastUpdated = json.optString("lastUpdated", "")
        
        val themes = mutableListOf<Theme>()
        val themesArr = json.optJSONArray("themes")
        if (themesArr != null) {
            for (i in 0 until themesArr.length()) {
                val themeObj = themesArr.getJSONObject(i)
                themes.add(parseTheme(themeObj))
            }
        }
        
        val categories = mutableListOf<ThemeCategory>()
        val catArr = json.optJSONArray("categories")
        if (catArr != null) {
            for (i in 0 until catArr.length()) {
                val catObj = catArr.getJSONObject(i)
                categories.add(ThemeCategory(
                   id = catObj.optString("id"),
                   name = catObj.optString("name"),
                   icon = catObj.optString("icon", "palette")
                ))
            }
        }

        val components = mutableListOf<ThemeComponent>()
        val compArr = json.optJSONArray("components")
        if (compArr != null) {
            for (i in 0 until compArr.length()) {
                val compObj = compArr.getJSONObject(i)
                components.add(ThemeComponent(
                    id = compObj.optString("id"),
                    name = compObj.optString("name"),
                    description = compObj.optString("description"),
                    targetPackage = compObj.optString("targetPackage"),
                    icon = compObj.optString("icon", "palette")
                ))
            }
        }

        return ThemesResponse(version, lastUpdated, themes, categories, components)
    }

    private fun parseTheme(json: JSONObject): Theme {
        val overlays = mutableListOf<ThemeOverlay>()
        val overlayArr = json.optJSONArray("overlays")
        if (overlayArr != null) {
            for (i in 0 until overlayArr.length()) {
                val obj = overlayArr.getJSONObject(i)
                
                val targets = mutableListOf<String>()
                val targetsArr = obj.optJSONArray("targets")
                if (targetsArr != null) {
                    for (j in 0 until targetsArr.length()) {
                        targets.add(targetsArr.getString(j))
                    }
                }
                
                overlays.add(ThemeOverlay(
                    componentId = obj.optString("componentId"),
                    packageName = obj.optString("packageName"),
                    targetPackage = obj.optString("targetPackage"),
                    targets = targets,
                    downloadUrl = obj.optString("downloadUrl"),
                    fileSize = obj.optLong("fileSize", 0),
                    enabled = obj.optBoolean("enabled", true)
                ))
            }
        }
        
        val tags = mutableListOf<String>()
        val tagsArr = json.optJSONArray("tags")
        if (tagsArr != null) {
            for (i in 0 until tagsArr.length()) {
                tags.add(tagsArr.getString(i))
            }
        }
        
        val previews = mutableListOf<String>()
        val prevArr = json.optJSONArray("previewImages")
        if (prevArr != null) {
            for (i in 0 until prevArr.length()) {
                previews.add(prevArr.getString(i))
            }
        }

        return Theme(
            id = json.optString("id"),
            name = json.optString("name"),
            description = json.optString("description"),
            author = json.optString("author"),
            version = json.optString("version"),
            versionCode = json.optInt("versionCode"),
            minSdk = json.optInt("minSdk", 31),
            previewImages = previews,
            category = json.optString("category"),
            tags = tags,
            overlays = overlays,
            isUnified = json.optBoolean("isUnified", false),
            supportsRegionSampling = json.optBoolean("supportsRegionSampling", false)
        )
    }
    
    suspend fun getThemes(forceRefresh: Boolean = false): Result<List<Theme>> {
        return fetchThemes(forceRefresh).map { it.themes }
    }
    
    suspend fun getCategories(forceRefresh: Boolean = false): Result<List<ThemeCategory>> {
        return fetchThemes(forceRefresh).map { it.categories }
    }
    
    suspend fun getThemesByCategory(
        categoryId: String, 
        forceRefresh: Boolean = false
    ): Result<List<Theme>> {
        return getThemes(forceRefresh).map { themes ->
            themes.filter { it.category == categoryId }
        }
    }
    
    suspend fun searchThemes(
        query: String, 
        forceRefresh: Boolean = false
    ): Result<List<Theme>> {
        return getThemes(forceRefresh).map { themes ->
            val lowerQuery = query.lowercase()
            themes.filter { theme ->
                theme.name.lowercase().contains(lowerQuery) ||
                theme.description.lowercase().contains(lowerQuery) ||
                theme.author.lowercase().contains(lowerQuery) ||
                theme.tags.any { it.lowercase().contains(lowerQuery) }
            }
        }
    }
    
    fun getInstalledVersionCode(packageName: String): Int? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            packageInfo.longVersionCode.toInt()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    
    fun isThemeInstalled(packageName: String): Boolean {
        return getInstalledVersionCode(packageName) != null
    }
    
    fun clearCache() {
        cachedResponse = null
        lastFetchTime = 0
    }
    
    fun getInstalledThirdPartyThemes(storeThemePackages: Set<String>): List<Theme> {
        val themes = mutableListOf<Theme>()
        
        try {
            val pm = context.packageManager
            val installedPackages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            
            for (packageInfo in installedPackages) {
                val packageName = packageInfo.packageName
                
                if (storeThemePackages.contains(packageName)) continue
                
                if (packageInfo.applicationInfo?.enabled == false) continue
                
                val overlayCategory = packageInfo.overlayCategory
                val isRroTheme = packageInfo.isOverlayPackage() &&
                        overlayCategory != null &&
                        overlayCategory.startsWith("android.theme.customization.") &&
                        overlayCategory !in THEMEPICKER_CATEGORIES
                val isAxionTheme = isThemePackage(packageInfo.applicationInfo?.metaData)

                if (isRroTheme || isAxionTheme) {
                    val appInfo = packageInfo.applicationInfo
                    val appLabel = appInfo?.let {
                        pm.getApplicationLabel(it).toString()
                    } ?: packageName

                    val theme = if (isRroTheme) {
                        val componentId = overlayCategory!!.removePrefix("android.theme.customization.")
                        val uiCategory = overlayCategory.removePrefix("android.theme.customization.")
                        Theme(
                            id = "local_$packageName",
                            name = appLabel,
                            description = "Locally installed theme overlay",
                            author = "Third-party",
                            version = packageInfo.versionName ?: "1.0",
                            versionCode = packageInfo.longVersionCode.toInt(),
                            minSdk = packageInfo.applicationInfo?.minSdkVersion ?: 31,
                            previewImages = emptyList(),
                            category = uiCategory,
                            tags = listOf(uiCategory, "local"),
                            overlays = listOf(
                                ThemeOverlay(
                                    componentId = componentId,
                                    packageName = packageName,
                                    targetPackage = packageInfo.overlayTarget ?: "",
                                    targets = listOf(overlayCategory),
                                    downloadUrl = "",
                                    fileSize = 0,
                                    enabled = true
                                )
                            ),
                            isUnified = true
                        )
                    } else {
                        val targets = getThemeTargets(packageInfo.applicationInfo?.metaData)
                        val iconThemeTargets = setOf(
                            "wifi", "signal",
                            "android", "systemui", "systemui_icons",
                            "settings", "com.android.settings",
                            "framework", "framework-res"
                        )
                        val isIconTheme = targets.isNotEmpty() && targets.all { target ->
                            iconThemeTargets.any { it.equals(target, ignoreCase = true) }
                        }
                        val category = if (isIconTheme) "icon_themes" else "local"
                        Theme(
                            id = "local_$packageName",
                            name = appLabel,
                            description = "Manually installed theme package",
                            author = "Third-party",
                            version = packageInfo.versionName ?: "1.0",
                            versionCode = packageInfo.longVersionCode.toInt(),
                            minSdk = packageInfo.applicationInfo?.minSdkVersion ?: 31,
                            previewImages = emptyList(),
                            category = category,
                            tags = if (isIconTheme) listOf("icons", "local", "third-party") else listOf("local", "third-party"),
                            overlays = listOf(
                                ThemeOverlay(
                                    componentId = "unified",
                                    packageName = packageName,
                                    targetPackage = "",
                                    targets = targets,
                                    downloadUrl = "",
                                    fileSize = 0,
                                    enabled = true
                                )
                            ),
                            isUnified = true
                        )
                    }
                    themes.add(theme)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan for third-party themes", e)
        }
        
        return themes
    }
    
    private fun isThemePackage(metaData: android.os.Bundle?): Boolean {
        return metaData?.containsKey("axion_theme") == true
    }
    
    private fun getThemeTargets(metaData: android.os.Bundle?): List<String> {
        if (metaData == null) return listOf("android", "systemui")
        
        val targetsString = metaData.getString("axion_theme")
        if (!targetsString.isNullOrBlank()) {
            return targetsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        
        return listOf("android", "systemui")
    }
    
    suspend fun getInstalledIconPacks(): List<IconPack> = withContext(Dispatchers.IO) {
        val iconPacks = mutableListOf<IconPack>()
        val pm = context.packageManager
        
        val intentActions = listOf(
            "org.adw.launcher.THEMES",
            "com.teslacoilsw.launcher.THEME"
        )
        
        val seenPackages = mutableSetOf<String>()
        
        for (action in intentActions) {
            val intent = Intent(action)
            val formatList = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            
            android.util.Log.d("ThemeRepository", "Icon pack query for action $action found ${formatList.size} packages")
            
            for (resolveInfo in formatList) {
                val packageName = resolveInfo.activityInfo.packageName
                if (packageName !in seenPackages) {
                    try {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        val label = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        
                        iconPacks.add(IconPack(packageName, label, icon))
                        seenPackages.add(packageName)
                        android.util.Log.d("ThemeRepository", "Added icon pack: $label ($packageName)")
                    } catch (e: Exception) {
                        android.util.Log.e("ThemeRepository", "Failed to load icon pack $packageName", e)
                    }
                }
            }
        }
        
        android.util.Log.d("ThemeRepository", "Total icon packs found: ${iconPacks.size}")
        
        val sortedPacks = iconPacks.sortedBy { it.label }
        
        listOf(IconPack("", "System Default", null)) + sortedPacks
    }
    
}
