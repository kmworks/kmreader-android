package com.kmworks.kmreader.ui.reader

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 阅读器背景（对齐原作 ReaderBackground：black/white/gray/sepia/system）。
 * Sepia 对齐原作的纸张色与前景色（ReaderBackground.swift）。
 */
enum class ReaderBackground(val label: String) {
    Black("黑色"),
    White("白色"),
    Gray("灰色"),
    Sepia("米色"),
    System("跟随系统");

    fun color(dark: Boolean): Color = when (this) {
        Black -> Color.Black
        White -> Color.White
        Gray -> Color(0xFF808080)
        Sepia -> SepiaPaper
        System -> if (dark) Color.Black else Color.White
    }

    /** 页面加载占位/文字的前景色。 */
    fun contentColor(dark: Boolean): Color = when (this) {
        Black, Gray, System -> if (this == System && !dark) Color.Black else Color.White
        White -> Color.Black
        Sepia -> SepiaInk
    }

    companion object {
        private val SepiaPaper = Color(0xFFF4ECD8)
        private val SepiaInk = Color(0xFF5C4A37)
    }
}

/** 翻页过渡（对齐原作 PageTransitionStyle.availableCases：scroll/cover；pageCurl 在 Android 不做，计入差异清单）。 */
enum class PageTransitionStyle(val label: String) {
    Scroll("滚动"),
    Cover("覆盖");

    val next: PageTransitionStyle get() = entries[(ordinal + 1) % entries.size]
}

/** 点按区域布局（对齐原作 TapZoneMode 六档）。 */
enum class TapZoneMode(val label: String) {
    None("关闭"),
    Default("默认"),
    Edge("边缘"),
    Kindle("Kindle"),
    LShape("L 形"),
    TSplit("上下分栏");

    val isDisabled: Boolean get() = this == None
    val next: TapZoneMode get() = entries[(ordinal + 1) % entries.size]
}

/** 点按区域左右镜像（对齐原作 TapZoneInversionMode：normal/auto/inverted）。 */
enum class TapZoneInversion(val label: String) {
    Normal("正常"),
    Auto("自动"),
    Inverted("反向");

    fun isInverted(isRtl: Boolean): Boolean = when (this) {
        Normal -> false
        Auto -> isRtl
        Inverted -> true
    }
}

/** 双击动作（对齐原作 DoubleTapZoomMode：enabled/disabled）。 */
enum class DoubleTapZoomMode(val label: String) {
    Enabled("缩放"),
    Disabled("关闭")
}

/** 长按动作（对齐原作 ReaderLongPressAction：contextMenu/disabled）。 */
enum class LongPressAction(val label: String) {
    ContextMenu("图片菜单"),
    Disabled("关闭")
}

/** 图片预加载窗口（对齐原作 ReaderPreloadProfile 的三档窗口）。 */
enum class PreloadProfile(val label: String, val before: Int, val after: Int) {
    LowMemory("低内存", 1, 1),
    Balanced("均衡", 2, 2),
    FastPageTurns("快速翻页", 4, 4)
}

/** 裁边模式（对齐原作 ReaderPageBorderCropMode：disabled/conservative/aggressive）。 */
enum class BorderCropMode(val label: String, val maxFraction: Float, val tolerance: Int) {
    Disabled("关闭", 0f, 0),
    Conservative("保守", 0.08f, 12),
    Aggressive("激进", 0.20f, 24)
}

/**
 * DIVINA 阅读器设置（对齐原作 ReaderSettingsSheet 的 @AppStorage 键与默认值）。
 * 面板模式 / Waifu2x 超分 / Live Text 按项目红线不做，记录在 README 差异清单。
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
)

/** 点按结果（对齐原作 TapZoneAction）。 */
enum class TapZoneAction { Previous, Next, ToggleControls }

/**
 * 点按区域判定（逐条对齐原作 Models/TapZoneHelper.swift）：
 * 归一化坐标 → 动作；inversion 默认 auto（RTL 时左右镜像）。
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
