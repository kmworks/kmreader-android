# PDF 原生阅读器 iOS 对齐实施计划

> 交接文档：本文档由 EPUB 修复会话产出，供新会话直接执行。
> 仓库：`G:\Github\KMReader-android`，分支 `fix/epub-reader-href-and-divina-routing`。
> iOS 参考实现（已浅克隆）：`G:\Github\KMReader-ios-ref`。

## 0. 背景与硬约束

- Android PDF 有**两条阅读路径**，由 `use_native_pdf_reader`（默认 false）切换：
  - **DIVINA 图片路径**（默认）：PDF 渲染成图片走漫画阅读器（`ui/reader/ReaderScreen.kt`）。
    ⚠️ **用户明确裁定：DIVINA 路径不读 pdfSettings 是设计如此，不要改它。**
  - **原生 PDF 路径**：`androidx.pdf:pdf-viewer:1.0.0-beta01` 的 `PdfView`，
    入口在 `ui/AppRoot.kt:738`（`"PDF" -> if (useNativePdfReader) { ... }`）。
- **本计划只改原生 PDF 路径**，即 `app/src/main/java/com/kmworks/kmreader/ui/reader/PdfReaderScreen.kt`（642 行，UI+ViewModel 一体）及其配套文件。
- 设置模型已齐备（UI、持久化、normalize 都有），问题全在**渲染层不消费**：
  - `ui/reader/PdfPreferencesSheet.kt:26-33` `PdfReaderSettings(continuousScroll=false, pagePresentation="auto", background, soloCoverPage=true, controlsScrim, showProgressWhileReading)`
  - `PdfPreferencesSheet.kt:36-69` 四个 presentation 常量 + `normalizePdfPagePresentation`（含旧中文标签映射）
  - `core/settings/DivinaSettingsStore.kt:204-228` 持久化读写（`PDF_CONTINUOUS_SCROLL` / `PDF_PAGE_PRESENTATION` / `PDF_SOLO_COVER`）

## 1. 问题清单（原生路径）

| # | 问题 | 证据 |
|---|------|------|
| P1 | `pagePresentation` 四档只消费 dualContinuous | `PdfReaderScreen.kt:258-266`：仅 `dualContinuous → pagesPerRow=2`，其余全部落到 1 |
| P2 | `continuousScroll` 死设置 | `PdfReaderScreen.kt:417-431` 菜单可切换并持久化，但 `PdfView` 永远连续滚动，渲染层无任何消费 |
| P3 | `soloCoverPage` 死设置 | 定义/持久化齐全，原生路径零消费（漫画阅读器有自己的实现可参照：`ReaderScreen.kt:343-351,1127`） |
| P4 | 无目录（TOC） | 全文件 grep 无 outline/toc；iOS 走 PDFOutline；androidx.pdf beta01 已实证无 outline API（§3），选型 PdfBox-Android |
| P5 | 无音量键翻页 | 漫画阅读器同样没有；iOS PDF 为可选设置 |

## 2. iOS 参考锚点（G:\Github\KMReader-ios-ref）

- `KMReader/Features/Reader/Models/Pdf/PdfPagePresentation.swift`：4 值 enum（auto/singlePaged/singleContinuous/dualContinuous）+ `resolved(for size:)`——auto 按横竖屏解析（横屏→dualContinuous，竖屏→singlePaged，以源码为准）。
- `KMReader/Features/Reader/Views/Pdf/PdfDocumentView_iOS.swift:113`：`pdfView.usePageViewController(pagePresentation == .singlePaged, ...)`；L119 注释要点：**usePageViewController 会重建 PDFView 内部层级**，模式切换后必须恢复页码，并在 `[0, 0.05, 0.2, 0.5, 1.0]s` 多次纠偏（PDFView 异步布局会盖掉恢复的页码）。
- `KMReader/Features/Reader/ViewModels/Pdf/PdfReaderViewModel.swift:362,386`：`buildTOCEntry(from outline:)` 递归遍历 `PDFOutline` → `ReaderTOCEntry`，`pageIndex(from:in:)` 解析 outline 目标页。
- 音量键：在 `KMReader/Features/Reader` 下搜 volume 相关（可选设置项）。

## 3. androidx.pdf 能力评估（动手前先验证）

`androidx.pdf:pdf-viewer:1.0.0-beta01`（`app/build.gradle.kts:114-115`）：

- `PdfView` 是连续滚动容器：`pagesPerRow`、`scrollToPage(index)`、`firstVisiblePage`、`pdfDocument`。
- **无内建离散翻页** → `singlePaged` 需自行实现：手势层拦截 + `scrollToPage` 逐页驱动，或改用 Compose `HorizontalPager`+每页位图（工作量大，优先前者；可参照本会话 EPUB 侧 `core/epub/EpubScrollWebView.kt` 的 paged 手势接管方案）。
- **Outline API 已验证（javap 反编译实证，2026-11-23）**：beta01 三个 AAR（pdf-core/viewer/document-service，beta01 即 Maven 最新版）全量类列表无任何 Outline/Bookmark/Toc 类；`androidx.pdf.PdfDocument` 接口全方法不含 outline 相关 API。
  - `android.graphics.pdf.PdfRenderer` 同样没有 outline API（原结论成立）。
  - **技术选型（已定，用户批准）：PdfBox-Android（TomRoush）仅解析 outline。** 理由：纯 Java、APK +2~4MB、无 ABI/16KB 页对齐/JNI 崩溃面、TomRoush 长期维护跟踪 PdfBox 2.0.x。弃用 PdfiumAndroid（为单点需求捆绑整套 native 引擎，universal APK +8~12MB，barteksc 上游已死靠个人 fork）；弃用服务端 manifest toc（仅自家 kmrs 可能支持，原生 Komga 无效、离线不可用，且 iOS 参考实现是客户端 PDFOutline 解析）。加密 PDF 不引 BouncyCastle，空密码尝试失败即降级。
  - 演化路径：盯 androidx.pdf issue tracker，官方一旦补 outline API 即删除 PdfBox-Android 依赖、回归零成本。

## 4. 实施任务（建议顺序）

1. **P1a · auto 解析**：`normalizePdfPagePresentation` 结果若为 `auto`，按当前屏幕方向解析（横屏→dualContinuous，竖屏→singlePaged 或 singleContinuous，对齐 iOS `resolved(for:)`）；配置变化（旋转）时重解析并恢复当前页。
2. **P1b · 四档落地**：
   - singleContinuous → `pagesPerRow=1` + 连续滚动（现状）；
   - dualContinuous → `pagesPerRow=2` + 连续滚动（现状）；
   - singlePaged → 离散翻页（手势拦截 + `scrollToPage`，页间不连续滚）。
3. **P2 · continuousScroll**：off 时强制离散翻页（与 singlePaged 共用实现）；on 时按 presentation 的 continuous 形态。注意与 P1 的组合矩阵（iOS 语义：singlePaged 是唯一离散档，continuousScroll 开关在其余档内生效——以实现时读 iOS 源码为准，别拍脑袋）。
4. **P3 · soloCoverPage**：双页模式下封面单独占一页（第 1 页不配对）。`PdfView.pagesPerRow=2` 无双页配对控制，需要在文档外层做「第 0 页补空」或换渲染方式；若 PdfView 不支持，降级方案：双页模式下用自定义 pager（评估工作量后再定，可先跳过标注 TODO）。
5. **P4 · TOC**（选型已定：PdfBox-Android）：新增隔离的 `PdfOutlineExtractor` 工具类（`PDDocumentOutline` 遍历 firstChild/nextSibling → `List<TocEntry(title, pageIndex, depth)>`；`MemoryUsageSetting.setupTempFileOnly()` 避免堆内存压力；全代码库唯一 import PdfBox 的位置，便于将来整体删除）；打开文档后后台协程解析，按 `(bookId, fileLastModified)` 缓存结果；无 outline / 解析异常 / 加密失败 → 静默隐藏 TOC 入口，绝不阻断阅读；UI 复用 EPUB 阅读器的 Contents 面板模式（`EpubReaderScreen.kt` 的 TOC 底部弹层）→ 点击 `scrollToPage`。
6. **P5 · 音量键翻页**（可选，最后做）：新增设置开关；在 `PdfReaderScreen` 的 `AndroidView` 容器上接 `onKeyEvent`（KEYCODE_VOLUME_UP/DOWN），消费掉避免改系统音量。
7. **进度对账**：`PdfReaderScreen.kt:146-167` 已有 `jumpTo` + `viewModel.reportProgress(currentPage)`；离散模式下确认 `currentPage` 来源（scroll 监听在离散模式不再触发，需改由翻页动作更新）。

## 5. 环境经验（EPUB 会话踩过的坑）

- 编译：`.\gradlew.bat :app:assembleDebug --console=plain`（约 40s）。
- adb：`C:\Users\qinmo\AppData\Local\Android\Sdk\platform-tools\adb.exe -s emulator-5554`（`$env:ANDROID_HOME` 未必有值）。
- 截图：`shell screencap -p /sdcard/x.png` + `pull`（exec-out 重定向会损坏二进制）；模拟器 `/sdcard` 会写满，定期 `rm /sdcard/kmr*.png`。
- **源码是 CRLF 行尾**：跨行 edit 会因行尾不匹配失败，单行编辑更稳。
- **用户 IDE 会并行改动/回滚工作区**：每次构建前用 grep 验证自己的改动还在。
- 测试服务器：`http://localhost:25600`（模拟器内 `10.0.2.2:25600`），账号 `epubtest@local.dev / Test1234!`（kmrs，Komga 的 Rust fork）。

## 6. 验收清单

- [ ] 四档 presentation 各自表现正确，旋转屏幕 auto 重解析且页码不丢
- [ ] continuousScroll 开关在原生路径实际生效
- [ ] 双页 + soloCover 封面单页（或明确降级说明）
- [ ] TOC 面板列出目录并可跳转（PdfBox-Android 解析 outline；无目录/加密 PDF 时入口正确降级隐藏）
- [ ] 进度上报/恢复在离散与连续模式下都正确
- [ ] DIVINA 路径行为零变化
