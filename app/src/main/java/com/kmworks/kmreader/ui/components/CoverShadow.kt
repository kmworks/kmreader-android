package com.kmworks.kmreader.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cover drop shadow, mirroring KMReader's `ShadowStyle` / `ShadowImageView`.
 *
 * iOS does not use `View.shadow`: it pre-renders one small stretchable shadow image per
 * (style, cornerRadius, colourScheme) and draws it behind every card, because a per-card
 * Core Graphics blur dominates scroll cost in a grid. This is the same implementation, on
 * Android: the blur is baked once into a bitmap and drawn as nine stretched patches, so a
 * cover never pays for a real-time blur at draw time.
 */
enum class CoverShadowStyle {
    /** No shadow. */
    None,

    /** One tight layer, no offset: the default for detail heroes. */
    Basic,

    /** Two layers (wide and far) offset downwards: grid cards and horizontal card covers. */
    Platform,
}

/** One pre-rendered shadow layer, in dp, mirroring `ShadowImageDescriptor.Layer`. */
private data class ShadowLayer(val alpha: Float, val blurDp: Float, val yDp: Float)

/** `shadowNear` / `shadowFar` from the asset catalogue: black on light, a 4 %/2 % white tint on dark. */
private val ShadowNearLight = Color.Black.copy(alpha = 0.40f)
private val ShadowNearDark = Color.White.copy(alpha = 0.04f)
private val ShadowFarLight = Color.Black.copy(alpha = 0.10f)
private val ShadowFarDark = Color.White.copy(alpha = 0.02f)

/** Mirrors the `CoverShape` radius; the shadow silhouette has to match the cover's own clip. */
internal val CoverShadowRadius = 8.dp

/**
 * Layer values from `ShadowImageView`: `.basic` is a single 2.5 pt blur with no offset, and
 * `.platform` stacks an 8 pt/4 pt layer under a 20 pt/8 pt one.
 */
private fun layersFor(style: CoverShadowStyle, dark: Boolean): List<ShadowLayer> {
    val near = ShadowLayer(
        alpha = if (dark) ShadowNearDark.alpha else ShadowNearLight.alpha,
        blurDp = if (style == CoverShadowStyle.Platform) 8f else 2.5f,
        yDp = if (style == CoverShadowStyle.Platform) 4f else 0f,
    )
    val far = ShadowLayer(
        alpha = if (dark) ShadowFarDark.alpha else ShadowFarLight.alpha,
        blurDp = 20f,
        yDp = 8f,
    )
    return when (style) {
        CoverShadowStyle.None -> emptyList()
        CoverShadowStyle.Basic -> listOf(near)
        CoverShadowStyle.Platform -> listOf(far, near)
    }
}

/** How far the shadow reaches past the cover edge: widest blur plus its offset, plus a point of slack. */
private fun marginDp(layers: List<ShadowLayer>): Float =
    layers.maxOfOrNull { it.blurDp + kotlin.math.abs(it.yDp) }?.plus(2f) ?: 0f

/** A rendered shadow patch plus how much of it is the un-stretched corner region. */
internal class CoverShadowPatch(
    val image: ImageBitmap,
    /** Pixels of the patch edge that must be drawn at 1:1 (corner curve plus the blur margin). */
    val cornerPx: Int,
)

/**
 * Renders the shadow layers into one bitmap. The straight edges of a rounded rectangle's blur
 * are translation-invariant, so only the corner region has to be kept pixel-exact — everything
 * between them can be stretched, exactly like iOS's `capInsets` stretch.
 */
private fun renderPatch(
    style: CoverShadowStyle,
    dark: Boolean,
    density: Float,
    radiusPx: Float,
): CoverShadowPatch {
    val layers = layersFor(style, dark)
    val marginPx = (marginDp(layers) * density).roundToInt()
    val coreSide = (2 * radiusPx).roundToInt() + ShadowStretchMiddlePx
    val side = 2 * marginPx + coreSide
    val bitmap = Bitmap.createBitmap(max(side, 1), max(side, 1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val core = RectF(
        marginPx.toFloat(),
        marginPx.toFloat(),
        (marginPx + coreSide).toFloat(),
        (marginPx + coreSide).toFloat(),
    )
    layers.forEach { layer ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // The core itself is opaque: it sits exactly under the cover and only carries the shadow.
        paint.color = android.graphics.Color.BLACK
        paint.setShadowLayer(
            layer.blurDp * density,
            0f,
            layer.yDp * density,
            (if (dark) Color.White else Color.Black).copy(alpha = layer.alpha).toArgb(),
        )
        canvas.drawRoundRect(core, radiusPx, radiusPx, paint)
        paint.reset()
    }
    // Punch the core out again: it only ever existed to cast the layers' shadows, and an opaque core is
    // visible as a black slab whenever a cover has not painted yet (a missing local cover in offline mode).
    val clear = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    canvas.drawRoundRect(core, radiusPx, radiusPx, clear)
    return CoverShadowPatch(bitmap.asImageBitmap(), cornerPx = marginPx + radiusPx.roundToInt())
}

/** Copy of the bitmap used for the stretchable middle of the patch. */
private const val ShadowStretchMiddlePx = 8

private const val MAX_CACHED_PATCHES = 6

private object CoverShadowCache {
    private val entries = LinkedHashMap<String, CoverShadowPatch>()

    fun get(style: CoverShadowStyle, dark: Boolean, density: Float): CoverShadowPatch {
        val key = "${style.name}#${dark}#${density}"
        entries[key]?.let { return it }
        val patch = renderPatch(
            style = style,
            dark = dark,
            density = density,
            radiusPx = CoverShadowRadius.value * density,
        )
        entries[key] = patch
        if (entries.size > MAX_CACHED_PATCHES) {
            entries.remove(entries.keys.first())
        }
        return patch
    }
}

/**
 * Draws the shadow behind the cover. Applied to a cover-sized layer: the shadow spills
 * [marginDp] past every edge, and no ancestor may clip it (that shear is what turned a
 * letterboxed cover's bottom shadow into a straight edge).
 */
@Composable
internal fun Modifier.coverShadow(style: CoverShadowStyle): Modifier {
    if (style == CoverShadowStyle.None) return this
    // Match the rendered theme rather than the device setting: the app can force light or dark.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val patch = CoverShadowCache.get(style, dark, density)
    val marginPx = (marginDp(layersFor(style, dark)) * density).roundToInt()
    return drawBehind {
        drawShadowPatch(patch, marginPx)
    }
}

/**
 * Nine-patch draw: four corner blocks at 1:1, the four edges and the middle stretched.
 * A cover narrower than two corner blocks (a very small thumbnail) falls back to one stretch.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShadowPatch(
    patch: CoverShadowPatch,
    marginPx: Int,
) {
    val image = patch.image
    val srcCorner = patch.cornerPx
    val srcMiddle = image.width - 2 * srcCorner
    if (srcMiddle <= 0 || srcCorner <= 0) {
        drawImage(
            image = image,
            dstOffset = IntOffset(-marginPx, -marginPx),
            dstSize = IntSize(
                (size.width + 2 * marginPx).roundToInt(),
                (size.height + 2 * marginPx).roundToInt(),
            ),
            filterQuality = FilterQuality.Low,
        )
        return
    }
    val dstCorner = srcCorner
    val dstWidth = (size.width + 2 * marginPx).roundToInt()
    val dstHeight = (size.height + 2 * marginPx).roundToInt()
    if (dstWidth <= 2 * dstCorner || dstHeight <= 2 * dstCorner) {
        drawImage(
            image = image,
            dstOffset = IntOffset(-marginPx, -marginPx),
            dstSize = IntSize(dstWidth, dstHeight),
            filterQuality = FilterQuality.Low,
        )
        return
    }
    val dstMiddleW = dstWidth - 2 * dstCorner
    val dstMiddleH = dstHeight - 2 * dstCorner
    // Column/row spans: [0, srcCorner) 1:1, the middle stretched, then the trailing corner 1:1.
    val srcXs = intArrayOf(0, srcCorner, srcCorner + srcMiddle, image.width)
    val dstXs = intArrayOf(0, dstCorner, dstCorner + dstMiddleW, dstWidth)
    val srcYs = intArrayOf(0, srcCorner, srcCorner + (image.height - 2 * srcCorner), image.height)
    val dstYs = intArrayOf(0, dstCorner, dstCorner + dstMiddleH, dstHeight)
    for (row in 0 until 3) {
        val srcH = srcYs[row + 1] - srcYs[row]
        val dstH = dstYs[row + 1] - dstYs[row]
        if (srcH <= 0 || dstH <= 0) continue
        for (col in 0 until 3) {
            val srcW = srcXs[col + 1] - srcXs[col]
            val dstW = dstXs[col + 1] - dstXs[col]
            if (srcW <= 0 || dstW <= 0) continue
            drawImage(
                image = image,
                srcOffset = IntOffset(srcXs[col], srcYs[row]),
                srcSize = IntSize(srcW, srcH),
                dstOffset = IntOffset(-marginPx + dstXs[col], -marginPx + dstYs[row]),
                dstSize = IntSize(dstW, dstH),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

/** Exposed for previews/tests: the dp margin each style reaches past the cover. */
internal fun coverShadowMarginDp(style: CoverShadowStyle): Float = marginDp(layersFor(style, dark = false))
