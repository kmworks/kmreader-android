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
import com.kmworks.kmreader.ui.reader.LongPressAction
import com.kmworks.kmreader.ui.reader.PageTransitionStyle
import com.kmworks.kmreader.ui.reader.PreloadProfile
import com.kmworks.kmreader.ui.reader.ReaderBackground
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
    }
}
