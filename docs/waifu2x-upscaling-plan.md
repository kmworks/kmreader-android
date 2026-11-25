# Waifu2x 图片超分实现方案（Android）

> 状态：方案待评审 · 2026-11-24
> 目标：补齐 iOS 已有、Android 缺失的阅读器图片超分功能
> 参考实现：[HaoweiLi97/mihon_img_upscale](https://github.com/HaoweiLi97/mihon_img_upscale)（Apache-2.0）
> 功能基准：KMReader iOS `KMReader/Features/Reader/Services/Upscaling/`

---

## 1. 背景与目标

### 1.1 iOS 版现状（功能基准）

| 维度 | iOS 实现 |
|---|---|
| 推理引擎 | Core ML，模型 `waifu2x_photo_noise0_scale2x.mlmodel` 内置 Bundle |
| 分块策略 | blockSize=156、shrinkSize=7（有效块 142px），边缘像素外扩填充，vDSP 加速归一化 |
| 触发逻辑 | `ReaderUpscaleDecision`：仅当 `min(屏宽/图源宽, 屏高/图源高) > autoTriggerScale` 时超分 |
| 并发控制 | Actor + 信号量，最多 2 并发，模型懒加载缓存 |

**关键语义**：超分的价值不是产出一张 2x 大图，而是让"低分辨率源图放大到屏幕"这一步有更锐利的源。最终显示尺寸仍是屏幕尺寸——因此 Android 端无需为超大输出 bitmap 做分块渲染，但需要控制内存与纹理上限。

### 1.2 目标

- 阅读器页图支持 waifu2x 2x 超分，画质与 iOS 对等
- 提供降噪强度选择（无降噪 / noise0–3 共 5 档，同一 upconv7 架构换权重，不增加引擎复杂度）
- 超分结果落盘缓存，重复阅读零推理开销
- 预载与翻页竞争有明确调度，不阻塞阅读
- 默认关闭或自动档，用户可控
- APK 体积增量可控（单 ABI ≤ 10MB）

非目标：Real-CUGAN / Real-ESRGAN / Anime4K 等多模型（架构预留，首期不做）；NPU 后端（QNN / MediaTek / LiteRT，砍掉）。

---

## 2. 技术选型

**方案：ncnn + Vulkan，移植 mihon_img_upscale 的最小子集。**

| 候选 | 结论 | 原因 |
|---|---|---|
| **ncnn + Vulkan（本方案）** | ✅ | 性能最好（典型 2–5s/页）；参考代码完整且 Apache-2.0；踩坑记录齐全 |
| LiteRT + GPU delegate | ❌ | 需转 TFLite 模型；GPU delegate/NNAPI 厂商碎片化比 ncnn Vulkan 更严重 |
| 纯 Kotlin CPU 推理 | ❌ | waifu2x CNN 在 CPU 上每页几十秒，不可用 |
| Anime4K GLSL | ❌（可作未来轻量档） | 非 CNN，画质低于 waifu2x |

从 mihon 裁剪保留：`waifu2x.cpp`（nihui waifu2x-ncnn-vulkan 移植）+ `waifu2x_jni.cpp` + CMake 构建 + **waifu2x-models-upconv7 全部 5 个权重**（`scale2.0x` 无降噪 + `noise0..3_scale2.0x`）；砍掉 `anime4k.cpp`、`qnn_backend.cpp`、`litert_backend.cpp` 及对应模型。降噪档位只是同架构换权重文件，引擎代码零分支。

---

## 3. 模块划分

```
app/
├── src/main/cpp/                        # 新增：native 层
│   ├── CMakeLists.txt                   # 链接预编译 ncnn，glslc 编译 preproc/postproc 着色器
│   ├── waifu2x.cpp                      # 移植自 mihon（nihui waifu2x-ncnn-vulkan 裁剪版）
│   └── waifu2x_jni.cpp                  # JNI 桥：init / process(in: Bitmap, out: Bitmap)
├── src/main/assets/upscale-models/      # 新增：waifu2x upconv7 模型（.param/.bin）
│   └── waifu2x-upconv7/
│       ├── scale2.0x_model.param / .bin        # 无降噪
│       ├── noise0_scale2.0x_model.param / .bin # 轻度（对齐 iOS）
│       ├── noise1_scale2.0x_model.param / .bin # 中度
│       ├── noise2_scale2.0x_model.param / .bin # 强度
│       └── noise3_scale2.0x_model.param / .bin # 最强
└── src/main/java/com/kmworks/kmreader/core/image/upscale/   # 新增：Kotlin 层
    ├── Waifu2xBridge.kt                 # JNI 封装：模型解压、初始化、单实例互斥 process
    ├── UpscaleConfig.kt                 # 配置 data class + MODEL_SIGNATURE（缓存键的模型部分）
    ├── UpscaleDecision.kt               # 触发决策（对齐 iOS autoTriggerScale 语义）
    ├── EnhancementCache.kt              # 增强结果磁盘缓存（独立 Coil DiskCache 实例）
    ├── EnhancementDecoder.kt            # Coil3 Decoder：解码 → 查缓存 → 超分 → 写缓存
    └── ImageEnhancer.kt                 # 优先级队列：当前页 > 次页 > 预载页，代际取消
third_party/ncnn/                        # 新增（gitignored）：ncnn android-vulkan 预编译 SDK
```

各模块职责：

### 3.1 `Waifu2xBridge.kt`
- `loadLibrary("waifu2x-jni")`；`nativeInit(modelDir, config)` / `nativeProcess(input, output)` / `nativeRelease()`
- 降噪档位切换 = 换权重重新 `nativeInit`（同一份 `.param`，仅 `.bin` 不同——若 5 档 param 一致可只保留一份 param 进一步省体积，Phase 0 核实）；当前档位缓存，重复切换不重复初始化
- 首次使用将 assets 模型解压到 `cacheDir/upscale-models/`（带 `MODEL_CACHE_VERSION` 常量，模型更新时递增以失效旧解压产物）
- `process()` 用 `@Synchronized` 或 Mutex 串行化（ncnn `Net` 实例非线程安全；GPU 单队列足够）
- 输出 Bitmap 由 Kotlin 侧按 `min(scale, 纹理上限)` 计算尺寸并分配（ARGB_8888），C++ 用 `AndroidBitmap_lockPixels` 写入

### 3.2 `UpscaleDecision.kt`
对齐 iOS 语义，Android 化：

```kotlin
fun shouldUpscale(srcWidth: Int, srcHeight: Int, screenWidth: Int, screenHeight: Int, config: UpscaleConfig): Boolean
```
- `mode == Off` → false
- `mode == Always` → true
- `mode == Auto` → `min(screenW/srcW, screenH/srcH) > config.autoTriggerScale`（默认 1.5，与 iOS 对齐）
- 附加保护：源图某边已 ≥ 纹理上限 → false；有效放大倍率 < 1.1 → false

纯函数，JVM 单测覆盖。

### 3.3 `EnhancementDecoder.kt`（Coil 集成核心，见 §5）
### 3.4 `EnhancementCache.kt`（见 §6）
### 3.5 `ImageEnhancer.kt`（见 §7）

---

## 4. 构建配置

### 4.1 ncnn SDK 获取

不把预编译库提交进 Git。新增 Gradle 任务拉取并校验：

```kotlin
// build.gradle.kts (app) — 任务示意
tasks.register<Exec>("fetchNcnnSdk") {
    // 下载 https://github.com/Tencent/ncnn/releases/download/20260113/ncnn-20260113-android-vulkan.zip
    // sha256 校验后解压到 third_party/ncnn/ncnn-20260113-android-vulkan/
}
```
- 版本与 sha256 写死在 `gradle.properties`（`ncnn.version` / `ncnn.sha256`），升级时显式修改
- `third_party/ncnn/` 加入 `.gitignore`
- `preBuild` 依赖 `fetchNcnnSdk`（已存在且校验通过则跳过）

### 4.2 app/build.gradle.kts 变更

```kotlin
android {
    defaultConfig {
        ndk {
            // 仅保留真机 ABI；x86/x86_64 模拟器不支持可靠 Vulkan，放弃
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"   // AGP 自带，无需额外安装
        }
    }
    // NDK 版本固定，保证 CI 可复现
    ndkVersion = "27.2.12479018"
}
```

### 4.3 CMakeLists.txt 要点

- `NCNN_SDK_DIR` 指向 `third_party/ncnn/ncnn-20260113-android-vulkan/${ANDROID_ABI}`
- 静态链接 `libncnn.a` + `libvulkan`（dlopen）+ `android` / `log` / `jnigraphics` / `dl`
- 链接时 gc-sections 裁剪未用符号，strip 后单 ABI `.so` 预期 3–6MB
- waifu2x fused preproc/postproc 计算着色器用 `glslc` 编译为 `.spv.hex.h`（CI 需装 Vulkan SDK 的 glslc，或改用运行时 `glslang` 编译——mihon 两者都支持，首选构建期编译）
- **实际实现（2026-11-25 修订）**：改为构建期零依赖——GLSL 源码放在 `app/src/main/cpp/fused_shaders.h`，运行时用 `ncnn::compile_spirv_module`（glslang 已静态链接在 `libncnn.a` 内）编译 SPIR-V，再用 `ncnn::resolve_shader_info` 反射后交给 `Pipeline::create`。因此无需安装 Vulkan SDK，CI 与本地构建行为一致。
- 注意：预编译 ncnn 用 `NCNN_DISABLE_RTTI` 构建，`Pipeline` 子类需同 `-fno-rtti` 编译，否则链接报 `undefined symbol: typeinfo for ncnn::Pipeline`。
- **fused 路径的三条 ncnn 约束（2026-11-25 定位，`fused: probe …` 系列日志可直接看出）**：
  1. `record_pipeline()` 的 dispatcher 必须是**真实 `ncnn::Mat`**（`Mat(tw, th, 1)`，dims=3、d=1）。手填 `ncnn::VkMat` 的 w/h/c 时 `dims=0、d=0`，ncnn 由此推导 group 数，z 方向变成 0，`vkCmdDispatch(x, y, 0)` 合法但**一个 invocation 都不执行**，且 `submit_and_wait()` 仍返回 0 —— 表现为 tile 内容保持未初始化显存（netout 打出 1e25–1e37 的随机 fp32）、目标页 alpha=0，自检报 `alpha is not opaque (0 at 0)`。
  2. 上传必须用 `record_clone(Mat, VkMat, opt)`，不能用 `record_upload()`：后者在集成 GPU 上 `cast_type_to=2`（fp16），会把打包成 uint32 的 ARGB 像素当 fp32 转半精度，shader 的 `uint` 读取全是垃圾。`record_clone` 是 staging memcpy + `vkCmdCopyBuffer` 的原样字节拷贝。
  3. 下载（`record_download` / `record_clone(VkMat, Mat)`）内部 `create_like()` **重建并替换调用方的 `Mat`**，所以包着 `rgba_out` 的 `Mat` 永远收不到像素；必须下到临时 `Mat` 再按行 `memcpy`。

### 4.4 APK 体积控制

| 项 | 单 ABI 增量 |
|---|---|
| `libwaifu2x-jni.so`（strip 后） | 约 3–6MB |
| waifu2x upconv7 模型 ×5（无降噪 + noise0–3） | 约 5–7MB |
| **合计** | **约 8–13MB** |

- **Play / AAB 分发**：Google Play 按 ABI 拆分交付，用户只承担单 ABI，无需额外配置
- **直接 APK 分发**：`abiFilters` 已裁掉 x86；如需进一步控制可提供 arm64-only 构建变体
- 模型不按需下载（对齐 iOS 内置策略，避免首用等待与下载失败态）

---

## 5. Coil3 管线集成

### 5.1 为什么不用 Transformation

现有 `BorderCropTransformation` 是 Coil3 `Transformation` 的先例，但**超分不适合走 Transformation**：

1. Transformation 的结果只进 Coil 内存缓存（磁盘缓存存的是 Fetcher 产出的源数据）——一次 2–5s 的 GPU 推理在内存换出后会反复重跑，不可接受
2. 2x 页图约 33MB（如 2160×3840 ARGB_8888），进 Coil 默认内存缓存会瞬间挤掉所有其他条目并增大 OOM 风险

### 5.2 方案：自定义 Decoder + 独立磁盘缓存（mihon 模式）

```
ImageRequest(pageUrl)
   │
   ▼
EnhancementDecoder.Factory（仅当 data 是页图 URL 且开关开启时接手）
   │
   ├─ 1. 从 pageLoader 磁盘缓存/网络取源图，sampled decode（复用现有解码路径）
   ├─ 2. UpscaleDecision.shouldUpscale() ── false → 直接返回源图（零开销透传）
   ├─ 3. 查 EnhancementCache（key = pageKey#Denoise）── hit → 解码返回
   │      └─ 目标档位 miss 但同页有其它档位 → 先返回该档位，后台重算目标档位
   └─ 4. miss → ImageEnhancer.submit(当前页优先级) 同步等待
                    │
                    ▼
              Waifu2xBridge.process() → 写 EnhancementCache → 返回
```

- `EnhancementDecoder` 实现 `coil3.decode.Decoder`，包一层现有的源图解码逻辑
- 注册到 `pageLoader` 的 `components { add(EnhancementDecoder.Factory(...)) }`，**coverLoader 不注册**（封面不需要超分）
- 页图请求的 `memoryCachePolicy` 设为 `DISABLED`（仅当超分开启时）：33MB 大图不进 Coil 内存缓存；重复显示靠 EnhancementCache 磁盘命中（解码 100–300ms 可接受）。若实测翻页回退有感知，再补一个独立的 2 条目 LRU
- 源图的 `diskCacheKey` **保持纯 URL**（与磁盘预载共享同一条目），配置信息只进 EnhancementCache 键 —— 否则同一页源图会按降噪档位重复落盘

### 5.3 与现有预载的衔接

现有 `enqueueDiskPreload`（ReaderScreen.kt:2085）用 `BlackholeDecoder` 只写源图磁盘缓存、不解码，**保持不变**。超分预载走 `ImageEnhancer` 队列（§7）：按预载 profile 对后续 N 页提交低优先级增强任务，增强结果写入 EnhancementCache；用户翻到该页时 `EnhancementDecoder` 第 3 步直接命中。

### 5.4 ReaderScreen 改动点（实现阶段）

- 页图 `ImageRequest.Builder`（ReaderScreen.kt:1772 附近）：条件 `memoryCachePolicy(DISABLED)`、保留 `BorderCropTransformation`（切边在超分**之后**由 Coil 按序执行，裁的是增强后的图，cacheKey 链正确）；`diskCacheKey` 保持纯 URL
- `enqueueDiskPreload` 之后追加 `imageEnhancer.preload(pages, targetIndex)`
- 章节切换/退出阅读器时 `imageEnhancer.nextGeneration()` / `cancelAll()`

---

## 6. 增强结果磁盘缓存（EnhancementCache）

复用现有 `ImageCachePaths` / `ImageCacheStore` 的双缓存模式，新增第三种：

```kotlin
// ImageCachePaths 追加
const val KIND_ENHANCED = "enhanced"
```

- 独立 Coil `DiskCache` 实例，目录 `image_cache/<namespace>/enhanced/`，`maxSizeBytes` 默认 512MB（设置项可调）
- **缓存键 = `pageKey#DenoiseName`**，其中 `pageKey = sha256(pageUrl + MODEL_SIGNATURE)`：
  - `MODEL_SIGNATURE` = 模型架构 + 倍率 + 模型解包版本（`upconv7-s2-mv1`）——这部分变化才让整页键失效（`Waifu2xBridge.MODEL_CACHE_VERSION` 自增即可全量作废）
  - 降噪档位是**变体维度**，不是页身份：切档只 miss 目标变体，仍能读到同页其它档位的结果
  - 键布局变更时用 `SharedPreferences` 里的 layout 标记在启动时清一次缓存，避免不可达旧条目长期占着 512MB 预算
- **stale-while-revalidate**（切降噪档不再整本重算）：目标变体 miss 时先返回同页最近的已缓存变体（按档位距离取最近），同时后台重算目标变体，完成后经 `ImageEnhancer.readyKeys` 换图。代价是切档瞬间看到的是旧档位的画面，收益是首屏不再等 10s/页
- **变体上限**：每页最多保留 `MAX_VARIANTS_PER_PAGE = 2` 个档位（刚写入的 + 刚被回退使用的），写入后立即裁剪其余档位；A→B→A 来回切仍是命中，同时磁盘占用相对单档位最多翻倍而不是 5 倍
- 存储格式：首期 **PNG**（无损，实现最简单）；实测体积后可选切 WebP 无损。不用有损格式（有损压缩会抵消超分收益）
- `clearBookPages(bookId)` 扩展：逐条 evict 对应 enhanced 条目（全部降噪档位，与 pages 缓存同生命周期）
- 写入策略：先写临时文件再 rename，崩溃不留半截文件（Coil DiskCache 的 `FileSystem` 写入已具备此语义）

---

## 7. 增强队列（ImageEnhancer）

直接移植 mihon `ImageEnhancer.kt` 的调度设计：

- **优先级**：当前可见页 3 > 相邻页 2 > 提升画质 1 > 预载 0；同优先级按距目标页距离排序，再按提交序号 FIFO
- **抢占**：可见页请求到来时 `preemptForVisibleRequest()` 把低优先级任务压后
- **代际取消**：章节切换 `nextGeneration()`，旧代际任务全部作废
- **首帧保护**：`waitingForVisibleImage` 标记，避免当前页的首帧显示被预载任务拖延
- **健壮性**：单页失败/OOM 只记日志不杀队列；连续 N 次失败后本次阅读会话自动停用超分（防 Vulkan 驱动故障设备反复炸）
- 执行：单 worker 协程（Dispatchers.Default 上一专用 `limitedParallelism(1)`），调 `Waifu2xBridge.process()`

UI 响应保护（来自 mihon 踩坑记录）：检测到用户正在滑动时，每个 tile 处理完强制睡 40ms，保证 UI 线程拿到 GPU 时间。native 层实现，配置项常量。

---

## 8. 设置项（DivinaSettings 扩展）

```kotlin
// DivinaSettings.kt 追加
val upscale: UpscaleSettings = UpscaleSettings()

data class UpscaleSettings(
    val mode: UpscaleMode = UpscaleMode.Auto,        // Off / Auto / Always
    val denoise: DenoiseLevel = DenoiseLevel.Low,    // 降噪强度，见下
    val autoTriggerScale: Float = 1.5f,              // 仅 Auto 模式生效
)

enum class UpscaleMode(@param:StringRes val labelRes: Int) { Off(...), Auto(...), Always(...) }

// 降噪强度 → 模型权重映射；档位名是 EnhancementCache 的变体维度，切换只 miss 该档位
enum class DenoiseLevel(@param:StringRes val labelRes: Int, val modelSuffix: String) {
    None(R.string.upscale_denoise_none,   "scale2.0x"),        // 无降噪，纯 2x 放大
    Low(R.string.upscale_denoise_low,     "noise0_scale2.0x"), // 轻度，默认，对齐 iOS
    Medium(R.string.upscale_denoise_medium, "noise1_scale2.0x"),
    High(R.string.upscale_denoise_high,   "noise2_scale2.0x"),
    Max(R.string.upscale_denoise_max,     "noise3_scale2.0x"),
}
```

- 设置 UI 挂在阅读器设置 Sheet，沿用现有 `@StringRes labelRes` 枚举模式，中英双语资源
- 默认值：**Auto**（对齐 iOS 默认行为）；降噪 **Low / noise0**（对齐 iOS 的 noise0 模型）
- 降噪强度在选择器中用分段控件或下拉展示 5 档；首版不暴露模型架构选择、tile 尺寸等高级项
- 修改设置 → 目标档位变体键变化 → 先复用同页旧档位结果并后台重算；超出每页变体上限的条目由裁剪 + LRU 容量淘汰

同时更新：
- `DivinaSettings.kt` 顶部注释中 "Waifu2x upscaling not implemented on Android" 的说明
- README 的 iOS/Android 差异清单

---

## 9. native 层关键参数（来自 mihon 踩坑记录，直接采用）

```cpp
net.opt.use_fp16_packed     = false;
net.opt.use_fp16_storage    = false;
net.opt.use_bf16_storage    = true;    // 带宽减半且比 FP16 稳，修花屏
net.opt.use_fp16_arithmetic = false;   // FP32 运算，规避驱动兼容性花屏

tilesize   = 128;   // 单 GPU 任务短，防 UI 卡顿
prepadding = 18;    // 防黑网格线与边缘模糊
// alpha 通道缩放用 nearest（pd.set(0, 1)），保文字锐利
```

纹理上限保护：Kotlin 侧 `Canvas.getMaximumBitmapWidth()/Height()`（minSdk 28 满足），输出倍率取 `min(2, maxTexW/srcW, maxTexH/srcH)`；有效倍率 < 1.1 则跳过超分。

---

## 10. 分阶段任务清单

### Phase 0 — PoC（验证可行性，约 1–2 天）
- [ ] `fetchNcnnSdk` Gradle 任务 + CMake 骨架，出 `libwaifu2x-jni.so`
- [ ] 移植 `waifu2x.cpp` / `waifu2x_jni.cpp` 最小子集（仅 upconv7 2x），内置全部 5 档权重并核实 param 是否可合并
- [ ] 调试页写死一张测试图跑通 2x 超分，日志输出每 tile / 整页耗时
- [ ] 至少 2 台不同 GPU（Adreno + Mali）真机跑基准，确认耗时 ≤ 5s/页且无花屏
- [ ] **决策点**：基准不达标或花屏不可控 → 回退评估 Real-CUGAN / 缩小支持设备范围

### Phase 1 — 引擎层（约 2–3 天）
- [ ] `Waifu2xBridge`：模型解压、配置化 init、互斥 process、资源释放
- [ ] 应用 §9 全部参数；纹理上限截断逻辑
- [ ] `UpscaleConfig` / `UpscaleDecision` + JVM 单测
- [ ] native 崩溃保护：JNI 层捕获 C++ 异常，返回错误码而非闪退

### Phase 2 — 管线集成（约 3–4 天）
- [ ] `EnhancementCache`（KIND_ENHANCED、`pageKey#Denoise` 双层键、切档 stale-while-revalidate、每页变体上限、clearBookPages 扩展）+ 单测
- [ ] `EnhancementDecoder` + `pageLoader` 注册
- [ ] `ImageEnhancer` 队列移植（优先级/抢占/代际/首帧保护/连续失败熔断）
- [ ] ReaderScreen 接线：请求键、内存策略、预载联动、生命周期取消
- [ ] 真机回归：翻页流畅度、内存水位（沿用 `heapFootprint()` 诊断日志）、缓存命中率

### Phase 3 — 设置与打磨（约 1–2 天）
- [ ] `DivinaSettings.upscale` + 设置 Sheet UI + 中英 string resources
- [ ] 增强缓存容量设置项；缓存管理页展示 enhanced 用量
- [ ] DivinaSettings 注释与 README 差异清单更新
- [ ] 长阅读会话发热实测；必要时加热保护降档（连续增强 N 页后暂停预载增强）

### 收尾检查
- [ ] release 构建（minify + shrinkResources）验证 `.so` 与模型不被裁剪
- [ ] AAB 分包确认单 ABI 增量 ≤ 10MB
- [ ] waifu2x 模型与 ncnn 的 LICENSE 文件归置（`app/src/main/assets/` 或关于页，见 §11）

---

## 11. 风险与开放问题

| 风险 | 缓解 |
|---|---|
| Vulkan 驱动碎片化导致花屏/崩溃 | 采用 mihon 验证过的 BF16/FP32 参数；连续失败自动熔断停用；Phase 0 真机门槛 |
| 长条漫 2x 后超纹理上限 | 倍率截断 + 有效倍率阈值跳过；webtoon 模式后续单独评估 |
| 内存压力（2x 页约 33MB） | 不进 Coil 内存缓存；Bitmap 复用池；OOM 捕获不杀队列；低内存跳过增强 |
| 功耗/发热 | 默认 Auto 档；UI 忙时 tile 间睡眠；必要时热保护降档 |
| CI 无法跑 Vulkan（模拟器） | native 逻辑收敛到接口后，JVM 单测用 fake；真机验证留给手工/设备农场 |
| 模型与 ncnn 授权 | ncnn BSD-3-Clause；waifu2x-ncnn-vulkan 为 BSD 系、模型源自 nagadomi/waifu2x（MIT）——Phase 0 核实并在应用内归置 LICENSE |
| APK 体积（直接分发渠道） | abiFilters 砍 x86；必要时出 arm64-only 变体；若 5 档模型体积超预期，可只保留 None/Low/High 三档；模型不按需下载是有意取舍 |

**开放问题**（实现阶段决策，不阻塞立项）：
1. 增强结果磁盘格式 PNG vs WebP 无损——Phase 2 实测体积后定
2. 是否需要独立的 2 条目内存 LRU 优化回翻——Phase 2 实测回翻延迟后定
3. 是否后续支持 Real-CUGAN（更快更好，模型更大）——架构已预留，单独立项
