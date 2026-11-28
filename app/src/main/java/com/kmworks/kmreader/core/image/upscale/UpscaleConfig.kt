package com.kmworks.kmreader.core.image.upscale

import com.kmworks.kmreader.ui.reader.DenoiseLevel
import com.kmworks.kmreader.ui.reader.UpscaleMode
import com.kmworks.kmreader.ui.reader.UpscalePrecision

/**
 * Resolved upscaling configuration for one image request.
 *
 * The EnhancementCache key is derived from two independent axes:
 *
 * - [MODEL_SIGNATURE] covers the model architecture, scale, bundled-model version and the native
 *   output implementation version. It is the stable, per-page part of the key, so changing any of
 *   those invalidates every entry at once.
 * - [denoise] is a *variant* dimension of the same page rather than part of its identity: switching
 *   the level misses only the requested variant and can fall back to a still-cached one instead of
 *   re-running the whole book (see [EnhancementCache.cacheKey]).
 * - [precision] is also a variant dimension, deliberately isolated: FP16 is an optimization that a
 *   buggy driver can make visibly wrong, and the FP32 switch exists to escape exactly that. Serving
 *   an FP16 result to an FP32 request would keep replaying the corrupted page forever.
 */
data class UpscaleConfig(
    val mode: UpscaleMode = UpscaleMode.Auto,
    val denoise: DenoiseLevel = DenoiseLevel.Low,
    val autoTriggerScale: Float = DEFAULT_AUTO_TRIGGER_SCALE,
    val precision: UpscalePrecision = UpscalePrecision.Auto,
) {
    val enabled: Boolean get() = mode != UpscaleMode.Off

    companion object {
        /**
         * Default Auto trigger threshold, mirroring iOS `AppConfig.imageUpscaleAutoTriggerScale`:
         * its getter returns 1.05 when nothing is stored, and its setter clamps to >= 1.0.
         *
         * The threshold is the *smaller* of the two display ratios
         * (see [UpscaleDecision.shouldUpscale]), so 1.05 means "the screen enlarges this source in
         * both directions", which is the point where a 2x pass starts adding detail that the user
         * can actually see. It used to be 1.5 — that is the *upper end* of the iOS slider
         * (`in: 1.0...1.5`), not its default, and at 1.5 almost every ordinary page was skipped.
         */
        const val DEFAULT_AUTO_TRIGGER_SCALE = 1.05f

        /** The iOS slider's range, kept identical so the same numbers mean the same thing. */
        const val MIN_AUTO_TRIGGER_SCALE = 1f
        const val MAX_AUTO_TRIGGER_SCALE = 1.5f

        /**
         * Clamps a stored or user-picked threshold into the iOS range. A non-finite value (a
         * corrupt preference) falls back to the default instead of poisoning the decision.
         */
        fun clampAutoTriggerScale(value: Float): Float =
            if (!value.isFinite()) DEFAULT_AUTO_TRIGGER_SCALE
            else value.coerceIn(MIN_AUTO_TRIGGER_SCALE, MAX_AUTO_TRIGGER_SCALE)

        /**
         * Bumped whenever the native path that turns a source page into pixels changes (tiling,
         * fused shaders, output conversion). Unlike the model version this says nothing about the
         * bundled weights, but it still has to invalidate every cached output they produced.
         */
        const val OUTPUT_VERSION = 2

        /**
         * Cache-key component shared by every denoise variant of a page: the model architecture, the
         * scale, the bundled-model version and the native output implementation. Denoise and
         * precision are *not* in here on purpose — they are variants of the same page identity.
         */
        const val MODEL_SIGNATURE =
            "upconv7-s${Waifu2xBridge.SCALE}-mv${Waifu2xBridge.MODEL_CACHE_VERSION}-ov$OUTPUT_VERSION"
    }
}
