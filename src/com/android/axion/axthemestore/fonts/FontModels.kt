/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.axion.axthemestore.fonts

import android.graphics.Typeface
import java.io.File

data class BundledFont(val packageName: String, val name: String, val typeface: Typeface)

data class DownloadableFont(val fileName: String, val name: String, val downloadUrl: String)

data class FontPreview(
    val name: String,
    val typeface: Typeface,
    val file: File? = null,
    val overlayPackage: String? = null,
    val downloadedFileName: String? = null,
)

data class FontSelection(
    val overlayPackage: String = "android",
    val customFontName: String = "",
    val emojiStyle: String = "android",
)

enum class FontRestart {
    SYSTEM_UI,
    DEVICE,
}
