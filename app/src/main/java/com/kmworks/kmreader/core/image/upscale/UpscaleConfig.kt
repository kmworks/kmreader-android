package com.kmworks.kmreader.core.image.upscale

import com.kmworks.kmreader.ui.reader.DenoiseLevel
import com.kmworks.kmreader.ui.reader.UpscaleMode
import com.kmworks.kmreader.ui.reader.UpscalePrecision

/**
 * Resolved upscaling configuration for one image request (docs/waifu2x-upscaling-plan.md §3.1).
 *
 * [configHash] feeds the EnhancementCache key: it covers the model architecture, scale, denoise
 * weights and the bundled-model cache version, so any configuration change automatically misses
 * old cache entries — no manual cache purge is ever needed. [precision] is deliberately excluded:
 * it changes rounding, not content, and including it would double every page's cache entry.
 */
data class UpscaleConfig(
    val mode: UpscaleMode = UpscaleMode.Auto,
    val denoise: DenoiseLevel = DenoiseLevel.Low,
    val autoTriggerScale: Float = DEFAULT_AUTO_TRIGGER_SCALE,
    val precision: UpscalePrecision = UpscalePrecision.Auto,
) {
    val enabled: Boolean get() = mode != UpscaleMode.Off

    val configHash: String get() = configHashFor(denoise)

    companion object {
        /** Same default as the iOS ReaderUpscaleDecision.autoTriggerScale. */
        const val DEFAULT_AUTO_TRIGGER_SCALE = 1.5f

        /** Cache-key component for [denoise] alone (used to evict every variant of a page). */
        fun configHashFor(denoise: DenoiseLevel): String =
            "upconv7-s${Waifu2xBridge.SCALE}-${denoise.name}-mv${Waifu2xBridge.MODEL_CACHE_VERSION}"
    }
}
