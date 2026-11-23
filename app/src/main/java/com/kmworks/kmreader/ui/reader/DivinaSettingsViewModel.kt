package com.kmworks.kmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.reader.EpubOverlayPreferences
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.settings.DivinaSettingsStore
import com.kmworks.kmreader.core.settings.Remembered
import com.kmworks.kmreader.core.settings.RememberedDirection
import com.kmworks.kmreader.core.settings.resolveRemembered
import com.kmworks.kmreader.core.settings.resolveRememberedDirection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    // EPUB/PDF reader preferences (persisted globally, mirrors the original @AppStorage semantics).
    // These MUST be declared before the init block below: those collectors run on Dispatchers.Main.immediate
    // and DataStore re-emits its already loaded value synchronously during launch, so on the second visit
    // (settings cache warm) the emit used to run while this field was still null → NPE on the main thread.
    private val _epubSettings = MutableStateFlow(EpubReaderSettings())
    val epubSettings: StateFlow<EpubReaderSettings> = _epubSettings.asStateFlow()

    private val _pdfSettings = MutableStateFlow(PdfReaderSettings())
    val pdfSettings: StateFlow<PdfReaderSettings> = _pdfSettings.asStateFlow()

    // EPUB margin-note ("info overlay") preferences (mirrors the iOS EpubOverlayPreferences @AppStorage).
    private val _epubOverlay = MutableStateFlow(EpubOverlayPreferences())
    val epubOverlay: StateFlow<EpubOverlayPreferences> = _epubOverlay.asStateFlow()

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
        viewModelScope.launch {
            store.epubOverlay.collect { _epubOverlay.value = it }
        }
    }

    /** Apply locally first (UI takes effect immediately), then persist. */
    fun update(transform: (DivinaSettings) -> DivinaSettings) {
        _settings.value = transform(_settings.value)
        viewModelScope.launch { store.update(transform) }
    }

    fun updateEpub(transform: (EpubReaderSettings) -> EpubReaderSettings) {
        _epubSettings.value = transform(_epubSettings.value)
        viewModelScope.launch { store.updateEpubSettings(transform) }
    }

    fun updatePdf(transform: (PdfReaderSettings) -> PdfReaderSettings) {
        _pdfSettings.value = transform(_pdfSettings.value)
        viewModelScope.launch { store.updatePdfSettings(transform) }
    }

    /** Apply locally first (UI takes effect immediately), then persist. */
    fun updateEpubOverlay(transform: (EpubOverlayPreferences) -> EpubOverlayPreferences) {
        _epubOverlay.value = transform(_epubOverlay.value)
        viewModelScope.launch { store.updateEpubOverlay(transform) }
    }

    /** Reading direction follows the default from "Settings - Reader Defaults" (same AppStorage semantics as the original). */
    fun setDefaultDirection(direction: ReadingDirection) {
        viewModelScope.launch { appSettings.setDivinaDefaultDirection(direction.name.lowercase()) }
    }

    /**
     * The direction remembered for this book or its series (null = follow the settings / series
     * metadata chain). A book exception wins over the series value. Read when a book opens (and when the
     * reader continues into the next book), so a remembered choice survives closing the reader.
     */
    suspend fun rememberedDirection(bookDtoId: String?, seriesDtoId: String?): RememberedDirection? =
        resolveRememberedDirection(
            bookDirections = store.bookDirections.first(),
            seriesDirections = store.seriesDirections.first(),
            bookDtoId = bookDtoId,
            seriesDtoId = seriesDtoId,
        )

    /** The wide-page split remembered for this book or its series (null = follow the reader defaults). */
    suspend fun rememberedSplitWideMode(
        bookDtoId: String?,
        seriesDtoId: String?,
    ): Remembered<SplitWidePageMode>? = resolveRemembered(
        bookValues = store.bookSplitWideModes.first(),
        seriesValues = store.seriesSplitWideModes.first(),
        bookDtoId = bookDtoId,
        seriesDtoId = seriesDtoId,
    )

    /** Remembers (or with null, forgets) the direction picked for one book as an exception. */
    fun setBookDirection(bookDtoId: String, direction: ReadingDirection?) {
        viewModelScope.launch { store.setBookDirection(bookDtoId, direction) }
    }

    /** Remembers (or with null, forgets) the direction for a whole series. */
    fun setSeriesDirection(seriesDtoId: String, direction: ReadingDirection?) {
        viewModelScope.launch { store.setSeriesDirection(seriesDtoId, direction) }
    }

    /** Remembers (or with null, forgets) the wide-page split for one book as an exception. */
    fun setBookSplitWideMode(bookDtoId: String, mode: SplitWidePageMode?) {
        viewModelScope.launch { store.setBookSplitWideMode(bookDtoId, mode) }
    }

    /** Remembers (or with null, forgets) the wide-page split for a whole series. */
    fun setSeriesSplitWideMode(seriesDtoId: String, mode: SplitWidePageMode?) {
        viewModelScope.launch { store.setSeriesSplitWideMode(seriesDtoId, mode) }
    }
}
