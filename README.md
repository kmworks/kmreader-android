# kmreader-android（设计稿）

KMReader（iOS/macOS/tvOS 原生 Komga 客户端）的 **Android 移植设计稿**。
本仓库当前是纯 UI 骨架：Jetpack Compose + 预览数据，无网络、无持久化、无真实封面图。
用途是评审信息架构、导航容器与页面布局，对齐 iOS 版设计后再接真实实现。

## 设计原则（与 iOS 版的对应关系）

- **布局 / 信息架构 / 功能集合 1:1 对齐 KMReader**；不照搬 iOS 视觉，也不添加 KMReader 没有的自创变体（无仪表盘渐变、无多列瀑布流）。
- 只做 Android 化**容器翻译**：
  - iOS TabView → Material 3 `NavigationSuiteScaffold`（手机=底部导航，平板=侧边导航栏，自动切换）
  - iOS sheet/fullScreenCover → `ModalBottomSheet`（筛选/编辑/识别/绑定）与全屏层（详情、设置、阅读器）
  - iOS contextMenu → 长按弹 `ContextMenuSheet`（`ui/components/ContextMenuSheet.kt`，工厂函数逐域对齐原作菜单项）
  - iOS 单色强调色（浅黑/深白）→ Material 3 单色 colorScheme（`ui/theme/Theme.kt`）
- **平板适配只保留 KMReader 已有的 5 点**：侧栏导航、网格列数随宽度自适应（`GridCells.Adaptive`）、宽版详情页（对应 `DetailWideLayoutView`，≥960dp 启用，信息栏宽 = max(宽×0.382, 340dp)，黄金分割小份）、弹层居中限宽、阅读器横屏双页（阅读器菜单内切换）。

## 页面与原作对照

| 设计稿 | KMReader 原作 |
| --- | --- |
| `ui/dashboard/DashboardScreen` | `Features/Dashboard`：分区列表，每分区 = 标题头(更多) + 横向卡片流；分区集合与默认卡片形态一致（继续阅读/待读/最近添加/最近更新/进行中的阅读列表，horizontal·large·small）；卡片宽度对齐 `LayoutConfig`（152/116/80/250） |
| `ui/dashboard/DashboardSectionDetailScreen` | `DashboardSectionDetailView`：分区「更多」全页，三档布局切换 + 库范围菜单 + 筛选面板 |
| `ui/dashboard/SettingsDashboardScreen` | 仪表盘自定义：分区显示开关、排序与卡片形态（`dashboardSectionSettings`） |
| `ui/dashboard/ReadListContinuationCard` | `ContinuationCardView`：进行中的阅读列表分区的续读卡（「继续阅读：第 N 本」） |
| `ui/library/LibraryScreen` | `LibraryBrowseView` 内容优先浏览：类型 chips(带计数) + 范围 caption + 工具栏库范围菜单（默认已钉选）；长按卡片弹上下文菜单 |
| `ui/lists/ListsScreen` | `ListsBrowseContentView`：收藏 / 阅读列表 / 智能列表三条横向条带（置顶在前，条带头进全页，智能列表带目标与更新时间）；长按弹菜单、菜单内进编辑 sheet |
| `ui/lists/CollectionDetailScreen` | `CollectionDetailView`：服务端缩略图 hero + 册数/有序/简介/时间戳 + 筛选工具条 + 多选 |
| `ui/lists/ReadListDetailScreen` | `ReadListDetailView`：hero + 续读提示卡 + 序号书籍行（进度/已读），多选删除 = 从列表移除 |
| `ui/lists/SmartListDetailScreen` | `SmartListDetailView`：hero（目标类型/更新时间）+ 命中网格（书籍或系列）+ 筛选工具条 |
| `ui/lists/SmartListEditSheet` | `SmartListEditSheet`：条件组构建器（字段/运算符/值、满足全部或任一、目标类型） |
| `ui/lists/CollectionEditSheet` / `ReadListEditSheet` | 对应编辑 sheet：名称/简介/有序 + 删除 |
| `ui/lists/AddToListSheet` | 加入收藏/阅读列表 picker（含新建入口、已在列表置灰） |
| `ui/offline/OfflineScreen` | `Features/Offline` 的下载管理面：存储占用 + 下载队列（暂停/继续/重试/取消）+ 每系列下载策略 + 已下载清理；不含离线模式（断网浏览态/离线横幅） |
| `ui/browse/BrowseScreen` | 搜索标签（iPhone 独立搜索 tab，工具栏含库范围菜单） |
| `ui/detail/SeriesDetailScreen` | `SeriesDetailContentView`：居中/宽栏 Hero + `DetailActionCard`（册数行 + 白色胶囊继续阅读 + 下载本系列）+ 元信息流 + chips + `TrackerSeriesSection`；更多菜单含编辑/komf；手机单列 / ≥960dp 宽版；书籍区三档布局 + 多选（`SelectionToolbar`） |
| `ui/detail/BookDetailScreen` | `BookDetailView`：系列链接行 + 标题 + 作者 chips + 元信息 + 操作卡（继续阅读白胶囊）+ 简介 + 标签/链接 + 媒体信息；宽版同系列详情 |
| `ui/detail/OneShotDetailScreen` | `OneShotDetailView`：同书籍详情骨架，无系列行与卷号 |
| `ui/detail/SeriesEditSheet.kt` | `SeriesEditSheet`：五段表单（通用/标题/标签/链接/分享标签）+ 字段锁（编辑自动上锁） |
| `ui/detail/KomfIdentifySheet.kt` | `KomfIdentifySheet`：搜索 + Provider 链接段 + 结果段，提交后 job 轮询通知 |
| `ui/components/BrowseFilter.kt` | `BookFilterView` + `BookBrowseOptionsSheet`：chip 流（[布局][预设][排序]+激活条件，排除态反色）+ 大面板（排序/阅读状态三态/标记/作者·标签多选∧∨/存预设） |
| `ui/components/ContextMenuSheet.kt` | 各域 `ContextMenu`：系列/书籍/收藏/阅读列表/智能列表工厂函数（置顶/标读/下载/加入列表/编辑/刷新/分享/删除） |
| `ui/components/SelectionToolbar.kt` | `SelectionActionsToolbar`：全选/已选计数/标读/未读/下载/加入阅读列表/删除/完成 |
| `ui/components/ConfirmDeleteDialog.kt` | 删除确认对话框（destructive） |
| `ui/reader/ReaderScreen` + `ReaderSheets.kt` | DIVINA chrome：顶栏更多菜单（方向/布局/**拆分宽页 5 档**/旋转/设置/目录/跳页/分享）；底栏缩略图条 + 全宽滑杆 + 中央页码胶囊（开 `PageJumpSheetView` 跳页）；`ReaderSettingsSheet`（外观/翻页/手势/性能/图像处理）；`DivinaTOCSheetView` 树形目录 |
| `data/ReaderPageModel.kt` + `ReaderScreen` 手势 | 页面单元生成对齐 `ReaderViewItemGenerator`：按 Komga `Media.pages[].dimensions` 判定宽图（跨页），「拆分宽页」开启时整幅平移或切前后半；封面/末页/双页配对规则同原作。手势用 Compose Pager 原语实现：横向滑动翻页（drag + fling + snap）、点按左/右 1/3 翻页、点按中间呼出 chrome、双击 1×/2.2× 缩放、双指捏合连续缩放（放大后单指平移）；竖排/条漫切换纵向 pager |
| `ui/reader/EpubReaderScreen` + `EpubSettingsSheets.kt` | EPUB 阅读器（WebView + ReadiumCSS 路线）：chrome + 阅读设置 + 主题预设 + 自定义字体入口 |
| `ui/reader/CustomFontsScreen` | EPUB 自定义字体管理（导入/删除） |
| `ui/reader/PdfReaderScreen` + `PdfPreferencesSheet.kt` | PDF 阅读器（PDFium 路线）：chrome + PDF 偏好设置 |
| `ui/tracker/*` | `Features/Tracker`：详情 chips、系列绑定管理、`TrackerLinkEditSheet`（搜索/多标题/系列链接建议/进度映射/远端进度）、设置 `TrackerAccountsView`（账号/默认/库范围/绑定管理/台账） |
| `ui/settings/SettingsScreen` + 各子页 | `Features/Settings`：外观（主题/纯黑/应用图标）/浏览/仪表盘/阅读器默认（Divina·EPUB·PDF，含拆分宽页 5 档）/Tracker 账号/阅读统计/自定义字体/缓存/网络/SSE/日志/服务器账户/关于/开源许可 |
| `ui/stats/ReadingStatsScreen` | `Features/History`：阅读统计（概览/连续天数/页面热力图） |
| `ui/login/LoginScreen` | 添加 Komga 服务器 |
| `ui/components/Components.kt` | `GridCardView` 骨架：封面 + 可选进度条 + 标题一行 + 副标题；全部卡片支持 `onLongClick` |

## 阅读器路由

`AppRoot` 按 `SampleListDetailData.bookMetaOf(book).format` 分发：CBZ/CBR → DIVINA（`ReaderScreen`），EPUB → `EpubReaderScreen`，PDF → `PdfReaderScreen`。
对齐原作「服务端 DIVINA 统一渲染图片页，EPUB/PDF 走专用引擎」的架构。

## 与 iOS 版的差异清单（有意裁剪，先不做）

- **面板分镜模式**（`DivinaPanelModeView` + CoreML 分镜检测）：不实现，仅记录。
- **超分辨率**（waifu2x `.mlmodel` 端侧超分）：不实现；后续可评估 ONNX Runtime。
- **漫画翻译**（Vision OCR + 翻译覆盖层）：不实现；后续可评估 ML Kit。
- **EPUB CoreText 排版引擎**：不做双引擎，EPUB 统一走 WebView + ReadiumCSS。
- **翻页样式「仿真卷页」**：设计稿保留选项，实现优先级低（覆盖/滚动先行）。
- **阅读中底部 accessory bar**（iOS 26）：无 Android 对应，不移植。
- **列表 tab 条带头的单类型全页**（全部收藏/全部阅读列表页）：设计稿以条带 + 详情页覆盖，全页浏览待评估。
- **离线模式**（`Features/Offline` 的断网浏览态 / 离线横幅 / 离线失败回退）：按需求不实现，仅记录；**下载与下载管理保留**（下载 tab、队列、每系列策略、各下载按钮与菜单项）。
- komf / Tracker 与原作一致做能力门控（管理员 + 服务器支持），非功能缺失。

## 构建

需要 Android Studio（Ladybug+，内置 JDK 17 与 Android SDK 35）：

```bash
./gradlew assembleDebug        # 命令行构建
```

或直接用 Android Studio 打开本目录，同步后运行 `app`。minSdk 26，targetSdk 35。

## 下一步（接真实实现时的挂点）

- `data/SampleData` 等样例数据 → Komga REST（仓库根 `openapi.json` 可生成 Kotlin 模型）+ Retrofit/OkHttp
- 封面占位 `CoverArt` → Coil + Komga 缩略图接口（书籍/系列 `/api/v1/books|series/{id}/thumbnail`；收藏 `/api/v1/collections/{id}/thumbnail`、阅读列表 `/api/v1/readlists/{id}/thumbnail`、智能列表 `/api/v1/smartlists/{id}/thumbnail`——列表封面一律用服务端生成图，客户端不再自绘拼贴）
- 卡片网格/列表 → Room（表结构参照 iOS 版 GRDB schema）
- `ReaderScreen` 页面占位 → DIVINA 页面图片流（服务端统一渲染 CBZ/CBR/EPUB/PDF）
- 仿真卷页（page curl）翻页动画不做：原作是 UIPageViewController 卷页栈；Android 侧只保留「滑动/覆盖/滚动」转场，手势本身（滑动翻页、点按分区、双击/捏合缩放）已用 Compose Pager 原语实现
- `docs/preview.html`：静态视觉预览（手机+平板对照），无需构建即可评审
