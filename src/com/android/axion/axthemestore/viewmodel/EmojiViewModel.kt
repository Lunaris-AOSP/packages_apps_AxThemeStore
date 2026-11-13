/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.axion.axthemestore.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.FontManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.android.axion.axthemestore.R
import com.android.axion.axthemestore.fonts.EmojiFilePreview
import com.android.axion.axthemestore.fonts.EmojiOption
import com.android.axion.axthemestore.fonts.EmojiRepository
import com.android.axion.axthemestore.fonts.FontRestart
import com.android.axion.axthemestore.fonts.SystemFontController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EmojiUiState(
    val options: List<EmojiOption> = emptyList(),
    val selectedKey: String = EmojiRepository.DEFAULT_KEY,
    val currentLabel: String = "",
    val currentTypeface: Typeface? = null,
    val stockTypeface: Typeface? = null,
    val customInstalled: Boolean = false,
    val loading: Boolean = true,
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val operation: String? = null,
    val progress: Float? = null,
    val preview: EmojiFilePreview? = null,
    val error: String? = null,
    val message: String? = null,
    val pendingRestart: Boolean = false,
    val restartPrompt: Boolean = false,
)

class EmojiViewModel(application: Application) : AndroidViewModel(application) {
    private val session = EmojiSession.get(application)
    val state = session.state

    fun refresh() = session.refresh()

    fun loadCatalog(force: Boolean = false) = session.loadCatalog(force)

    fun inspect(uri: Uri) = session.inspect(uri)

    fun dismissPreview() = session.dismissPreview()

    fun installPreview() = session.installPreview()

    fun apply(option: EmojiOption) = session.apply(option)

    fun download(option: EmojiOption) = session.download(option)

    fun deleteDownload(option: EmojiOption) = session.deleteDownload(option)

    fun restoreStock() = session.restoreStock()

    fun updateApps() = session.updateApps()

    fun showRestart() = session.showRestart()

    fun dismissRestart() = session.dismissRestart()

    fun restart() = session.restart()

    fun clearError() = session.clearError()

    fun clearMessage() = session.clearMessage()
}

private class EmojiSession(private val context: Context) {
    private val repository = EmojiRepository(context)
    private val system = SystemFontController(context)
    private val preferences = context.getSharedPreferences("emoji_restart", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(EmojiUiState(pendingRestart = pendingRestart()))
    val state = mutableState.asStateFlow()
    private var operationJob: Job? = null
    private var refreshJob: Job? = null
    private var catalogJob: Job? = null
    private var catalogRequested = false

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true || operationJob?.isActive == true) return
        refreshJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { refreshSelection() }
            } catch (e: Exception) {
                fail(e)
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    private fun refreshSelection() {
        val options = repository.cachedOptions()
        val file = repository.activeEmojiFile()
        val name = if (file != null) repository.customEmojiName() else null
        val downloadedId = file?.let(repository::downloadedId)
        val key =
            when {
                name == null -> EmojiRepository.DEFAULT_KEY
                downloadedId != null -> downloadedId
                else -> EmojiRepository.CUSTOM_KEY
            }
        val label = options.find { it.key == key }?.label ?: name ?: text(R.string.emoji_stock)
        val stockTypeface = repository.stockTypeface()
        val typeface = file?.let(repository::typeface) ?: stockTypeface
        mutableState.update {
            it.copy(
                options = options,
                selectedKey = key,
                currentLabel = label,
                currentTypeface = typeface,
                stockTypeface = stockTypeface,
                customInstalled = name != null,
                pendingRestart = pendingRestart(),
                loading = false,
            )
        }
    }

    fun loadCatalog(force: Boolean) {
        if (
            operationJob?.isActive == true ||
                catalogJob?.isActive == true ||
                (catalogRequested && !force)
        )
            return
        catalogRequested = true
        catalogJob = scope.launch {
            mutableState.update { it.copy(catalogLoading = true, catalogError = null) }
            try {
                refreshJob?.join()
                val options = withContext(Dispatchers.IO) { repository.catalog() }
                mutableState.update { it.copy(options = options) }
            } catch (e: Exception) {
                Log.w(TAG, "Emoji catalog unavailable", e)
                mutableState.update { it.copy(catalogError = text(R.string.emoji_catalog_failed)) }
            } finally {
                mutableState.update { it.copy(catalogLoading = false) }
            }
        }
    }

    fun inspect(uri: Uri) =
        runOperation(text(R.string.emoji_reading)) {
            val preview =
                repository.inspect(uri) ?: throw EmojiFailure(text(R.string.emoji_invalid_file))
            repository.discardPreview(mutableState.value.preview)
            mutableState.update { it.copy(preview = preview) }
        }

    fun dismissPreview() {
        if (mutableState.value.operation != null) return
        repository.discardPreview(mutableState.value.preview)
        mutableState.update { it.copy(preview = null) }
    }

    fun installPreview() {
        val preview = mutableState.value.preview ?: return
        runOperation(text(R.string.emoji_applying, preview.displayName ?: preview.postScriptName)) {
            change { repository.install(preview) }
            mutableState.update { it.copy(preview = null) }
        }
    }

    fun apply(option: EmojiOption) {
        val entry = option.entry ?: return
        runOperation(text(R.string.emoji_applying, option.label)) {
            checkResult(repository.download(entry, ::progress))
            change { repository.installDownloaded(entry) }
        }
    }

    fun download(option: EmojiOption) {
        val entry = option.entry ?: return
        runOperation(text(R.string.emoji_downloading, option.label)) {
            checkResult(repository.download(entry, ::progress))
            mutableState.update {
                it.copy(message = text(R.string.emoji_downloaded_message, option.label))
            }
        }
    }

    fun deleteDownload(option: EmojiOption) {
        val entry = option.entry ?: return
        runOperation(text(R.string.emoji_deleting)) {
            repository.deleteDownload(entry)
            mutableState.update { it.copy(message = text(R.string.emoji_deleted)) }
        }
    }

    fun restoreStock() =
        runOperation(text(R.string.emoji_restoring)) {
            change { repository.clear() }
        }

    fun updateApps() =
        runOperation(text(R.string.emoji_updating_apps)) {
            val count = repository.applyToApps()
            if (count < 0) checkResult(count)
            mutableState.update {
                it.copy(
                    message =
                        context.resources.getQuantityString(
                            R.plurals.emoji_apps_updated,
                            count,
                            count,
                        )
                )
            }
        }

    private fun change(action: () -> Int) {
        val previous = pendingRestart()
        savePendingRestart(true)
        try {
            checkResult(action())
        } catch (e: Exception) {
            savePendingRestart(previous)
            throw e
        }
        mutableState.update {
            it.copy(
                pendingRestart = true,
                restartPrompt = true,
                message = text(R.string.font_emoji_applied),
            )
        }
    }

    fun showRestart() {
        if (pendingRestart()) mutableState.update { it.copy(restartPrompt = true) }
    }

    fun dismissRestart() = mutableState.update { it.copy(restartPrompt = false) }

    fun restart() =
        runOperation(text(R.string.font_restarting)) {
            repository.killRunningApps()
            system.restart(FontRestart.SYSTEM_UI)
            savePendingRestart(false)
            mutableState.update { it.copy(pendingRestart = false, restartPrompt = false) }
        }

    fun clearError() = mutableState.update { it.copy(error = null) }

    fun clearMessage() = mutableState.update { it.copy(message = null) }

    private fun progress(value: Float) = mutableState.update { it.copy(progress = value) }

    private fun runOperation(label: String, action: () -> Unit) {
        if (operationJob?.isActive == true) return
        mutableState.update { it.copy(operation = label, progress = null, error = null) }
        operationJob = scope.launch {
            try {
                refreshJob?.join()
                catalogJob?.join()
                withContext(Dispatchers.IO) { action() }
            } catch (e: Exception) {
                fail(e)
            } finally {
                try {
                    withContext(Dispatchers.IO) { refreshSelection() }
                } catch (e: Exception) {
                    fail(e)
                }
                mutableState.update { it.copy(operation = null, progress = null) }
            }
        }
    }

    private fun pendingRestart(): Boolean =
        preferences.getBoolean("pending", false) && preferences.getInt("boot", -2) == bootCount()

    private fun savePendingRestart(pending: Boolean) {
        check(
            preferences.edit().putBoolean("pending", pending).putInt("boot", bootCount()).commit()
        ) {
            text(R.string.emoji_state_failed)
        }
    }

    private fun bootCount() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun checkResult(result: Int) {
        if (result == FontManager.RESULT_SUCCESS) return
        val message =
            when (result) {
                FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                FontManager.RESULT_ERROR_INVALID_FONT_NAME -> R.string.emoji_invalid_file
                FontManager.RESULT_ERROR_FONT_NOT_FOUND -> R.string.emoji_not_installed
                FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED -> R.string.emoji_updater_disabled
                FontManager.RESULT_ERROR_VERIFICATION_FAILURE -> R.string.emoji_verification_failed
                EmojiRepository.RESULT_DOWNLOAD_FAILED -> R.string.emoji_download_failed
                EmojiRepository.RESULT_CHECKSUM_MISMATCH -> R.string.emoji_checksum_failed
                else -> R.string.emoji_operation_failed
            }
        throw EmojiFailure(text(message))
    }

    private fun fail(error: Exception) {
        Log.e(TAG, "Emoji operation failed", error)
        mutableState.update {
            it.copy(
                error =
                    if (error is EmojiFailure) error.message
                    else text(R.string.emoji_operation_failed)
            )
        }
    }

    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)

    private class EmojiFailure(message: String) : Exception(message)

    companion object {
        private const val TAG = "EmojiViewModel"
        @Volatile private var instance: EmojiSession? = null

        fun get(context: Context): EmojiSession =
            instance
                ?: synchronized(this) {
                    instance ?: EmojiSession(context.applicationContext).also { instance = it }
                }
    }
}
