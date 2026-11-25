// waifu2x (upconv7) upscaling via ncnn + Vulkan.
// Ported/trimmed from nihui/waifu2x-ncnn-vulkan (MIT) as referenced by
// HaoweiLi97/mihon_img_upscale (Apache-2.0). See docs/waifu2x-upscaling-plan.md.
//
// Two paths:
//  * fused   — the page stays in VRAM: a preproc compute shader samples the padded tile out of
//              the source page and a postproc compute shader writes the 2x result straight back
//              into the output page, so there is one upload and one download per page.
//  * staged  — the fallback: CPU builds the tile / converts the output. Slower, but it only
//              needs the network on the GPU, so it covers drivers where the shaders fail.
// The shaders are compiled to SPIR-V at runtime with ncnn::compile_spirv_module plus glslang,
// which is already linked into libncnn.a (no glslc build step). Alpha is forced to opaque, same
// as the iOS implementation.
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
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "net.h"
#include "gpu.h"
#include "command.h"
#include "pipeline.h"
#include "cpu.h"
#include "mat.h"

#include "fused_shaders.h"

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

/** Tag shared with EnhancementDecoder.APP_TAG so native breadcrumbs file under the same filter. */
constexpr const char* kAppTag = "Upscale";

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

/**
 * Breadcrumb log: native logs only reach logcat, which is unreachable on the test tablet when a
 * Vulkan call segfaults. Appending to the app's own log file leaves a last-reached-milestone trail
 * that survives the crash, so the next report actually says where it died.
 */
std::mutex g_log_mutex;
std::string g_log_path;

void append_log_line(const char* level, const char* msg) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_log_path.empty()) return;
    FILE* f = fopen(g_log_path.c_str(), "a");
    if (!f) return;
    // Match AppLog's own line format (yyyy-MM-dd HH:mm:ss.SSS LEVEL [tag] message) so the entries
    // show up as normal rows on the logs page instead of being folded into the previous message.
    const auto now = std::chrono::system_clock::now();
    const std::time_t secs = std::chrono::system_clock::to_time_t(now);
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count() % 1000;
    std::tm tm_buf{};
    localtime_r(&secs, &tm_buf);
    char stamp[32];
    strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", &tm_buf);
    fprintf(f, "%s.%03lld %s [%s-native] %s\n", stamp, static_cast<long long>(ms), level, kAppTag, msg);
    fclose(f);
}

void nlog(const char* fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    LOGE("%s", buf);
    append_log_line("INFO", buf);
}

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
    // Failures are only ever visible on the device's log page, so mirror them into the file the
    // app log renders; g_last_error alone dies with the process.
    append_log_line("ERROR", buf);
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

/**
 * Builds a ready-to-use compute pipeline from GLSL source. ncnn's public Pipeline::create() takes
 * SPIR-V plus the reflected ShaderInfo, so the reflection step is done here explicitly
 * (ncnn::resolve_shader_info) before the descriptor set layout is derived from it.
 */
class ShaderPipeline : public ncnn::Pipeline {
public:
    explicit ShaderPipeline(const ncnn::VulkanDevice* vkdev) : ncnn::Pipeline(vkdev) {}

    int create_from_source(const char* name, const char* source, const ncnn::Option& opt) {
        std::vector<uint32_t> spirv;
        if (ncnn::compile_spirv_module(source, opt, spirv) != 0 || spirv.empty()) {
            set_error("fused shader %s: spirv compilation failed", name);
            return -1;
        }
        ncnn::ShaderInfo shader_info;
        if (ncnn::resolve_shader_info(spirv.data(), spirv.size() * sizeof(uint32_t), shader_info) != 0) {
            set_error("fused shader %s: spirv reflection failed", name);
            return -1;
        }
        set_shader_info(shader_info);
        // Mirrors upstream (waifu2x.cpp:247-262): the dispatch size in record_pipeline() is derived
        // from these values, so they must agree with the literal local sizes in the shader (8x8x1).
        set_optimal_local_size_xyz(8, 8, 1);
        const std::vector<ncnn::vk_specialization_type> specializations;
        const int ret = create(spirv.data(), spirv.size() * sizeof(uint32_t), specializations);
        if (ret != 0) {
            set_error("fused shader %s: pipeline creation failed (%d)", name, ret);
            return -1;
        }
        return 0;
    }
};

struct Waifu2xContext {
    ncnn::Net net;
    bool use_vulkan = false;
    /** FP16 storage halves intermediate bandwidth; falls back to FP32 when the driver chokes. */
    bool use_fp16 = false;
    bool use_bf16 = false;
    /** True when the fused preproc/postproc shaders are usable; otherwise the staged CPU path runs. */
    bool fused = false;
    std::unique_ptr<ShaderPipeline> preproc;
    std::unique_ptr<ShaderPipeline> postproc_fp32;
    std::unique_ptr<ShaderPipeline> postproc_fp16;
    std::string param_path;
    std::string bin_path;

    // Pipelines must die before the net: both reference the VulkanDevice.
    ~Waifu2xContext() {
        preproc.reset();
        postproc_fp32.reset();
        postproc_fp16.reset();
        net.clear();
    }
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

/** Marks a buffer written by one of our own pipelines so ncnn inserts the right barrier later. */
void mark_shader_write(ncnn::VkMat& mat) {
    if (!mat.data) return;
    mat.data->access_flags = VK_ACCESS_SHADER_WRITE_BIT;
    mat.data->stage_flags = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
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
 * Compiles the fused preproc/postproc shaders. Failure is not fatal: the staged CPU path stays
 * available, so an exotic driver loses speed instead of losing the feature.
 */
bool create_fused_pipelines(Waifu2xContext* ctx) {
    if (!ctx->use_vulkan) return false;
    ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(ncnn::get_default_gpu_index());
    if (!vkdev) {
        set_error("fused: vulkan device unavailable");
        return false;
    }

    auto preproc = std::make_unique<ShaderPipeline>(vkdev);
    auto postproc_fp32 = std::make_unique<ShaderPipeline>(vkdev);
    auto postproc_fp16 = std::make_unique<ShaderPipeline>(vkdev);
    const ncnn::Option& opt = ctx->net.opt;
    nlog("fused: compiling preproc shader");
    if (preproc->create_from_source("preproc", kPreprocShader, opt) != 0) return false;
    nlog("fused: preproc ready, compiling postproc_fp32 shader");
    if (postproc_fp32->create_from_source("postproc_fp32", kPostprocFp32Shader, opt) != 0) return false;
    nlog("fused: postproc_fp32 ready, compiling postproc_fp16 shader");
    if (postproc_fp16->create_from_source("postproc_fp16", kPostprocFp16Shader, opt) != 0) return false;

    ctx->preproc = std::move(preproc);
    ctx->postproc_fp32 = std::move(postproc_fp32);
    ctx->postproc_fp16 = std::move(postproc_fp16);
    LOGI("fused preproc/postproc shaders compiled");
    return true;
}

bool process(Waifu2xContext* ctx, const unsigned char* rgba_in, int in_stride,
             int w, int h, unsigned char* rgba_out, int out_stride);

/**
 * Runs a tiny page through the real entry point and sanity-checks the result, so it exercises
 * whatever path will be used later (fused shaders included). Doubles as shader warm-up: without
 * it the first real page pays pipeline compilation and looks like a stall.
 */
bool run_selftest(Waifu2xContext* ctx) {
    const auto t0 = std::chrono::steady_clock::now();
    const int t = 32;
    const int out_edge = t * kScale;
    std::vector<unsigned char> src(static_cast<size_t>(t) * t * 4);
    std::vector<unsigned char> dst(static_cast<size_t>(out_edge) * out_edge * 4, 0);
    for (int y = 0; y < t; y++) {
        for (int x = 0; x < t; x++) {
            unsigned char* p = src.data() + (static_cast<size_t>(y) * t + x) * 4;
            p[0] = static_cast<unsigned char>(x * 8);
            p[1] = static_cast<unsigned char>(y * 8);
            p[2] = static_cast<unsigned char>((x + y) * 4);
            p[3] = 255;
        }
    }

    if (!process(ctx, src.data(), t * 4, t, t, dst.data(), out_edge * 4)) {
        const std::string reason = g_last_error.empty() ? std::string("unknown") : g_last_error;
        set_error("self-test: engine run failed: %s", reason.c_str());
        return false;
    }

    int mn = 255;
    int mx = 0;
    for (size_t i = 0; i < dst.size(); i += 4) {
        const int v = dst[i];
        mn = std::min(mn, v);
        mx = std::max(mx, v);
        if (dst[i + 3] != 255) {
            set_error("self-test: alpha is not opaque (%d at %zu)", dst[i + 3], i);
            return false;
        }
    }
    if (mx - mn < 4) {
        set_error("self-test: degenerate output (min=%d max=%d)", mn, mx);
        return false;
    }

    const auto t1 = std::chrono::steady_clock::now();
    LOGI("self-test ok in %lld ms (fp16=%d vulkan=%d fused=%d)",
         static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count()),
         ctx->use_fp16 ? 1 : 0, ctx->use_vulkan ? 1 : 0, ctx->fused ? 1 : 0);
    return true;
}

struct TileTiming {
    long long convert_us = 0;
    long long infer_us = 0;
    long long write_us = 0;
};

// Run one padded tile through the network and write the scaled tile into `out`.
bool process_tile(Waifu2xContext* ctx,
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
    // A fresh extractor per tile: a reused one serves the previous tile's cached blobs when the
    // tile shape changes (edge tiles are narrower than kTileSize), which silently yields a full
    // 2*kTileSize output for a small tile.
    ncnn::Extractor ex = ctx->net.create_extractor();
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

/**
 * Page-in-VRAM path: upload the page once, run preproc -> network -> postproc per tile with the
 * destination living on the GPU, download once. Removes the CPU tile building and the per-tile
 * CPU readback that the staged path pays for.
 */
bool run_fused(Waifu2xContext* ctx, const unsigned char* rgba_in, int in_stride,
               int w, int h, unsigned char* rgba_out, int out_stride,
               TileTiming& timing, int& tiles) {
    using clock = std::chrono::steady_clock;
    auto us = [](clock::duration d) { return std::chrono::duration_cast<std::chrono::microseconds>(d).count(); };

    ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(ncnn::get_default_gpu_index());
    if (!vkdev) {
        set_error("fused: vulkan device unavailable");
        return false;
    }
    ncnn::VkAllocator* blob_alloc = vkdev->acquire_blob_allocator();
    ncnn::VkAllocator* staging_alloc = vkdev->acquire_staging_allocator();

    ncnn::Option opt = ctx->net.opt;
    opt.blob_vkallocator = blob_alloc;
    opt.workspace_vkallocator = blob_alloc;
    opt.staging_vkallocator = staging_alloc;

    // The bitmaps are wrapped rather than copied: giving the Mat a width of stride/4 makes ncnn
    // read exactly the bitmap rows, padding included.
    const int srcw_px = in_stride / 4;
    const int dstw_px = out_stride / 4;
    const int out_h = h * kScale;

    // Neither shader touches an image; every binding is a storage buffer.
    const std::vector<ncnn::VkImageMat> no_images;

    bool ok = true;
    {
        ncnn::Mat src_cpu(srcw_px, h, 1, const_cast<unsigned char*>(rgba_in), 4u);
        ncnn::VkMat src_vk;
        ncnn::VkMat dst_vk(dstw_px, out_h, 1, 4u, blob_alloc);
        if (dst_vk.empty()) {
            set_error("fused: output page allocation failed (%dx%d)", dstw_px, out_h);
            ok = false;
        }

        if (ok) {
            const auto c0 = clock::now();
            ncnn::VkCompute cmd(vkdev);
            // Clone, not upload. record_upload() casts the source to fp16 on integrated GPUs
            // (cast_type_to = 2), which reinterprets our packed ARGB uint32 words as float pixels
            // and hands the preproc shader garbage. A clone is a raw byte copy.
            cmd.record_clone(src_cpu, src_vk, opt);
            if (cmd.submit_and_wait() != 0) {
                set_error("fused: page upload failed");
                ok = false;
            }
            timing.convert_us += us(clock::now() - c0);
            nlog("fused: page %dx%d uploaded (stride %d)", w, h, in_stride);
        }

        for (int yi = 0; ok && yi < h; yi += kTileSize) {
            const int yi1 = std::min(h, yi + kTileSize);
            for (int xi = 0; ok && xi < w; xi += kTileSize) {
                const int xi1 = std::min(w, xi + kTileSize);
                const int inner_w = xi1 - xi;
                const int inner_h = yi1 - yi;
                const int tw = inner_w + 2 * kPrepadding;
                const int th = inner_h + 2 * kPrepadding;

                ncnn::VkMat tile_vk(tw, th, 3, 4u, blob_alloc);
                if (tile_vk.empty()) {
                    set_error("fused: tile allocation failed (%dx%d)", tw, th);
                    ok = false;
                    break;
                }

                ncnn::VkCompute cmd(vkdev);

                const auto c0 = clock::now();
                std::vector<ncnn::vk_constant_type> pre_constants(9);
                pre_constants[0].i = srcw_px;
                pre_constants[1].i = h;
                pre_constants[2].i = w;
                pre_constants[3].i = tw;
                pre_constants[4].i = th;
                pre_constants[5].i = static_cast<int>(tile_vk.cstep);
                pre_constants[6].i = kPrepadding;
                pre_constants[7].i = xi;
                pre_constants[8].i = yi;
                // The dispatcher has to be a real Mat. A default-constructed VkMat with only w/h/c
                // hand-filled keeps dims=0 and d=0, ncnn derives the group counts (z in particular)
                // from those, and vkCmdDispatch(..., 0) legally does nothing — which is exactly how
                // both dispatches silently became no-ops and the alpha self-test failed.
                const ncnn::Mat pre_dispatcher(tw, th, 1);
                cmd.record_pipeline(ctx->preproc.get(), {src_vk, tile_vk}, no_images,
                                    pre_constants, pre_dispatcher);
                // ncnn tracks buffer hazards through these flags; a custom dispatch does not update
                // them, so without this the network may read the tile before the write is visible.
                mark_shader_write(tile_vk);
                timing.convert_us += us(clock::now() - c0);

                const auto i0 = clock::now();
                // Fresh extractor per tile, same reason as in process_tile(). It also binds the
                // allocators the shaders wrote into (upstream waifu2x.cpp:474-482 does the same),
                // otherwise the network would allocate blobs from the default pool.
                ncnn::Extractor ex = ctx->net.create_extractor();
                ex.set_blob_vkallocator(blob_alloc);
                ex.set_workspace_vkallocator(blob_alloc);
                ex.set_staging_vkallocator(staging_alloc);
                if (ex.input(kInputBlob, tile_vk) != 0) {
                    set_error("fused: extractor input failed");
                    ok = false;
                    break;
                }
                ncnn::VkMat out_packed;
                if (ex.extract(kOutputBlob, out_packed, cmd) != 0) {
                    set_error("fused: extractor extract failed for blob %s", kOutputBlob);
                    ok = false;
                    break;
                }
                timing.infer_us += us(clock::now() - i0);

                const auto w0 = clock::now();
                ncnn::VkMat out_tile;
                vkdev->convert_packing(out_packed, out_tile, 1, /*cast_type_to=*/0, cmd, opt);
                if (out_tile.empty()) {
                    set_error("fused: output unpacking failed");
                    ok = false;
                    break;
                }
                if (out_tile.w != inner_w * kScale || out_tile.h != inner_h * kScale || out_tile.c < 3) {
                    set_error("fused: unexpected output tile %dx%dx%d (expected %dx%dx3)",
                              out_tile.w, out_tile.h, out_tile.c, inner_w * kScale, inner_h * kScale);
                    ok = false;
                    break;
                }
                const size_t element_size = out_tile.elemsize / out_tile.elempack;
                if (element_size != 2 && element_size != 4) {
                    set_error("fused: unsupported output element size %zu", element_size);
                    ok = false;
                    break;
                }

                std::vector<ncnn::vk_constant_type> post_constants(6);
                post_constants[0].i = out_tile.w;
                post_constants[1].i = out_tile.h;
                post_constants[2].i = static_cast<int>(out_tile.cstep);
                post_constants[3].i = dstw_px;
                post_constants[4].i = xi * kScale;
                post_constants[5].i = yi * kScale;
                const ncnn::Mat post_dispatcher(out_tile.w, out_tile.h, 1);
                const ncnn::Pipeline* post =
                    element_size == 2 ? ctx->postproc_fp16.get() : ctx->postproc_fp32.get();
                cmd.record_pipeline(post, {out_tile, dst_vk}, no_images, post_constants,
                                    post_dispatcher);
                mark_shader_write(dst_vk);

                if (tiles < 2 || tiles % 20 == 0) {
                    nlog("fused: tile %d at (%d,%d) %dx%d -> %dx%d, submitting", tiles, xi, yi, tw, th,
                         out_tile.w, out_tile.h);
                }
                if (cmd.submit_and_wait() != 0) {
                    set_error("fused: tile submit failed at (%d,%d)", xi, yi);
                    ok = false;
                    break;
                }
                if (tiles == 0) {
                    // First-tile sanity probe: tells a broken network output apart from a broken
                    // postproc dispatch, which otherwise both show up as an all-zero page.
                    ncnn::Mat probe;
                    ncnn::VkCompute pcmd(vkdev);
                    pcmd.record_download(out_tile, probe, opt);
                    if (pcmd.submit_and_wait() == 0 && !probe.empty()) {
                        // record_download() re-creates the Mat, so read the element size off the
                        // result: an fp16 tile comes back as fp32 here.
                        const size_t probe_elem = probe.elemsize / probe.elempack;
                        const void* row = probe.channel(0).row(0);
                        float v[3] = {-1.0f, -1.0f, -1.0f};
                        for (int i = 0; i < 3; i++) {
                            if (probe_elem == 4) {
                                v[i] = static_cast<const float*>(row)[i];
                            } else if (probe_elem == 2) {
                                v[i] = ncnn::float16_to_float32(
                                    static_cast<const unsigned short*>(row)[i]);
                            }
                        }
                        nlog("fused: probe netout %dx%dx%d elemsize=%zu = %.3f %.3f %.3f", probe.w,
                             probe.h, probe.c, probe_elem, v[0], v[1], v[2]);
                    }

                    // Preproc probe: ~0..1 means the shader wrote the tile, garbage means the
                    // dispatch still does nothing.
                    ncnn::Mat tile_probe;
                    ncnn::VkCompute tcmd(vkdev);
                    tcmd.record_clone(tile_vk, tile_probe, opt);
                    if (tcmd.submit_and_wait() == 0 && !tile_probe.empty()) {
                        const void* trow = tile_probe.channel(0).row(0);
                        const size_t telem = tile_probe.elemsize / tile_probe.elempack;
                        float tv[3] = {-1.0f, -1.0f, -1.0f};
                        for (int i = 0; i < 3; i++) {
                            if (telem == 4) {
                                tv[i] = static_cast<const float*>(trow)[i];
                            } else if (telem == 2) {
                                tv[i] = ncnn::float16_to_float32(
                                    static_cast<const unsigned short*>(trow)[i]);
                            }
                        }
                        nlog("fused: probe tile %dx%dx%d elemsize=%zu = %.3f %.3f %.3f", tile_probe.w,
                             tile_probe.h, tile_probe.c, telem, tv[0], tv[1], tv[2]);
                    }
                }
                timing.write_us += us(clock::now() - w0);
                tiles++;
            }
        }

        if (ok) {
            const auto w0 = clock::now();
            // Download into a fresh Mat and copy row by row: record_download()/record_clone()
            // rebuild the destination with create_like(), so a Mat wrapped around rgba_out is
            // replaced and would never receive the pixels.
            ncnn::Mat page;
            ncnn::VkCompute cmd(vkdev);
            cmd.record_clone(dst_vk, page, opt);
            if (cmd.submit_and_wait() != 0) {
                set_error("fused: result download failed");
                ok = false;
            } else if (page.empty() || page.w != dstw_px || page.h != out_h) {
                set_error("fused: unexpected download page %dx%dx%d", page.w, page.h, page.c);
                ok = false;
            } else {
                for (int y = 0; y < out_h; y++) {
                    std::memcpy(rgba_out + static_cast<size_t>(y) * out_stride, page.row(y),
                                static_cast<size_t>(dstw_px) * 4);
                }
                nlog("fused: probe page pixel0 = %u %u %u %u", rgba_out[0], rgba_out[1], rgba_out[2],
                     rgba_out[3]);
            }
            timing.write_us += us(clock::now() - w0);
        }
    }

    vkdev->reclaim_blob_allocator(blob_alloc);
    vkdev->reclaim_staging_allocator(staging_alloc);
    return ok;
}

/**
 * Loads the net at the requested precision and proves the whole pipeline works on this device.
 * The fused shaders are tried first and dropped quietly when they fail, so a driver quirk costs
 * speed, never the feature.
 */
bool load_and_verify(Waifu2xContext* ctx, bool fp16) {
    if (!load_net(ctx, fp16)) return false;

    ctx->fused = ctx->use_vulkan && create_fused_pipelines(ctx);
    nlog("init: net loaded (fp16=%d), fused pipelines=%d", fp16 ? 1 : 0, ctx->fused ? 1 : 0);
    if (run_selftest(ctx)) return true;

    if (ctx->fused) {
        nlog("init: fused path rejected by this device (%s); keeping the staged CPU path",
             g_last_error.c_str());
        ctx->fused = false;
        return run_selftest(ctx);
    }
    return false;
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

    TileTiming timing;
    int tiles = 0;
    bool used_fused = false;

    if (ctx->fused) {
        if (run_fused(ctx, rgba_in, in_stride, w, h, rgba_out, out_stride, timing, tiles)) {
            used_fused = true;
        } else {
            // Sticky off: a driver that rejects the fused shaders will not start accepting them
            // mid-page, and retrying would burn a page of work every time. The fused path only
            // writes into a VRAM page, so the output bitmap is untouched and the staged path can
            // take over immediately.
            const std::string reason = g_last_error.empty() ? std::string("unknown") : g_last_error;
            ctx->fused = false;
            tiles = 0;
            timing = TileTiming();
            LOGW("fused path failed (%s); switching to the staged CPU path", reason.c_str());
        }
    }

    if (!used_fused) {
        for (int yi = 0; yi < h; yi += kTileSize) {
            const int yi1 = std::min(h, yi + kTileSize);
            for (int xi = 0; xi < w; xi += kTileSize) {
                const int xi1 = std::min(w, xi + kTileSize);
                if (!process_tile(ctx, rgba_in, in_stride, w, h, rgba_out, out_stride,
                                  xi, yi, xi1, yi1, timing)) {
                    return false;
                }
                tiles++;
            }
        }
    }

    const auto t1 = std::chrono::steady_clock::now();
    const long long total_ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    const long long convert_ms = timing.convert_us / 1000;
    const long long infer_ms = timing.infer_us / 1000;
    const long long write_ms = timing.write_us / 1000;
    const long long accounted = convert_ms + infer_ms + write_ms;
    LOGI("process %dx%d -> %dx%d: %d tiles, %lld ms (vulkan=%d fp16=%d path=%s) convert=%lld infer=%lld write=%lld",
         w, h, w * kScale, h * kScale, tiles, total_ms, ctx->use_vulkan ? 1 : 0,
         ctx->use_fp16 ? 1 : 0, used_fused ? "fused" : "staged", convert_ms, infer_ms, write_ms);

    char stats[256];
    snprintf(stats, sizeof(stats),
             "tiles=%d total=%lldms convert=%lldms infer=%lldms write=%lldms other=%lldms precision=%s path=%s",
             tiles, total_ms, convert_ms, infer_ms, write_ms,
             total_ms - accounted, ctx->use_fp16 ? "fp16" : "fp32", used_fused ? "fused" : "staged");
    {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        g_last_stats = stats;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeInit(
    JNIEnv* env, jobject /* thiz */, jstring model_dir, jstring model_suffix, jint precision_mode,
    jstring log_path) {
    ensure_gpu_instance();

    const char* dir = env->GetStringUTFChars(model_dir, nullptr);
    const char* suffix = env->GetStringUTFChars(model_suffix, nullptr);
    if (log_path) {
        const char* path = env->GetStringUTFChars(log_path, nullptr);
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            g_log_path = path;
        }
        env->ReleaseStringUTFChars(log_path, path);
    }
    nlog("init: model=%s precision_mode=%d", suffix, precision_mode);
    auto* ctx = new Waifu2xContext();
    ctx->param_path = std::string(dir) + "/" + suffix + "_model.param";
    ctx->bin_path = std::string(dir) + "/" + suffix + "_model.bin";
    env->ReleaseStringUTFChars(model_dir, dir);
    env->ReleaseStringUTFChars(model_suffix, suffix);

    ctx->use_vulkan = g_gpu_ok;
    if (!ctx->use_vulkan) {
        LOGW("vulkan unavailable, falling back to CPU inference (slow)");
    }

    // precision_mode mirrors the Kotlin UpscalePrecision enum:
    //   0 = Auto  — try FP16 storage, prove it on this driver, fall back to FP32 (mihon's default)
    //   1 = Fp16  — force FP16 storage (still self-tested, so a broken driver errors instead of
    //               painting artifacts)
    //   2 = Fp32  — force FP32 storage; the escape hatch for drivers where FP16 passes the
    //               self-test but pages still band or sparkle
    bool ok = false;
    if (!ctx->use_vulkan) {
        // No compute queue: ncnn runs on CPU where fp16 storage needs a hardware-capable backend.
        ok = load_and_verify(ctx, /*fp16=*/false);
    } else if (precision_mode == 2) {
        ok = load_and_verify(ctx, /*fp16=*/false);
        if (!ok) {
            LOGE("fp32 engine self-test failed: %s", g_last_error.c_str());
        }
    } else {
        ok = load_and_verify(ctx, /*fp16=*/true);
        if (!ok && precision_mode == 0) {
            LOGW("fp16 path rejected on this device (%s), retrying with fp32", g_last_error.c_str());
            ctx->net.clear();
            ok = load_and_verify(ctx, /*fp16=*/false);
        } else if (!ok) {
            LOGE("fp16 forced but the engine self-test failed: %s", g_last_error.c_str());
        }
    }

    if (!ok) {
        set_error("engine init failed: %s", g_last_error.c_str());
        delete ctx;
        return 0;
    }

    LOGI("model loaded: %s (%s, %s, %s)", ctx->bin_path.c_str(),
         ctx->use_vulkan ? "vulkan" : "cpu", ctx->use_fp16 ? "fp16" : "fp32",
         ctx->fused ? "fused" : "staged");
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

/** True when preproc/postproc run as GPU compute shaders instead of CPU tile conversion. */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kmworks_kmreader_core_image_upscale_Waifu2xBridge_nativeIsFused(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* ctx = reinterpret_cast<Waifu2xContext*>(handle);
    return (ctx && ctx->fused) ? JNI_TRUE : JNI_FALSE;
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
