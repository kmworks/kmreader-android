// waifu2x (upconv7) upscaling via ncnn + Vulkan.
// Ported/trimmed from nihui/waifu2x-ncnn-vulkan (MIT) as referenced by
// HaoweiLi97/mihon_img_upscale (Apache-2.0). See docs/waifu2x-upscaling-plan.md.
//
// Simplifications vs upstream: no custom preproc/postproc compute shaders —
// pixel conversion runs on CPU while only the network inference is on Vulkan.
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
#include <mutex>
#include <string>

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

/** Writes the inner (unpadded) region of a scaled tile into the output RGBA buffer. */
void write_tile(const ncnn::Mat& out_tile, unsigned char* rgba, int stride,
                int dx, int dy, int w, int h) {
    const int sx = kPrepadding * kScale;
    const int sy = kPrepadding * kScale;
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

// Run one padded tile through the network and write the scaled inner region into `out`.
bool process_tile(ncnn::Net& net, const unsigned char* in, int in_stride, int w, int h,
                  unsigned char* out, int out_stride,
                  int xi0, int yi0, int xi1, int yi1) {
    ncnn::Mat in_tile = make_tile(in, in_stride, w, h, xi0, yi0, xi1, yi1);
    if (in_tile.empty()) {
        LOGE("tile allocation failed");
        return false;
    }

    ncnn::Extractor ex = net.create_extractor();
    if (ex.input(kInputBlob, in_tile) != 0) {
        LOGE("extractor input failed");
        return false;
    }
    ncnn::Mat out_tile;
    if (ex.extract(kOutputBlob, out_tile) != 0) {
        LOGE("extractor extract failed");
        return false;
    }
    // With Vulkan + bf16 storage the extracted Mat may stay packed (elempack 4), which would
    // make channel()/row() arithmetic invalid; unpack defensively and verify the geometry.
    if (out_tile.elempack != 1) {
        ncnn::Mat unpacked;
        ncnn::convert_packing(out_tile, unpacked, 1);
        out_tile = unpacked;
    }
    const int expected_w = (xi1 - xi0 + 2 * kPrepadding) * kScale;
    const int expected_h = (yi1 - yi0 + 2 * kPrepadding) * kScale;
    if (out_tile.empty() || out_tile.c < 3 || out_tile.w != expected_w || out_tile.h != expected_h) {
        LOGE("unexpected output tile %dx%dx%d (expected %dx%dx3)",
             out_tile.w, out_tile.h, out_tile.c, expected_w, expected_h);
        return false;
    }

    write_tile(out_tile, out, out_stride,
               xi0 * kScale, yi0 * kScale,
               (xi1 - xi0) * kScale, (yi1 - yi0) * kScale);
    return true;
}

bool process(Waifu2xContext* ctx, const unsigned char* rgba_in, int in_stride,
             int w, int h, unsigned char* rgba_out, int out_stride) {
    const auto t0 = std::chrono::steady_clock::now();

    // A 2x page bitmap is 4x the source pixels; beyond this the output allocation (and the
    // GPU uploads) reliably OOMs on mid-range devices, so refuse instead of crashing.
    constexpr int kMaxOutputEdge = 4096;
    if (w <= 0 || h <= 0 || w * kScale > kMaxOutputEdge || h * kScale > kMaxOutputEdge) {
        LOGW("page too large to upscale: %dx%d -> %dx%d", w, h, w * kScale, h * kScale);
        return false;
    }

    int tiles = 0;
    for (int yi = 0; yi < h; yi += kTileSize) {
        const int yi1 = std::min(h, yi + kTileSize);
        for (int xi = 0; xi < w; xi += kTileSize) {
            const int xi1 = std::min(w, xi + kTileSize);
            if (!process_tile(ctx->net, rgba_in, in_stride, w, h, rgba_out, out_stride,
                              xi, yi, xi1, yi1)) {
                return false;
            }
            tiles++;
        }
    }

    const auto t1 = std::chrono::steady_clock::now();
    LOGI("process %dx%d -> %dx%d: %d tiles, %lld ms (%s)",
         w, h, w * kScale, h * kScale, tiles,
         static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count()),
         ctx->use_vulkan ? "vulkan" : "cpu");
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeInit(
    JNIEnv* env, jobject /* thiz */, jstring model_dir, jstring model_suffix) {
    ensure_gpu_instance();

    const char* dir = env->GetStringUTFChars(model_dir, nullptr);
    const char* suffix = env->GetStringUTFChars(model_suffix, nullptr);
    const std::string param_path = std::string(dir) + "/" + suffix + "_model.param";
    const std::string bin_path = std::string(dir) + "/" + suffix + "_model.bin";
    env->ReleaseStringUTFChars(model_dir, dir);
    env->ReleaseStringUTFChars(model_suffix, suffix);

    auto* ctx = new Waifu2xContext();
    ctx->use_vulkan = g_gpu_ok;

    // Precision/stability settings verified by mihon_img_upscale (IMAGE_ENHANCEMENT_FIXES.md):
    // BF16 storage halves bandwidth while FP32 arithmetic avoids driver "flower screen" bugs.
    ctx->net.opt.use_fp16_packed = false;
    ctx->net.opt.use_fp16_storage = false;
    ctx->net.opt.use_fp16_arithmetic = false;
    ctx->net.opt.use_bf16_storage = true;
    ctx->net.opt.num_threads = ncnn::get_big_cpu_count();
    if (ctx->use_vulkan) {
        ctx->net.opt.use_vulkan_compute = true;
        ctx->net.set_vulkan_device(ncnn::get_default_gpu_index());
    } else {
        LOGW("vulkan unavailable, falling back to CPU inference (slow)");
    }

    if (ctx->net.load_param(param_path.c_str()) != 0) {
        LOGE("load_param failed: %s", param_path.c_str());
        delete ctx;
        return 0;
    }
    if (ctx->net.load_model(bin_path.c_str()) != 0) {
        LOGE("load_model failed: %s", bin_path.c_str());
        delete ctx;
        return 0;
    }
    LOGI("model loaded: %s (%s)", bin_path.c_str(), ctx->use_vulkan ? "vulkan" : "cpu");
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
        LOGE("AndroidBitmap_getInfo failed");
        return JNI_FALSE;
    }
    if (in_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        out_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("bitmaps must be RGBA_8888 (in=%d out=%d)", in_info.format, out_info.format);
        return JNI_FALSE;
    }
    if (out_info.width != in_info.width * kScale || out_info.height != in_info.height * kScale) {
        LOGE("output size must be %dx the input (in=%ux%u out=%ux%u)", kScale,
             in_info.width, in_info.height, out_info.width, out_info.height);
        return JNI_FALSE;
    }

    void* in_pixels = nullptr;
    void* out_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, in_bitmap, &in_pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, out_bitmap, &out_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("lockPixels failed");
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

extern "C" JNIEXPORT void JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeRelease(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    delete reinterpret_cast<Waifu2xContext*>(handle);
}
