/*
 * Copyright (C) 2025 AxionOS
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.axion.axthemestore.fonts

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.FontFamilyUpdateRequest
import android.graphics.fonts.FontFileUpdateRequest
import android.graphics.fonts.FontFileUtil
import android.graphics.fonts.FontManager
import android.graphics.fonts.FontStyle
import android.graphics.fonts.FontVariationAxis
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.util.lunaris.FontController
import com.android.internal.util.lunaris.ThemeUtils
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import org.json.JSONObject

class SystemFontController(private val context: Context) {
    private val resolver = context.contentResolver
    private val themeUtils = ThemeUtils.getInstance(context)
    private val manager
        get() = checkNotNull(context.getSystemService(FontManager::class.java))

    fun selection(): FontSelection =
        FontSelection(
            overlayPackage =
                themeUtils.getOverlayInfos(CATEGORY).firstOrNull { it.isEnabled }?.packageName
                    ?: "android",
            customFontName =
                Settings.Secure.getStringForUser(resolver, CUSTOM_NAME, UserHandle.USER_CURRENT)
                    .orEmpty(),
        )

    fun bundledFonts(): List<BundledFont> =
        themeUtils
            .getOverlayPackagesForCategory(CATEGORY, "android")
            .distinct()
            .filter { it != EXTERNAL_OVERLAY }
            .mapNotNull { pkg ->
                runCatching {
                        val pm = context.packageManager
                        val name =
                            if (pkg == "android") ""
                            else pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                        val resources =
                            if (pkg == "android") Resources.getSystem()
                            else pm.getResourcesForApplication(pkg)
                        val familyKey =
                            if (name == "NothingDot57") "config_headlineFontFamily"
                            else "config_bodyFontFamily"
                        val id = resources.getIdentifier(familyKey, "string", pkg)
                        val family =
                            if (pkg == "android" || id == 0) {
                                SystemProperties.get(
                                    FontController.PROP_DEFAULT_FONT,
                                    FontController.GSF_FONT,
                                )
                            } else resources.getString(id)
                        val typeface = Typeface.getSystemDefaultTypeface(family)
                        BundledFont(pkg, name, typeface)
                    }
                    .getOrNull()
            }
            .sortedWith(
                compareBy<BundledFont> { it.packageName != "android" }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )

    fun preview(file: File, name: String): FontPreview {
        require(file.length() in 1..MAX_FONT_BYTES) { "Invalid font file size" }
        postScriptName(file)
        return FontPreview(name, Typeface.createFromFile(file), file = file)
    }

    fun applyBundled(font: BundledFont) {
        val property =
            if (font.packageName == "android") ""
            else {
                val res = context.packageManager.getResourcesForApplication(font.packageName)
                listOf(
                        "config_bodyFontFamily",
                        "config_bodyFontFamilyMedium",
                        "config_headlineFontFamily",
                        "config_headlineFontFamilyMedium",
                    )
                    .joinToString(":") { key ->
                        val id = res.getIdentifier(key, "string", font.packageName)
                        if (id != 0) res.getString(id) else ""
                    }
            }
        applySelection(font.packageName, property, "")
    }

    private fun applySelection(packageName: String, property: String, customName: String) {
        val previous = selection()
        val previousProperty = SystemProperties.get(OVERLAY_PROPERTY, "")
        val previousOverlays =
            Settings.Secure.getStringForUser(
                resolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                UserHandle.USER_CURRENT,
            )
        val overlays =
            if (previousOverlays.isNullOrBlank()) JSONObject() else JSONObject(previousOverlays)
        if (packageName == "android") overlays.remove(CATEGORY)
        else overlays.put(CATEGORY, packageName)
        try {
            SystemProperties.set(OVERLAY_PROPERTY, property)
            putCustomName(customName)
            themeUtils.setOverlayEnabled(CATEGORY, packageName, "android")
            val selected =
                themeUtils.getOverlayInfos(CATEGORY).firstOrNull { it.isEnabled }?.packageName
                    ?: "android"
            check(selected == packageName) { "Could not apply the font overlay" }
            writeOverlays(overlays)
        } catch (e: Exception) {
            fun restore(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Exception) {
                    e.addSuppressed(failure)
                }
            }
            restore { SystemProperties.set(OVERLAY_PROPERTY, previousProperty) }
            restore { putCustomName(previous.customFontName) }
            restore { themeUtils.setOverlayEnabled(CATEGORY, previous.overlayPackage, "android") }
            restore {
                check(
                    Settings.Secure.putStringForUser(
                        resolver,
                        Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                        previousOverlays,
                        UserHandle.USER_CURRENT,
                    )
                )
            }
            throw e
        }
    }

    fun install(file: File): String {
        val staged = stageFont(file)
        try {
            return installStaged(staged)
        } finally {
            staged.delete()
        }
    }

    private fun stageFont(source: File): File {
        require(source.length() in 1..MAX_FONT_BYTES) { "Invalid font file size" }
        val staged = File.createTempFile("font-install-", ".font", context.cacheDir)
        try {
            source.inputStream().use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_FONT_BYTES) { "Font file is too large" }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0) { "Invalid font file size" }
                }
            }
            return staged
        } catch (e: Exception) {
            staged.delete()
            throw e
        }
    }

    private fun installStaged(file: File): String {
        val name = postScriptName(file)
        val (supportedAxes, style) =
            FileInputStream(file).use { stream ->
                val buffer =
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, stream.channel.size())
                FontFileUtil.getSupportedAxes(buffer, 0) to FontFileUtil.analyzeStyle(buffer, 0, null)
            }
        require(FontFileUtil.isSuccess(style)) { "Could not read the font style" }
        val hasWeightAxis = supportedAxes.contains(0x77676874)
        val hasItalicAxis = supportedAxes.contains(0x6974616c)
        val weights =
            if (hasWeightAxis) (100..900 step 100).toList()
            else listOf(FontFileUtil.unpackWeight(style))
        val slants =
            if (hasItalicAxis) {
                listOf(FontStyle.FONT_SLANT_UPRIGHT, FontStyle.FONT_SLANT_ITALIC)
            } else {
                listOf(
                    if (FontFileUtil.unpackItalic(style)) FontStyle.FONT_SLANT_ITALIC
                    else FontStyle.FONT_SLANT_UPRIGHT
                )
            }
        val fonts = mutableListOf<FontFamilyUpdateRequest.Font>()
        for (weight in weights) {
            for (slant in slants) {
                val axes = buildList {
                    if (hasWeightAxis) add(FontVariationAxis("wght", weight.toFloat()))
                    if (hasItalicAxis) add(FontVariationAxis("ital", slant.toFloat()))
                }
                fonts +=
                    FontFamilyUpdateRequest.Font.Builder(name, FontStyle(weight, slant))
                        .setAxes(axes)
                        .build()
            }
        }
        val family = FontFamilyUpdateRequest.FontFamily.Builder(EXTERNAL_FAMILY, fonts).build()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val request =
                FontFamilyUpdateRequest.Builder()
                    .addFontFileUpdateRequest(FontFileUpdateRequest(descriptor, byteArrayOf()))
                    .addFontFamily(family)
                    .build()
            val result = manager.updateFontFamily(request, manager.fontConfig.configVersion)
            check(result == FontManager.RESULT_SUCCESS) { "Font service returned " + result }
        }
        applySelection(
            EXTERNAL_OVERLAY,
            List(4) { EXTERNAL_FAMILY }.joinToString(":"),
            name,
        )
        return name
    }

    fun reset() = applyBundled(BundledFont("android", "", Typeface.DEFAULT))

    fun restart(kind: FontRestart) {
        val service =
            checkNotNull(IStatusBarService.Stub.asInterface(ServiceManager.getService("statusbar")))
        when (kind) {
            FontRestart.SYSTEM_UI -> service.restartSystemUI()
            FontRestart.DEVICE -> service.reboot(false, "system_font_change")
        }
    }

    private fun postScriptName(file: File): String =
        FileInputStream(file).use { stream ->
            require(stream.channel.size() in 1..MAX_FONT_BYTES) { "Invalid font file size" }
            val buffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, stream.channel.size())
            requireNotNull(FontFileUtil.getPostScriptName(buffer, 0)?.takeIf { it.isNotBlank() }) {
                "This file is not a supported font"
            }
        }

    private fun putCustomName(name: String) {
        check(
            Settings.Secure.putStringForUser(resolver, CUSTOM_NAME, name, UserHandle.USER_CURRENT)
        )
    }

    private fun writeOverlays(overlays: JSONObject) {
        check(
            Settings.Secure.putStringForUser(
                resolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                overlays.toString(),
                UserHandle.USER_CURRENT,
            )
        )
    }

    companion object {
        const val MAX_FONT_BYTES = 50L * 1024 * 1024
        const val CUSTOM_NAME = "custom_font_name"
        const val CATEGORY = "android.theme.customization.font"
        const val OVERLAY_PROPERTY = FontController.PROP_OVERLAY_FONTS
        const val EXTERNAL_FAMILY = "ext_font"
        const val EXTERNAL_OVERLAY = "com.android.theme.font.extfont"
    }
}
