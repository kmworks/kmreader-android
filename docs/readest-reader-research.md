# Readest EPUB 阅读器实现调研报告

> 调研对象：[readest/readest](https://github.com/readest/readest)（main 分支，v0.12.20，TypeScript / Next.js 16.4 + React 19 + Tauri 2 + zustand 5 + Tailwind 4 / daisyUI 5）。
> 阅读器代码集中在 `apps/readest-app/src/app/reader/`，主题系统在 `src/styles/themes.ts` + `src/utils/style.ts` + `src/store/themeStore.ts`。

---

## 1. EPUB 渲染方案：自维护 foliate-js fork（非 epub.js）

**渲染引擎是 foliate-js，且是 readest 自己维护的 fork**。`apps/readest-app/package.json` 中为 `"foliate-js": "workspace:*"`，`packages/foliate-js` 是 git submodule，指向 [readest/foliate-js](https://github.com/readest/foliate-js)（fork 自 johnfactotum/foliate-js）。readest 在 fork 上做了大量阅读体验修补（翻页动画、页数测量、双栏对齐等，提交引用大量 readest issue 编号）。

### 1.1 组件装配（`apps/readest-app/src/app/reader/components/FoliateViewer.tsx`，1743 行）

- 动态创建 Web Component：`document.createElement('foliate-view')`，`await import('foliate-js/view.js')`，`view.open(bookDoc)`。
- EPUB 解压与 BookDoc 构造在 `src/libs/document.ts`（fflate / @zip.js/zip.js），`bookDataStore` 缓存 BookDoc 供下次秒开。
- 进度恢复：`view.init({ lastLocation })`（EPUB CFI），无存档时 `view.goToFraction(0)`；`?cfi=` 深链覆盖存档且标记 previewMode（用户首次翻页前不写进度、不同步）。
- renderer attributes：`animated`（e-ink 强制关）、`eink`、`max-column-count` / `max-inline-size` / `max-block-size`；固定版式另有 `zoom`/`spread`/`scale-factor`/`scroll-gap`（webtoon）/`lock-pan-x`。
- 平台差异：**iOS 加 `gpu-composite` 走 GPU 合成翻页（readest#4768），而 Android 高 DPR Blink 用该路径会冻结**——Android 保持 CPU 快照路径。

### 1.2 分页实现（`packages/foliate-js` 的 `paginator.js`，约 5100 行）

核心是 **CSS multi-column 布局**——把一章 XHTML 装进一个 iframe，`column-width` + `column-fill: auto` 让内容自然分成"列"，宿主容器横向 translate 按列取页：

```js
// View.columnize()（paginator.js:1092）
setStylesImportant(doc.documentElement, {
  'box-sizing': 'border-box',
  'column-width': `${columnWidthHint}px`,
  'column-gap': vertical ? `${(marginTop + marginBottom) * 1.5}px` : `${horizontalColumnGap}px`,
  'column-fill': 'auto',
  'overflow': 'hidden',
  'overflow-wrap': 'break-word',
  'position': 'static', 'border': '0', 'margin': '0',
  'max-height': 'none', 'max-width': 'none',
  '-webkit-line-box-contain': 'block glyphs replaced', // 修 WebKit 字形裁剪
})
```

关键细节：

- 每页 = `size / columnCount` 的 tile；根元素侧 padding 放在 tile **内**（否则替换元素跨列间隙会把颜色画到下一页边距，readest#6221）。
- 双栏铺开（columnCount>1）时 `#findBreakTargets` + `#alignBreakTargets`：扫描 `break-before/after: column` 元素并注入假 div 强制对齐，保证一章第一页从双栏铺开的篇列开始。
- columnCount 由宿主（Paginator）尺寸算出，**不用 container 尺寸**（防 1↔2 列振荡）；支持 `column-gap` 覆盖时用反函数 `f⁻¹ = -x/(x-1)` 补偿外边距放大。
- **滚动模式**（`View.scrolled()`）：`column-width: auto`、`body { max-width: columnWidth; margin: auto; position: static }`（`position:static` 防 absolute/fixed 尺寸耦合 iframe 导致 expand 发散循环），并向根元素注入 `--page-margin-*` / `--full-width/height` / `--available-width/height` 供书内样式使用。
- 方向：CSS Writing Modes §8.1——主书写模式从 `body` 取，`direction` 写到 body 并注入 `body > *:not([dir]) { direction: ... }`；`vertical-rl`（中日竖排）列右向左推进。

### 1.3 翻页动画与跟手

- **View Transitions API 快照翻页**（readest#555）：翻页时对当前页截图作快照层，旧快照滑出/新快照滑入；纹理主题或书内背景先与页色合成再做快照。
- 分层 snapshot 拖拽跟手：touchstart 快照当前页作可拖层跟手指，松手后 `snap()` 按"速度×2 vs 距离阈值"决定提交还是回弹（`paginator.js:2947`）。
- e-ink 模式关闭 snapping 动画；Android Blink 在高分辨率页面上快照准备会阻塞 UI 约 1s，有特判降级。

### 1.4 relocate 合帧与页数测量（FoliateViewer.tsx:285-418）

```ts
// foliate 的 relocate 事件在一次滑动 burst 中触发多次；
// 曾用 requestIdleCallback，但 Android 上 rIC 队列堆积 → 28s 会话累计 2s+ 卡顿，
// 改为 rAF 合帧：每帧只提交最后一次 relocate。
```

- `commitRelocate`：`atEnd` 时 `currentPage = total - 1` 钳制；`setProgress` 写入 cfi/tocItem/pageItem/section/pageInfo/time/range/fraction。
- 页数（"本章第 x/y 页"）：默认 `stablePageNumbers` 用**字节估算**（章节 size 占全书比例）；可选实测校准 `chapterScreens.ts`（`CHUNKED_SECTION_THRESHOLD` 以下的小节在成为 primary 时记录实测列数，章节边界冻结快照修正估算，绝不在章中改数以免破坏一页一翻节奏；大章 chunked 渲染则保持字节估算）。
- `PositionFollowers` 组件把 progress sync 订阅隔离，避免翻页时整棵 React 树重渲染。

### 1.5 内容变换管线（FoliateViewer.tsx:551-699）

`book.transformTarget` 的 `'data'` 事件拦截每个资源：CSS → `transformStylesheet`；SVG → `sanitizeSvg`；XHTML → `transformContent(ctx)` 跑 11 个 transformer（epubSwitch / style / punctuation / footnote / whitespace / language / sanitizer / simplecc / nbsp / proofread / warichu）。`'load'` 事件控制 `allow = viewSettings.allowScript`（书内脚本默认禁用）、书内 fonts/ 字体若与用户自定义字体同名则替换为 blobUrl。`docLoadHandler` 里做约 20 项 DOM 修复（命名空间属性补回、链接热区、视口单位、图片样式、主题 class 等）。

---

## 2. 顶栏 / 底栏样式与主题适配

### 2.1 HeaderBar（`app/reader/components/HeaderBar.tsx`，402 行）

```tsx
// 本体：纯色底 + 细阴影 + 300ms 过渡，不用半透明/毛玻璃
<header className={clsx(
  'header-bar bg-base-100 absolute top-0 z-10 flex h-11 w-full items-center pr-4 shadow-xs',
  'transition-[opacity,margin-top] duration-300',
  isVisible ? 'opacity-100 pointer-events-auto' : 'opacity-0',
)} style={{ marginTop: Math.max(insets.top, statusBarHeight) }}>
```

- **不是半透明毛玻璃**：顶栏是 daisyUI `bg-base-100` 纯色 + `shadow-xs`。全库 `backdrop-blur` 只出现在浮层控件（PageNavigationButtons 翻页按钮、Toast、ImageViewer 等），顶/底栏本身不用。
- 可见性：外层 wrapper `pointer-events-none` + `paddingTop: safe-area`（#5429：刘海区长按被吞的教训）；移动端无 hover，靠点按正文切换（usePagination 的中央点击区），悬停触发条 `pointer-events-none`。
- 桌面端：顶部悬停触发条高度跟随内容顶（不固定 44px，避免盖住正文首行）；隐藏延迟 100ms 防跨目标闪烁；`mouseleave` 用坐标判定（y=0 顶边算内部，#7015）；指针直接穿进书区时触发条补 mouseleave（#6999）。
- 布局：左侧 侧栏开关 / 回书库 / 书签 / 翻译 / DuoBook；中央标题 `text-xs font-semibold line-clamp-1 max-w-[50%]` 绝对居中；右侧 Notebook / ViewMenu / Tauri 窗口按钮。`React.memo` 导出避免翻页重渲染。下拉打开时 `header-bar-pinned` 保持显示。

### 2.2 FooterBar（`app/reader/components/footerbar/FooterBar.tsx`，348 行）

```tsx
<div className={clsx(
  'footer-bar shadow-xs bottom-0 z-10 transition-[opacity,transform] duration-300',
  isVisible ? 'pointer-events-auto translate-y-0 opacity-100'
            : 'translate-y-full opacity-0',
)}>
```

- 渐进显示 = `opacity` + `translate-y-full` 300ms 过渡（底栏从底部滑入，顶栏只淡入）。
- 隐藏时 progress 订阅置 null（`isVisible ? s.progresses[bookKey] : null`）避免翻页时重渲染隐藏底栏；显示时用"渲染期同步 state"追平进度。
- 桌面端底部 52px invisible 悬停触发条；移动端（MobileFooterBar）由 ColorPanel / NavigationPanel / FontLayoutPanel + NavigationBar 组合，bottomOffset = `gridInsets.bottom * 0.33 + 64px`（safe area 适配）。
- Android 返回键：栏显示时 `acquireBackKeyInterception` + 监听 `native-key-down`（Back/Escape 关栏）。
- RTL：`dir={viewSettings?.rtl ? 'rtl' : 'ltr'}`，导航箭头图标交换。
- DesktopFooterBar：prev/next section、prev/next page、`view.history.back/forward`（阅读历史跳转）、页码输入、进度滑块（拖动时 previewFraction 预览 + 100ms debounce + `goToFraction`）、TTS。

### 2.3 顶底栏"背板"样式决策（`app/reader/utils/headerFooterStyle.ts`）

- `getChromeChip(viewSettings, 'header'|'footer', {...})`：背景选项 auto / none / #hex。
  - auto：仅滚动模式且底栏浮于文字上时给主题 pill（`bg-base-100/85`）；
  - 自定义色：`withOpacity(hex, 0.85)` 拼 `#rrggbbaa` 8 位 hex——**半透明是这么做的，不是 CSS opacity 整块半透明**；
  - 竖排模式一律无背板。
- e-ink 设备：字号默认 14（普通 12），永不自定义文字色。
- `isChromeStyled` 决定固定版式页是否放弃 `mix-blend-difference` 兜底。

### 2.4 底栏空间预留（`app/reader/utils/footerBand.ts`）

`footerReservesBand(viewSettings)`：sticky 进度条恒保留；滚动模式仅 `solidScrolledFooter` 时保留（否则信息以 shrink-wrap pill 浮于文字上）；分页模式仅当实际显示信息（剩余时间/页数/进度/时钟/电量）时保留。`getCornerClearance(radius, textBottom)` 用 `R - sqrt(R² - rise²) + 4` 计算圆角屏幕底部弧线的水平避让 padding。

---

## 3. 书籍结束页 / 上一本·下一本续读：Readest 没有此功能

全库搜索 `finished / bookEnd / endOfBook / completed / nextBook` 均无对应 UI。实际情况：

- **书尾没有结束页**：读到最后一页只是 paginator 的 `atEnd` 状态，FoliateViewer 做 `currentPage = total - 1` 页码钳制（FoliateViewer.tsx:394-396），再翻不动。
- **没有"读完续读下一本"**：`ReaderContent.tsx:78` 的 `getNextBookKey` 是"关闭当前书后切换到另一本**已打开**的书"（多书并存 / BooksGrid 网格视图），不是按书库顺序续读。
- 书库侧有阅读状态标记（SetStatusAlert：标记已读等），但阅读器内无结束过渡。

**这是 Readest 的功能空白点**——"读完显示结束页 + 一键续读下一本"恰恰是 KMReader 可以做出差异化的地方。

---

## 4. 主题系统

### 4.1 主题定义（`src/styles/themes.ts`，268 行）

- `ThemeMode = 'auto' | 'light' | 'dark' | 'ambient'`（ambient = 环境光传感器 lux 驱动，Android 优先）；`ThemeScope = 'library' | 'reader'`（书库与阅读器主题分离，#5945）。
- 内置 11 主题（default / gray / sepia / grass / cherry / sky / solarized / gruvbox / nord / contrast / sunset），每个主题只定义 **fg / bg / primary 三基色 × 明暗两套**。例如 sepia：light `{fg:'#5b4636', bg:'#f1e8d0', primary:'#008b8b'}`，dark `{fg:'#ffd595', bg:'#342e25'}`。e-ink 默认 contrast（纯黑白）。
- `generateLightPalette / generateDarkPalette`：由三基色用 tinycolor 派生完整 daisyUI palette（base-200 = darken(5)/lighten(5)，base-300 = ±12，neutral = bg±15 去饱和 20，content 色朝白/黑 mix 80%）。
- 自定义主题（ThemeEditor.tsx）：用户每明暗各填 fg/bg/primary 六色，实时预览，`name = md5Fingerprint(label)`；`applyCustomTheme` 把 palette 转成 `[data-theme="name-light"/"name-dark"]` 两块 CSS 注入 `<style id="theme-{name}-styles">`。

### 4.2 注入书内的样式（`src/utils/style.ts`，96KB；核心 `getStyles` :1299-1374）

每节 XHTML 注入一串生成 CSS：

```
@namespace epub ... 
+ customFontFaces（@font-face blob URL，须先于内容加载避免字体闪换）
+ pageLayout（页边距/栏）
+ paragraphLayout（对齐/连字/缩进/行高）
+ cjkLineFlow + fontStyles + colorStyles
+ dialogue + translation + warichu + ruby
+ userStylesheet（用户自定义 CSS 末尾追加）
```

`getColorStyles` 要点：

- 在 html 上设 CSS 变量 `--theme-bg-color / --theme-fg-color / --theme-primary-color / --bg-texture-id / --override-color` 和 `color-scheme: dark|light`——**书内所有主题感知样式都经 CSS 变量，不动书自己的规则**。
- `overrideColor` 开启时对块级元素 `background/color/border-color` `!important` 覆盖（表格单元格特殊处理保住白字）。
- 暗色模式下不 override 时，把书内内联白色背景替换为主题 bg（`getDarkModeLightBackgroundOverrides`）。
- 图片暗色模式：`invert(100%)` 或 `invert(1) hue-rotate(180deg) + mix-blend-mode: screen`（行内图/单色示意图）；override 且非反色时 `mix-blend-mode: multiply`。
- `body.theme-dark` 背景必须 transparent（否则盖住纹理）。
- 有针对 Gutenberg / Feedbooks / calibre / 多看（duokan-*）产书的专用修正。

`transformStylesheet(css, vw, vh, vertical, isFixedLayout)` 对书自带的 CSS 做源码级改写（移动端 fontScale 1.25、px/pt→rem 并包 `max(..., var(--min-font-size, 8px))`、`vw/vh`→`calc(N*var(--readest-vw/vh))`、`@media` 宽高条件对阅读区尺寸求值恒真/恒假、`background-attachment: fixed`→`scroll`（多列长条 iframe 上 fixed 背景会错位糊字 #5711）、`font-family: serif/sans-serif` 泛型族改写指向用户字体链、`color:black/#000`→`var(--theme-fg-color)` 等几十条规则）。

### 4.3 DOM 侧适配（同一文件后半）

- `applyThemeModeClass`：body 加 `theme-dark|theme-light`；`applyScrollModeClass` 加 `scroll-mode|paginated-mode`；`applyEinkModeAttribute` 镜像 `data-eink`（供 userStylesheet 分支）。
- `applyNamespacedAttributes`：srcdoc 经 HTML 解析丢命名空间，手动 setAttributeNS 补回 `epub:type` 等（否则书内 `epub|type` 选择器失效）。
- `applyViewportUnits`：在根元素注入 `--readest-vw/vh`。
- `applyImageStyle`：**两阶段 read-then-write 避免强制重排**（N 次 layout→1 次）；百分比宽高折算 px。
- `applyLinkHitArea`：`a::before { inset: -Npx }` 扩大点击区，仅当 `document.links <= MAX_ENLARGED_LINKS`（273k 链接的词典 1.3s→10s 的排版教训）。
- `applyFixedlayoutStyles`：Chrome Android 的文字自动放大（text autosizing）会破坏逐字定位文本 → `-webkit-text-size-adjust: none`（#5641）。

---

## 5. 值得 KMReader（Android WebView EPUB 阅读器）借鉴的具体细节

按价值排序：

1. **foliate-js 而非 epub.js**：CSS multi-column 分页 + 单 iframe/章节 + View Transitions 快照翻页，架构比 epub.js 的 rendition 简洁且性能好。可以直接用 readest 的 fork（AGPL-3.0 注意传染性）或上游 johnfactotum/foliate-js（MIT）。
2. **relocate 事件 rAF 合帧，绝不用 requestIdleCallback**——Android 上 rIC 队列堆积造成"松手后卡一下"，这是实测 2s+/28s 的教训（FoliateViewer.tsx:285-301）。
3. **Android 高 DPR 不走 GPU 合成翻页路径**（`gpu-composite` 只给 iOS，readest#4768）；Android Blink 大页面快照准备阻塞 UI ~1s，需要降级策略。
4. **隐藏中的顶/底栏不订阅进度流**，显示时才"渲染期同步 state"追平——避免翻页时不可见 UI 的重渲染。
5. **点按分区在列内测量**：双栏时页满图/表只占一列，中央区按 column 算（#6745）；中央 37.5%~62.5% 切栏、左右翻页、栏已显示时先关栏再翻页；media 中央点击开大图查看器。音量键翻页要做 `volumeTurnInFlight` 防连发（导航 promise settle 后才复位）。
6. **主题注入 = CSS 变量 + 源码级 CSS 改写两层**：html 上设 `--theme-bg-color` 等变量和 `color-scheme`，书内主题感知样式全走变量；书自带 CSS 用 transformStylesheet 做 px→rem、vw/vh 变量化、`@media` 求值等改写。用户自定义 CSS 永远追加在最后。
7. **三基色派生完整调色板**：内置主题每套只存 fg/bg/primary × 明暗共 6 色，base-200/300、neutral、content 色全部算法派生，自定义主题成本极低。
8. **顶底栏半透明用 8 位 hex（`#rrggbbaa`）而非元素 opacity**，纯色时用 `bg-base-100 + shadow-xs`；竖排模式一律无背板；底栏是否预留空间由实际显示内容决定（sticky 进度条恒预留，pill 浮动则不预留）。
9. **safe area / 圆角细节**：外层 wrapper 吃刘海 padding（#5429），底栏 bottomOffset = `inset.bottom * 0.33 + 64px`，圆角屏幕底部弧线用 `R - sqrt(R²-rise²) + 4` 算水平避让。
10. **Android 返回键拦截**：栏显示时 acquireBackKeyInterception，Back/Escape 先关栏而不是退出阅读器。
11. **WebView 兼容红线**：readest 的 browserslist 目标 chrome 92 / safari 15.2，构建后脚本检查禁用 optional chaining 和 lookbehind regex；`-webkit-text-size-adjust: none` 防文字自动放大破坏定位（#5641）；`img { -webkit-touch-callout: none }` 防长按图片冻结（WebView callout bug）。
12. **页数测量策略**：默认字节估算（stablePageNumbers），可选在章节边界用实测列数冻结校准，绝不在章中改总页数——保证"一页一翻"节奏不被打破。
13. **方向/竖排**：`direction` 写到 body + 注入 `body > *:not([dir])` 规则；连续滚动下只有 primary section"代表全书"方向，防止晚加载的横向 front matter 覆盖竖排书的 RTL/vertical 设置。
14. **功能空白即机会**：Readest 没有书籍结束页/续读下一本、阅读器内无"读完"仪式——结束页 + 一键续读是可以直接超车的点。

---

## 关键文件索引

| 主题 | 路径 |
|---|---|
| 阅读器主视图 | `apps/readest-app/src/app/reader/components/FoliateViewer.tsx` |
| 分页引擎（foliate fork） | `packages/foliate-js` → readest/foliate-js `paginator.js` |
| 翻页/点按/音量键 | `apps/readest-app/src/app/reader/hooks/usePagination.ts` |
| 顶栏 | `apps/readest-app/src/app/reader/components/HeaderBar.tsx` |
| 底栏 | `apps/readest-app/src/app/reader/components/footerbar/{FooterBar,MobileFooterBar,DesktopFooterBar}.tsx` |
| 顶底栏背板/避让 | `apps/readest-app/src/app/reader/utils/{headerFooterStyle,footerBand}.ts` |
| 主题定义/派生 | `apps/readest-app/src/styles/themes.ts` |
| 书内样式注入 | `apps/readest-app/src/utils/style.ts`（`getStyles`/`transformStylesheet`） |
| 主题状态 | `apps/readest-app/src/store/themeStore.ts` |
| 自定义主题编辑器 | `apps/readest-app/src/components/settings/theme/ThemeEditor.tsx` |
| BookDoc 构造 | `apps/readest-app/src/libs/document.ts` |
