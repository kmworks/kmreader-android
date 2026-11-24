// waifu2x (upconv7) upscaling via ncnn + Vulkan.
// Ported/trimmed from nihui/waifu2x-ncnn-vulkan (MIT) as referenced by
// HaoweiLi97/mihon_img_upscale (Apache-2.0). See docs/waifu2x-upscaling-plan.md.
//
// Simplifications vs upstream: no custom preproc/postproc compute shaders — pixel
// conversion runs on CPU while only the network inference is on Vulkan.
// Alpha is forced to opaque (same as the iOS implementation).
//
// Memory model: tiles are sampled straight out of the source bitmap and results are
// written straight into the output bitmap, so the peak cost is one 156x156 float tile
// per step. A full-page float Mat would be ~48 bytes per source pixel (100 MB+ for a
// 1200x1800 page) and gets the app killed under memory pressure.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include "net.h"
#include "gpu.h"
#include "cpu.h"
#include "mat.h"

#define TAG "Waifu2xJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// waifu2x upconv7 constants (docs/waifu2x-upscaling-plan.md §9)
constexpr int kScale = 2;
constexpr int kTileSize = 128;
// upconv7 receptive field; equivalent to shrinkSize=7 in the iOS Core ML implementation.
// (mihon's prepadding=18 is Real-CUGAN specific and does not apply here.)
constexpr int kPrepadding = 7;

constexpr const char* kInputBlob = "Input1";
constexpr const char* kOutputBlob = "Eltwise4";

/**
 * Last failure, readable from Kotlin via nativeLastError(). Native logs only reach logcat, so
 * without this an "upscaling did nothing" report is undiagnosable on a device with no adb —
 * the Kotlin side copies this text into the in-app log. Calls are serialized by the bridge's
 * mutex, but the read happens on the JNI caller thread, hence a plain guarded string.
 */
std::mutex g_error_mutex;
std::string g_last_error;

/** Timing summary of the last run, readable from Kotlin via nativeLastStats(). */
std::string g_last_stats;

void set_error(const char* fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        g_last_error = buf;
    }
    LOGE("%s", buf);
}

std::once_flag g_gpu_once;
bool g_gpu_ok = false;

void ensure_gpu_instance() {
    std::call_once(g_gpu_once, [] {
        g_gpu_ok = ncnn::create_gpu_instance() == 0 && ncnn::get_gpu_count() > 0;
        LOGI("ncnn gpu instance: %s, device count: %d", g_gpu_ok ? "ok" : "unavailable",
             ncnn::get_gpu_count());
    });
}

struct Waifu2xContext {
    ncnn::Net net;
    bool use_vulkan = false;
    /** FP16 storage halves intermediate bandwidth; falls back to FP32 when the driver chokes. */
    bool use_fp16 = false;
    bool use_bf16 = false;
    std::string param_path;
    std::string bin_path;

    ~Waifu2xContext() { net.clear(); }
};

inline unsigned char to_u8(float v) {
    const int i = static_cast<int>(std::lround(v * 255.f));
    return static_cast<unsigned char>(std::clamp(i, 0, 255));
}

/**
 * Samples the padded tile region for tile (xi0,yi0)-(xi1,yi1) out of the source RGBA buffer,
 * replicating edge pixels, and normalizes to 0..1 floats. Size is
 * (xi1-xi0+2*prepad) x (yi1-yi0+2*prepad), 3 channels.
 */
ncnn::Mat make_tile(const unsigned char* rgba, int stride, int w, int h,
                    int xi0, int yi0, int xi1, int yi1) {
    const int tw = (xi1 - xi0) + 2 * kPrepadding;
    const int th = (yi1 - yi0) + 2 * kPrepadding;
    ncnn::Mat tile(tw, th, 3, (size_t)sizeof(float));
    if (tile.empty()) return tile;

    float* r = tile.channel(0);
    float* g = tile.channel(1);
    float* b = tile.channel(2);
    for (int ty = 0; ty < th; ty++) {
        const int sy = std::clamp(yi0 - kPrepadding + ty, 0, h - 1);
        const unsigned char* src = rgba + (size_t)sy * stride;
        for (int tx = 0; tx < tw; tx++) {
            const int sx = std::clamp(xi0 - kPrepadding + tx, 0, w - 1);
            const unsigned char* px = src + (size_t)sx * 4;
            r[tx] = px[0] / 255.f;
            g[tx] = px[1] / 255.f;
            b[tx] = px[2] / 255.f;
        }
        r += tile.w;
        g += tile.w;
        b += tile.w;
    }
    return tile;
}

/** Writes the scaled tile into the output RGBA buffer at (dx,dy). */
void write_tile(const ncnn::Mat& out_tile, unsigned char* rgba, int stride,
                int dx, int dy, int w, int h) {
    // No output crop: the network's valid convolutions already consume the padding. Feeding a
    // (tile + 2*7) input yields exactly a (2*tile) output whose pixels correspond 1:1 to the
    // tile — see expected geometry in process_tile().
    const int sx = 0;
    const int sy = 0;
    const float* r = out_tile.channel(0).row(sy) + sx;
    const float* g = out_tile.channel(1).row(sy) + sx;
    const float* b = out_tile.channel(2).row(sy) + sx;
    for (int y = 0; y < h; y++) {
        unsigned char* p = rgba + (size_t)(dy + y) * stride + (size_t)dx * 4;
        for (int x = 0; x < w; x++) {
            p[0] = to_u8(r[x]);
            p[1] = to_u8(g[x]);
            p[2] = to_u8(b[x]);
            p[3] = 255;
            p += 4;
        }
        r += out_tile.w;
        g += out_tile.w;
        b += out_tile.w;
    }
}

/**
 * Vulkan may hand back the result as fp16/bf16 (2 bytes per element) and/or channel-packed.
 * Both would make the float* row arithmetic above invalid, so normalize to a plain fp32 Mat
 * before anything reads pixels.
 */
bool normalize_output(const Waifu2xContext* ctx, ncnn::Mat& out_tile) {
    if (out_tile.elempack > 0 && out_tile.elemsize / out_tile.elempack == 2) {
        ncnn::Mat f32;
        if (ctx->use_bf16) {
            ncnn::cast_bfloat16_to_float32(out_tile, f32, ctx->net.opt);
        } else if (ctx->use_fp16) {
            ncnn::cast_float16_to_float32(out_tile, f32, ctx->net.opt);
        } else {
            set_error("2-byte output without fp16/bf16 enabled (elemsize=%zu elempack=%d)",
                      out_tile.elemsize, out_tile.elempack);
            return false;
        }
        if (f32.empty()) {
            set_error("half->float32 conversion failed (elemsize=%zu elempack=%d)",
                      out_tile.elemsize, out_tile.elempack);
            return false;
        }
        out_tile = f32;
    }
    if (out_tile.elempack != 1) {
        ncnn::Mat unpacked;
        ncnn::convert_packing(out_tile, unpacked, 1, ctx->net.opt);
        if (unpacked.empty()) {
            set_error("convert_packing failed (elempack=%d)", out_tile.elempack);
            return false;
        }
        out_tile = unpacked;
    }
    return true;
}

/** Loads param+model into a fresh net with the given precision; returns false on failure. */
bool load_net(Waifu2xContext* ctx, bool fp16) {
    ncnn::Option& opt = ctx->net.opt;
    // Precision follows mihon_img_upscale: FP16 *storage* halves intermediate bandwidth, but
    // FP16 arithmetic is left off because some Adreno drivers produce the "flower screen" bug.
    opt.use_fp16_packed = fp16;
    opt.use_fp16_storage = fp16;
    opt.use_fp16_arithmetic = false;
    opt.use_bf16_storage = false;
    opt.use_bf16_packed = false;
    opt.use_int8_inference = false;
    opt.use_int8_packed = false;
    opt.use_int8_storage = false;
    opt.use_int8_arithmetic = false;
    // Free performance knobs (upstream enables all of them).
    opt.use_packing_layout = true;
    opt.use_sgemm_convolution = true;
    opt.use_winograd_convolution = true;
    opt.use_local_pool_allocator = true;
    opt.use_shader_local_memory = true;
    // Leave cores free for the UI: a blocked reader step costs more than an extra inference thread.
    opt.num_threads = std::clamp(ncnn::get_big_cpu_count() - 1, 1, 3);

    if (ctx->use_vulkan) {
        opt.use_vulkan_compute = true;
        ctx->net.set_vulkan_device(ncnn::get_default_gpu_index());
    }

    if (ctx->net.load_param(ctx->param_path.c_str()) != 0) {
        set_error("load_param failed: %s", ctx->param_path.c_str());
        return false;
    }
    if (ctx->net.load_model(ctx->bin_path.c_str()) != 0) {
        set_error("load_model failed: %s", ctx->bin_path.c_str());
        return false;
    }
    ctx->use_fp16 = fp16;
    return true;
}

/**
 * Runs a tiny tile through the engine and sanity-checks the result. Doubles as shader
 * warm-up: without it the first real tile pays pipeline compilation and looks like a stall.
 */
bool run_selftest(Waifu2xContext* ctx) {
    const auto t0 = std::chrono::steady_clock::now();
    const int t = 32;
    std::vector<unsigned char> rgba(static_cast<size_t>(t) * t * 4);
    for (int y = 0; y < t; y++) {
        for (int x = 0; x < t; x++) {
            unsigned char* p = rgba.data() + (static_cast<size_t>(y) * t + x) * 4;
            p[0] = static_cast<unsigned char>(x * 8);
            p[1] = static_cast<unsigned char>(y * 8);
            p[2] = static_cast<unsigned char>((x + y) * 4);
            p[3] = 255;
        }
    }

    ncnn::Mat in_tile = make_tile(rgba.data(), t * 4, t, t, 0, 0, t, t);
    if (in_tile.empty()) {
        set_error("self-test: input tile allocation failed");
        return false;
    }
    ncnn::Extractor ex = ctx->net.create_extractor();
    if (ex.input(kInputBlob, in_tile) != 0) {
        set_error("self-test: extractor input failed");
        return false;
    }
    ncnn::Mat out;
    if (ex.extract(kOutputBlob, out) != 0) {
        set_error("self-test: extractor extract failed for blob %s", kOutputBlob);
        return false;
    }
    if (!normalize_output(ctx, out)) {
        set_error("self-test: output normalization failed");
        return false;
    }
    if (out.w != t * kScale || out.h != t * kScale || out.c < 3) {
        set_error("self-test: unexpected output %dx%dx%d (expected %dx%dx3)",
                  out.w, out.h, out.c, t * kScale, t * kScale);
        return false;
    }

    const float* r = out.channel(0);
    const int count = out.w * out.h;
    float mn = r[0];
    float mx = r[0];
    for (int i = 0; i < count; i++) {
        const float v = r[i];
        if (!std::isfinite(v)) {
            set_error("self-test: non-finite output");
            return false;
        }
        mn = std::min(mn, v);
        mx = std::max(mx, v);
    }
    if (mx - mn < 1e-3f) {
        set_error("self-test: degenerate output (min=%f max=%f)", mn, mx);
        return false;
    }

    const auto t1 = std::chrono::steady_clock::now();
    LOGI("self-test ok in %lld ms (fp16=%d vulkan=%d)", 
         static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count()),
         ctx->use_fp16 ? 1 : 0, ctx->use_vulkan ? 1 : 0);
    return true;
}

struct TileTiming {
    long long convert_us = 0;
    long long infer_us = 0;
    long long write_us = 0;
};

// Run one padded tile through the network and write the scaled tile into `out`.
bool process_tile(Waifu2xContext* ctx, ncnn::Extractor& ex,
                  const unsigned char* in, int in_stride, int w, int h,
                  unsigned char* out, int out_stride,
                  int xi0, int yi0, int xi1, int yi1, TileTiming& timing) {
    using clock = std::chrono::steady_clock;

    const auto c0 = clock::now();
    ncnn::Mat in_tile = make_tile(in, in_stride, w, h, xi0, yi0, xi1, yi1);
    if (in_tile.empty()) {
        set_error("tile allocation failed (tile too large)");
        return false;
    }
    timing.convert_us += std::chrono::duration_cast<std::chrono::microseconds>(clock::now() - c0).count();

    const auto i0 = clock::now();
    if (ex.input(kInputBlob, in_tile) != 0) {
        set_error("extractor input failed");
        return false;
    }
    ncnn::Mat out_tile;
    if (ex.extract(kOutputBlob, out_tile) != 0) {
        set_error("extractor extract failed for blob %s", kOutputBlob);
        return false;
    }
    timing.infer_us += std::chrono::duration_cast<std::chrono::microseconds>(clock::now() - i0).count();

    if (!normalize_output(ctx, out_tile)) {
        return false;
    }

    // Geometry of the upconv7 model (see the bundled .param): six 3x3 valid convolutions shrink
    // the input by 1px per side each, then a 4x4/stride-2 deconvolution computes
    // out = (in - 12 - 1) * 2 + 4 - 2 * 3 = 2 * in - 28. So a (tile + 2*kPrepadding) input with
    // kPrepadding = 7 produces exactly 2 * tile: the padding is consumed as receptive-field
    // context and never appears in the output, hence no output crop.
    const int expected_w = (xi1 - xi0) * kScale;
    const int expected_h = (yi1 - yi0) * kScale;
    if (out_tile.empty() || out_tile.c < 3 || out_tile.w != expected_w || out_tile.h != expected_h) {
        set_error("unexpected output tile %dx%dx%d elemsize=%zu elempack=%d (expected %dx%dx3)",
                  out_tile.w, out_tile.h, out_tile.c, out_tile.elemsize, out_tile.elempack,
                  expected_w, expected_h);
        return false;
    }

    const auto w0 = clock::now();
    write_tile(out_tile, out, out_stride,
               xi0 * kScale, yi0 * kScale,
               (xi1 - xi0) * kScale, (yi1 - yi0) * kScale);
    timing.write_us += std::chrono::duration_cast<std::chrono::microseconds>(clock::now() - w0).count();
    return true;
}

bool process(Waifu2xContext* ctx, const unsigned char* rgba_in, int in_stride,
             int w, int h, unsigned char* rgba_out, int out_stride) {
    const auto t0 = std::chrono::steady_clock::now();
    {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        g_last_error.clear();
        g_last_stats.clear();
    }

    // A 2x page bitmap is 4x the source pixels; beyond this the output allocation (and the
    // GPU uploads) reliably OOMs on mid-range devices, so refuse instead of crashing.
    constexpr int kMaxOutputEdge = 4096;
    if (w <= 0 || h <= 0 || w * kScale > kMaxOutputEdge || h * kScale > kMaxOutputEdge) {
        set_error("page too large to upscale: %dx%d -> %dx%d (limit %d)", w, h, w * kScale, h * kScale, kMaxOutputEdge);
        return false;
    }

    // One extractor for the whole page: creating one per tile churns blob allocations, and the
    // bridge already serializes every native call. Thread count comes from net.opt.
    ncnn::Extractor ex = ctx->net.create_extractor();

    TileTiming timing;
    int tiles = 0;
    for (int yi = 0; yi < h; yi += kTileSize) {
        const int yi1 = std::min(h, yi + kTileSize);
        for (int xi = 0; xi < w; xi += kTileSize) {
            const int xi1 = std::min(w, xi + kTileSize);
            if (!process_tile(ctx, ex, rgba_in, in_stride, w, h, rgba_out, out_stride,
                              xi, yi, xi1, yi1, timing)) {
                return false;
            }
            tiles++;
        }
    }

    const auto t1 = std::chrono::steady_clock::now();
    const long long total_ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    const long long convert_ms = timing.convert_us / 1000;
    const long long infer_ms = timing.infer_us / 1000;
    const long long write_ms = timing.write_us / 1000;
    const long long accounted = convert_ms + infer_ms + write_ms;
    LOGI("process %dx%d -> %dx%d: %d tiles, %lld ms (vulkan=%d fp16=%d) convert=%lld infer=%lld write=%lld",
         w, h, w * kScale, h * kScale, tiles, total_ms, ctx->use_vulkan ? 1 : 0,
         ctx->use_fp16 ? 1 : 0, convert_ms, infer_ms, write_ms);

    char stats[256];
    snprintf(stats, sizeof(stats),
             "tiles=%d total=%lldms convert=%lldms infer=%lldms write=%lldms other=%lldms precision=%s",
             tiles, total_ms, convert_ms, infer_ms, write_ms,
             total_ms - accounted, ctx->use_fp16 ? "fp16" : "fp32");
    {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        g_last_stats = stats;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeInit(
    JNIEnv* env, jobject /* thiz */, jstring model_dir, jstring model_suffix) {
    ensure_gpu_instance();

    const char* dir = env->GetStringUTFChars(model_dir, nullptr);
    const char* suffix = env->GetStringUTFChars(model_suffix, nullptr);
    auto* ctx = new Waifu2xContext();
    ctx->param_path = std::string(dir) + "/" + suffix + "_model.param";
    ctx->bin_path = std::string(dir) + "/" + suffix + "_model.bin";
    env->ReleaseStringUTFChars(model_dir, dir);
    env->ReleaseStringUTFChars(model_suffix, suffix);

    ctx->use_vulkan = g_gpu_ok;
    if (!ctx->use_vulkan) {
        LOGW("vulkan unavailable, falling back to CPU inference (slow)");
    }

    // Prefer FP16 storage for bandwidth, but prove it on this driver with a self-test and fall
    // back to FP32 rather than shipping a page full of driver artifacts.
    bool ok = false;
    if (ctx->use_vulkan) {
        ok = load_net(ctx, /*fp16=*/true) && run_selftest(ctx);
        if (!ok) {
            LOGW("fp16 path rejected on this device (%s), retrying with fp32",
                 g_last_error.c_str());
            ctx->net.clear();
            ok = load_net(ctx, /*fp16=*/false) && run_selftest(ctx);
        }
    } else {
        ok = load_net(ctx, /*fp16=*/false) && run_selftest(ctx);
    }

    if (!ok) {
        set_error("engine init failed: %s", g_last_error.c_str());
        delete ctx;
        return 0;
    }

    LOGI("model loaded: %s (%s, %s)", ctx->bin_path.c_str(),
         ctx->use_vulkan ? "vulkan" : "cpu", ctx->use_fp16 ? "fp16" : "fp32");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeProcess(
    JNIEnv* env, jobject /* thiz */, jlong handle, jobject in_bitmap, jobject out_bitmap) {
    auto* ctx = reinterpret_cast<Waifu2xContext*>(handle);
    if (!ctx) return JNI_FALSE;

    AndroidBitmapInfo in_info;
    AndroidBitmapInfo out_info;
    if (AndroidBitmap_getInfo(env, in_bitmap, &in_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, out_bitmap, &out_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        set_error("AndroidBitmap_getInfo failed");
        return JNI_FALSE;
    }
    if (in_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        out_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        set_error("bitmaps must be RGBA_8888 (in=%d out=%d)", in_info.format, out_info.format);
        return JNI_FALSE;
    }
    if (out_info.width != in_info.width * kScale || out_info.height != in_info.height * kScale) {
        set_error("output size must be %dx the input (in=%ux%u out=%ux%u)", kScale,
                  in_info.width, in_info.height, out_info.width, out_info.height);
        return JNI_FALSE;
    }

    void* in_pixels = nullptr;
    void* out_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, in_bitmap, &in_pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, out_bitmap, &out_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        set_error("lockPixels failed");
        if (in_pixels) AndroidBitmap_unlockPixels(env, in_bitmap);
        return JNI_FALSE;
    }

    const bool ok = process(ctx, static_cast<const unsigned char*>(in_pixels),
                            static_cast<int>(in_info.stride),
                            static_cast<int>(in_info.width),
                            static_cast<int>(in_info.height),
                            static_cast<unsigned char*>(out_pixels),
                            static_cast<int>(out_info.stride));

    AndroidBitmap_unlockPixels(env, in_bitmap);
    AndroidBitmap_unlockPixels(env, out_bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeIsVulkan(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* ctx = reinterpret_cast<Waifu2xContext*>(handle);
    return (ctx && ctx->use_vulkan) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeIsFp16(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* ctx = reinterpret_cast<Waifu2xContext*>(handle);
    return (ctx && ctx->use_fp16) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeRelease(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    delete reinterpret_cast<Waifu2xContext*>(handle);
}

/** Text of the last native failure (null when nothing failed); surfaced in the in-app log. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeLastError(
    JNIEnv* env, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    if (g_last_error.empty()) return nullptr;
    return env->NewStringUTF(g_last_error.c_str());
}

/** Timing summary of the last successful run (null when nothing ran yet). */
extern "C" JNIEXPORT jstring JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeLastStats(
    JNIEnv* env, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    if (g_last_stats.empty()) return nullptr;
    return env->NewStringUTF(g_last_stats.c_str());
}
