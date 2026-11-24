package com.kmworks.kmreader.core.image.upscale

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * JNI bridge to the ncnn+Vulkan waifu2x engine (docs/waifu2x-upscaling-plan.md §3.1).
 *
 * Models ship in assets and are extracted to the cache dir on first use; bump
 * [MODEL_CACHE_VERSION] whenever the bundled models change so stale extractions
 * are refreshed. All calls are serialized through [mutex] because the ncnn Net
 * instance is not thread-safe and the GPU has a single compute queue anyway.
 */
object Waifu2xBridge {

    const val SCALE = 2

    private const val TAG = "Waifu2xBridge"
    private const val MODEL_ASSET_DIR = "upscale-models/waifu2x-upconv7"

    /** Public because [UpscaleConfig.configHash] embeds it so cache entries self-invalidate. */
    const val MODEL_CACHE_VERSION = 1

    /** Denoise level → bundled model weight file suffix. Order matches the plan's DenoiseLevel enum. */
    enum class DenoiseModel(val modelSuffix: String) {
        NONE("scale2.0x"),
        LOW("noise0_scale2.0x"),
        MEDIUM("noise1_scale2.0x"),
        HIGH("noise2_scale2.0x"),
        MAX("noise3_scale2.0x"),
    }

    private val mutex = Mutex()

    @Volatile
    private var handle: Long = 0

    @Volatile
    private var activeModel: DenoiseModel? = null

    @Volatile
    var isVulkan: Boolean = false
        private set

    val isReady: Boolean get() = handle != 0L

    init {
        System.loadLibrary("waifu2x-jni")
    }

    /**
     * Ensures the engine is initialized for [model], re-initializing when the denoise
     * level changes. Returns false when the engine/model failed to load — callers must
     * treat that as "upscaling unavailable" and fall back to the source image.
     */
    suspend fun ensureInitialized(context: Context, model: DenoiseModel): Boolean = mutex.withLock {
        if (handle != 0L && activeModel == model) return@withLock true
        releaseLocked()
        val dir = try {
            extractModels(context)
        } catch (e: IOException) {
            Log.e(TAG, "model extraction failed", e)
            return@withLock false
        }
        val h = nativeInit(dir.absolutePath, model.modelSuffix)
        if (h == 0L) {
            Log.e(TAG, "nativeInit failed for ${model.modelSuffix}")
            return@withLock false
        }
        handle = h
        activeModel = model
        isVulkan = nativeIsVulkan(h)
        Log.i(TAG, "initialized: ${model.modelSuffix}, vulkan=$isVulkan")
        true
    }

    /**
     * Upscales [input] (any config; converted to ARGB_8888 internally) by [SCALE].
     * Must be called after [ensureInitialized] returned true. Returns null on failure.
     */
    suspend fun process(input: Bitmap): Bitmap? = mutex.withLock {
        val h = handle
        if (h == 0L) return@withLock null
        val argb = if (input.config == Bitmap.Config.ARGB_8888) {
            input
        } else {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return@withLock null
        val output = try {
            Bitmap.createBitmap(argb.width * SCALE, argb.height * SCALE, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "output bitmap allocation failed: ${argb.width * SCALE}x${argb.height * SCALE}", e)
            return@withLock null
        }
        if (!nativeProcess(h, argb, output)) {
            output.recycle()
            return@withLock null
        }
        output
    }

    suspend fun release() = mutex.withLock { releaseLocked() }

    private fun releaseLocked() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
            activeModel = null
            isVulkan = false
        }
    }

    /**
     * Copies the bundled models into the cache dir. Skipped entirely when the version
     * marker matches, so steady-state cost is one file-exists check.
     */
    private fun extractModels(context: Context): File {
        val dir = File(context.cacheDir, "upscale-models/$MODEL_ASSET_DIR")
        val marker = File(dir, ".v$MODEL_CACHE_VERSION")
        if (marker.exists()) return dir

        val assetManager = context.assets
        val names = assetManager.list(MODEL_ASSET_DIR)
            ?: throw IOException("asset dir $MODEL_ASSET_DIR not found")
        dir.deleteRecursively()
        dir.mkdirs()
        for (name in names) {
            assetManager.open("$MODEL_ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        marker.writeText("ok")
        Log.i(TAG, "models extracted: ${names.size} files (v$MODEL_CACHE_VERSION)")
        return dir
    }

    private external fun nativeInit(modelDir: String, modelSuffix: String): Long
    private external fun nativeProcess(handle: Long, input: Bitmap, output: Bitmap): Boolean
    private external fun nativeIsVulkan(handle: Long): Boolean
    private external fun nativeRelease(handle: Long)
}
