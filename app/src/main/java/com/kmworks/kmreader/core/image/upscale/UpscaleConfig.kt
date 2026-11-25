package com.kmworks.kmreader.core.image.upscale

import com.kmworks.kmreader.ui.reader.DenoiseLevel
import com.kmworks.kmreader.ui.reader.UpscaleMode
import com.kmworks.kmreader.ui.reader.UpscalePrecision

/**
 * Resolved upscaling configuration for one image request (docs/waifu2x-upscaling-plan.md §3.1).
 *
 * The EnhancementCache key is derived from two independent axes:
 *
 * - [MODEL_SIGNATURE] covers the model architecture, scale and bundled-model version. It is the
 *   stable, per-page part of the key, so bumping the bridge's `MODEL_CACHE_VERSION` invalidates
 *   every entry at once.
 * - [denoise] is a *variant* dimension of the same page rather than part of its identity: switching
 *   the level misses only the requested variant and can fall back to a still-cached one instead of
 *   re-running the whole book (see [EnhancementCache.cacheKey]).
 *
 * [precision] is deliberately in neither: it changes rounding, not content, and including it would
 * double every page's cache entry.
 */
data class UpscaleConfig(
    val mode: UpscaleMode = UpscaleMode.Auto,
    val denoise: DenoiseLevel = DenoiseLevel.Low,
    val autoTriggerScale: Float = DEFAULT_AUTO_TRIGGER_SCALE,
    val precision: UpscalePrecision = UpscalePrecision.Auto,
) {
    val enabled: Boolean get() = mode != UpscaleMode.Off

    companion object {
        /** Same default as the iOS ReaderUpscaleDecision.autoTriggerScale. */
        const val DEFAULT_AUTO_TRIGGER_SCALE = 1.5f

        /**
         * Cache-key component shared by every denoise variant of a page: the model architecture, the
         * scale and the bundled-model version. Denoise is *not* in here on purpose — see the class doc.
         */
        const val MODEL_SIGNATURE =
            "upconv7-s${Waifu2xBridge.SCALE}-mv${Waifu2xBridge.MODEL_CACHE_VERSION}"
    }
}
