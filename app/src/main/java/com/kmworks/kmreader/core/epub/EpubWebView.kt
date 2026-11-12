package com.kmworks.kmreader.core.epub

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * EPUB WebView 资源域（mirrors EpubResourceSchemeHandler 的假 scheme 方案）。
 * 页面用 https 假域加载，章节 href 与相对资源统一走拦截转发。
 */
object EpubWeb {

    const val FAKE_BASE = "https://kmreader-epub.local"
    const val FAKE_HOST = "kmreader-epub.local"

    /** 本地字体的假域路由前缀（对齐原作 __fonts__ 虚拟前缀）。 */
    const val FONTS_PREFIX = "/fonts/"

    /** Fake-domain font file URL（服务端 css 的重写目标）。 */
    fun fontUrl(family: String, fileName: String): String =
        "$FAKE_BASE$FONTS_PREFIX${Uri.encode(family, "/")}/${Uri.encode(fileName, "/")}"

    /** 章节页面 URL：/{bookDtoId}/{href}（href 按段编码）。 */
    fun pageUrl(bookDtoId: String, href: String): String =
        "$FAKE_BASE/$bookDtoId/${Uri.encode(href, "/")}"

    /** 从假域 URL 反解 href；非假域返回 null。 */
    fun hrefFromUrl(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.host != FAKE_HOST) return null
        val segments = uri.pathSegments ?: return null
        if (segments.size < 2) return null
        return segments.drop(1).joinToString("/")
    }

    /** 资源请求完整 URL（href 原样拼进 query）。 */
    fun resourceRequestUrl(baseUrl: String, bookDtoId: String, href: String): String =
        "$baseUrl/api/v1/books/$bookDtoId/resource?$href"

    /** 媒体类型（对齐原作 mediaTypeForExtension，文本类型补 utf-8）。 */
    fun mediaTypeFor(href: String): String =
        when (href.substringAfterLast('.', "").lowercase()) {
            "xhtml", "html", "htm" -> "application/xhtml+xml"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            "xml", "opf", "ncx" -> "application/xml"
            else -> "application/octet-stream"
        }

    fun isTextMediaType(mime: String): Boolean =
        mime.startsWith("text/") || mime == "application/xhtml+xml" ||
            mime == "application/xml" || mime == "application/javascript" ||
            mime == "image/svg+xml"
}

/** 注入主题配置（EpubReaderSettings 到 CSS 覆盖的映射）。 */
data class EpubThemeConfig(
    val background: String = "#FFFFFF",
    val foreground: String = "#1A1A1A",
    val fontSizePx: Int = 18,
    val fontFamily: String = "",
    val lineHeight: Float = 1.4f,
    val marginHPx: Int = 24,
    val publisherStyles: Boolean = true,
    /** 本地字体 @font-face css（url 已重写为假域 fonts 路由）；null = 无自定义字体。 */
    val fontCss: String? = null,
) {

    /** JS 对象字面量（值均已白名单/格式化，无注入面）。 */
    fun toJs(): String {
        val safeFamily = fontFamily.replace("'", "")
        return "{bg:'$background',fg:'$foreground',fontSize:$fontSizePx," +
            "fontFamily:'$safeFamily',lineHeight:$lineHeight," +
            "marginH:$marginHPx,publisherStyles:$publisherStyles}"
    }
}

/** 注入 JS：主题覆盖 + 滚动量测上报 + 点按回调（mirrors ScrolledEpubViewController 注入思路）。 */
object EpubJs {

    const val BRIDGE = "kmBridge"

    /**
     * readerBridge 模式：滚动/resize 200ms 节流上报 metrics；
     * 点按（无文本选择）上报 tap 的 x 视口比例。
     */
    fun injectScript(config: EpubThemeConfig): String {
        val fontCssJs = org.json.JSONObject.quote(config.fontCss ?: "")
        return """
        (function() {
          window.__kmFontCss = $fontCssJs;
          if (window.__kmInjected) { window.__kmApplyTheme(${config.toJs()}); return; }
          window.__kmInjected = true;
          window.__kmApplyTheme = function(c) {
            var style = document.getElementById('km-theme');
            if (!style) { style = document.createElement('style'); style.id = 'km-theme'; document.head.appendChild(style); }
            var ff = c.fontFamily ? 'font-family:' + c.fontFamily + ' !important;' : '';
            var css = 'html,body{background:' + c.bg + ' !important;color:' + c.fg + ' !important;}'
              + 'body{padding:24px ' + c.marginH + 'px !important;margin:0 !important;font-size:' + c.fontSize + 'px !important;line-height:' + c.lineHeight + ' !important;' + ff + '}'
              + (c.publisherStyles ? '' : 'body,body *{font-size:' + c.fontSize + 'px !important;line-height:' + c.lineHeight + ' !important;' + ff + 'color:' + c.fg + ' !important;}')
              + 'img,svg,video{max-width:100% !important;height:auto !important;}';
            style.textContent = css;
            var fontStyle = document.getElementById('km-fonts');
            if (!fontStyle) { fontStyle = document.createElement('style'); fontStyle.id = 'km-fonts'; document.head.appendChild(fontStyle); }
            fontStyle.textContent = window.__kmFontCss || '';
            document.documentElement.style.background = c.bg;
            window.__kmReport();
          };
          var timer = null;
          window.__kmReport = function() {
            if (timer) return;
            timer = setTimeout(function() {
              timer = null;
              var st = window.scrollY || document.documentElement.scrollTop || 0;
              var sh = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
              var vh = window.innerHeight || 1;
              var max = Math.max(sh - vh, 1);
              var f = Math.min(Math.max(st / max, 0), 1);
              $BRIDGE.onMetrics(f.toFixed(6), st, sh, vh);
            }, 200);
          };
          window.__kmScrollToFraction = function(f) {
            var sh = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
            var vh = window.innerHeight || 1;
            window.scrollTo(0, Math.max(sh - vh, 0) * f);
          };
          window.__kmChapterFraction = function() {
            var st = window.scrollY || document.documentElement.scrollTop || 0;
            var sh = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
            var vh = window.innerHeight || 1;
            var max = Math.max(sh - vh, 1);
            return Math.min(Math.max(st / max, 0), 1);
          };
          window.addEventListener('scroll', window.__kmReport, { passive: true });
          window.addEventListener('resize', window.__kmReport);
          document.addEventListener('click', function(e) {
            var sel = window.getSelection();
            if (sel && !sel.isCollapsed) return;
            var vh = window.innerWidth || 1;
            $BRIDGE.onTap(e.clientX / vh);
          }, true);
          window.__kmApplyTheme(${config.toJs()});
        })();
    """.trimIndent()
    }
}

/** JS 桥（@JavascriptInterface 在 JS 线程回调，统一切主线程）。 */
class EpubJsBridge(
    private val onMetrics: (Float) -> Unit,
    private val onTap: (Float) -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMetrics(fraction: String, scrollTop: Int, scrollHeight: Int, viewportHeight: Int) {
        val f = fraction.toFloatOrNull() ?: return
        mainHandler.post { onMetrics(f) }
    }

    @JavascriptInterface
    fun onTap(xFraction: Float) {
        mainHandler.post { onTap(xFraction) }
    }
}

/**
 * WebViewClient：假域请求经 resolveResource 同步转发到服务端资源流；
 * 其余 http(s) 请求一律空响应拦截（对齐原作外部资源不加载策略）。
 */
class EpubWebViewClient(
    private val resolveResource: (String) -> EpubResource?,
    private val onPageReady: (WebView) -> Unit,
    private val resolveFont: (String) -> EpubResource? = { null },
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url?.toString() ?: return emptyResponse()
        if (!url.startsWith(EpubWeb.FAKE_BASE)) return emptyResponse()
        val path = request.url?.path.orEmpty()
        val resource = if (path.startsWith(EpubWeb.FONTS_PREFIX)) {
            resolveFont(path)
        } else {
            val href = EpubWeb.hrefFromUrl(url) ?: return emptyResponse()
            resolveResource(href)
        } ?: return emptyResponse()
        val encoding = resource.encoding
            ?: if (EpubWeb.isTextMediaType(resource.mimeType)) "utf-8" else null
        return WebResourceResponse(resource.mimeType, encoding, ByteArrayInputStream(resource.data))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url?.host != EpubWeb.FAKE_HOST

    override fun onPageFinished(view: WebView, url: String?) {
        onPageReady(view)
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}

/** 创建并配置阅读用 WebView（滚动模式，禁 JS 外部网络）。 */
@SuppressLint("SetJavaScriptEnabled")
fun createEpubWebView(
    context: android.content.Context,
    bridge: EpubJsBridge,
    client: EpubWebViewClient,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.loadsImagesAutomatically = true
    settings.mediaPlaybackRequiresUserGesture = true
    webViewClient = client
    addJavascriptInterface(bridge, EpubJs.BRIDGE)
}
