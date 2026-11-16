package com.kmworks.kmreader.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmworks.kmreader.ui.reader.BorderCropMode
import com.kmworks.kmreader.ui.reader.DivinaSettings
import com.kmworks.kmreader.ui.reader.DoubleTapZoomMode
import com.kmworks.kmreader.ui.reader.EpubReaderSettings
import com.kmworks.kmreader.ui.reader.LongPressAction
import com.kmworks.kmreader.ui.reader.PageLayout
import com.kmworks.kmreader.ui.reader.PageTransitionStyle
import com.kmworks.kmreader.ui.reader.PdfReaderSettings
import com.kmworks.kmreader.ui.reader.PreloadProfile
import com.kmworks.kmreader.ui.reader.ReaderBackground
import com.kmworks.kmreader.ui.reader.SplitWidePageMode
import com.kmworks.kmreader.ui.reader.TapZoneInversion
import com.kmworks.kmreader.ui.reader.TapZoneMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.divinaSettingsStore by preferencesDataStore(name = "divina_settings")

/**
 * DIVINA 阅读器设置的持久化（对齐原作 ReaderSettingsSheet 的 @AppStorage 键语义）。
 * 键名与原作一致（readerBackground/showPageNumber/...），便于对照排查。
 */
@Singleton
class DivinaSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val store = context.divinaSettingsStore

    val settings: Flow<DivinaSettings> = store.data.map { it.toSettings() }

    suspend fun update(transform: (DivinaSettings) -> DivinaSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.BACKGROUND] = next.background.name
            prefs[Keys.SHOW_PAGE_NUMBER] = next.showPageNumber
            prefs[Keys.SHOW_PAGE_SHADOW] = next.showPageShadow
            prefs[Keys.CONTROLS_GRADIENT] = next.controlsGradient
            prefs[Keys.SHOW_PROGRESS_BAR] = next.showProgressBar
            prefs[Keys.WEBTOON_PAGE_WIDTH] = next.webtoonPageWidthPercentage
            prefs[Keys.PAGE_TRANSITION] = next.pageTransition.name
            prefs[Keys.ANIMATE_TAP_TURNS] = next.animateTapTurns
            prefs[Keys.TAP_ZONE_MODE] = next.tapZoneMode.name
            prefs[Keys.TAP_ZONE_INVERSION] = next.tapZoneInversion.name
            prefs[Keys.SHOW_TAP_ZONE_HINTS] = next.showTapZoneHints
            prefs[Keys.WEBTOON_TAP_SCROLL] = next.webtoonTapScrollPercentage
            prefs[Keys.DOUBLE_TAP_MODE] = next.doubleTapZoomMode.name
            prefs[Keys.DOUBLE_TAP_SCALE] = next.doubleTapZoomScale
            prefs[Keys.LONG_PRESS_ACTION] = next.longPressAction.name
            prefs[Keys.PRELOAD_PROFILE] = next.preloadProfile.name
            prefs[Keys.BORDER_CROP] = next.borderCrop.name
            prefs[Keys.KEEP_SCREEN_AWAKE] = next.keepScreenAwake
            prefs[Keys.DEFAULT_PAGE_LAYOUT] = next.defaultPageLayout.name
            prefs[Keys.DEFAULT_SPLIT_WIDE_MODE] = next.defaultSplitWideMode.name
            prefs[Keys.DEFAULT_SOLO_COVER] = next.defaultSoloCoverPage
            prefs[Keys.FORCE_DEFAULT_DIRECTION] = next.forceDefaultDirection
        }
    }

    // ---- 单页独显集合（对齐原作 soloPagesByBookId，按书籍持久化，存服务器页号）----
    private val keySoloPages = stringPreferencesKey("divinaSoloPages")

    fun soloPages(bookDtoId: String): Flow<Set<Int>> =
        store.data.map { prefs ->
            prefs[keySoloPages]?.split("||")
                ?.firstOrNull { it.startsWith("$bookDtoId:") }
                ?.substringAfter(':')
                ?.split(',')
                ?.mapNotNull { it.toIntOrNull() }
                ?.toSet()
                ?: emptySet()
        }

    suspend fun setSoloPages(bookDtoId: String, pages: Set<Int>) {
        store.edit { prefs ->
            val others = prefs[keySoloPages]?.split("||")
                ?.filterNot { it.startsWith("$bookDtoId:") }
                ?: emptyList()
            val entry = if (pages.isEmpty()) null else "$bookDtoId:" + pages.sorted().joinToString(",")
            val joined = (others + listOfNotNull(entry)).joinToString("||")
            if (joined.isEmpty()) prefs.remove(keySoloPages) else prefs[keySoloPages] = joined
        }
    }

    // ---- EPUB 阅读器设置（对齐原作 epubPreferences/epubFlowStyle/animateEpubTapTurns 等 @AppStorage）----
    val epubSettings: Flow<EpubReaderSettings> = store.data.map { prefs ->
        val defaults = EpubReaderSettings()
        EpubReaderSettings(
            theme = prefs[Keys.EPUB_THEME] ?: defaults.theme,
            fontSizeSp = prefs[Keys.EPUB_FONT_SIZE] ?: defaults.fontSizeSp,
            typeface = prefs[Keys.EPUB_TYPEFACE] ?: defaults.typeface,
            fontWeight = prefs[Keys.EPUB_FONT_WEIGHT] ?: defaults.fontWeight,
            margins = prefs[Keys.EPUB_MARGINS] ?: defaults.margins,
            lineHeight = prefs[Keys.EPUB_LINE_HEIGHT] ?: defaults.lineHeight,
            flowStyle = prefs[Keys.EPUB_FLOW_STYLE] ?: defaults.flowStyle,
            columns = prefs[Keys.EPUB_COLUMNS] ?: defaults.columns,
            publisherStyles = prefs[Keys.EPUB_PUBLISHER_STYLES] ?: defaults.publisherStyles,
            animatePageTurns = prefs[Keys.EPUB_ANIMATE_TURNS] ?: defaults.animatePageTurns,
        )
    }

    suspend fun updateEpubSettings(transform: (EpubReaderSettings) -> EpubReaderSettings) {
        store.edit { prefs ->
            val defaults = EpubReaderSettings()
            val current = EpubReaderSettings(
                theme = prefs[Keys.EPUB_THEME] ?: defaults.theme,
                fontSizeSp = prefs[Keys.EPUB_FONT_SIZE] ?: defaults.fontSizeSp,
                typeface = prefs[Keys.EPUB_TYPEFACE] ?: defaults.typeface,
                fontWeight = prefs[Keys.EPUB_FONT_WEIGHT] ?: defaults.fontWeight,
                margins = prefs[Keys.EPUB_MARGINS] ?: defaults.margins,
                lineHeight = prefs[Keys.EPUB_LINE_HEIGHT] ?: defaults.lineHeight,
                flowStyle = prefs[Keys.EPUB_FLOW_STYLE] ?: defaults.flowStyle,
                columns = prefs[Keys.EPUB_COLUMNS] ?: defaults.columns,
                publisherStyles = prefs[Keys.EPUB_PUBLISHER_STYLES] ?: defaults.publisherStyles,
                animatePageTurns = prefs[Keys.EPUB_ANIMATE_TURNS] ?: defaults.animatePageTurns,
            )
            val next = transform(current)
            prefs[Keys.EPUB_THEME] = next.theme
            prefs[Keys.EPUB_FONT_SIZE] = next.fontSizeSp
            prefs[Keys.EPUB_TYPEFACE] = next.typeface
            prefs[Keys.EPUB_FONT_WEIGHT] = next.fontWeight
            prefs[Keys.EPUB_MARGINS] = next.margins
            prefs[Keys.EPUB_LINE_HEIGHT] = next.lineHeight
            prefs[Keys.EPUB_FLOW_STYLE] = next.flowStyle
            prefs[Keys.EPUB_COLUMNS] = next.columns
            prefs[Keys.EPUB_PUBLISHER_STYLES] = next.publisherStyles
            prefs[Keys.EPUB_ANIMATE_TURNS] = next.animatePageTurns
        }
    }

    // ---- PDF 阅读器设置（对齐原作 pdfReaderBackground/showPdfControlsGradientBackground/showPdfProgressBarWhileReading 等）----
    val pdfSettings: Flow<PdfReaderSettings> = store.data.map { prefs ->
        val defaults = PdfReaderSettings()
        PdfReaderSettings(
            continuousScroll = prefs[Keys.PDF_CONTINUOUS_SCROLL] ?: defaults.continuousScroll,
            pagePresentation = prefs[Keys.PDF_PAGE_PRESENTATION] ?: defaults.pagePresentation,
            background = prefs[Keys.PDF_BACKGROUND] ?: defaults.background,
            soloCoverPage = prefs[Keys.PDF_SOLO_COVER] ?: defaults.soloCoverPage,
            controlsScrim = prefs[Keys.PDF_CONTROLS_SCRIM] ?: defaults.controlsScrim,
            showProgressWhileReading = prefs[Keys.PDF_SHOW_PROGRESS] ?: defaults.showProgressWhileReading,
        )
    }

    suspend fun updatePdfSettings(transform: (PdfReaderSettings) -> PdfReaderSettings) {
        store.edit { prefs ->
            val defaults = PdfReaderSettings()
            val current = PdfReaderSettings(
                continuousScroll = prefs[Keys.PDF_CONTINUOUS_SCROLL] ?: defaults.continuousScroll,
                pagePresentation = prefs[Keys.PDF_PAGE_PRESENTATION] ?: defaults.pagePresentation,
                background = prefs[Keys.PDF_BACKGROUND] ?: defaults.background,
                soloCoverPage = prefs[Keys.PDF_SOLO_COVER] ?: defaults.soloCoverPage,
                controlsScrim = prefs[Keys.PDF_CONTROLS_SCRIM] ?: defaults.controlsScrim,
                showProgressWhileReading = prefs[Keys.PDF_SHOW_PROGRESS] ?: defaults.showProgressWhileReading,
            )
            val next = transform(current)
            prefs[Keys.PDF_CONTINUOUS_SCROLL] = next.continuousScroll
            prefs[Keys.PDF_PAGE_PRESENTATION] = next.pagePresentation
            prefs[Keys.PDF_BACKGROUND] = next.background
            prefs[Keys.PDF_SOLO_COVER] = next.soloCoverPage
            prefs[Keys.PDF_CONTROLS_SCRIM] = next.controlsScrim
            prefs[Keys.PDF_SHOW_PROGRESS] = next.showProgressWhileReading
        }
    }

    private fun Preferences.toSettings(): DivinaSettings {
        val defaults = DivinaSettings()
        return DivinaSettings(
            background = enumOrNull<ReaderBackground>(this[Keys.BACKGROUND]) ?: defaults.background,
            showPageNumber = this[Keys.SHOW_PAGE_NUMBER] ?: defaults.showPageNumber,
            showPageShadow = this[Keys.SHOW_PAGE_SHADOW] ?: defaults.showPageShadow,
            controlsGradient = this[Keys.CONTROLS_GRADIENT] ?: defaults.controlsGradient,
            showProgressBar = this[Keys.SHOW_PROGRESS_BAR] ?: defaults.showProgressBar,
            webtoonPageWidthPercentage = this[Keys.WEBTOON_PAGE_WIDTH] ?: defaults.webtoonPageWidthPercentage,
            pageTransition = enumOrNull<PageTransitionStyle>(this[Keys.PAGE_TRANSITION]) ?: defaults.pageTransition,
            animateTapTurns = this[Keys.ANIMATE_TAP_TURNS] ?: defaults.animateTapTurns,
            tapZoneMode = enumOrNull<TapZoneMode>(this[Keys.TAP_ZONE_MODE]) ?: defaults.tapZoneMode,
            tapZoneInversion = enumOrNull<TapZoneInversion>(this[Keys.TAP_ZONE_INVERSION]) ?: defaults.tapZoneInversion,
            showTapZoneHints = this[Keys.SHOW_TAP_ZONE_HINTS] ?: defaults.showTapZoneHints,
            webtoonTapScrollPercentage = this[Keys.WEBTOON_TAP_SCROLL] ?: defaults.webtoonTapScrollPercentage,
            doubleTapZoomMode = enumOrNull<DoubleTapZoomMode>(this[Keys.DOUBLE_TAP_MODE]) ?: defaults.doubleTapZoomMode,
            doubleTapZoomScale = this[Keys.DOUBLE_TAP_SCALE] ?: defaults.doubleTapZoomScale,
            longPressAction = enumOrNull<LongPressAction>(this[Keys.LONG_PRESS_ACTION]) ?: defaults.longPressAction,
            preloadProfile = enumOrNull<PreloadProfile>(this[Keys.PRELOAD_PROFILE]) ?: defaults.preloadProfile,
            borderCrop = enumOrNull<BorderCropMode>(this[Keys.BORDER_CROP]) ?: defaults.borderCrop,
            keepScreenAwake = this[Keys.KEEP_SCREEN_AWAKE] ?: defaults.keepScreenAwake,
            defaultPageLayout = enumOrNull<PageLayout>(this[Keys.DEFAULT_PAGE_LAYOUT]) ?: defaults.defaultPageLayout,
            defaultSplitWideMode = enumOrNull<SplitWidePageMode>(this[Keys.DEFAULT_SPLIT_WIDE_MODE]) ?: defaults.defaultSplitWideMode,
            defaultSoloCoverPage = this[Keys.DEFAULT_SOLO_COVER] ?: defaults.defaultSoloCoverPage,
            forceDefaultDirection = this[Keys.FORCE_DEFAULT_DIRECTION] ?: defaults.forceDefaultDirection,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(raw: String?): T? =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

    private object Keys {
        val BACKGROUND = stringPreferencesKey("readerBackground")
        val SHOW_PAGE_NUMBER = booleanPreferencesKey("showPageNumber")
        val SHOW_PAGE_SHADOW = booleanPreferencesKey("showPageShadow")
        val CONTROLS_GRADIENT = booleanPreferencesKey("showDivinaControlsGradientBackground")
        val SHOW_PROGRESS_BAR = booleanPreferencesKey("showDivinaProgressBarWhileReading")
        val WEBTOON_PAGE_WIDTH = floatPreferencesKey("webtoonPageWidthPercentage")
        val PAGE_TRANSITION = stringPreferencesKey("pageTransitionStyle")
        val ANIMATE_TAP_TURNS = booleanPreferencesKey("animateTapTurns")
        val TAP_ZONE_MODE = stringPreferencesKey("tapZoneMode")
        val TAP_ZONE_INVERSION = stringPreferencesKey("tapZoneInversionMode")
        val SHOW_TAP_ZONE_HINTS = booleanPreferencesKey("showTapZoneHints")
        val WEBTOON_TAP_SCROLL = floatPreferencesKey("webtoonTapScrollPercentage")
        val DOUBLE_TAP_MODE = stringPreferencesKey("doubleTapZoomMode")
        val DOUBLE_TAP_SCALE = floatPreferencesKey("doubleTapZoomScale")
        val LONG_PRESS_ACTION = stringPreferencesKey("readerLongPressAction")
        val PRELOAD_PROFILE = stringPreferencesKey("divinaPreloadProfile")
        val BORDER_CROP = stringPreferencesKey("divinaPageBorderCropMode")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keepScreenAwakeWhileReading")
        val DEFAULT_PAGE_LAYOUT = stringPreferencesKey("defaultPageLayout")
        val DEFAULT_SPLIT_WIDE_MODE = stringPreferencesKey("splitWidePageMode")
        val DEFAULT_SOLO_COVER = booleanPreferencesKey("soloCoverPage")
        val FORCE_DEFAULT_DIRECTION = booleanPreferencesKey("forceDefaultReadingDirection")
        val EPUB_THEME = stringPreferencesKey("epubTheme")
        val EPUB_FONT_SIZE = floatPreferencesKey("epubFontSizeSp")
        val EPUB_TYPEFACE = stringPreferencesKey("epubTypeface")
        val EPUB_FONT_WEIGHT = floatPreferencesKey("epubFontWeight")
        val EPUB_MARGINS = floatPreferencesKey("epubMargins")
        val EPUB_LINE_HEIGHT = floatPreferencesKey("epubLineHeight")
        val EPUB_FLOW_STYLE = stringPreferencesKey("epubFlowStyle")
        val EPUB_COLUMNS = stringPreferencesKey("epubColumns")
        val EPUB_PUBLISHER_STYLES = booleanPreferencesKey("epubPublisherStyles")
        val EPUB_ANIMATE_TURNS = booleanPreferencesKey("animateEpubTapTurns")
        val PDF_CONTINUOUS_SCROLL = booleanPreferencesKey("pdfContinuousScroll")
        val PDF_PAGE_PRESENTATION = stringPreferencesKey("pdfPagePresentation")
        val PDF_BACKGROUND = stringPreferencesKey("pdfReaderBackground")
        val PDF_SOLO_COVER = booleanPreferencesKey("pdfSoloCoverPage")
        val PDF_CONTROLS_SCRIM = booleanPreferencesKey("showPdfControlsGradientBackground")
        val PDF_SHOW_PROGRESS = booleanPreferencesKey("showPdfProgressBarWhileReading")
    }
}
