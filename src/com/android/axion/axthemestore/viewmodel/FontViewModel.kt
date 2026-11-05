/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.axion.axthemestore.viewmodel

import android.app.Application
import android.graphics.Typeface
import android.net.Uri
import android.os.DeadObjectException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.axion.axthemestore.R
import com.android.axion.axthemestore.fonts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FontUiState(
    val bundled: List<BundledFont> = emptyList(),
    val catalog: List<DownloadableFont> = emptyList(),
    val downloaded: List<DownloadableFont> = emptyList(),
    val selection: FontSelection = FontSelection(),
    val activeTypeface: Typeface? = null,
    val loading: Boolean = true,
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val operation: String? = null,
    val progress: Float? = null,
    val preview: FontPreview? = null,
    val error: String? = null,
    val message: String? = null,
    val restartPrompt: FontRestart? = null,
    val pendingRestart: FontRestart? = null,
)

class FontViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FontRepository(application)
    private val _state = MutableStateFlow(FontUiState(pendingRestart = repository.pendingRestart()))
    val state = _state.asStateFlow()
    private var catalogJob: Job? = null
    private var operationJob: Job? = null
    private var refreshJob: Job? = null
    private var catalogRequested = false
    private val typefaces = android.util.LruCache<String, Typeface>(24)

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true || operationJob?.isActive == true) return
        refreshJob =
            viewModelScope.launch {
                try {
                    val fonts = withContext(Dispatchers.IO) { repository.system.bundledFonts() }
                    val selection = withContext(Dispatchers.IO) { repository.system.selection() }
                    val downloaded =
                        withContext(Dispatchers.IO) { repository.downloadedCatalog() }
                    val cached =
                        withContext(Dispatchers.IO) {
                            (repository.cachedCatalog() + downloaded)
                                .distinctBy { it.fileName }
                        }
                    _state.update {
                        it.copy(
                            bundled = fonts,
                            selection = selection,
                            downloaded = downloaded,
                            catalog = if (it.catalog.isEmpty()) cached else it.catalog,
                            loading = false,
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            loading = false,
                            error = e.message ?: text(R.string.font_operation_failed),
                        )
                    }
                }
            }
    }

    fun loadCatalog(force: Boolean = false) {
        if (catalogJob?.isActive == true || (catalogRequested && !force)) return
        catalogRequested = true
        catalogJob =
            viewModelScope.launch {
                _state.update { it.copy(catalogLoading = true, catalogError = null) }
                try {
                    val fonts = withContext(Dispatchers.IO) { repository.fetchCatalog() }
                    val saved = withContext(Dispatchers.IO) { repository.downloadedCatalog() }
                    _state.update {
                        it.copy(catalog = (fonts + saved).distinctBy { font -> font.fileName })
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(catalogError = e.message ?: text(R.string.font_catalog_failed))
                    }
                } finally {
                    _state.update { it.copy(catalogLoading = false) }
                }
            }
    }

    fun previewBundled(font: BundledFont) {
        if (_state.value.operation != null) return
        repository.releasePreview(_state.value.preview)
        _state.update {
            it.copy(
                preview =
                    FontPreview(
                        font.name.ifEmpty { text(R.string.font_default) },
                        font.typeface,
                        overlayPackage = font.packageName,
                    )
            )
        }
    }

    fun previewDownload(font: DownloadableFont) =
        runOperation(font.name) {
            val file =
                repository.download(font) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
            val preview =
                repository.system.preview(file, font.name).copy(downloadedFileName = font.fileName)
            val downloaded = repository.downloadedCatalog()
            _state.update { it.copy(preview = preview, downloaded = downloaded) }
        }

    suspend fun downloadedTypeface(font: DownloadableFont): Typeface? =
        withContext(Dispatchers.IO) {
            typefaces.get(font.fileName)
                ?: runCatching {
                        repository.downloadedFiles()[font.fileName]?.let {
                            repository.system.preview(it, font.name).typeface
                        }
                    }
                    .getOrNull()
                    ?.also { typefaces.put(font.fileName, it) }
        }

    fun importFont(uri: Uri) =
        runOperation(text(R.string.font_loading_preview)) {
            val preview = repository.importFont(uri)
            _state.update { it.copy(preview = preview) }
        }

    fun dismissPreview() {
        if (_state.value.operation != null) return
        repository.releasePreview(_state.value.preview)
        _state.update { it.copy(preview = null) }
    }

    fun deletePreview() {
        val fileName = _state.value.preview?.downloadedFileName ?: return
        runOperation(text(R.string.font_deleting)) {
            repository.deleteDownload(fileName)
            typefaces.remove(fileName)
            val downloaded = repository.downloadedCatalog()
            _state.update {
                it.copy(
                    preview = null,
                    downloaded = downloaded,
                    message = text(R.string.font_deleted),
                )
            }
        }
    }

    fun applyPreview() {
        val preview = _state.value.preview ?: return
        runOperation(text(R.string.font_applying)) {
            val overlay = preview.overlayPackage
            val restart =
                if (overlay != null) {
                    val font = _state.value.bundled.first { it.packageName == overlay }
                    val hadCustom = repository.system.selection().customFontName.isNotBlank()
                    repository.system.applyBundled(font)
                    if (hadCustom) FontRestart.DEVICE else FontRestart.SYSTEM_UI
                } else {
                    repository.system.install(checkNotNull(preview.file))
                    FontRestart.DEVICE
                }
            val selection = repository.system.selection()
            repository.releasePreview(preview)
            val required =
                if (_state.value.pendingRestart == FontRestart.DEVICE) FontRestart.DEVICE
                else restart
            repository.savePendingRestart(required)
            _state.update {
                it.copy(
                    preview = null,
                    selection = selection,
                    activeTypeface = preview.typeface,
                    restartPrompt = required,
                    pendingRestart = required,
                    message = text(R.string.font_applied),
                )
            }
        }
    }

    fun reset() =
        runOperation(text(R.string.font_resetting)) {
            repository.system.reset()
            val selection = repository.system.selection()
            repository.savePendingRestart(FontRestart.DEVICE)
            _state.update {
                it.copy(
                    selection = selection,
                    activeTypeface = Typeface.DEFAULT,
                    preview = null,
                    restartPrompt = FontRestart.DEVICE,
                    pendingRestart = FontRestart.DEVICE,
                    message = text(R.string.font_reset_done),
                )
            }
        }

    fun setEmoji(style: String) =
        runOperation(text(R.string.font_applying)) {
            repository.system.setEmoji(style)
            val selection = repository.system.selection()
            repository.savePendingRestart(FontRestart.DEVICE)
            _state.update {
                it.copy(
                    selection = selection,
                    restartPrompt = FontRestart.DEVICE,
                    pendingRestart = FontRestart.DEVICE,
                    message = text(R.string.font_emoji_applied),
                )
            }
        }

    fun showRestart() {
        _state.update { it.copy(restartPrompt = it.pendingRestart ?: FontRestart.DEVICE) }
    }

    fun dismissRestart() {
        _state.update { it.copy(restartPrompt = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun restart() {
        val kind = _state.value.restartPrompt ?: return
        dismissRestart()
        runOperation(text(R.string.font_restarting)) {
            repository.system.restart(kind)
            if (kind == FontRestart.SYSTEM_UI) {
                repository.savePendingRestart(null)
                _state.update { it.copy(pendingRestart = null) }
            }
        }
    }

    private fun runOperation(label: String, action: suspend () -> Unit) {
        if (operationJob?.isActive == true) return
        _state.update { it.copy(operation = label, progress = null, error = null) }
        operationJob =
            viewModelScope.launch {
                refreshJob?.join()
                try {
                    withContext(Dispatchers.IO) { action() }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("FontViewModel", "Font operation failed: $label", e)
                    val serviceFailure =
                        generateSequence<Throwable>(e) { it.cause }
                            .any { it is DeadObjectException }
                    val message =
                        if (serviceFailure) text(R.string.font_service_failed)
                        else e.message ?: text(R.string.font_operation_failed)
                    _state.update { it.copy(error = message) }
                } finally {
                    _state.update { it.copy(operation = null, progress = null) }
                }
            }
    }

    private fun text(id: Int) = getApplication<Application>().getString(id)

    override fun onCleared() {
        repository.releasePreview(_state.value.preview)
        super.onCleared()
    }
}
