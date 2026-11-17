package com.kmworks.kmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.settings.DivinaSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reads/writes DIVINA reader settings: panel changes take effect immediately and persist
 * (mirrors the original @AppStorage behavior); also exposes the default reading direction
 * (divinaDefaultDirection persisted under Settings - Reader Defaults).
 */
@HiltViewModel
class DivinaSettingsViewModel @Inject constructor(
    private val store: DivinaSettingsStore,
    private val appSettings: AppSettingsStore,
) : ViewModel() {

    private val _settings = MutableStateFlow(DivinaSettings())
    val settings: StateFlow<DivinaSettings> = _settings.asStateFlow()

    /** Default reading direction saved in settings (divinaDefaultDirection persisted under Settings - Reader Defaults). */
    val defaultDirection: StateFlow<ReadingDirection> = appSettings.divinaDefaultDirection
        .map { raw -> ReadingDirection.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ReadingDirection.Ltr }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReadingDirection.Ltr)

    /** "Wi-Fi only preloading" toggle (read-only consumption of AppSettingsStore, mirrors the original wifiOnlyPreload). */
    val wifiOnlyPreload: StateFlow<Boolean> = appSettings.wifiOnlyPreload
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            store.settings.collect { _settings.value = it }
        }
        viewModelScope.launch {
            store.epubSettings.collect { _epubSettings.value = it }
        }
        viewModelScope.launch {
            store.pdfSettings.collect { _pdfSettings.value = it }
        }
    }

    /** Apply locally first (UI takes effect immediately), then persist. */
    fun update(transform: (DivinaSettings) -> DivinaSettings) {
        _settings.value = transform(_settings.value)
        viewModelScope.launch { store.update(transform) }
    }

    // ---- EPUB / PDF reader preferences (persisted globally, mirrors the original @AppStorage semantics) ----

    private val _epubSettings = MutableStateFlow(EpubReaderSettings())
    val epubSettings: StateFlow<EpubReaderSettings> = _epubSettings.asStateFlow()

    private val _pdfSettings = MutableStateFlow(PdfReaderSettings())
    val pdfSettings: StateFlow<PdfReaderSettings> = _pdfSettings.asStateFlow()

    fun updateEpub(transform: (EpubReaderSettings) -> EpubReaderSettings) {
        _epubSettings.value = transform(_epubSettings.value)
        viewModelScope.launch { store.updateEpubSettings(transform) }
    }

    fun updatePdf(transform: (PdfReaderSettings) -> PdfReaderSettings) {
        _pdfSettings.value = transform(_pdfSettings.value)
        viewModelScope.launch { store.updatePdfSettings(transform) }
    }

    /** Reading direction follows the default from "Settings - Reader Defaults" (same AppStorage semantics as the original). */
    fun setDefaultDirection(direction: ReadingDirection) {
        viewModelScope.launch { appSettings.setDivinaDefaultDirection(direction.name.lowercase()) }
    }
}
