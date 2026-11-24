package com.kmworks.kmreader.ui.reader

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.kmworks.kmreader.R
import kotlin.math.max
import kotlin.math.min

/**
 * Reader background (mirrors the original ReaderBackground: black/white/gray/sepia/system).
 * Sepia mirrors the original paper and foreground colors (ReaderBackground.swift).
 */
enum class ReaderBackground(@StringRes val labelRes: Int) {
    Black(R.string.reader_background_black),
    White(R.string.reader_background_white),
    Gray(R.string.reader_background_gray),
    Sepia(R.string.reader_background_sepia),
    System(R.string.reader_background_system);

    /** Mirrors the original ReaderBackground.appliesImageMultiplyBlend: only sepia applies a multiply blend to page images. */
    val appliesImageMultiplyBlend: Boolean get() = this == Sepia

    fun color(dark: Boolean): Color = when (this) {
        Black -> Color.Black
        White -> Color.White
        Gray -> Color(0xFF808080)
        Sepia -> SepiaPaper
        System -> if (dark) Color.Black else Color.White
    }

    /** Foreground color for page placeholders and text. */
    fun contentColor(dark: Boolean): Color = when (this) {
        Black, Gray, System -> if (this == System && !dark) Color.Black else Color.White
        White -> Color.Black
        Sepia -> SepiaInk
    }

    companion object {
        val SepiaPaper = Color(0xFFF4ECD8)
        val SepiaInk = Color(0xFF5C4A37)
    }
}

/** Page transition (mirrors the original PageTransitionStyle.availableCases: scroll/cover; pageCurl is not implemented on Android, recorded in the differences list). */
enum class PageTransitionStyle(@StringRes val labelRes: Int) {
    Scroll(R.string.reader_transition_scroll),
    Cover(R.string.reader_transition_cover);

    val next: PageTransitionStyle get() = entries[(ordinal + 1) % entries.size]
}

/** Tap zone layout (mirrors the original TapZoneMode's six cases). */
enum class TapZoneMode(@StringRes val labelRes: Int) {
    None(R.string.reader_tap_zone_none),
    Default(R.string.reader_tap_zone_default),
    Edge(R.string.reader_tap_zone_edge),
    Kindle(R.string.reader_tap_zone_kindle),
    LShape(R.string.reader_tap_zone_l_shape),
    TSplit(R.string.reader_tap_zone_t_split);

    val isDisabled: Boolean get() = this == None
    val next: TapZoneMode get() = entries[(ordinal + 1) % entries.size]
}

/** Tap zone left/right mirroring (mirrors the original TapZoneInversionMode: normal/auto/inverted). */
enum class TapZoneInversion(@StringRes val labelRes: Int) {
    Normal(R.string.reader_tap_inversion_normal),
    Auto(R.string.reader_tap_inversion_auto),
    Inverted(R.string.reader_tap_inversion_inverted);

    fun isInverted(isRtl: Boolean): Boolean = when (this) {
        Normal -> false
        Auto -> isRtl
        Inverted -> true
    }
}

/** Double-tap action (mirrors the original DoubleTapZoomMode: enabled/disabled). */
enum class DoubleTapZoomMode(@StringRes val labelRes: Int) {
    Enabled(R.string.reader_double_tap_zoom),
    Disabled(R.string.reader_off)
}

/** Long-press action (mirrors the original ReaderLongPressAction: contextMenu/disabled). */
enum class LongPressAction(@StringRes val labelRes: Int) {
    ContextMenu(R.string.reader_long_press_menu),
    Disabled(R.string.reader_off)
}

/**
 * Image preload window (1:1 mirror of the original ReaderPreloadProfile's three windows, ReaderViewModel.swift):
 * before/after = prefetch window, keepBefore/keepAfter = in-memory retention window (pages beyond it are evicted).
 */
enum class PreloadProfile(
    @StringRes val labelRes: Int,
    val before: Int,
    val after: Int,
    val keepBefore: Int,
    val keepAfter: Int,
) {
    LowMemory(R.string.reader_preload_low_memory, 1, 3, 1, 3),
    Balanced(R.string.reader_preload_balanced, 3, 5, 5, 7),
    FastPageTurns(R.string.reader_preload_fast_page_turns, 5, 7, 7, 9)
}

/** Border crop mode (mirrors the original ReaderPageBorderCropMode: disabled/conservative/aggressive). */
enum class BorderCropMode(@StringRes val labelRes: Int, val maxFraction: Float, val tolerance: Int) {
    Disabled(R.string.reader_off, 0f, 0),
    Conservative(R.string.reader_crop_conservative, 0.08f, 12),
    Aggressive(R.string.reader_crop_aggressive, 0.20f, 24)
}

/** Waifu2x upscaling trigger (mirrors the iOS upscale setting: off / auto when enlarged on screen / always). */
enum class UpscaleMode(@StringRes val labelRes: Int) {
    Off(R.string.reader_off),
    Auto(R.string.upscale_mode_auto),
    Always(R.string.upscale_mode_always),
}

/**
 * Waifu2x denoise weights (mirrors the iOS noise level choice: 无降噪 + noise0..3).
 * Maps 1:1 onto Waifu2xBridge.DenoiseModel.
 */
enum class DenoiseLevel(@StringRes val labelRes: Int) {
    None(R.string.upscale_denoise_none),
    Low(R.string.upscale_denoise_low),
    Medium(R.string.upscale_denoise_medium),
    High(R.string.upscale_denoise_high),
    Max(R.string.upscale_denoise_max),
    ;

    val bridgeModel: com.kmworks.kmreader.core.image.upscale.Waifu2xBridge.DenoiseModel
        get() = com.kmworks.kmreader.core.image.upscale.Waifu2xBridge.DenoiseModel.valueOf(name.uppercase())
}

/** Waifu2x upscaling settings (default Auto + Low matches the iOS waifu2x photo noise0 model). */
data class UpscaleSettings(
    val mode: UpscaleMode = UpscaleMode.Auto,
    val denoise: DenoiseLevel = DenoiseLevel.Low,
    val autoTriggerScale: Float = com.kmworks.kmreader.core.image.upscale.UpscaleConfig.DEFAULT_AUTO_TRIGGER_SCALE,
)

/**
 * DIVINA reader settings (mirrors the original ReaderSettingsSheet's @AppStorage keys and defaults).
 * Panel mode / Live Text are not implemented per project constraints; recorded in the README differences list.
 *
 * defaultPageLayout/defaultSplitWideMode/defaultSoloCoverPage correspond to the original DivinaPreferencesView's
 * "Default Reading Options": consumed only as initial values when opening a new session; in-reader changes are not
 * written back (mirrors the original DivinaReaderView @State initial values + resetReaderPreferencesForCurrentBook semantics).
 */
data class DivinaSettings(
    val background: ReaderBackground = ReaderBackground.System,
    val showPageNumber: Boolean = true,
    val showPageShadow: Boolean = true,
    val controlsGradient: Boolean = true,
    val showProgressBar: Boolean = true,
    val webtoonPageWidthPercentage: Float = 100f,
    val pageTransition: PageTransitionStyle = PageTransitionStyle.Cover,
    val animateTapTurns: Boolean = true,
    val tapZoneMode: TapZoneMode = TapZoneMode.Default,
    val tapZoneInversion: TapZoneInversion = TapZoneInversion.Auto,
    val showTapZoneHints: Boolean = true,
    val webtoonTapScrollPercentage: Float = 80f,
    val doubleTapZoomMode: DoubleTapZoomMode = DoubleTapZoomMode.Enabled,
    val doubleTapZoomScale: Float = 3f,
    val longPressAction: LongPressAction = LongPressAction.ContextMenu,
    val preloadProfile: PreloadProfile = PreloadProfile.Balanced,
    val borderCrop: BorderCropMode = BorderCropMode.Disabled,
    val upscale: UpscaleSettings = UpscaleSettings(),
    val keepScreenAwake: Boolean = false,
    val defaultPageLayout: PageLayout = PageLayout.Auto,
    val defaultSplitWideMode: SplitWidePageMode = SplitWidePageMode.Auto,
    val defaultSoloCoverPage: Boolean = true,
    /** Mirrors the original forceDefaultReadingDirection: when true, ignores the series direction and always uses the global default direction. */
    val forceDefaultDirection: Boolean = false,
)

/** Tap result (mirrors the original TapZoneAction). */
enum class TapZoneAction { Previous, Next, ToggleControls }

/**
 * Tap zone resolution (mirrors the original Models/TapZoneHelper.swift case by case):
 * normalized coordinates → action; inversion defaults to auto (mirrored left/right for RTL).
 */
object TapZoneHelper {

    fun action(
        normalizedX: Float,
        normalizedY: Float,
        mode: TapZoneMode,
        inversion: TapZoneInversion,
        isRtl: Boolean,
        stripHeightFraction: Float = 0.12f,
    ): TapZoneAction {
        if (mode.isDisabled) return TapZoneAction.ToggleControls

        val inverted = inversion.isInverted(isRtl)
        if (mode == TapZoneMode.TSplit) {
            if (normalizedY <= stripHeightFraction) return TapZoneAction.ToggleControls
            val leftHalf = normalizedX < 0.5f
            return if (leftHalf != inverted) TapZoneAction.Previous else TapZoneAction.Next
        }

        val column = columnIndex(normalizedX, inverted)
        val row = rowIndex(normalizedY)
        return action(row, column, mode)
    }

    private fun action(row: Int, column: Int, mode: TapZoneMode): TapZoneAction {
        if (mode.isDisabled) return TapZoneAction.ToggleControls
        return when (mode) {
            TapZoneMode.None -> TapZoneAction.ToggleControls
            TapZoneMode.Default -> when (column) {
                0 -> TapZoneAction.Previous
                1 -> TapZoneAction.ToggleControls
                else -> TapZoneAction.Next
            }
            TapZoneMode.Edge -> when {
                row == 1 && column == 1 -> TapZoneAction.ToggleControls
                row == 2 && column == 1 -> TapZoneAction.Previous
                else -> TapZoneAction.Next
            }
            TapZoneMode.Kindle -> when {
                row == 0 -> TapZoneAction.ToggleControls
                column == 0 -> TapZoneAction.Previous
                else -> TapZoneAction.Next
            }
            TapZoneMode.LShape -> when {
                row == 1 && column == 1 -> TapZoneAction.ToggleControls
                row == 0 || (row == 1 && column == 0) -> TapZoneAction.Previous
                else -> TapZoneAction.Next
            }
            TapZoneMode.TSplit -> TapZoneAction.ToggleControls
        }
    }

    private fun columnIndex(normalizedX: Float, inverted: Boolean): Int {
        val clamped = min(max(normalizedX, 0f), 0.999_999f)
        val column = (clamped * 3f).toInt()
        return if (inverted) 2 - column else column
    }

    private fun rowIndex(normalizedY: Float): Int {
        val clamped = min(max(normalizedY, 0f), 0.999_999f)
        return (clamped * 3f).toInt()
    }
}
