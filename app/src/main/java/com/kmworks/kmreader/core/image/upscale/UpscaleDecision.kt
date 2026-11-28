package com.kmworks.kmreader.core.image.upscale

import com.kmworks.kmreader.ui.reader.UpscaleMode
import kotlin.math.min

/**
 * Pure trigger decision, mirroring the iOS ReaderUpscaleDecision semantics:
 * upscale only when a low-resolution source
 * would be enlarged for display — the point is to add detail to content the screen
 * must scale up anyway, not to reprocess already-crisp pages.
 */
object UpscaleDecision {

    /**
     * @param srcWidth/srcHeight decoded source dimensions
     * @param screenWidth/screenHeight display target size in px
     * @param maxTextureWidth/maxTextureHeight device texture limits (0 = unknown, skips the guard)
     */
    fun shouldUpscale(
        srcWidth: Int,
        srcHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        config: UpscaleConfig,
        maxTextureWidth: Int = 0,
        maxTextureHeight: Int = 0,
    ): Boolean {
        if (srcWidth <= 0 || srcHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return false

        return when (config.mode) {
            UpscaleMode.Off -> false
            UpscaleMode.Always -> fitsTexture(srcWidth, srcHeight, maxTextureWidth, maxTextureHeight)
            UpscaleMode.Auto ->
                fitsTexture(srcWidth, srcHeight, maxTextureWidth, maxTextureHeight) &&
                    min(screenWidth.toFloat() / srcWidth, screenHeight.toFloat() / srcHeight) >
                    config.autoTriggerScale
        }
    }

    /** A 2x output that cannot fit a texture would be downscaled on draw, destroying the gain (plan §9). */
    private fun fitsTexture(srcWidth: Int, srcHeight: Int, maxTextureWidth: Int, maxTextureHeight: Int): Boolean {
        if (maxTextureWidth > 0 && srcWidth * Waifu2xBridge.SCALE > maxTextureWidth) return false
        if (maxTextureHeight > 0 && srcHeight * Waifu2xBridge.SCALE > maxTextureHeight) return false
        return true
    }
}
