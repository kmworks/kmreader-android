package com.kmworks.kmreader.core.image.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import coil3.Extras
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Dimension
import com.kmworks.kmreader.ui.reader.DenoiseLevel
import com.kmworks.kmreader.ui.reader.UpscaleMode

/**
 * Coil decoder that upscales page images with waifu2x (docs/waifu2x-upscaling-plan.md §5.2).
 *
 * Registered only on the page loader, and only for requests carrying [parameters], so covers,
 * thumbnails and plain page loads keep the default decoder. Flow:
 *
 * 1. decode bounds only
 * 2. [UpscaleDecision]: not worth it → plain sampled decode of the source
 * 3. [EnhancementCache] hit → return the cached enhanced bitmap
 * 4. full-res decode → [ImageEnhancer] queue (priority from the request) → write cache
 * 5. any failure falls back to the source bitmap — enhancement must never break page display
 *
 * Note: the page request must NOT override `diskCacheKey` with the config hash — the source
 * entry in Coil's disk cache is keyed by plain URL so it stays shared with the disk preloader;
 * the config hash lives in the EnhancementCache key only.
 */
class EnhancementDecoder private constructor(
    private val source: ImageSource,
    private val pageUrl: String,
    private val config: UpscaleConfig,
    private val priority: Int,
    private val options: Options,
    private val context: Context,
    private val cache: EnhancementCache,
    private val enhancer: ImageEnhancer,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.source().inputStream().use { BitmapFactory.decodeStream(it, null, boundsOptions) }
        val srcWidth = boundsOptions.outWidth
        val srcHeight = boundsOptions.outHeight
        // Malformed / unsupported source: leave it to the default decoder's error reporting.
        if (srcWidth <= 0 || srcHeight <= 0) return null

        val (targetWidth, targetHeight) = resolveTargetSize()
        val upscale = UpscaleDecision.shouldUpscale(
            srcWidth, srcHeight, targetWidth, targetHeight, config,
            maxTextureWidth = textureLimits.first,
            maxTextureHeight = textureLimits.second,
        )
        if (!upscale) {
            // Preload requests only warm the disk source cache: decoding the full page here would
            // burn heap on a bitmap the sized display request would reject anyway (Precision.EXACT),
            // so a tiny decode is enough.
            return if (priority == ImageEnhancer.PRIORITY_PRELOAD) {
                decodeSampled(srcWidth, srcHeight, PRELOAD_DECODE_TARGET, PRELOAD_DECODE_TARGET)
            } else {
                decodeSampled(srcWidth, srcHeight, targetWidth, targetHeight)
            }
        }

        val cacheKey = EnhancementCache.cacheKey(pageUrl, config.configHash)
        cache.read(cacheKey)?.let { return DecodeResult(it.asImage(), false) }

        // Enhancement input is the full-resolution source (plan §5.2): the 2x output IS the detail.
        val sourceBitmap = decodeBitmap(sampleSize = 1) ?: return null
        val enhanced = enhancer.submit(cacheKey, priority) {
            if (!Waifu2xBridge.ensureInitialized(context, config.denoise.bridgeModel)) null
            else Waifu2xBridge.process(sourceBitmap)
        }
        if (enhanced != null) {
            cache.write(cacheKey, enhanced)
            return DecodeResult(enhanced.asImage(), false)
        }
        Log.i(TAG, "enhancement unavailable, showing source page: $pageUrl")
        return DecodeResult(sourceBitmap.asImage(), false)
    }

    /** Device texture limits (0,0 = unknown), read once from a scratch software canvas. */
    private val textureLimits: Pair<Int, Int> by lazy {
        runCatching {
            val canvas = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            canvas.maximumBitmapWidth to canvas.maximumBitmapHeight
        }.getOrDefault(0 to 0)
    }

    /** Request target in px; prefetch requests have no target size, so fall back to the display metrics. */
    private fun resolveTargetSize(): Pair<Int, Int> {
        val width = (options.size.width as? Dimension.Pixels)?.px?.takeIf { it > 0 }
        val height = (options.size.height as? Dimension.Pixels)?.px?.takeIf { it > 0 }
        val metrics = context.resources.displayMetrics
        return (width ?: metrics.widthPixels) to (height ?: metrics.heightPixels)
    }

    /** Plain Coil-style sampled decode for pages that stay below the trigger scale. */
    private fun decodeSampled(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): DecodeResult? {
        val sample = sampleSizeFor(srcWidth, srcHeight, targetWidth, targetHeight)
        val bitmap = decodeBitmap(sample) ?: return null
        return DecodeResult(bitmap.asImage(), sample > 1)
    }

    private fun sampleSizeFor(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (srcWidth / (sample * 2) >= targetWidth && srcHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    private fun decodeBitmap(sampleSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        return try {
            source.source().inputStream().use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "decode failed: $pageUrl (sample=$sampleSize)", e)
            null
        }
    }

    class Factory(
        private val context: Context,
        private val cache: EnhancementCache,
        private val enhancer: ImageEnhancer,
    ) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val extras = options.extras
            if (extras[KEY_ENABLED] != true) return null
            val pageUrl = extras[KEY_PAGE_URL] ?: return null
            val config = UpscaleConfig(
                mode = enumExtra(extras[KEY_MODE], UpscaleMode.Auto),
                denoise = enumExtra(extras[KEY_DENOISE], DenoiseLevel.Low),
                autoTriggerScale = extras[KEY_AUTO_SCALE] ?: UpscaleConfig.DEFAULT_AUTO_TRIGGER_SCALE,
            )
            val priority = extras[KEY_PRIORITY] ?: ImageEnhancer.PRIORITY_VISIBLE
            return EnhancementDecoder(result.source, pageUrl, config, priority, options, context, cache, enhancer)
        }
    }

    companion object {
        private const val TAG = "EnhancementDecoder"

        /** Decode size for preloaded pages that do not need upscaling (result is discarded). */
        private const val PRELOAD_DECODE_TARGET = 512

        // Typed request extras (Coil 3 per-request storage); identity-shared with the request builder.
        private val KEY_ENABLED = Extras.Key(default = false)
        private val KEY_PAGE_URL = Extras.Key(default = "")
        private val KEY_MODE = Extras.Key(default = UpscaleMode.Auto.name)
        private val KEY_DENOISE = Extras.Key(default = DenoiseLevel.Low.name)
        private val KEY_AUTO_SCALE = Extras.Key(default = UpscaleConfig.DEFAULT_AUTO_TRIGGER_SCALE)
        private val KEY_PRIORITY = Extras.Key(default = ImageEnhancer.PRIORITY_VISIBLE)

        private inline fun <reified T : Enum<T>> enumExtra(name: String?, fallback: T): T =
            if (name == null) fallback else enumValues<T>().firstOrNull { it.name == name } ?: fallback

        /** Marks [builder] as an upscaling page request and records the config for the decoder. */
        fun applyTo(builder: ImageRequest.Builder, config: UpscaleConfig, pageUrl: String, priority: Int) {
            builder.extras.set(KEY_ENABLED, true)
            builder.extras.set(KEY_PAGE_URL, pageUrl)
            builder.extras.set(KEY_MODE, config.mode.name)
            builder.extras.set(KEY_DENOISE, config.denoise.name)
            builder.extras.set(KEY_AUTO_SCALE, config.autoTriggerScale)
            builder.extras.set(KEY_PRIORITY, priority)
        }
    }
}
