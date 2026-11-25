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
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.ui.reader.DenoiseLevel
import com.kmworks.kmreader.ui.reader.UpscaleMode
import com.kmworks.kmreader.ui.reader.UpscalePrecision

/**
 * Coil decoder that upscales page images with waifu2x (docs/waifu2x-upscaling-plan.md §5.2).
 *
 * Registered only on the page loader, and only for requests marked by [applyTo], so covers,
 * thumbnails and plain page loads keep the default decoder. Flow:
 *
 * 1. decode bounds only
 * 2. [UpscaleDecision]: not worth it → plain sampled decode of the source
 * 3. [EnhancementCache] hit → return the cached enhanced bitmap
 * 4. miss, but another denoise variant of the page is cached → return that one immediately and
 *    regenerate the requested variant in the background (a denoise switch must not re-run the book)
 * 5. full-res decode → [ImageEnhancer] queue (priority from the request) → write cache
 * 6. any failure falls back to the source bitmap — enhancement must never break page display
 *
 * Diagnostics go to logcat and to the in-app log (tag "Upscale") so a device without adb
 * can still be inspected through the app's own Logs screen.
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
        val (srcWidth, srcHeight) = decodeBounds() ?: run {
            // Malformed / unsupported source: leave it to the default decoder's error reporting.
            warn("unreadable source, deferring to default decoder: $pageUrl")
            return null
        }

        val (targetWidth, targetHeight) = resolveTargetSize()
        val upscale = UpscaleDecision.shouldUpscale(
            srcWidth, srcHeight, targetWidth, targetHeight, config,
            maxTextureWidth = textureLimits.first,
            maxTextureHeight = textureLimits.second,
        )
        info(
            "decision: src=${srcWidth}x$srcHeight target=${targetWidth}x$targetHeight " +
                "mode=${config.mode} threshold=${config.autoTriggerScale} -> upscale=$upscale",
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

        val cacheKey = EnhancementCache.cacheKey(pageUrl, config.denoise)
        cache.read(cacheKey)?.let {
            info("cache hit (${it.width}x${it.height}): $pageUrl")
            return DecodeResult(it.asImage(), false)
        }

        // Stale-while-revalidate: the exact denoise variant is missing (the level was just changed, or
        // the bundled model was bumped), but another level of the same page is usually still on disk.
        // Serve it instead of paying a full native re-run for the whole book on the critical path, and
        // regenerate the requested variant in the background; ImageEnhancer.readyKeys swaps it in.
        val stale = cache.readNearest(pageUrl, config.denoise)
        if (stale != null) {
            info(
                "cache hit, stale denoise=${stale.denoise} for ${config.denoise} " +
                    "(${stale.bitmap.width}x${stale.bitmap.height}): $pageUrl",
            )
            if (!enhancer.isDisabled) {
                // The native input is the full-resolution source; decoding it here costs a few tens of
                // milliseconds against a ~10 s re-run, and it cannot be deferred past this frame anyway
                // because ImageSource is single-shot.
                decodeSample(1)?.let { sourceBitmap ->
                    requestEnhancement(cacheKey, sourceBitmap, config, priority, keepAlso = stale.denoise)
                }
            }
            return DecodeResult(stale.bitmap.asImage(), false)
        }

        // Enhancement input is the full-resolution source (plan §5.2): the 2x output IS the detail.
        val sourceBitmap = decodeSample(1) ?: run {
            warn("full-size decode failed, showing source page: $pageUrl")
            return null
        }
        // Do not hold the first frame hostage to a ~1 s native run: hand the source bitmap back
        // immediately and let the enhancer fill the cache. ReaderScreen watches
        // ImageEnhancer.readyKeys and re-requests this page, which then hits the cache.
        requestEnhancement(cacheKey, sourceBitmap, config, priority, keepAlso = null)
        return DecodeResult(sourceBitmap.asImage(), false)
    }

    /**
     * Queues the native enhancement of [sourceBitmap] under [cacheKey] and writes the result to the
     * cache. [keepAlso] is the variant that was served instead of the requested one: it stays on disk
     * so toggling the denoise level back and forth remains a hit.
     */
    private fun requestEnhancement(
        cacheKey: String,
        sourceBitmap: Bitmap,
        config: UpscaleConfig,
        priority: Int,
        keepAlso: DenoiseLevel?,
    ) {
        enhancer.prefetch(cacheKey, priority) {
            if (!Waifu2xBridge.ensureInitialized(context, config.denoise.bridgeModel, config.precision)) {
                warn("engine unavailable (${config.denoise.bridgeModel.modelSuffix})")
                return@prefetch null
            }
            val enhanced = Waifu2xBridge.process(sourceBitmap) ?: return@prefetch null
            cache.write(cacheKey, enhanced)
            // Bounded per-page footprint: the requested variant plus the one that was just served.
            cache.pruneVariants(
                pageUrl,
                buildSet {
                    add(config.denoise)
                    if (keepAlso != null) add(keepAlso)
                },
            )
            info("enhanced: ${sourceBitmap.width}x${sourceBitmap.height} -> ${enhanced.width}x${enhanced.height} ($pageUrl)")
            enhanced
        }
    }

    /** Device texture limits (0,0 = unknown), read once from a scratch software canvas. */
    private val textureLimits: Pair<Int, Int> by lazy {
        runCatching {
            val canvas = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            canvas.maximumBitmapWidth to canvas.maximumBitmapHeight
        }.getOrDefault(0 to 0)
    }

    /**
     * Backing bytes for repeated decodes. [ImageSource.source] can only be consumed once, so the
     * bounds pass and the real decode share one read: prefer the on-disk path Coil already has
     * (network fetches land in a temp file, disk-cache hits are files), and only buffer the bytes
     * when the source has no file at all.
     */
    private val sourcePath: String? by lazy {
        runCatching { source.file().toString() }.getOrNull()
    }

    private val sourceBytes: ByteArray? by lazy {
        if (sourcePath != null) {
            null
        } else {
            source.sourceOrNull()?.use { input -> runCatching { input.readByteArray() }.getOrNull() }
        }
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
        val bitmap = decodeSample(sample) ?: return null
        return DecodeResult(bitmap.asImage(), sample > 1)
    }

    private fun sampleSizeFor(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (srcWidth / (sample * 2) >= targetWidth && srcHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    private fun decodeBounds(): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { decode(opts) }
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    private fun decodeSample(sampleSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        return try {
            decode(opts)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "decode failed: $pageUrl (sample=$sampleSize)", e)
            AppLog.e(APP_TAG, "decode failed: $pageUrl (sample=$sampleSize)", e)
            null
        }
    }

    private fun decode(opts: BitmapFactory.Options): Bitmap? {
        val path = sourcePath
        return runCatching {
            if (path != null) {
                BitmapFactory.decodeFile(path, opts)
            } else {
                sourceBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
            }
        }.getOrElse { e ->
            warn("source decode failed (sample=${opts.inSampleSize}): $pageUrl (${e.message})")
            null
        }
    }

    private fun info(message: String) {
        Log.i(TAG, message)
        AppLog.i(APP_TAG, message)
    }

    private fun warn(message: String) {
        Log.w(TAG, message)
        AppLog.w(APP_TAG, message)
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
                precision = enumExtra(extras[KEY_PRECISION], UpscalePrecision.Auto),
            )
            val priority = extras[KEY_PRIORITY] ?: ImageEnhancer.PRIORITY_VISIBLE
            Log.i(TAG, "marked for upscaling: $pageUrl (mode=${config.mode}, denoise=${config.denoise}, priority=$priority)")
            AppLog.i(APP_TAG, "marked for upscaling (mode=${config.mode}, denoise=${config.denoise}, priority=$priority)")
            return EnhancementDecoder(result.source, pageUrl, config, priority, options, context, cache, enhancer)
        }
    }

    companion object {
        private const val TAG = "EnhancementDecoder"

        /** In-app log tag, so the Logs screen can filter upscaling diagnostics. */
        const val APP_TAG = "Upscale"

        /** Decode size for preloaded pages that do not need upscaling (result is discarded). */
        private const val PRELOAD_DECODE_TARGET = 512

        // Typed request extras (Coil 3 per-request storage); identity-shared with the request builder.
        private val KEY_ENABLED = Extras.Key(default = false)
        private val KEY_PAGE_URL = Extras.Key(default = "")
        private val KEY_MODE = Extras.Key(default = UpscaleMode.Auto.name)
        private val KEY_DENOISE = Extras.Key(default = DenoiseLevel.Low.name)
        private val KEY_AUTO_SCALE = Extras.Key(default = UpscaleConfig.DEFAULT_AUTO_TRIGGER_SCALE)
        private val KEY_PRECISION = Extras.Key(default = UpscalePrecision.Auto.name)
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
            builder.extras.set(KEY_PRECISION, config.precision.name)
            builder.extras.set(KEY_PRIORITY, priority)
        }
    }
}
