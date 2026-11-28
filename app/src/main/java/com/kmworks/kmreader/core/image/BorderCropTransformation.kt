package com.kmworks.kmreader.core.image

import android.graphics.Bitmap
import androidx.core.graphics.get
import coil3.size.Size
import coil3.transform.Transformation
import com.kmworks.kmreader.ui.reader.BorderCropMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Border crop (mirrors the conservative/aggressive tiers of the original ReaderPageBorderCropMode):
 * takes the average of the four corner pixels as the page background color, then shrinks inward from the
 * four edges while whole rows/columns stay close to that color.
 * Crops at most [BorderCropMode.maxFraction] of each edge and never below half the original area, so
 * actual artwork is not cut away.
 */
class BorderCropTransformation(private val mode: BorderCropMode) : Transformation() {

    override val cacheKey: String = "borderCrop-${mode.name}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (mode == BorderCropMode.Disabled) return input
        val width = input.width
        val height = input.height
        if (width < 16 || height < 16) return input

        val tolerance = mode.tolerance
        val bg = cornerColor(input)
        val maxCropX = (width * mode.maxFraction).toInt()
        val maxCropY = (height * mode.maxFraction).toInt()

        fun rowMatches(y: Int): Boolean {
            val step = max(1, width / 48)
            var x = 0
            while (x < width) {
                if (!closeTo(input[x, y], bg, tolerance)) return false
                x += step
            }
            return true
        }

        fun columnMatches(x: Int): Boolean {
            val step = max(1, height / 48)
            var y = 0
            while (y < height) {
                if (!closeTo(input[x, y], bg, tolerance)) return false
                y += step
            }
            return true
        }

        var top = 0
        while (top < maxCropY && rowMatches(top)) top++
        var bottom = 0
        while (bottom < maxCropY && rowMatches(height - 1 - bottom)) bottom++
        var left = 0
        while (left < maxCropX && columnMatches(left)) left++
        var right = 0
        while (right < maxCropX && columnMatches(width - 1 - right)) right++

        // Conservatively pull back 1px per edge so no sliver of background color stays on the page
        top = max(0, top - 1)
        bottom = max(0, bottom - 1)
        left = max(0, left - 1)
        right = max(0, right - 1)

        val newWidth = width - left - right
        val newHeight = height - top - bottom
        if (newWidth < width / 2 || newHeight < height / 2) return input
        if (left == 0 && top == 0 && right == 0 && bottom == 0) return input
        return Bitmap.createBitmap(input, left, top, newWidth, newHeight)
    }

    private fun cornerColor(bitmap: Bitmap): Int {
        val xs = intArrayOf(0, bitmap.width - 1)
        val ys = intArrayOf(0, bitmap.height - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var a = 0L
        for (x in xs) for (y in ys) {
            val c = bitmap[x, y]
            a += (c ushr 24) and 0xFF
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        val n = 4L
        return ((((a / n).toInt()) and 0xFF) shl 24) or
            ((((r / n).toInt()) and 0xFF) shl 16) or
            ((((g / n).toInt()) and 0xFF) shl 8) or
            ((b / n).toInt() and 0xFF)
    }

    private fun closeTo(color: Int, bg: Int, tolerance: Int): Boolean =
        abs(((color ushr 24) and 0xFF) - ((bg ushr 24) and 0xFF)) <= tolerance &&
            abs(((color shr 16) and 0xFF) - ((bg shr 16) and 0xFF)) <= tolerance &&
            abs(((color shr 8) and 0xFF) - ((bg shr 8) and 0xFF)) <= tolerance &&
            abs((color and 0xFF) - (bg and 0xFF)) <= tolerance

    override fun equals(other: Any?): Boolean =
        other is BorderCropTransformation && other.mode == mode

    override fun hashCode(): Int = mode.hashCode() * 31 + 7

    @Suppress("unused")
    private fun clamp(v: Int, lo: Int, hi: Int): Int = min(max(v, lo), hi)
}
