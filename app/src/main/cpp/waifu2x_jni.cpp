// waifu2x (upconv7) upscaling via ncnn + Vulkan.
// Ported/trimmed from nihui/waifu2x-ncnn-vulkan (MIT) as referenced by
// HaoweiLi97/mihon_img_upscale (Apache-2.0). See docs/waifu2x-upscaling-plan.md.
//
// Simplifications vs upstream: no custom preproc/postproc compute shaders —
// pixel conversion runs on CPU via ncnn::Mat, only the network inference is on
// Vulkan. Alpha channel is forced to opaque (same as the iOS implementation).

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <chrono>
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

// Copy src[channels][rows][cols] into dst at (dst_x, dst_y). Both 3-channel float Mats.
void blit(const ncnn::Mat& src, int src_x, int src_y,
          ncnn::Mat& dst, int dst_x, int dst_y, int w, int h) {
    for (int c = 0; c < 3; c++) {
        const float* sptr = src.channel(c).row(src_y) + src_x;
        float* dptr = dst.channel(c).row(dst_y) + dst_x;
        for (int y = 0; y < h; y++) {
            memcpy(dptr, sptr, w * sizeof(float));
            sptr += src.w;
            dptr += dst.w;
        }
    }
}

// Run one padded tile through the network and write the scaled inner region into `out`.
bool process_tile(ncnn::Net& net, const ncnn::Mat& in, ncnn::Mat& out,
                  int xi0, int yi0, int xi1, int yi1) {
    const int w = in.w;
    const int h = in.h;

    // Desired padded region, clamped to the image; borders beyond the edge are replicated.
    const int px0 = std::max(0, xi0 - kPrepadding);
    const int py0 = std::max(0, yi0 - kPrepadding);
    const int px1 = std::min(w, xi1 + kPrepadding);
    const int py1 = std::min(h, yi1 + kPrepadding);

    // ncnn::Mat has no row/col range view in recent releases: copy the clamped
    // region out with the blit helper, then replicate-pad it back to full prepad.
    ncnn::Mat roi(px1 - px0, py1 - py0, 3, (size_t)sizeof(float));
    if (roi.empty()) return false;
    blit(in, px0, py0, roi, 0, 0, px1 - px0, py1 - py0);
    ncnn::Mat in_tile;
    ncnn::copy_make_border(roi, in_tile,
                           py0 - (yi0 - kPrepadding), (yi1 + kPrepadding) - py1,
                           px0 - (xi0 - kPrepadding), (xi1 + kPrepadding) - px1,
                           ncnn::BORDER_REPLICATE, 0.f);
    if (in_tile.empty()) return false;

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

    // Crop the padding back off (scaled) and blit the inner region into the output.
    blit(out_tile, kPrepadding * kScale, kPrepadding * kScale,
         out, xi0 * kScale, yi0 * kScale,
         (xi1 - xi0) * kScale, (yi1 - yi0) * kScale);
    return true;
}

bool process(Waifu2xContext* ctx, const void* rgba_in, int w, int h, void* rgba_out) {
    const auto t0 = std::chrono::steady_clock::now();

    ncnn::Mat in = ncnn::Mat::from_pixels(
        static_cast<const unsigned char*>(rgba_in), ncnn::Mat::PIXEL_RGBA2RGB, w, h);
    const float norm[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    // NB: ncnn's historical "substract" spelling is kept upstream.
    in.substract_mean_normalize(nullptr, norm);

    ncnn::Mat out(3, w * kScale, h * kScale, (size_t)sizeof(float));
    if (out.empty()) return false;

    int tiles = 0;
    for (int yi = 0; yi < h; yi += kTileSize) {
        const int yi1 = std::min(h, yi + kTileSize);
        for (int xi = 0; xi < w; xi += kTileSize) {
            const int xi1 = std::min(w, xi + kTileSize);
            if (!process_tile(ctx->net, in, out, xi, yi, xi1, yi1)) return false;
            tiles++;
        }
    }

    const float scale255[3] = {255.f, 255.f, 255.f};
    out.substract_mean_normalize(nullptr, scale255);
    out.to_pixels(static_cast<unsigned char*>(rgba_out), ncnn::Mat::PIXEL_RGB2RGBA);

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

    const bool ok = process(ctx, in_pixels, static_cast<int>(in_info.width),
                            static_cast<int>(in_info.height), out_pixels);

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
