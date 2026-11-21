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
 * EPUB WebView resource domain (mirrors the fake-scheme approach of EpubResourceSchemeHandler).
 * Pages load under an https fake domain; chapter hrefs and relative resources are uniformly intercepted
 * and forwarded.
 */
object EpubWeb {

    const val FAKE_BASE = "https://kmreader-epub.local"
    const val FAKE_HOST = "kmreader-epub.local"

    /** Fake-domain route prefix for local fonts (mirrors the original __fonts__ virtual prefix). */
    const val FONTS_PREFIX = "/fonts/"

    /** Fake-domain font file URL (the rewrite target of the server CSS). */
    fun fontUrl(family: String, fileName: String): String =
        "$FAKE_BASE$FONTS_PREFIX${Uri.encode(family, "/")}/${Uri.encode(fileName, "/")}"

    /** Chapter page URL: /{bookDtoId}/{href} (href segment-encoded). */
    fun pageUrl(bookDtoId: String, href: String): String =
        "$FAKE_BASE/$bookDtoId/${Uri.encode(href, "/")}"

    /** Resolve the href back from a fake-domain URL; null for non-fake domains. */
    fun hrefFromUrl(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.host != FAKE_HOST) return null
        val segments = uri.pathSegments ?: return null
        if (segments.size < 2) return null
        // Normalized so the zip lookup and the resource cache agree on one spelling per file.
        return EpubHref.normalize(segments.drop(1).joinToString("/"))
    }

    /**
     * Path-style resource URL: `/resource/<href segments>`. Preferred over the legacy query form
     * because it keeps the href a path (the reader looks resources up by path), and it is the form
     * Komga documents; the query form stays available as a fallback for older servers.
     */
    fun resourcePathUrl(baseUrl: String, bookDtoId: String, href: String): String {
        val path = EpubHref.normalize(href)
        return "$baseUrl/api/v1/books/$bookDtoId/resource/" +
            path.split('/').joinToString("/") { Uri.encode(it) }
    }

    /** Legacy resource URL (href joined verbatim into the query); kept as a fallback. */
    fun resourceRequestUrl(baseUrl: String, bookDtoId: String, href: String): String =
        "$baseUrl/api/v1/books/$bookDtoId/resource?$href"

    /** Media type (mirrors the original mediaTypeForExtension; text types get a utf-8 suffix). */
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

/** Injected theme configuration (mapping of EpubReaderSettings to CSS overrides). */
data class EpubThemeConfig(
    val background: String = "#FFFFFF",
    val foreground: String = "#1A1A1A",
    val fontSizePx: Int = 18,
    val fontFamily: String = "",
    val lineHeight: Float = 1.4f,
    val marginHPx: Int = 24,
    val publisherStyles: Boolean = true,
    /** Local @font-face css (urls already rewritten to the fake-domain fonts route); null = no custom fonts. */
    val fontCss: String? = null,
    /** The story reads right-to-left: the document root gets dir="rtl" (mirrors the iOS page builder). */
    val rtl: Boolean = false,
) {

    /** JS object literal (values are whitelisted/formatted — no injection surface). */
    fun toJs(): String {
        val safeFamily = fontFamily.replace("'", "")
        return "{bg:'$background',fg:'$foreground',fontSize:$fontSizePx," +
            "fontFamily:'$safeFamily',lineHeight:$lineHeight," +
            "marginH:$marginHPx,publisherStyles:$publisherStyles,rtl:$rtl}"
    }
}

/** Injected JS: theme overrides + scroll measurement reporting + tap callbacks (mirrors the injection approach of ScrolledEpubViewController). */
object EpubJs {

    const val BRIDGE = "kmBridge"

    /**
     * readerBridge pattern: scroll/resize metrics are throttled to 200ms before reporting;
     * a tap (without a text selection) reports its x viewport ratio.
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
            // Reading direction: a right-to-left story gets dir='rtl' on the root element, which is
            // what the iOS page builder does. Attributes set here are marked, and only those are
            // removed again: a publisher-declared dir='rtl' must survive when the series says LTR.
            var root = document.documentElement;
            if (c.rtl) {
              if (root.getAttribute('dir') !== 'rtl') root.setAttribute('dir', 'rtl');
              root.setAttribute('data-km-rtl', '1');
            } else if (root.getAttribute('data-km-rtl') === '1') {
              root.removeAttribute('dir');
              root.removeAttribute('data-km-rtl');
            }
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

/** JS bridge (@JavascriptInterface callbacks arrive on the JS thread; everything hops to the main thread). */
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
 * WebViewClient: fake-domain requests are synchronously forwarded to server resource streams via
 * resolveResource; every other http(s) request is intercepted with an empty response (mirrors the
 * original do-not-load-external-resources policy).
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

/** Create and configure the reading WebView (scroll mode, external network disabled for JS). */
@SuppressLint("SetJavaScriptEnabled")
fun createEpubWebView(
    context: android.content.Context,
    bridge: EpubJsBridge,
    client: EpubWebViewClient,
    onBottomOverscroll: (() -> Unit)? = null,
): EpubScrollWebView = EpubScrollWebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.loadsImagesAutomatically = true
    settings.mediaPlaybackRequiresUserGesture = true
    webViewClient = client
    addJavascriptInterface(bridge, EpubJs.BRIDGE)
    this.onBottomOverscroll = onBottomOverscroll
}
