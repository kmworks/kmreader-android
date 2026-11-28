package com.kmworks.kmreader.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmworks.kmreader.core.image.upscale.UpscaleConfig
import com.kmworks.kmreader.core.reader.EpubOverlayPreferences
import com.kmworks.kmreader.ui.reader.BorderCropMode
import com.kmworks.kmreader.ui.reader.DenoiseLevel
import com.kmworks.kmreader.ui.reader.DivinaSettings
import com.kmworks.kmreader.ui.reader.DoubleTapZoomMode
import com.kmworks.kmreader.ui.reader.EpubReaderSettings
import com.kmworks.kmreader.ui.reader.LongPressAction
import com.kmworks.kmreader.ui.reader.PageLayout
import com.kmworks.kmreader.ui.reader.PageTransitionStyle
import com.kmworks.kmreader.ui.reader.PreloadProfile
import com.kmworks.kmreader.ui.reader.ReaderBackground
import com.kmworks.kmreader.ui.reader.ReadingDirection
import com.kmworks.kmreader.ui.reader.SplitWidePageMode
import com.kmworks.kmreader.ui.reader.TapZoneInversion
import com.kmworks.kmreader.ui.reader.TapZoneMode
import com.kmworks.kmreader.ui.reader.UpscaleMode
import com.kmworks.kmreader.ui.reader.UpscalePrecision
import com.kmworks.kmreader.ui.reader.UpscaleSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.divinaSettingsStore by preferencesDataStore(name = "divina_settings")

/**
 * Persistence of reader settings (mirrors the @AppStorage key semantics of the original
 * ReaderSettingsSheet). Despite the class name this is the one store behind both readers:
 * [DivinaSettings] holds the DIVINA options plus the switches shared by DIVINA/EPUB
 * (keepScreenAwake, drawUnderCutout, volumeButtonPageTurnEnabled), while [EpubReaderSettings]
 * serves the other reader.
 * Key names match the original (readerBackground/showPageNumber/...), easing side-by-side debugging.
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
            prefs[Keys.UPSCALE_MODE] = next.upscale.mode.name
            prefs[Keys.UPSCALE_DENOISE] = next.upscale.denoise.name
            prefs[Keys.UPSCALE_AUTO_SCALE] = UpscaleConfig.clampAutoTriggerScale(next.upscale.autoTriggerScale)
            prefs[Keys.UPSCALE_PRECISION] = next.upscale.precision.name
            prefs[Keys.UPSCALE_PRELOAD_AHEAD] = next.upscale.preloadAhead
                .coerceIn(0, UpscaleSettings.MAX_PRELOAD_AHEAD)
            prefs[Keys.KEEP_SCREEN_AWAKE] = next.keepScreenAwake
            prefs[Keys.DRAW_UNDER_CUTOUT] = next.drawUnderCutout
            prefs[Keys.DEFAULT_PAGE_LAYOUT] = next.defaultPageLayout.name
            prefs[Keys.DEFAULT_SPLIT_WIDE_MODE] = next.defaultSplitWideMode.name
            prefs[Keys.DEFAULT_SOLO_COVER] = next.defaultSoloCoverPage
            prefs[Keys.FORCE_DEFAULT_DIRECTION] = next.forceDefaultDirection
            prefs[Keys.VOLUME_BUTTON_PAGE_TURN] = next.volumeButtonPageTurnEnabled
        }
    }

    // ---- Solo-page sets (mirrors the original soloPagesByBookId, persisted per book, storing server page numbers) ----
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

    // ---- Reading direction / wide-page split remembered per series and per book ----------------
    // The menu's "current reading options" are session-scoped (mirrors the original @State values),
    // except these two: choosing one writes the whole series by default (so continuing to the next
    // volume keeps it), and "only for this book" writes an exception that wins over the series value.
    // All four maps use the soloPages format ("id:ValueName||…").
    val seriesDirections: Flow<Map<String, ReadingDirection>> = store.data.map { prefs ->
        parseDirectionMap(prefs[keySeriesDirections])
    }

    val bookDirections: Flow<Map<String, ReadingDirection>> = store.data.map { prefs ->
        parseDirectionMap(prefs[keyBookDirections])
    }

    val seriesSplitWideModes: Flow<Map<String, SplitWidePageMode>> = store.data.map { prefs ->
        parseSplitWideMap(prefs[keySeriesSplitWideModes])
    }

    val bookSplitWideModes: Flow<Map<String, SplitWidePageMode>> = store.data.map { prefs ->
        parseSplitWideMap(prefs[keyBookSplitWideModes])
    }

    /** Remembers [direction] for the whole [seriesDtoId] (null forgets it). */
    suspend fun setSeriesDirection(seriesDtoId: String, direction: ReadingDirection?) {
        putRemembered(keySeriesDirections, seriesDtoId, direction?.name)
    }

    /** Remembers [direction] as an exception for one [bookDtoId] (null forgets the exception). */
    suspend fun setBookDirection(bookDtoId: String, direction: ReadingDirection?) {
        putRemembered(keyBookDirections, bookDtoId, direction?.name)
    }

    /** Remembers [mode] for the whole [seriesDtoId] (null forgets it). */
    suspend fun setSeriesSplitWideMode(seriesDtoId: String, mode: SplitWidePageMode?) {
        putRemembered(keySeriesSplitWideModes, seriesDtoId, mode?.name)
    }

    /** Remembers [mode] as an exception for one [bookDtoId] (null forgets the exception). */
    suspend fun setBookSplitWideMode(bookDtoId: String, mode: SplitWidePageMode?) {
        putRemembered(keyBookSplitWideModes, bookDtoId, mode?.name)
    }

    private suspend fun putRemembered(key: Preferences.Key<String>, id: String, name: String?) {
        store.edit { prefs ->
            val next = parseEnumNames(prefs[key]).toMutableMap()
            if (name == null) next.remove(id) else next[id] = name
            val formatted = formatEnumNames(next)
            if (formatted == null) prefs.remove(key) else prefs.set(key, formatted)
        }
    }

    private val keySeriesDirections = stringPreferencesKey("divinaSeriesDirections")
    private val keyBookDirections = stringPreferencesKey("divinaBookDirections")
    private val keySeriesSplitWideModes = stringPreferencesKey("divinaSeriesSplitWideModes")
    private val keyBookSplitWideModes = stringPreferencesKey("divinaBookSplitWideModes")

    // ---- EPUB reader settings (mirrors the original epubPreferences/epubFlowStyle/animateEpubTapTurns etc. @AppStorage) ----
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

    // ---- EPUB margin-note ("info overlay") preferences: header/footer slots + optional progress bar,
    // persisted as one JSON string (mirrors the iOS EpubOverlayPreferences @AppStorage value) ----
    val epubOverlay: Flow<EpubOverlayPreferences> = store.data.map { prefs ->
        EpubOverlayPreferences.fromJson(prefs[Keys.EPUB_OVERLAY])
    }

    suspend fun updateEpubOverlay(transform: (EpubOverlayPreferences) -> EpubOverlayPreferences) {
        store.edit { prefs ->
            val next = transform(EpubOverlayPreferences.fromJson(prefs[Keys.EPUB_OVERLAY]))
            prefs[Keys.EPUB_OVERLAY] = next.toJson()
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
            upscale = UpscaleSettings(
                mode = enumOrNull<UpscaleMode>(this[Keys.UPSCALE_MODE]) ?: defaults.upscale.mode,
                denoise = enumOrNull<DenoiseLevel>(this[Keys.UPSCALE_DENOISE]) ?: defaults.upscale.denoise,
                autoTriggerScale = UpscaleConfig.clampAutoTriggerScale(
                    this[Keys.UPSCALE_AUTO_SCALE] ?: defaults.upscale.autoTriggerScale,
                ),
                precision = enumOrNull<UpscalePrecision>(this[Keys.UPSCALE_PRECISION]) ?: defaults.upscale.precision,
                preloadAhead = (this[Keys.UPSCALE_PRELOAD_AHEAD] ?: defaults.upscale.preloadAhead)
                    .coerceIn(0, UpscaleSettings.MAX_PRELOAD_AHEAD),
            ),
            keepScreenAwake = this[Keys.KEEP_SCREEN_AWAKE] ?: defaults.keepScreenAwake,
            drawUnderCutout = this[Keys.DRAW_UNDER_CUTOUT] ?: defaults.drawUnderCutout,
            defaultPageLayout = enumOrNull<PageLayout>(this[Keys.DEFAULT_PAGE_LAYOUT]) ?: defaults.defaultPageLayout,
            defaultSplitWideMode = enumOrNull<SplitWidePageMode>(this[Keys.DEFAULT_SPLIT_WIDE_MODE]) ?: defaults.defaultSplitWideMode,
            defaultSoloCoverPage = this[Keys.DEFAULT_SOLO_COVER] ?: defaults.defaultSoloCoverPage,
            forceDefaultDirection = this[Keys.FORCE_DEFAULT_DIRECTION] ?: defaults.forceDefaultDirection,
            volumeButtonPageTurnEnabled = this[Keys.VOLUME_BUTTON_PAGE_TURN] ?: defaults.volumeButtonPageTurnEnabled,
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
        val UPSCALE_MODE = stringPreferencesKey("divinaWaifu2xMode")
        val UPSCALE_DENOISE = stringPreferencesKey("divinaWaifu2xDenoise")
        val UPSCALE_AUTO_SCALE = floatPreferencesKey("divinaWaifu2xAutoTriggerScale")
        val UPSCALE_PRECISION = stringPreferencesKey("divinaWaifu2xPrecision")
        val UPSCALE_PRELOAD_AHEAD = intPreferencesKey("divinaWaifu2xPreloadAhead")
        /** Kept under its legacy key name (written by the DIVINA-only switch before it became
         *  shared), so an existing user's choice carries over to EPUB unchanged. */
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keepScreenAwakeWhileReading")
        /** Shared by both readers; see [com.kmworks.kmreader.ui.reader.DivinaSettings.drawUnderCutout]. */
        val DRAW_UNDER_CUTOUT = booleanPreferencesKey("drawUnderCutout")
        val DEFAULT_PAGE_LAYOUT = stringPreferencesKey("defaultPageLayout")
        val DEFAULT_SPLIT_WIDE_MODE = stringPreferencesKey("splitWidePageMode")
        val DEFAULT_SOLO_COVER = booleanPreferencesKey("soloCoverPage")
        val FORCE_DEFAULT_DIRECTION = booleanPreferencesKey("forceDefaultReadingDirection")
        /** Mirrors the original volumeButtonPageTurnEnabled (shared by DIVINA and EPUB readers). */
        val VOLUME_BUTTON_PAGE_TURN = booleanPreferencesKey("volumeButtonPageTurnEnabled")
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
        val EPUB_OVERLAY = stringPreferencesKey("epubOverlayPreferences")
    }
}

/**
 * Raw "id:ValueName||…" entries. Malformed entries (no separator, empty id or value) are skipped, so a
 * single corrupt entry cannot forget every remembered series/book.
 */
internal fun parseEnumNames(raw: String?): Map<String, String> =
    raw?.split("||")
        ?.mapNotNull { entry ->
            val separator = entry.indexOf(':')
            if (separator <= 0 || separator == entry.lastIndex) return@mapNotNull null
            entry.substring(0, separator) to entry.substring(separator + 1)
        }
        ?.toMap()
        .orEmpty()

/** Inverse of [parseEnumNames]; null when empty (so the whole preferences key can be removed). */
internal fun formatEnumNames(entries: Map<String, String>): String? =
    entries.entries
        .sortedBy { it.key }
        .joinToString("||") { (id, name) -> "$id:$name" }
        .ifEmpty { null }

/** Typed view of [parseEnumNames]: names this build does not know (e.g. a dropped mode) are skipped. */
internal fun <E : Enum<E>> parseEnumMap(raw: String?, values: List<E>): Map<String, E> =
    parseEnumNames(raw)
        .mapNotNull { (id, name) -> values.firstOrNull { it.name == name }?.let { id to it } }
        .toMap()

/** Parses the per-book/per-series reading direction map ("id:DirectionName||…"). */
internal fun parseDirectionMap(raw: String?): Map<String, ReadingDirection> =
    parseEnumMap(raw, ReadingDirection.entries)

/** Parses the per-book/per-series wide-page split map ("id:SplitWidePageModeName||…"). */
internal fun parseSplitWideMap(raw: String?): Map<String, SplitWidePageMode> =
    parseEnumMap(raw, SplitWidePageMode.entries)

/** Inverse of [parseDirectionMap]; null when nothing is remembered (so the key can be removed). */
internal fun formatDirectionMap(directions: Map<String, ReadingDirection>): String? =
    formatEnumNames(directions.mapValues { (_, direction) -> direction.name })

/** Inverse of [parseSplitWideMap]; null when nothing is remembered (so the key can be removed). */
internal fun formatSplitWideMap(modes: Map<String, SplitWidePageMode>): String? =
    formatEnumNames(modes.mapValues { (_, mode) -> mode.name })

/** A value remembered for a book or for its series, and which level supplied it. */
data class Remembered<T>(val value: T, val perBook: Boolean)

/** A direction remembered for a book or for its series. */
typealias RememberedDirection = Remembered<ReadingDirection>

/**
 * Two-level lookup: a book exception ("only for this book") wins over the series value. Null when
 * neither level remembers anything, so the caller falls back to the reader defaults / metadata chain.
 */
internal fun <T> resolveRemembered(
    bookValues: Map<String, T>,
    seriesValues: Map<String, T>,
    bookDtoId: String?,
    seriesDtoId: String?,
): Remembered<T>? {
    bookDtoId?.let { id -> bookValues[id]?.let { return Remembered(it, perBook = true) } }
    seriesDtoId?.let { id -> seriesValues[id]?.let { return Remembered(it, perBook = false) } }
    return null
}

/** [resolveRemembered] for the reading direction. */
internal fun resolveRememberedDirection(
    bookDirections: Map<String, ReadingDirection>,
    seriesDirections: Map<String, ReadingDirection>,
    bookDtoId: String?,
    seriesDtoId: String?,
): RememberedDirection? = resolveRemembered(bookDirections, seriesDirections, bookDtoId, seriesDtoId)
