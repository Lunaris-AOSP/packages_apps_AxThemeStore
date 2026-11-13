/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.axion.axthemestore.fonts

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontFileUtil
import android.graphics.fonts.FontManager
import android.graphics.fonts.SystemFonts
import android.graphics.text.TextRunShaper
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.UserHandle
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class EmojiOption(
    val key: String,
    val label: String,
    val typeface: Typeface? = null,
    val preview: File? = null,
    val sizeBytes: Long = 0L,
    val entry: EmojiCatalog.Entry? = null,
    val downloaded: Boolean = false,
) {
    val isDefault: Boolean
        get() = key == EmojiRepository.DEFAULT_KEY
}

object EmojiCatalog {

    private const val TAG = "EmojiCatalog"
    private const val BASE_URL =
        "https://raw.githubusercontent.com/Lunaris-AOSP/AxThemeStore_themes_repository/refs/heads/16.2/emoji/"
    private const val INDEX = "emoji.json"
    private const val SUPPORTED_VERSION = 1
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_INDEX_BYTES = 256L * 1024L
    private const val MAX_PREVIEW_BYTES = 2L * 1024L * 1024L
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    class Entry(
        val id: String,
        val name: String,
        val file: String,
        val size: Long,
        val sha256: String,
        val preview: String?,
    )

    class DownloadException(message: String) : IOException(message)

    class ChecksumException(message: String) : IOException(message)

    fun fetch(): String = String(fetchBytes(INDEX, MAX_INDEX_BYTES), Charsets.UTF_8)

    fun parseCatalog(data: String): List<Entry> {
        val json = JSONObject(data)
        require(json.getInt("version") == SUPPORTED_VERSION) { "Unsupported emoji catalog" }
        val array = json.getJSONArray("emoji")
        val seen = HashSet<String>()
        val files = HashSet<String>()
        return (0 until array.length()).mapNotNull { i ->
            runCatching { parse(array.getJSONObject(i)) }
                .onFailure { Log.w(TAG, "Skipping catalog entry $i", it) }
                .getOrNull()
                ?.takeIf { seen.add(it.id) && files.add(it.file) }
        }
    }

    fun encode(entries: List<Entry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("file", entry.file)
                    .put("size", entry.size)
                    .put("sha256", entry.sha256)
                    .apply { entry.preview?.let { put("preview", it) } }
            )
        }
        return JSONObject().put("version", SUPPORTED_VERSION).put("emoji", array).toString()
    }

    private fun parse(o: JSONObject): Entry {
        val id = o.getString("id")
        val file = o.getString("file")
        val size = o.getLong("size")
        val sha256 = o.getString("sha256")
        val preview = o.optString("preview").takeIf { it.isNotEmpty() }
        require(id.length in 1..128 && SAFE_NAME.matches(id)) { "Bad id $id" }
        require(id != EmojiRepository.DEFAULT_KEY && id != EmojiRepository.CUSTOM_KEY)
        require(
            file.length in 1..200 &&
                SAFE_NAME.matches(file) &&
                !file.startsWith(".") &&
                file.endsWith(".ttf", true)
        ) {
            "Bad file $file"
        }
        require(
            preview == null ||
                (preview.length in 1..200 && SAFE_NAME.matches(preview) && !preview.startsWith("."))
        )
        require(size in 1..EmojiRepository.MAX_EMOJI_BYTES) { "Bad size $size" }
        require(SHA256.matches(sha256)) { "Bad sha256" }
        return Entry(
            id,
            o.optString("name").ifEmpty { id },
            file,
            size,
            sha256.lowercase(),
            preview,
        )
    }

    fun preview(entry: Entry, dir: File): File? {
        val name = entry.preview ?: return null
        val target = File(dir, name)
        if (target.isFile && target.length() in 1..MAX_PREVIEW_BYTES) return target
        return runCatching {
            dir.mkdirs()
            val temp = File.createTempFile("preview-", ".part", dir)
            try {
                temp.writeBytes(fetchBytes(name, MAX_PREVIEW_BYTES))
                check(temp.renameTo(target))
                target
            } finally {
                temp.delete()
            }
        }
            .onFailure { Log.w(TAG, "Preview $name unavailable", it) }
            .getOrNull()
    }

    fun download(entry: Entry, target: File, onProgress: (Float) -> Unit) {
        val temp = File(target.parentFile, "${target.name}.part")
        var done = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            open(entry.file).use { conn ->
                conn.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReported = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > entry.size) {
                                throw ChecksumException("${entry.file} is larger than listed")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            val percent = (total * 100 / entry.size).toInt()
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent / 100f)
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (total != entry.size || hash != entry.sha256) {
                throw ChecksumException("${entry.file} doesn't match the catalog")
            }
            if (!temp.renameTo(target)) throw DownloadException("Can't move ${entry.file}")
            done = true
        } catch (e: ChecksumException) {
            throw e
        } catch (e: DownloadException) {
            throw e
        } catch (e: Exception) {
            throw DownloadException("Download of ${entry.file} failed: $e")
        } finally {
            if (!done) temp.delete()
        }
    }

    private fun fetchBytes(name: String, limit: Long): ByteArray =
        open(name).use { conn ->
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) throw DownloadException("$name exceeds $limit bytes")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }

    private class Connection(val connection: HttpURLConnection) : Closeable {
        val inputStream
            get() = connection.inputStream

        override fun close() = connection.disconnect()
    }

    private fun open(name: String): Connection {
        require(SAFE_NAME.matches(name) && !name.startsWith("."))
        val conn = URL(BASE_URL + name).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw DownloadException("HTTP $code for $name")
            }
            return Connection(conn)
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }
}

/** A picked emoji font, copied into the cache and checked. */
class EmojiFilePreview(
    val file: File,
    val postScriptName: String,
    val displayName: String?,
    val sizeBytes: Long,
    val typeface: Typeface,
)

/**
 * Talks to the custom emoji API of FontManager. The font sits in front of the system emoji
 * fallbacks, so it takes effect in every process started afterwards and needs no reboot.
 */
internal class EmojiRepository(context: Context) {

    private val context: Context = context.applicationContext
    private val fontManager: FontManager? = this.context.getSystemService(FontManager::class.java)

    fun customEmojiName(): String? =
        checkNotNull(fontManager).customEmojiName?.takeIf { it.isNotEmpty() }

    private val downloads = this.context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
    private val catalogFile = AtomicFile(File(this.context.filesDir, "emoji_catalog.json"))

    fun activeEmojiFile(): File? {
        if (SystemFonts.getEmojiStyle() != SystemFonts.EMOJI_STYLE_CUSTOM) return null
        return checkNotNull(fontManager)
            .fontConfig
            .fontFamilies
            .flatMap { it.fontList }
            .firstOrNull { it.file.parent == INSTALLED_EMOJI_DIR }
            ?.file
    }

    fun downloadedId(file: File): String? =
        downloads.getString(KEY_INSTALLED_ID, null)?.takeIf {
            downloads.getString(KEY_INSTALLED_PATH, null) == file.path
        }

    fun typeface(file: File): Typeface? {
        val stamp = "${file.length()}:${file.lastModified()}"
        typefaces[file.path]
            ?.takeIf { it.first == stamp }
            ?.let {
                return it.second
            }
        return fileTypeface(file)?.also { typefaces.put(file.path, stamp to it) }
    }

    fun stockTypeface(): Typeface? = File(STOCK_EMOJI).takeIf { it.isFile }?.let { typeface(it) }

    private val downloadDir = File(this.context.filesDir, DOWNLOAD_DIR)

    fun cachedOptions(): List<EmojiOption> {
        if (cachedCatalog == null) {
            cachedCatalog =
                runCatching {
                        catalogFile.openRead().bufferedReader().use { input ->
                            EmojiCatalog.parseCatalog(input.readText()).map { entry ->
                                entry to
                                    entry.preview
                                        ?.let { File(context.cacheDir, "$PREVIEW_DIR/$it") }
                                        ?.takeIf { it.isFile }
                            }
                        }
                    }
                    .getOrDefault(emptyList())
        }
        return options(checkNotNull(cachedCatalog))
    }

    fun catalog(): List<EmojiOption> {
        val data = EmojiCatalog.fetch()
        val remote = EmojiCatalog.parseCatalog(data)
        cachedOptions()
        val retained =
            cachedCatalog
                .orEmpty()
                .map { it.first }
                .filter { entry ->
                    remote.none { it.id == entry.id || it.file == entry.file } &&
                        localFile(entry) != null
                }
        val parsed = remote + retained
        var output: FileOutputStream? = null
        try {
            output = catalogFile.startWrite()
            output.write(EmojiCatalog.encode(parsed).toByteArray(Charsets.UTF_8))
            output.fd.sync()
            catalogFile.finishWrite(output)
        } catch (e: IOException) {
            catalogFile.failWrite(output)
            Log.w(TAG, "Failed to cache the emoji catalog", e)
        }
        val entries = run {
            val previewDir = File(context.cacheDir, PREVIEW_DIR)
            parsed
                .map { it to EmojiCatalog.preview(it, previewDir) }
                .also {
                    cachedCatalog = it
                }
        }
        return options(entries)
    }

    private fun options(entries: List<Pair<EmojiCatalog.Entry, File?>>): List<EmojiOption> {
        return entries
            .map { (entry, preview) ->
                val local = localFile(entry)
                EmojiOption(
                    key = entry.id,
                    label = entry.name,
                    typeface = local?.let { typeface(it) },
                    preview = preview,
                    sizeBytes = entry.size,
                    entry = entry,
                    downloaded = local != null,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun download(entry: EmojiCatalog.Entry, onProgress: (Float) -> Unit): Int {
        if (localFile(entry) != null) return FontManager.RESULT_SUCCESS
        if (!downloadDir.isDirectory && !downloadDir.mkdirs()) return RESULT_DOWNLOAD_FAILED
        val file = File(downloadDir, entry.file)
        var staged: File? = null
        try {
            staged = File.createTempFile("emoji-download-", ".ttf", downloadDir)
            EmojiCatalog.download(entry, staged, onProgress)
            inspectFile(staged, entry.name) ?: return FontManager.RESULT_ERROR_INVALID_FONT_FILE
            if (!staged.renameTo(file)) throw IOException("Cannot save the emoji font")
            typefaces.remove(file.path)
            return FontManager.RESULT_SUCCESS
        } catch (e: EmojiCatalog.ChecksumException) {
            Log.e(TAG, "Emoji download rejected", e)
            return RESULT_CHECKSUM_MISMATCH
        } catch (e: Exception) {
            Log.e(TAG, "Emoji download failed", e)
            return RESULT_DOWNLOAD_FAILED
        } finally {
            staged?.delete()
        }
    }

    fun installDownloaded(entry: EmojiCatalog.Entry): Int {
        val file = localFile(entry) ?: return FontManager.RESULT_ERROR_FONT_NOT_FOUND
        val code = installFile(file)
        if (code == FontManager.RESULT_SUCCESS) {
            runCatching {
                check(
                    downloads
                        .edit()
                        .putString(KEY_INSTALLED_ID, entry.id)
                        .putString(KEY_INSTALLED_PATH, activeEmojiFile()?.path)
                        .commit()
                )
            }
                .onFailure { Log.w(TAG, "Failed to record the downloaded emoji selection", it) }
        }
        return code
    }

    fun deleteDownload(entry: EmojiCatalog.Entry) {
        File(downloadDir, entry.file).let {
            typefaces.remove(it.path)
            check(!it.exists() || it.delete()) { "Could not delete the emoji font" }
        }
        hashFile(entry).let { check(!it.exists() || it.delete()) }
    }

    private fun localFile(entry: EmojiCatalog.Entry): File? {
        val file = File(downloadDir, entry.file)
        return file.takeIf {
            it.isFile && it.length() == entry.size && sha256(it) == entry.sha256
        }
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
        .getOrNull()

    private fun hashFile(entry: EmojiCatalog.Entry) = File(downloadDir, entry.file + HASH_SUFFIX)

    /** Null when the file can't be read, is too large, or has no emoji in it. Blocking. */
    fun inspect(uri: Uri): EmojiFilePreview? {
        val file = copyToCache(uri) ?: return null
        return inspectFile(file, displayName(uri))
    }

    private fun inspectFile(file: File, displayName: String?): EmojiFilePreview? {
        val postScriptName = runCatching {
            FileInputStream(file).use { fis ->
                val buffer = fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
                FontFileUtil.getPostScriptName(buffer, 0)
            }
        }
            .getOrNull()
        val typeface = fileTypeface(file)
        if (postScriptName.isNullOrEmpty() || typeface == null || !hasEmoji(typeface, file)) {
            file.delete()
            return null
        }
        return EmojiFilePreview(file, postScriptName, displayName, file.length(), typeface)
    }

    fun discardPreview(preview: EmojiFilePreview?) {
        preview?.file?.delete()
    }

    /** Returns a FontManager.RESULT_* code or [RESULT_SERVICE_FAILURE]. Blocking. */
    fun install(preview: EmojiFilePreview): Int =
        installFile(preview.file).also {
            if (it == FontManager.RESULT_SUCCESS) {
                preview.file.delete()
                forgetDownloadedSelection()
            }
        }

    private fun forgetDownloadedSelection() {
        runCatching {
            check(downloads.edit().remove(KEY_INSTALLED_ID).remove(KEY_INSTALLED_PATH).commit())
        }
            .onFailure { Log.w(TAG, "Failed to clear the downloaded emoji selection", it) }
    }

    private fun installFile(file: File): Int {
        val manager = fontManager ?: return RESULT_SERVICE_FAILURE
        return runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    manager.installCustomEmoji(it)
                }
            }
            .onFailure { Log.e(TAG, "installCustomEmoji failed", it) }
            .getOrDefault(RESULT_SERVICE_FAILURE)
    }

    fun clear(): Int =
        runCatching { fontManager?.clearCustomEmoji() }
            .onFailure { Log.e(TAG, "clearCustomEmoji failed", it) }
            .getOrNull()
            ?.also {
                if (it == FontManager.RESULT_SUCCESS) {
                    forgetDownloadedSelection()
                }
            } ?: RESULT_SERVICE_FAILURE

    /** Number of app files written, or a negative FontManager.RESULT_* code. Blocking. */
    fun applyToApps(): Int =
        runCatching { fontManager?.applyCustomEmojiToApps() }
            .onFailure { Log.e(TAG, "applyCustomEmojiToApps failed", it) }
            .getOrNull() ?: RESULT_SERVICE_FAILURE

    /**
     * Apps like Gboard cache rendered emoji until their process dies. killUid rather than
     * forceStopPackage so apps aren't left stopped and keep their alarms and pushes. Blocking.
     */
    fun killRunningApps() {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val uids = runCatching {
            am.runningAppProcesses
        }
            .onFailure { Log.e(TAG, "getRunningAppProcesses failed", it) }
            .getOrNull()
            .orEmpty()
            .groupBy { it.uid }
            .filter { (uid, _) ->
                uid != Process.myUid() &&
                    UserHandle.getAppId(uid) in
                        Process.FIRST_APPLICATION_UID..Process.LAST_APPLICATION_UID
            }
            .filter { (uid, processes) ->
                val packages =
                    (processes.flatMap { it.pkgList.orEmpty().toList() } +
                            context.packageManager.getPackagesForUid(uid).orEmpty())
                        .distinct()
                packages.isNotEmpty() &&
                    GMS_PACKAGE !in packages &&
                    packages.none { isPersistent(it, UserHandle.getUserId(uid)) }
            }
            .keys
        val service = ActivityManager.getService()
        uids.forEach { uid ->
            runCatching {
                service.killUid(UserHandle.getAppId(uid), UserHandle.getUserId(uid), KILL_REASON)
            }
                .onFailure { Log.w(TAG, "killUid $uid failed", it) }
        }
    }

    private fun isPersistent(pkg: String, userId: Int): Boolean = runCatching {
        (context.packageManager.getApplicationInfoAsUser(pkg, 0, userId).flags and
            ApplicationInfo.FLAG_PERSISTENT) != 0
    }
        .getOrDefault(true)

    private fun fileTypeface(file: File): Typeface? = runCatching {
        val family = FontFamily.Builder(Font.Builder(file).build()).build()
        Typeface.CustomFallbackBuilder(family).build()
    }
        .onFailure { Log.w(TAG, "Can't load $file", it) }
        .getOrNull()

    /** Paint.hasGlyph would also see the system fallback; the shaper says which font drew it. */
    private fun hasEmoji(typeface: Typeface, file: File): Boolean = runCatching {
        val paint = Paint().apply { this.typeface = typeface }
        val glyphs =
            TextRunShaper.shapeTextRun(
                PROBE_EMOJI,
                0,
                PROBE_EMOJI.length,
                0,
                PROBE_EMOJI.length,
                0f,
                0f,
                false,
                paint,
            )
        glyphs.glyphCount() > 0 &&
            glyphs.getGlyphId(0) != 0 &&
            glyphs.getFont(0).file?.absolutePath == file.absolutePath
    }
        .getOrDefault(false)

    private fun copyToCache(uri: Uri): File? {
        val target = File.createTempFile("emoji-import-", ".ttf", context.cacheDir)
        val temp = File(context.cacheDir, "${target.name}.part")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_EMOJI_BYTES) { "Emoji font exceeds size limit" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IOException("Cannot open the emoji font")
            check(temp.length() > 0L && temp.renameTo(target))
            target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy $uri", e)
            temp.delete()
            target.delete()
            null
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }
        .getOrNull()

    companion object {
        private const val TAG = "EmojiRepository"
        private const val KILL_REASON = "system emoji changed"
        private const val PROBE_EMOJI = "😀"

        private const val GMS_PACKAGE = "com.google.android.gms"

        /** Where FontManagerService keeps the installed font (UpdatableFontDir). */
        private const val INSTALLED_EMOJI_DIR = "/data/fonts/files/emoji"

        @Volatile private var cachedCatalog: List<Pair<EmojiCatalog.Entry, File?>>? = null

        private val typefaces = LruCache<String, Pair<String, Typeface>>(8)

        private const val PREVIEW_DIR = "emoji_previews"
        private const val DOWNLOAD_DIR = "emoji_downloads"
        private const val HASH_SUFFIX = ".sha256"
        private const val DOWNLOAD_PREFS = "emoji_downloads"
        private const val KEY_INSTALLED_ID = "installed_id"
        private const val KEY_INSTALLED_PATH = "installed_path"
        private val STOCK_EMOJI = SystemFonts.SYSTEM_FONT_DIR + "/NotoColorEmoji.ttf"

        const val DEFAULT_KEY = "__default_emoji__"
        const val CUSTOM_KEY = "__custom_emoji__"

        const val RESULT_SERVICE_FAILURE = -20000
        const val RESULT_DOWNLOAD_FAILED = -20001
        const val RESULT_CHECKSUM_MISMATCH = -20002

        /** Same limit as UpdatableFontDir.MAX_CUSTOM_EMOJI_FILE_SIZE. */
        const val MAX_EMOJI_BYTES = 128L * 1024L * 1024L
    }
}
