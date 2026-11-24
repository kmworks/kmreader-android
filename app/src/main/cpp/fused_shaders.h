// Vulkan compute shaders for the fused upscaling path, compiled to SPIR-V at runtime with
// ncnn::compile_spirv_module (glslang is already linked into libncnn.a — no glslc build step).
//
// Why they exist: the staged path builds every (tile + 14)² float tile on the CPU and converts
// every 2x output tile back to RGBA on the CPU, which costs two full passes over the page *and*
// keeps the GPU waiting per tile. These shaders move both conversions onto the GPU and let the
// page live in VRAM end to end: one upload of the page, one download of the result.
//
// Both shaders are written for elempack=1 buffers. ncnn may still return the network output
// channel-packed; the host unpacks it before dispatching the postproc shader.

#ifndef WAIFU2X_FUSED_SHADERS_H
#define WAIFU2X_FUSED_SHADERS_H

namespace {

// Source page RGBA (one uint per pixel, Android bitmap row stride handled by srcw_px) ->
// fp32 3-channel padded tile, edge pixels replicated (equivalent to the CPU make_tile()).
inline constexpr const char* kPreprocShader = R"(
#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0) buffer src_blob { uint src_data[]; };
layout(binding = 1) buffer tile_blob { float tile_data[]; };

layout(push_constant) uniform parameter {
    int srcw_px;     // source uints per row (bitmap stride / 4)
    int srch;
    int src_w;       // real bitmap width, used to clamp
    int tilew;       // padded tile width
    int tileh;
    int tile_cstep;  // channel stride of the fp32 tile, in elements
    int pad;
    int sx0;         // inner (unpadded) origin in source pixels
    int sy0;
} p;

void main() {
    const int tx = int(gl_GlobalInvocationID.x);
    const int ty = int(gl_GlobalInvocationID.y);
    if (tx >= p.tilew || ty >= p.tileh) return;

    const int sx = clamp(p.sx0 + tx - p.pad, 0, p.src_w - 1);
    const int sy = clamp(p.sy0 + ty - p.pad, 0, p.srch - 1);

    const uint px = src_data[sy * p.srcw_px + sx];
    const int idx = ty * p.tilew + tx;
    tile_data[idx] = float(px & 0xFFu) / 255.0;
    tile_data[idx + p.tile_cstep] = float((px >> 8) & 0xFFu) / 255.0;
    tile_data[idx + 2 * p.tile_cstep] = float((px >> 16) & 0xFFu) / 255.0;
}
)";

// 2x output tile (fp32) -> RGBA page, written in place at (dx,dy). Alpha forced to 255 to match
// the iOS implementation.
inline constexpr const char* kPostprocFp32Shader = R"(
#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0) buffer out_blob { float out_data[]; };
layout(binding = 1) buffer dst_blob { uint dst_data[]; };

layout(push_constant) uniform parameter {
    int outw;
    int outh;
    int cstep;   // channel stride of the network output, in elements
    int dstw_px; // destination uints per row (bitmap stride / 4)
    int dx;
    int dy;
} p;

void main() {
    const int x = int(gl_GlobalInvocationID.x);
    const int y = int(gl_GlobalInvocationID.y);
    if (x >= p.outw || y >= p.outh) return;

    const int idx = y * p.outw + x;
    const float r = clamp(out_data[idx], 0.0, 1.0);
    const float g = clamp(out_data[idx + p.cstep], 0.0, 1.0);
    const float b = clamp(out_data[idx + 2 * p.cstep], 0.0, 1.0);

    dst_data[(p.dy + y) * p.dstw_px + (p.dx + x)] =
        0xFF000000u | uint(round(r * 255.0)) | (uint(round(g * 255.0)) << 8) | (uint(round(b * 255.0)) << 16);
}
)";

// Same as above for fp16 storage: elements are 16 bit, so they are read as the low/high half of
// a 32 bit word. unpackHalf2x16 keeps this GLSL 450 without the float16 type extensions.
inline constexpr const char* kPostprocFp16Shader = R"(
#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0) buffer out_blob { uint out_data[]; };
layout(binding = 1) buffer dst_blob { uint dst_data[]; };

layout(push_constant) uniform parameter {
    int outw;
    int outh;
    int cstep;
    int dstw_px;
    int dx;
    int dy;
} p;

float load_half(int i) {
    const vec2 halves = unpackHalf2x16(out_data[i >> 1]);
    return (i & 1) == 0 ? halves.x : halves.y;
}

void main() {
    const int x = int(gl_GlobalInvocationID.x);
    const int y = int(gl_GlobalInvocationID.y);
    if (x >= p.outw || y >= p.outh) return;

    const int idx = y * p.outw + x;
    const float r = clamp(load_half(idx), 0.0, 1.0);
    const float g = clamp(load_half(idx + p.cstep), 0.0, 1.0);
    const float b = clamp(load_half(idx + 2 * p.cstep), 0.0, 1.0);

    dst_data[(p.dy + y) * p.dstw_px + (p.dx + x)] =
        0xFF000000u | uint(round(r * 255.0)) | (uint(round(g * 255.0)) << 8) | (uint(round(b * 255.0)) << 16);
}
)";

} // namespace

#endif // WAIFU2X_FUSED_SHADERS_H
