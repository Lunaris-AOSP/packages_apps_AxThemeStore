/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.axion.axthemestore.fonts

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

class FontRepository(private val context: Context) {
    val system = SystemFontController(context)
    private val library = File(context.filesDir, "fonts")
    private val legacyLibrary = File(Environment.getExternalStorageDirectory(), "LunarisFont")
    private val catalogCache = AtomicFile(File(context.filesDir, "font_catalog.json"))
    private val previews = File(context.cacheDir, "font_previews")
    private val preferences =
        context.getSharedPreferences("font_customization", Context.MODE_PRIVATE)

    fun pendingRestart(): FontRestart? {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (preferences.getInt("restart_boot", -2) != boot) return null
        return preferences.getString("restart_kind", null)?.let { value ->
            FontRestart.entries.find { it.name == value }
        }
    }

    fun savePendingRestart(kind: FontRestart?) {
        preferences
            .edit()
            .putString("restart_kind", kind?.name)
            .putInt(
                "restart_boot",
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
            )
            .commit()
    }

    fun downloadedFiles(): Map<String, File> =
        listOf(legacyLibrary, library)
            .flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.canRead() && isFontFileName(it.name) && it.length() > 0 }
            .associateBy { it.name }

    fun cachedCatalog(): List<DownloadableFont> =
        runCatching { catalogCache.openRead().bufferedReader().use { parseCatalog(it.readText()) } }
            .getOrDefault(emptyList())

    fun downloadedCatalog(): List<DownloadableFont> =
        downloadedFiles().keys.map(::catalogFont).sortedBy { it.name.lowercase() }

    fun deleteDownload(fileName: String) {
        require(isFontFileName(fileName))
        for (directory in listOf(legacyLibrary, library)) {
            val file = File(directory, fileName)
            check(!file.exists() || (file.isFile && file.delete())) {
                "Could not delete the downloaded font"
            }
        }
    }

    suspend fun fetchCatalog(): List<DownloadableFont> {
        val connection = openConnection(CATALOG_URL)
        try {
            requireSuccess(connection)
            val data =
                connection.inputStream.use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    copyBounded(stream, buffer, 4L * 1024 * 1024)
                    buffer.toString("UTF-8")
                }
            val fonts = parseCatalog(data)
            var output: java.io.FileOutputStream? = null
            try {
                output = catalogCache.startWrite()
                output.write(data.toByteArray(Charsets.UTF_8))
                catalogCache.finishWrite(output)
            } catch (e: Exception) {
                catalogCache.failWrite(output)
            }
            return fonts
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(font: DownloadableFont, onProgress: (Float?) -> Unit): File {
        require(isFontFileName(font.fileName))
        downloadedFiles()[font.fileName]?.let { file ->
            if (runCatching { system.preview(file, font.name) }.isSuccess) return file
        }
        val directory =
            if ((legacyLibrary.isDirectory || legacyLibrary.mkdirs()) && legacyLibrary.canWrite()) {
                legacyLibrary
            } else {
                check(library.isDirectory || library.mkdirs()) { "Cannot create the font library" }
                library
            }
        val target = File(directory, font.fileName)
        val temporary = File.createTempFile("download-", ".part", directory)
        val connection = openConnection(catalogFont(font.fileName).downloadUrl)
        try {
            requireSuccess(connection)
            val length = connection.contentLengthLong
            require(length <= SystemFontController.MAX_FONT_BYTES) { "Font file is too large" }
            onProgress(if (length > 0) 0f else null)
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    var lastProgress = -1
                    val copied =
                        copyBounded(input, output, SystemFontController.MAX_FONT_BYTES) { total ->
                            if (length > 0) {
                                val percent = (total * 100 / length).toInt().coerceIn(0, 100)
                                if (percent != lastProgress) {
                                    lastProgress = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                    check(length <= 0 || copied == length) { "The font download was interrupted" }
                }
            }
            currentCoroutineContext().ensureActive()
            system.preview(temporary, font.name)
            check(temporary.renameTo(target)) { "Cannot save the downloaded font" }
            return target
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    suspend fun importFont(uri: Uri): FontPreview {
        val name =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: uri.lastPathSegment.orEmpty()
        require(isFontFileName(name)) { "Choose a TTF or OTF font file" }
        check(previews.isDirectory || previews.mkdirs())
        val file = File.createTempFile("import-", ".font", previews)
        try {
            checkNotNull(context.contentResolver.openInputStream(uri)) {
                    "Cannot read this font file"
                }
                .use { input ->
                    file.outputStream().use { output ->
                        copyBounded(input, output, SystemFontController.MAX_FONT_BYTES)
                    }
                }
            return system.preview(file, name.substringBeforeLast('.'))
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun releasePreview(preview: FontPreview?) {
        preview?.file?.takeIf { it.parentFile == previews }?.delete()
    }

    private suspend fun copyBounded(
        input: InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "File is too large" }
            output.write(buffer, 0, count)
            onProgress(total)
        }
        return total
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Lunaris-ThemeStore")
        }

    private fun requireSuccess(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw IOException(
                if (code == 403 || code == 429) "GitHub request limit reached. Try again later."
                else "Server returned HTTP " + code
            )
        }
    }

    private fun parseCatalog(json: String): List<DownloadableFont> {
        val array = JSONArray(json)
        return (0 until array.length())
            .mapNotNull { index ->
                val item = array.getJSONObject(index)
                val name = item.optString("name")
                if (item.optString("type", "file") == "file" && isFontFileName(name))
                    catalogFont(name)
                else null
            }
            .distinctBy { it.fileName }
            .sortedBy { it.name.lowercase() }
    }

    private fun catalogFont(name: String) =
        DownloadableFont(
            fileName = name,
            name = name.substringBeforeLast('.'),
            downloadUrl = RAW_URL + Uri.encode(name),
        )

    private fun isFontFileName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= 255 &&
            '/' !in name &&
            '\\' !in name &&
            name.none { it.isISOControl() } &&
            (name.endsWith(".ttf", true) || name.endsWith(".otf", true))

    companion object {
        private const val CATALOG_URL =
            "https://api.github.com/repos/Lunaris-CLO/font_prebuilt/contents/fonts?ref=16"
        private const val RAW_URL =
            "https://raw.githubusercontent.com/Lunaris-CLO/font_prebuilt/16/fonts/"
    }
}
