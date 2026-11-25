package com.kmworks.kmreader.core.epub

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
    /** Manifest-declared vertical writing (readingProgression ttb/btt): pins writing-mode on
     *  the root. Content-declared vertical is detected from computed styles instead. */
    val vertical: Boolean = false,
    /** paged = CSS multi-column horizontal pages driven by native gestures; scrolled = vertical flow. */
    val paged: Boolean = false,
    /** Columns per page in paged mode (1 or 2; mirrors the iOS --USER__colCount auto→2 on wide screens). */
    val colCount: Int = 1,
    /** CSS font-weight (100–900) applied to the body text. */
    val fontWeight: Int = 400,
    /** Smooth scroll animation on page turns (the animatePageTurns setting). */
    val animatePageTurns: Boolean = true,
    /** Extra top/bottom content inset (CSS px) reserving room for the header/footer info
     *  overlay, so page text never slides under it (mirrors the iOS containerInsets +24pt). */
    val insetVPx: Int = 0,
    /** True when the resolved background/foreground pair is a dark theme: enables the dark-mode
     *  text/link rewrites and the monochrome/inline image inversion in the page JS
     *  (iOS makeDarkTextColorOverrideCSS / readest imageClassifier parity). */
    val darkTheme: Boolean = false,
) {

    /** JS object literal (values are whitelisted/formatted — no injection surface). */
    fun toJs(): String {
        val safeFamily = fontFamily.replace("'", "")
        return "{bg:'$background',fg:'$foreground',fontSize:$fontSizePx," +
            "fontFamily:'$safeFamily',lineHeight:$lineHeight," +
            "marginH:$marginHPx,publisherStyles:$publisherStyles,rtl:$rtl,vertical:$vertical," +
            "paged:$paged,colCount:$colCount,fontWeight:$fontWeight,animate:$animatePageTurns," +
            "insetV:$insetVPx,darkTheme:$darkTheme}"
    }
}

/**
 * Injected JS: theme overrides + scroll/pagination state machine + tap callbacks.
 *
 * Paged mode mirrors the iOS WebPubPagedJavaScriptBuilder pipeline: CSS multi-column layout turns
 * the chapter into a horizontal strip of exactly `ceil(scrollWidth / viewportWidth)` pages
 * (column width = vw/colCount - 2*margin, column gap = 2*margin, so the strip width is an exact
 * multiple of the viewport); the native side disables user scrolling and drives `scrollTo`
 * itself; fonts.ready + a measure pass produce the page count, and a ResizeObserver re-measures
 * when images finish loading. RTL books page along the negative scroll axis, so positions are
 * always computed through |scrollX| and the direction multiplier `dir`.
 */
object EpubJs {

    const val BRIDGE = "kmBridge"

    /**
     * readerBridge pattern: scroll/resize metrics are throttled to 200ms before reporting;
     * a tap (without a text selection) reports its x viewport ratio.
     */
    fun injectScript(config: EpubThemeConfig): String {
        val fontCssJs = org.json.JSONObject.quote(config.fontCss ?: "")
        return """
        (function() { try {
          window.__kmFontCss = $fontCssJs;
          if (window.__kmInjected) { window.__kmApplyTheme(${config.toJs()}); return; }
          window.__kmInjected = true;
          window.__kmGen = 0;

          // Pin the layout viewport to the device width (mirrors the iOS page builder's meta).
          // Without this the WebView auto-fits wide content: once the paged column strip grows
          // past one screen, the layout viewport silently widens (e.g. 411 -> 1645 CSS px) and
          // every vw-based measure and clientX-based tap-zone ratio breaks in different units.
          var head = document.head || document.getElementsByTagName('head')[0];
          if (head) {
            var metas = head.querySelectorAll('meta[name="viewport"]');
            for (var mi = 0; mi < metas.length; mi++) metas[mi].remove();
            var meta = document.createElement('meta');
            meta.name = 'viewport';
            meta.content = 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no';
            head.appendChild(meta);
          }

          var S = { c: null, paged: false, vw: 1, vh: 1, pages: 1, page: 0, dir: 1,
                    animate: true, marginH: 24, ready: false, pendingFrac: null, quiet: false,
                    // Bumped by every navigation attempt; a deferred scroll re-assert only runs
                    // while its generation is still the current one (see goToPage/cancelSnap).
                    pageGen: 0,
                    // Vertical writing mode (writing-mode: vertical-rl): paged columns stack
                    // DOWNWARD, so pagination runs along scrollTop — always positive, no dir
                    // flip (foliate: "vertical books page along scrollTop, which never flips")
                    vertical: false };

          function doc() { return document.documentElement; }
          function sx() { return window.scrollX || window.pageXOffset || 0; }
          function sy() { return window.scrollY || window.pageYOffset || 0; }

          // ---------- position <-> fraction ----------
          function fraction() {
            if (S.paged && !S.vertical) {
              var maxX = Math.max((S.pages - 1) * S.vw, 1);
              return Math.min(Math.max(Math.abs(sx()) / maxX, 0), 1);
            }
            if (S.paged && S.vertical) {
              var maxY = Math.max((S.pages - 1) * S.vh, 1);
              return Math.min(Math.max(sy() / maxY, 0), 1);
            }
            var sh = Math.max(doc().scrollHeight, document.body ? document.body.scrollHeight : 0);
            var vh = window.innerHeight || 1;
            var max = Math.max(sh - vh, 1);
            return Math.min(Math.max(sy() / max, 0), 1);
          }
          window.__kmChapterFraction = fraction;

          function restoreFraction(f, animate) {
            if (S.paged) { goToPage(Math.round(f * (S.pages - 1)), animate); return; }
            var sh = Math.max(doc().scrollHeight, document.body ? document.body.scrollHeight : 0);
            var vh = window.innerHeight || 1;
            window.scrollTo(0, Math.max(sh - vh, 0) * f);
          }
          // Restores queue until the first measure pass: before it, the paged page count is unknown.
          window.__kmScrollToFraction = function(f) {
            if (!S.ready) { S.pendingFrac = f; return; }
            restoreFraction(f, false);
          };

          // ---------- paging ----------
          function goToPage(i, animate) {
            if (!S.paged) return;
            S.page = Math.max(0, Math.min(S.pages - 1, Math.round(i)));
            cancelSnap();
            var animating = !!(animate && S.animate);
            if (S.vertical) {
              var y = S.page * S.vh;
              if (animating) { animateScrollTo(y, true); }
              else { window.scrollTo(0, y); }
            } else {
              var x = S.dir * S.page * S.vw;
              if (animating) { animateScrollTo(x, false); }
              else { window.scrollTo(x, 0); }
            }
            // A direct scrollTo is exact, but the compositor can still adjust or drop the offset
            // on the next frame (see animateScrollTo), leaving the strip between two pages.
            // Verify once on the following frame unless a newer navigation superseded this one;
            // the animated path verifies itself when its last frame lands.
            var gen = S.pageGen;
            if (!animating) {
              requestAnimationFrame(function() {
                if (gen !== S.pageGen) return;
                var want = S.vertical ? S.page * S.vh : S.dir * S.page * S.vw;
                var now = S.vertical ? sy() : sx();
                if (Math.abs(now - want) > 1) {
                  if (S.vertical) { window.scrollTo(0, want); } else { window.scrollTo(want, 0); }
                }
              });
            }
            // The Kotlin side keeps its page state exclusively from this callback: every turn
            // must report, or the next tap/swipe computes its target from a stale page.
            // Suppressed while repaginate performs its restore (S.quiet): scrollTo updates the
            // offset synchronously but the COMPOSITOR rasterizes the new position a frame
            // later — notifying here would lift the alpha gate in the same frame and flash the
            // stale raster (the previous scroll position, i.e. the chapter start) for one or
            // two frames before the restored page paints. repaginate notifies after a double
            // rAF instead (the iOS controller likewise delays its ready signal by 16-60ms).
            if (!S.quiet) { notifyPages(); report(); }
          }
          window.__kmGoToPage = goToPage;

          // Page-turn animation driven by rAF on the main thread. Chromium's own
          // scrollTo({behavior:'smooth'}) runs on the compositor in Android WebView: a
          // programmatic snap during the animation gets overridden, and the strip is left
          // resting between two pages (cut glyphs on both edges). Main-thread steps are
          // exact and cancellable.
          function animateScrollTo(target, alongY) {
            var start = alongY ? sy() : sx();
            var delta = target - start;
            var gen = (S.pageGen = (S.pageGen || 0) + 1);
            if (Math.abs(delta) < 1) { window.scrollTo(alongY ? 0 : target, alongY ? target : 0); return; }
            var dur = 260;
            var t0 = null;
            function step(ts) {
              if (t0 == null) t0 = ts;
              var k = Math.min(1, (ts - t0) / dur);
              var e = k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
              var pos = start + delta * e;
              window.scrollTo(alongY ? 0 : pos, alongY ? pos : 0);
              if (k < 1) { S.animId = requestAnimationFrame(step); }
              else {
                S.animId = null;
                window.scrollTo(alongY ? 0 : target, alongY ? target : 0);
                // The compositor owns the scroll offset for a frame after the last step, so a
                // snap written inside it can still be dropped or adjusted. Re-assert once on
                // the next frame unless a newer navigation took over in the meantime.
                requestAnimationFrame(function() {
                  if (gen !== S.pageGen) return;
                  var now = alongY ? sy() : sx();
                  if (Math.abs(now - target) > 1) window.scrollTo(alongY ? 0 : target, alongY ? target : 0);
                });
              }
            }
            S.animId = requestAnimationFrame(step);
          }

          function cancelSnap() {
            if (S.snapTimer) { clearTimeout(S.snapTimer); S.snapTimer = null; }
            if (S.animId) { cancelAnimationFrame(S.animId); S.animId = null; }
            // Invalidate any deferred re-assert from a navigation that is no longer current.
            S.pageGen = (S.pageGen || 0) + 1;
          }

          // A page turn must rest exactly on a page boundary. Two things move the strip off the
          // grid after the turn has landed: a rAF snap interrupted before its last frame (the
          // WebView stops drawing mid-gesture) and a reflow that redistributes the columns while
          // body's box keeps its size (an image finishing in an EARLIER column, a font swap).
          // Chromium's scroll anchoring only compensates the BLOCK axis, so a horizontal paged
          // strip slides silently and the reader ends up between two pages — the previous
          // column's glyph tails at the left edge and the current column clipped on the right.
          function settlePage(animate) {
            if (!S.paged || !S.ready) return;
            var want = S.vertical ? S.page * S.vh : S.dir * S.page * S.vw;
            var now = S.vertical ? sy() : sx();
            if (Math.abs(now - want) <= 1) return;
            if (animate && S.animate) { animateScrollTo(want, S.vertical); }
            else if (S.vertical) { window.scrollTo(0, want); }
            else { window.scrollTo(want, 0); }
          }

          // Interactive page drag (mirrors the iOS pan-to-turn and the comic pager): the finger
          // drags the page strip directly, overshoot past either end is damped at 0.35 (iOS
          // value). The native side settles the target page on release.
          window.__kmDragTo = function(dx) {
            // No horizontal strip to drag in vertical mode: pages stack downward, so the
            // finger-follow is skipped and the release snap animates along scrollTop instead.
            if (!S.paged || S.vertical) return;
            cancelSnap();
            var x = S.dir * S.page * S.vw - dx;
            var lo = S.dir === 1 ? 0 : -(S.pages - 1) * S.vw;
            var hi = S.dir === 1 ? (S.pages - 1) * S.vw : 0;
            if (x < lo) x = lo + (x - lo) * 0.35;
            if (x > hi) x = hi + (x - hi) * 0.35;
            window.scrollTo(x, 0);
          };

          // The page step has to equal the width the columns were actually laid out against.
          // window.innerWidth is rounded to an integer (411 on a 411.43 px viewport) while the
          // layout viewport keeps the fraction, so stepping by innerWidth walks the strip by a
          // fraction of that error on every page: after ~55 pages of a long chapter the text has
          // left the right gutter and the previous column's tail shows in the left one (the
          // "cut glyphs on the outer edges" symptom). visualViewport.width keeps the fraction;
          // the document's own width is the fallback. The < 8 px guard keeps a pinch-zoomed
          // viewport or a publisher-widened <html> from poisoning the step.
          function viewportWidth() {
            var w = window.innerWidth || 1;
            var vv = window.visualViewport;
            if (vv && isFinite(vv.width) && vv.width > 0 && Math.abs(vv.width - w) < 8) return vv.width;
            var el = document.documentElement;
            if (el) {
              var r = el.getBoundingClientRect().width;
              if (isFinite(r) && r > 0 && Math.abs(r - w) < 8) return r;
            }
            return w;
          }

          function measure() {
            S.vw = viewportWidth();
            S.vh = window.innerHeight || 1;
            // Re-detect vertical writing: at theme-apply time the publisher stylesheet may not
            // be parsed yet, so the first detection can miss a content-declared vertical book.
            // The manifest-forced case (c.vertical) never flips back.
            if (S.c && !S.c.vertical) {
              var v = contentIsVertical();
              if (v !== S.vertical) { S.vertical = v; refreshLayout(); }
            }
            if (!S.paged) { S.pages = 1; S.page = 0; return; }
            var b = document.body || doc();
            if (S.vertical) {
              // Columns advance along the (vertical) inline axis, so the strip grows downward
              // and pages are vh-sized steps of scrollTop.
              var sh = Math.max(doc().scrollHeight, b.scrollHeight || 0);
              S.pages = Math.max(1, Math.ceil(sh / S.vh - 0.002));
              S.page = Math.max(0, Math.min(S.pages - 1, Math.round(sy() / S.vh)));
              return;
            }
            var sw = Math.max(doc().scrollWidth, b.scrollWidth || 0);
            // The strip is an exact multiple of the viewport: layoutCss() gives the columns to
            // <html> with column-gap 0 and keeps the page gutter on the fragmented <body>, so the
            // trailing gutter is part of the LAST column instead of trailing padding the scroll
            // area drops. Round (not ceil) absorbs a fractional column width reported as an
            // integral scrollWidth; with two columns per page the odd last column rounds up to a
            // full page, which is the spread the reader expects.
            S.pages = Math.max(1, Math.round(sw / S.vw));
            S.page = Math.max(0, Math.min(S.pages - 1, Math.round(Math.abs(sx()) / S.vw)));
          }

          // The load token ties a measure pass to its page load (mirrors the iOS generation/
          // readyToken): the Kotlin side reveals the WebView only for the newest token, so a
          // late repaginate from the PREVIOUS chapter (backstop timers, late image loads in
          // the loadUrl window) can never un-hide it mid-load and flash the old page.
          function notifyPages() {
            // dir: 0 = ltr, 1 = rtl, 2 = vertical — Kotlin mirrors its tap zones for 1 and 2
            var d = S.vertical ? 2 : (S.dir === -1 ? 1 : 0);
            $BRIDGE.onPageMeasured(S.pages, S.page, window.__kmLoadToken || 0, d);
          }

          // Unfragmentable boxes (flex/grid/table/inline-block) taller than the page cannot
          // split across columns: the overflow is silently CLIPPED, losing whole blocks of
          // content. Demote them to plain block so they fragment (mirrors foliate's
          // #demoteUnfragmentableBoxes). Marked so re-runs stay cheap; never restored —
          // chapter content is static for the chapter's lifetime.
          var DEMOTE_DISPLAY = /^(inline-block|flex|inline-flex|grid|inline-grid|table|inline-table)$/;
          function demoteUnfragmentableBoxes() {
            if (!S.paged || !document.body) return;
            var all = document.body.querySelectorAll('*');
            var vh = window.innerHeight || 1;
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              if (el.getAttribute('data-km-demoted') != null) continue;
              if (el.offsetHeight <= vh) continue;
              var d = '';
              try { d = window.getComputedStyle(el).display || ''; } catch (e) { continue; }
              if (DEMOTE_DISPLAY.test(d)) {
                el.setAttribute('data-km-demoted', d);
                el.style.setProperty('display', 'block', 'important');
              }
            }
          }

          // A chapter-leading image whose top sits below some leading space (a heading, an
          // epigraph) overflows its page vertically even under the global max-height clamp:
          // Chromium moves the whole image and leaves the first page blank, other engines
          // slice its bottom into the next page (readest#6676). Shrink the clamp by the
          // leading amount and pin the image to the top (mirrors foliate getLeadingSpace).
          function compensateLeadingImage() {
            if (!S.paged || !document.body || !S.c) return;
            var img = document.body.querySelector('img');
            if (!img) return;
            var insetV = S.c.insetV || 0;
            var topPad = (S.vertical ? S.marginH : 24) + insetV;
            var pageCap = S.vertical ? Math.max(80, S.vh - 2 * S.marginH) : (S.vh - 48);
            var r = img.getBoundingClientRect();
            var absTop = r.top + sy();
            var leading = absTop - topPad;
            if (leading <= 8) return;
            var cap = pageCap - leading;
            if (cap > 80 && r.height > cap) {
              img.style.setProperty('max-height', Math.floor(cap) + 'px', 'important');
              img.style.setProperty('vertical-align', 'top', 'important');
            }
          }

          // Measure, then restore the pending (or current) position and report the page count.
          // keepPage matters for late content growth (an image finishing after the capped
          // measure wait inserts pages mid-strip): keeping the page INDEX leaves a reader
          // before the insertion untouched and drops a reader who paged past the not-yet-
          // measured image back onto it — fraction-keeping would pin them to the strip end
          // and silently skip the image page (the "page turn jumps over the picture" bug).
          function repaginate(keepPage) {
            var keepFrac = S.pendingFrac != null ? S.pendingFrac : (keepPage ? null : fraction());
            var keepIdx = S.page;
            // A reader sitting on the LAST page almost always got there through the
            // previous-chapter seam (restoreFraction(1)). If late content (an image finishing
            // after the measure cap) then grows the strip, keeping the bare page INDEX strands
            // them near the chapter start — track the end instead (mirrors the iOS
            // preferLastPageOnReady intent surviving late pagination).
            var wasAtEnd = keepPage && S.paged && S.pages > 0 && keepIdx >= S.pages - 1;
            demoteUnfragmentableBoxes();
            compensateLeadingImage();
            classifyAllImages();
            rewriteDarkText(S.c);
            measure();
            S.pendingFrac = null;
            S.quiet = true;
            try {
              if (keepFrac != null) restoreFraction(keepFrac, false);
              else if (wasAtEnd) goToPage(S.pages - 1, false);
              else goToPage(keepIdx, false);
            } finally { S.quiet = false; }
            measure();
            // The restore above went through goToPage, but measure() can have changed the page
            // COUNT and a restore target can clamp short at either end of the strip, so re-assert
            // the grid: a late repagination must never leave the strip resting between two pages.
            settlePage(false);
            // Defer the notification (and with it the Kotlin-side reveal) until the compositor
            // has had two frames to rasterize the restored scroll position — see goToPage.
            requestAnimationFrame(function() {
              requestAnimationFrame(function() { settlePage(false); notifyPages(); report(); });
            });
            // Late content can still be in flight when the capped measure wait expired (a large
            // illustration). Re-run once it actually settles so the page count and the resting
            // offset follow the final layout; the !imagesStillPending() guard keeps a
            // never-loading image from re-triggering this on every pass.
            if (imagesStillPending()) {
              imagesReady(3000).then(function() {
                if (S.ready && S.paged && !imagesStillPending()) scheduleRepaginate();
              });
            }
          }

          // ---------- layout ----------
          function layoutCss() {
            var c = S.c;
            // Vertical books always need the column layout: without it the block axis runs
            // right-to-left and the document extends off-screen instead of scrolling.
            if (!c || (!c.paged && !S.vertical)) return '';
            var vw = window.innerWidth || 1;
            var vh = window.innerHeight || 1;
            var m = c.marginH;
            if (S.vertical) {
              // Vertical-rl multicol: the inline axis is vertical, so the columns repeat
              // downward and one page = the container's inline extent (vh). Same two
              // invariants as the horizontal strip, with the axes swapped:
              //   * pitch == vh, because a column's tile is the container's inline size and
              //     column-gap is 0 — there is no arithmetic pitch (old rule: column-width
              //     vh-2m plus a 2m gap) that a fractional viewport could desynchronise;
              //   * strip height == pages * vh, because the trailing inline gutter belongs to
              //     the LAST column instead of being trailing padding that the scrollable
              //     overflow drops. That is what lets a turn land exactly on page * vh and
              //     stops the final page from being clamped short of the strip's end.
              // Which padding goes where matters, and it is the mirror of the horizontal
              // branch: the inline-axis (top/bottom) gutter and the overlay reservation are
              // <body> padding, because a fragmented element repeats its INLINE padding in
              // every column fragment — that keeps text clear of the header and footer on
              // EVERY page (on the container it only pads the strip's ends, so every later
              // page slid under the chrome, which is what the old rule did). The block-axis
              // (left/right) gutter is container padding, because it has to shrink every
              // column's tile; on the fragmented <body> it would pad only the first and last
              // fragment.
              var inset = m + (c.insetV || 0);
              var ch = Math.max(80, vh - 2 * inset);
              // overflow-anchor:none — a paged strip that Chromium may "anchor" scrolls itself
              // off the page grid whenever late layout lands above the reader (see settlePage).
              return 'html,body{margin:0 !important;overflow-anchor:none !important;}'
                // width:100% (not `vw px`) so the block extent is resolved by the browser against
                // the real ICB; only the inline extent is pinned, because that is the page step.
                + 'html{width:100% !important;height:' + vh + 'px !important;'
                + 'overflow-x:hidden !important;overflow-y:auto !important;'
                + 'box-sizing:border-box !important;padding:0 ' + m + 'px !important;'
                + 'column-count:1 !important;column-gap:0 !important;'
                + 'column-fill:auto !important;}'
                // `html body` (0,0,2) rather than `body` (0,0,1): the theme sheet also declares
                // body padding, and this rule must win on specificity instead of on sheet order.
                + 'html body{box-sizing:border-box !important;width:100% !important;'
                + 'padding:' + inset + 'px 0 !important;}'
                // break-inside: an image must not be sliced across two columns/pages (foliate
                // setImageSize); the max-* clamps keep it inside one page box, which now means
                // the tile minus the inline reservation on both sides.
                + 'img,svg,video{max-width:' + Math.max(120, vw - 2 * m) + 'px !important;max-height:' + ch + 'px !important;break-inside:avoid !important;}';
            }
            // The columns live on <html> and the page gutter lives on the fragmented <body>, so
            // the two invariants the pager relies on are structural instead of arithmetic:
            //   * column pitch == the real ICB width (100%, resolved by the browser) — never a
            //     rounded window.innerWidth, so a fractional viewport cannot desynchronise the
            //     page step from the strip;
            //   * strip width == pages * viewport, because the trailing gutter belongs to the
            //     LAST column instead of being trailing padding that the scrollable overflow
            //     drops. That is what lets a turn land exactly on page * vw and makes the last
            //     page need no spacer.
            // column-gap must stay 0: the gap is added OUTSIDE the column box, so as soon as the
            // gutter is no longer container padding (the other half of the old pitch arithmetic)
            // a non-zero gap turns the pitch into vw + gap and every page drifts.
            // Which padding goes where matters: the horizontal gutter is <body> padding, because
            // a fragmented element repeats its INLINE padding in every column fragment; the
            // top/bottom reservation for the info overlay is the container's own padding, because
            // it must shrink EVERY column box to vh - 2*(24+inset) — put on <body> instead it
            // would pad only the first and last fragment and a page would gain two lines.
            // overflow-anchor:none is load-bearing: scroll anchoring only compensates the BLOCK
            // axis, so when a late image or font changes the column distribution above the
            // reader, Chromium "anchors" the horizontal strip and moves it off the page grid by
            // a fraction of a page — the reported mobile symptom (the previous column's glyph
            // tails visible at the left edge, the current column clipped on the right).
            return 'html,body{margin:0 !important;overflow-anchor:none !important;}'
              + 'html{width:100% !important;height:' + vh + 'px !important;'
              + 'overflow-y:hidden !important;box-sizing:border-box !important;'
              + 'padding:' + (24 + (c.insetV || 0)) + 'px 0 !important;'
              + 'column-count:' + S.cols + ' !important;column-gap:0 !important;'
              + 'column-fill:auto !important;}'
              // `html body` (0,0,2) rather than `body` (0,0,1): the theme sheet also declares
              // body padding, and this rule must win on specificity instead of on sheet order.
              + 'html body{box-sizing:border-box !important;width:100% !important;'
              + 'padding:0 ' + m + 'px !important;}'
              + 'img,svg,video{max-height:' + Math.max(120, vh - 48) + 'px !important;break-inside:avoid !important;}';
          }
          function refreshLayout() {
            var lay = document.getElementById('km-layout');
            if (!lay) { lay = document.createElement('style'); lay.id = 'km-layout'; document.head.appendChild(lay); }
            lay.textContent = layoutCss();
          }

          function forceEagerImages() {
            var imgs = document.querySelectorAll('img[loading="lazy"]');
            for (var i = 0; i < imgs.length; i++) imgs[i].setAttribute('loading', 'eager');
          }

          // Images pending on the network; resolves once every <img> settled (load or error)
          // or after a cap, so a hanging image can never wedge the measure pass. Loaded images
          // also go through decode(): "complete" only means the bytes arrived — without a decode
          // pass the rasterizer shows a white band through a big illustration for a few frames
          // right after the page is revealed (the video-captured "white stripe" flash).
          function imagesReady(capMs) {
            var imgs = document.querySelectorAll('img');
            var pending = [];
            for (var i = 0; i < imgs.length; i++) {
              (function(img) {
                // complete covers broken images too (naturalWidth == 0): they are settled,
                // and treating them as pending would burn the whole 3s cap on every chapter
                // that references a missing file.
                if (img.complete) {
                  if (img.naturalWidth > 0 && img.decode) {
                    pending.push(img.decode().catch(function() {}));
                  }
                  return;
                }
                pending.push(new Promise(function(res) {
                  img.addEventListener('load', function() {
                    if (img.decode) img.decode().catch(function() {}).then(res); else res();
                  }, { once: true });
                  img.addEventListener('error', res, { once: true });
                }));
              })(imgs[i]);
            }
            if (!pending.length) return Promise.resolve();
            return Promise.race([
              Promise.all(pending),
              new Promise(function(res) { setTimeout(res, capMs); }),
            ]);
          }

          function imagesStillPending() {
            var imgs = document.querySelectorAll('img');
            for (var i = 0; i < imgs.length; i++) { if (!imgs[i].complete) return true; }
            return false;
          }

          // Publisher stylesheets load asynchronously; the dark-text rewrite reads COMPUTED
          // colours, so it must not run before <link rel=stylesheet> sheets have landed —
          // otherwise a class like .colorg{color:#000} applies after the pass and repaints
          // the text black on the dark page. Stylesheets are local (fake-host intercept), so
          // waiting for them costs nothing; the 2s cap guards a hanging request.
          function stylesReady() {
            var links = document.querySelectorAll('link[rel="stylesheet"]');
            var ps = [];
            for (var i = 0; i < links.length; i++) {
              (function(l) {
                if (l.sheet) return; // already loaded
                ps.push(new Promise(function(res) {
                  l.addEventListener('load', res, { once: true });
                  l.addEventListener('error', res, { once: true });
                }));
              })(links[i]);
            }
            if (!ps.length) return Promise.resolve();
            return Promise.race([Promise.all(ps), new Promise(function(res) { setTimeout(res, 2000); })]);
          }

          // Async measure pass. Fonts are always awaited (they are local and fast); images
          // follow the iOS fullExtentGuard split (preferLast || target > 0): a POSITIONED
          // landing (progress restore, backward turn onto the previous chapter's last page)
          // needs the true strip width and waits out unsettled images once, bounded — a
          // forward turn starts at page 0 and does not wait at all, leaving growth to the
          // keepPage repagination below. Hard backstops (3s/10s, mirrors the iOS 10s fallback)
          // make a hanging resource unwedgeable.
          function kickMeasure() {
            // Injection now happens at commitVisible (much earlier than pageFinished — that one
            // waits for every image request to settle, which is exactly what made chapter turns
            // flash blank for a second on image-heavy books). The parser may not have produced
            // <body> yet: without it no layout can be measured, so wait for DOMContentLoaded.
            if (!document.body) {
              document.addEventListener('DOMContentLoaded', function() { kickMeasure(); }, { once: true });
              return;
            }
            var gen = ++window.__kmGen;
            var done = false;
            var finish = function(force) {
              if (done || gen !== window.__kmGen) return;
              requestAnimationFrame(function() {
                if (done || gen !== window.__kmGen) return;
                if (!force && S.paged && imagesStillPending()) {
                  var positioned = S.pendingFrac != null && S.pendingFrac > 0;
                  // A POSITIONED landing (progress restore, backward turn onto the previous
                  // chapter's last page) needs the true strip width, so it waits out unsettled
                  // images once, bounded. A FORWARD turn lands on page 0 — its content does not
                  // depend on later images, so (mirroring the iOS fullExtentGuard: target=0 has
                  // needsFullExtent=false) it reveals immediately and lets the keepPage
                  // repagination absorb late image growth instead of paying an 800ms wait on
                  // every image chapter.
                  if (positioned) {
                    imagesReady(3000).then(function() { finish(true); });
                    return;
                  }
                }
                done = true;
                if (S.paged) { repaginate(false); }
                else {
                  measure();
                  // The scrolled branch never goes through repaginate, and the commit-time
                  // theme application may have run before the parser produced <body> — so
                  // the dark-mode text/image rewrite must run here too (covers both modes).
                  if (S.c && S.c.darkTheme) { classifyAllImages(); rewriteDarkText(S.c); }
                  if (S.pendingFrac != null) {
                    var f = S.pendingFrac;
                    S.pendingFrac = null;
                    restoreFraction(f, false);
                    scheduleReRestore(f);
                  }
                  // Same rasterization race as the paged restore: reveal only after the new
                  // scroll position has actually painted (double rAF).
                  requestAnimationFrame(function() {
                    requestAnimationFrame(function() { notifyPages(); });
                  });
                  // Scrolled mode reveals before stylesheets may have landed: re-run the
                  // rewrite once they are in (idempotent — a no-op when already applied).
                  stylesReady().then(function() {
                    if (S.ready && S.c && S.c.darkTheme) rewriteDarkText(S.c);
                  });
                }
                S.ready = true;
              });
            };
            if (!S.paged) { finish(false); return; }
            forceEagerImages();
            var fontWait = (document.fonts && document.fonts.ready) ? document.fonts.ready : Promise.resolve();
            // Wait for publisher stylesheets too: the dark-text rewrite reads computed colours
            // and must see the final cascade, not the pre-stylesheet default.
            Promise.all([fontWait, stylesReady()]).then(function() { finish(false); });
            setTimeout(function() { finish(false); }, 3000);
            setTimeout(function() { finish(true); }, 10000);
          }

          // Scrolled mode restores before images/fonts settle — first paint must stay fast —
          // so the fraction is re-applied once every <img> has loaded or errored. Skipped when
          // the reader already scrolled on their own: growth below the viewport never moves
          // scrollTop, so an unchanged offset means "still where we put them".
          function scheduleReRestore(f) {
            var y0 = sy();
            imagesReady(3000).then(function() {
              if (!S.ready || S.paged) return;
              if (Math.abs(sy() - y0) > 4) return;
              restoreFraction(f, false);
              report();
            });
          }

          // Vertical writing detection (mirrors foliate getDirection): the publisher's own
          // stylesheet decides — CJK novels declare writing-mode on body or its first child;
          // a manga EPUB on an RTL series must NOT be misread as vertical. c.vertical only
          // forces the mode when the manifest says ttb/btt.
          function contentIsVertical() {
            var b = document.body;
            if (!b || !window.getComputedStyle) return false;
            var wm = getComputedStyle(b).writingMode || '';
            if (wm.indexOf('vertical') === 0) return true;
            var f = b.firstElementChild;
            if (f) {
              var wm2 = getComputedStyle(f).writingMode || '';
              if (wm2.indexOf('vertical') === 0) return true;
            }
            return false;
          }

          // Dark-mode image classification (mirrors readest imageClassifier.ts): black ink on
          // a transparent background — title calligraphy, diagrams, gaiji — is invisible on a
          // dark theme. Those images get invert(1) hue-rotate(180deg) (flips luminance, keeps
          // hue, so the coloured parts of a mixed logo survive). Opaque (non-transparent)
          // images are NEVER inverted: a colour plate, photo or opaque diagram keeps its
          // original colours. Page and images share the fake origin, so canvas reads are
          // untainted.
          var kmCanvas = null;
          function classifyImage(img) {
            if (!img || img.kmClassified) return;
            img.kmClassified = true;
            var p = img.parentElement;
            var txt = p ? (p.textContent || '').replace(/\s+/g, '') : '';
            var inline = /[\p{L}\p{N}]/u.test(txt);
            var w = img.naturalWidth | 0, h = img.naturalHeight | 0;
            if (!w || !h) return;
            try {
              if (!kmCanvas) kmCanvas = document.createElement('canvas');
              var sw = Math.min(w, 96);
              var sh = Math.max(1, Math.round(sw * h / w));
              if (sh > 96) { sh = 96; sw = Math.max(1, Math.round(sw * w / h)); }
              kmCanvas.width = sw; kmCanvas.height = sh;
              var ctx = kmCanvas.getContext('2d', { willReadFrequently: true });
              if (!ctx) return;
              ctx.clearRect(0, 0, sw, sh);
              ctx.drawImage(img, 0, 0, sw, sh);
              var d = ctx.getImageData(0, 0, sw, sh).data;
              var total = d.length / 4, opaque = 0, colored = 0, dark = 0, light = 0;
              for (var i = 0; i < d.length; i += 4) {
                if (d[i + 3] < 40) continue;
                opaque++;
                var r = d[i], g = d[i + 1], b = d[i + 2];
                var mx = Math.max(r, g, b), mn = Math.min(r, g, b);
                if (mx - mn > 24) colored++;
                var luma = 0.299 * r + 0.587 * g + 0.114 * b;
                if (luma < 96) dark++;
                else if (luma > 200) light++;
              }
              if (!opaque) return;
              var transparentRatio = 1 - opaque / total;
              // 非透明图一律不反色：彩页、照片、不透明的单色图都保持原样。
              if (transparentRatio <= 0.3) return;
              // Inline glyph (an image sitting inside a text run): invert so it follows the
              // surrounding text colour — only reached when the image IS transparent.
              if (inline) { img.classList.add('km-inline-img'); return; }
              // Transparent calligraphy / logo glyph with black strokes: invert so the
              // strokes turn light. Coloured badges (pink CONTENTS chip) stay colourful.
              if (dark / opaque > 0.25) img.classList.add('km-mono-img');
            } catch (e) { /* canvas unavailable — leave the image alone */ }
          }
          function classifyAllImages() {
            if (!S.c || !S.c.darkTheme) return;
            var imgs = document.querySelectorAll('img');
            for (var i = 0; i < imgs.length; i++) {
              if (imgs[i].complete && imgs[i].naturalWidth) classifyImage(imgs[i]);
            }
          }

          // Dark-mode overrides (iOS makeDarkTextColorOverrideCSS + the readest dark rewrites).
          // Deliberately NO blanket background-color:transparent — that would erase a book's
          // coloured chips/badges. Links get the iOS dark link blue; hard-coded black text is
          // additionally caught by the computed-style rewrite pass below (attribute selectors
          // only see INLINE styles — black text from a publisher stylesheet class, like the
          // mixed-colour title glyphs of a TOC page, needs the runtime pass); classified images
          // are inverted.
          function darkModeCss(c) {
            return 'html{color-scheme:dark}'
              + 'a:link,a:link *{color:#63CAFF !important;-webkit-text-fill-color:#63CAFF !important}'
              + 'a:visited,a:visited *{color:#0099E5 !important;-webkit-text-fill-color:#0099E5 !important}'
              + 'body svg text{fill:currentColor !important;stroke:none !important}'
              + 'font[color="#000000"],font[color="#000"],font[color="black"],'
              + 'font[color="rgb(0,0,0)"],font[color="rgb(0, 0, 0)"],'
              + '*[style*="color: #000"],*[style*="color: #000000"],*[style*="color: black"],'
              + '*[style*="color:#000"],*[style*="color:#000000"],*[style*="color:black"],'
              + '*[style*="color: rgb(0, 0, 0)"],*[style*="color: rgb(0,0,0)"],'
              + '*[style*="color:rgb(0, 0, 0)"],*[style*="color:rgb(0,0,0)"]'
              + '{color:' + c.fg + ' !important;-webkit-text-fill-color:' + c.fg + ' !important}'
              + '*[style*="background-color: #fff"],*[style*="background-color:#fff"],'
              + '*[style*="background-color: #ffffff"],*[style*="background-color:#ffffff"],'
              + '*[style*="background-color: white"],*[style*="background-color:white"],'
              + '*[style*="background: #fff"],*[style*="background:#fff"],'
              + '*[style*="background: #ffffff"],*[style*="background:#ffffff"],'
              + '*[style*="background: white"],*[style*="background:white"],'
              + '*[style*="background-color: rgb(255"],*[style*="background-color:rgb(255"],'
              + '*[style*="background: rgb(255"],*[style*="background:rgb(255"]'
              + '{background-color:' + c.bg + ' !important}'
              + 'img.km-mono-img,img.km-inline-img{filter:invert(1) hue-rotate(180deg)}';
          }

          // Computed-style dark-text rewrite: any element whose effective text colour is
          // near-black gets repinned to the theme foreground. Covers stylesheet classes, inline
          // styles and fill-colour tricks in one pass — the attribute selectors above can only
          // see inline styles. Coloured text (pink/orange glyphs) passes the threshold and is
          // left alone. Judge by `color` only, and DO NOT touch -webkit-text-fill-color unless
          // the publisher set it explicitly (fill != color): fill INHERITS, so writing it on a
          // heading would flatten every coloured span inside it to the theme foreground.
          function rewriteDarkText(c) {
            if (!c || !c.darkTheme) return;
            // NOTE: must be document.body — doc() is document.documentElement and
            // documentElement.body is undefined (that early-return made this pass a
            // silent no-op until the headless-Edge harness caught it).
            var b = document.body;
            if (!b) return;
            var els = b.querySelectorAll('*');
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              var cs = getComputedStyle(el);
              var color = cs.color || '';
              var m = /rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/.exec(color);
              if (!m) continue;
              var mx = Math.max(+m[1], +m[2], +m[3]);
              var fill = cs.getPropertyValue('-webkit-text-fill-color');
              if (mx > 96) {
                // Coloured text stays — but fill INHERITS and beats color at paint time, so
                // a rewritten (near-black) ancestor would still flatten this element through
                // its inherited fill. Pin the element's fill to its own colour (harness:
                // the pink/orange TOC glyphs rendered grey until pinned).
                if (fill && fill !== color) {
                  el.style.setProperty('-webkit-text-fill-color', color, 'important');
                }
                continue; // only near-black text is unreadable on a dark page
              }
              // Links are NOT exempt: a TOC entry whose publisher class paints it black is
              // exactly the invisible text we are fixing (the a:link dark-blue rule loses to a
              // higher-specificity publisher rule). Properly-blue links pass the mx check above.
              el.style.setProperty('color', c.fg, 'important');
              if (fill && fill !== color) {
                el.style.setProperty('-webkit-text-fill-color', c.fg, 'important');
              }
              if (el.namespaceURI === 'http://www.w3.org/2000/svg') {
                el.style.setProperty('fill', c.fg, 'important');
              }
            }
          }

          window.__kmApplyTheme = function(c) {
            var had = S.c != null;
            var keep = had ? fraction() : null;
            S.c = c;
            S.paged = !!c.paged;
            S.dir = c.rtl ? -1 : 1;
            S.cols = c.colCount > 1 ? 2 : 1;
            S.animate = !!c.animate;
            S.marginH = c.marginH;
            var style = document.getElementById('km-theme');
            if (!style) { style = document.createElement('style'); style.id = 'km-theme'; document.head.appendChild(style); }
            var ff = c.fontFamily ? 'font-family:' + c.fontFamily + ' !important;' : '';
            var fw = 'font-weight:' + c.fontWeight + ' !important;';
            var css = 'html,body{background:' + c.bg + ' !important;color:' + c.fg + ' !important;'
              // Blink's font autosizing silently inflates "small" text runs, which desyncs the
              // column pitch from the measured page width (readest #5641) — pin it off.
              + '-webkit-text-size-adjust:none !important;text-size-adjust:none !important;}'
              // Unbreakable long words (URLs, CJK-less runs) must not overflow their column:
              // an overflowing run widens the strip past the computed page boundary.
              + 'body{padding:' + (24 + (c.insetV || 0)) + 'px ' + c.marginH + 'px !important;margin:0 !important;font-size:' + c.fontSize + 'px !important;line-height:' + c.lineHeight + ' !important;overflow-wrap:break-word !important;' + fw + ff + '}'
              + (c.publisherStyles ? '' : 'body,body *{font-size:' + c.fontSize + 'px !important;line-height:' + c.lineHeight + ' !important;' + fw + ff + 'color:' + c.fg + ' !important;}')
              + 'img,svg,video{max-width:100% !important;height:auto !important;}'
              // Manifest-forced vertical (readingProgression ttb/btt): pinned on the root so
              // publisher styles cannot talk it back into horizontal.
              + (c.vertical ? 'html{writing-mode:vertical-rl !important;-webkit-writing-mode:vertical-rl !important;}' : '')
              + (c.darkTheme ? darkModeCss(c) : '');
            style.textContent = css;
            S.vertical = !!c.vertical || contentIsVertical();
            var fontStyle = document.getElementById('km-fonts');
            if (!fontStyle) { fontStyle = document.createElement('style'); fontStyle.id = 'km-fonts'; document.head.appendChild(fontStyle); }
            fontStyle.textContent = window.__kmFontCss || '';
            document.documentElement.style.background = c.bg;
            // Reading direction: a right-to-left story gets dir='rtl' on the root element, which is
            // what the iOS page builder does. Vertical books must NOT get it (mirrors foliate):
            // dir=rtl would flip the vertical line direction; their strip runs along scrollTop,
            // which never flips sign. Attributes set here are marked, and only those are removed
            // again: a publisher-declared dir='rtl' must survive when the series says LTR.
            var root = document.documentElement;
            if (c.rtl && !S.vertical) {
              if (root.getAttribute('dir') !== 'rtl') root.setAttribute('dir', 'rtl');
              root.setAttribute('data-km-rtl', '1');
            } else if (root.getAttribute('data-km-rtl') === '1') {
              root.removeAttribute('dir');
              root.removeAttribute('data-km-rtl');
            }
            refreshLayout();
            // Never clobber a queued restore (a mid-load theme re-apply while pendingFrac
            // still holds the target would re-impose the half-laid-out strip's position).
            if (keep != null && S.pendingFrac == null) S.pendingFrac = keep;
            if (c.darkTheme) { classifyAllImages(); rewriteDarkText(c); }
            kickMeasure();
            report();
          };

          // ---------- reporting ----------
          var timer = null;
          function report() {
            if (timer) return;
            timer = setTimeout(function() {
              timer = null;
              if (S.paged) {
                S.page = S.vertical
                  ? Math.max(0, Math.min(S.pages - 1, Math.round(sy() / S.vh)))
                  : Math.max(0, Math.min(S.pages - 1, Math.round(Math.abs(sx()) / S.vw)));
              }
              var f = fraction();
              // The load token rides along (same reason as notifyPages): a report still in
              // flight from the PREVIOUS chapter in the loadUrl window must not be attributed
              // to the new chapter index — that corrupts the whole-book progression.
              $BRIDGE.onMetrics(f.toFixed(6), S.page, S.pages, window.__kmLoadToken || 0);
            }, 200);
          }
          window.__kmReport = report;

          // ---------- helpers called from Kotlin ----------
          window.__kmScrollByViewport = function(dir, animate) {
            var vh = window.innerHeight || 1;
            if (animate && S.animate) { window.scrollBy({ top: dir * vh * 0.96, behavior: 'smooth' }); }
            else { window.scrollBy(0, dir * vh * 0.96); }
          };
          window.__kmGoToAnchor = function(id) {
            var el = document.getElementById(id);
            if (!el) { try { el = document.querySelector('[name="' + id + '"]'); } catch (e) { el = null; } }
            if (!el) return;
            if (S.paged) {
              var r = el.getBoundingClientRect();
              if (S.vertical) {
                goToPage(Math.floor((r.top + sy()) / S.vh), false);
              } else {
                var absX = Math.abs(r.left + sx());
                goToPage(Math.floor(absX / S.vw), false);
              }
            } else {
              el.scrollIntoView();
            }
          };

          // ---------- footnotes ----------
          // A tapped note reference pops its body over the page instead of jumping to it. The page
          // already holds the styled DOM, so the body is extracted here rather than in Kotlin: the
          // bubble renders through HtmlCompat, which has no CSS engine, so the computed style is
          // folded back into semantic tags (<b>/<i>/<u>/<sup>) before it leaves the WebView.
          // Anything unusable returns null and the click falls through to the normal link path.
          var NOTE_NS = 'http://www.idpf.org/2007/ops';
          var NOTE_REF_CLASSES = /(^|\s)(noteref|footnote-ref|endnote-ref|duokan-footnote)(\s|$)/;
          var NOTE_TAGS = {
            P: 1, DIV: 1, BLOCKQUOTE: 1, UL: 1, OL: 1, LI: 1,
            H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, BR: 1,
            B: 1, STRONG: 1, I: 1, EM: 1, U: 1, S: 1, DEL: 1, STRIKE: 1,
            SUB: 1, SUP: 1, SMALL: 1, MARK: 1, Q: 1, CITE: 1, ABBR: 1, CODE: 1,
            SPAN: 1
          };
          var NOTE_MAX_HTML = 16000;
          var NOTE_MAX_TEXT = 2000;
          var NOTE_MAX_IMAGES = 8;

          function noteEpubTypeOf(el) {
            if (!el || !el.getAttribute) return '';
            var plain = el.getAttribute('epub:type') || '';
            // Served as application/xhtml+xml the WebView parses the chapter as XML, where
            // epub:type is a namespaced attribute and getAttribute('epub:type') returns null.
            var namespaced = el.getAttributeNS ? (el.getAttributeNS(NOTE_NS, 'type') || '') : '';
            return plain + ' ' + namespaced;
          }

          function isNoteref(link) {
            if (!link || !link.getAttribute) return false;
            if (/(^|\s)noteref(\s|$)/.test(noteEpubTypeOf(link))) return true;
            if ((link.getAttribute('role') || '') === 'doc-noteref') return true;
            return NOTE_REF_CLASSES.test(link.getAttribute('class') || '');
          }

          function noteFragmentOf(link) {
            var stamped = link.getAttribute('data-km-noteref');
            if (stamped) return stamped;
            var raw = link.getAttribute('href') || '';
            var hash = raw.indexOf('#');
            if (hash < 0 || hash === raw.length - 1) return '';
            return raw.slice(hash + 1);
          }

          // Publishers (Duokan et al.) run a script that strips the href off every noteref after
          // load. The stamp is taken while the href is still there, so the reference survives it.
          function stampNoteref(link) {
            if (link.getAttribute('data-km-noteref')) return;
            var frag = noteFragmentOf(link);
            if (frag) link.setAttribute('data-km-noteref', frag);
          }

          function stampNoterefs() {
            var links = document.querySelectorAll ? document.querySelectorAll('a') : [];
            for (var i = 0; i < links.length; i++) {
              if (isNoteref(links[i])) stampNoteref(links[i]);
            }
          }

          function noteEscape(s) {
            return String(s)
              .replace(/&/g, '&amp;')
              .replace(/</g, '&lt;')
              .replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;');
          }

          function noteAbsoluteUrl(el, attr) {
            var raw = el.getAttribute ? el.getAttribute(attr) : null;
            if (!raw) return '';
            try { return new URL(raw, location.href).href; } catch (e) { return ''; }
          }

          // Computed style -> semantic tag. This is what recovers the emphasis a publisher wrote as
          // a class (".fn-em { font-style: italic }") instead of <i>: the chapter stylesheet is
          // already applied in the page, the bubble has no stylesheet of its own.
          function noteStyleTags(el) {
            var tags = [];
            var cs = null;
            try { cs = window.getComputedStyle(el); } catch (e) { cs = null; }
            if (!cs) return tags;
            var weight = parseInt(cs.fontWeight, 10);
            if (weight >= 600) tags.push('b');
            if (cs.fontStyle === 'italic' || cs.fontStyle === 'oblique') tags.push('i');
            var deco = cs.textDecorationLine || cs.textDecoration || '';
            if (deco.indexOf('underline') >= 0) tags.push('u');
            if (cs.verticalAlign === 'super') tags.push('sup');
            if (cs.verticalAlign === 'sub') tags.push('sub');
            return tags;
          }

          // Tables have no representation in a TextView: flatten each row to "cell · cell".
          function noteTableHtml(table, state) {
            var rows = table.querySelectorAll ? table.querySelectorAll('tr') : [];
            if (!rows.length) return noteChildrenHtml(table, state);
            var html = '';
            for (var i = 0; i < rows.length; i++) {
              var cells = rows[i].querySelectorAll ? rows[i].querySelectorAll('td,th') : [];
              var line = '';
              for (var c = 0; c < cells.length; c++) {
                var cell = noteChildrenHtml(cells[c], state);
                if (cell) line += (line ? ' · ' : '') + cell;
              }
              if (line) html += '<p>' + line + '</p>';
            }
            return html;
          }

          function noteChildrenHtml(node, state) {
            var out = '';
            var kids = node.childNodes || [];
            for (var i = 0; i < kids.length; i++) {
              out += noteNodeHtml(kids[i], state);
            }
            return out;
          }

          function noteNodeHtml(n, state) {
            if (!n) return '';
            if (n.nodeType === 3) {
              var text = String(n.nodeValue || '').replace(/\s+/g, ' ');
              return text ? noteEscape(text) : '';
            }
            if (n.nodeType !== 1) return '';
            var tag = n.nodeName ? n.nodeName.toUpperCase() : '';
            if (tag === 'IMG') {
              if (state.images.length >= NOTE_MAX_IMAGES) return '';
              var src = noteAbsoluteUrl(n, 'src');
              if (!src) return '';
              var alt = n.getAttribute('alt') || '';
              state.images.push({ src: src, alt: alt });
              return '<img src="' + noteEscape(src) + '" alt="' + noteEscape(alt) + '">';
            }
            if (tag === 'RT') {
              // Ruby annotation: keep it readable instead of gluing it into the base text.
              var rt = noteChildrenHtml(n, state);
              return rt ? '（' + rt + '）' : '';
            }
            if (tag === 'TABLE') return noteTableHtml(n, state);
            if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'TITLE' || tag === 'NOSCRIPT' ||
                tag === 'IFRAME' || tag === 'OBJECT' || tag === 'EMBED' || tag === 'SVG' ||
                tag === 'AUDIO' || tag === 'VIDEO' || tag === 'HEAD') {
              return '';
            }
            if (tag === 'BR') return '<br>';
            if (tag === 'A') {
              var inner = noteChildrenHtml(n, state);
              if (!inner) return '';
              var href = noteAbsoluteUrl(n, 'href');
              var inBook = false;
              try { inBook = !!href && new URL(href).origin === location.origin; } catch (e) { inBook = false; }
              return inBook ? '<a href="' + noteEscape(href) + '">' + inner + '</a>' : inner;
            }
            var body = noteChildrenHtml(n, state);
            if (!body) return '';
            var lower = tag.toLowerCase();
            if (!NOTE_TAGS[tag]) return body;  // unknown wrapper: keep what it contains
            var html = (lower === 'span' || lower === 'div') ? body : '<' + lower + '>' + body + '</' + lower + '>';
            var wraps = noteStyleTags(n);
            for (var w = 0; w < wraps.length; w++) {
              var wt = wraps[w];
              if (wt === lower) continue;
              html = '<' + wt + '>' + html + '</' + wt + '>';
            }
            return html;
          }

          function notePayload(link, ev) {
            var frag = noteFragmentOf(link);
            if (!frag) return null;
            var target = document.getElementById(frag);
            if (!target) {
              try { target = document.querySelector('[name="' + frag.replace(/"/g, '') + '"]'); } catch (e) { target = null; }
            }
            if (!target) return null;  // cross-file note: the caller falls back to the link jump
            var state = { images: [] };
            var html = noteChildrenHtml(target, state);
            var text = String(target.textContent || '').replace(/\s+/g, ' ').trim();
            if (!html || (!text && !state.images.length)) return null;
            var truncated = false;
            if (text.length > NOTE_MAX_TEXT) {
              text = text.slice(0, NOTE_MAX_TEXT);
              truncated = true;
            }
            if (html.length > NOTE_MAX_HTML) {
              html = html.slice(0, NOTE_MAX_HTML);
              var cut = html.lastIndexOf('>');
              if (cut > 0) html = html.slice(0, cut + 1);
              truncated = true;
            }
            var vw = S.vw || window.innerWidth || 1;
            var vh = S.vh || window.innerHeight || 1;
            var href = noteAbsoluteUrl(link, 'href');
            if (!href) href = (location.href.split('#')[0] || '') + '#' + frag;
            return {
              v: 1,
              href: href,
              frag: frag,
              html: html,
              text: text,
              truncated: truncated,
              images: state.images,
              anchor: {
                x: Math.min(1, Math.max(0, ev.clientX / vw)),
                y: Math.min(1, Math.max(0, ev.clientY / vh))
              },
              token: window.__kmLoadToken || 0
            };
          }

          stampNoterefs();
          window.addEventListener('load', function() { stampNoterefs(); });

          // ---------- listeners ----------
          window.addEventListener('scroll', report, { passive: true });
          window.addEventListener('resize', function() {
            if (S.paged && S.ready) { refreshLayout(); repaginate(false); }
            report();
          });
          // Content growth (images finishing) changes the paged page count: re-measure and keep
          // the reader on the same fraction (mirrors the iOS ResizeObserver pageCountUpdate).
          var roTimer = null;
          function scheduleRepaginate() {
            if (!S.ready || roTimer) return;
            roTimer = setTimeout(function() {
              roTimer = null;
              if (!S.ready) return;
              if (S.paged) { repaginate(true); }
              // Scrolled mode: growth changes the chapter fraction — re-report so the
              // whole-book progression does not go stale until the next scroll.
              else { measure(); report(); }
            }, 150);
          }
          if (window.ResizeObserver) {
            var ro = new ResizeObserver(scheduleRepaginate);
            ro.observe(document.body || doc());
          }
          // foliate also watches DOM mutations: books that inject content through scripts
          // (deferred galleries, ad-style wrappers) change the page count without any image
          // load or body resize. Our own <style> writes live in <head> and are filtered out.
          if (window.MutationObserver) {
            var mo = new MutationObserver(function(muts) {
              for (var i = 0; i < muts.length; i++) {
                var t = muts[i].target;
                if (t && t.closest && t.closest('body')) { scheduleRepaginate(); return; }
              }
            });
            mo.observe(doc(), { childList: true, subtree: true });
          }
          // The body's box is pinned to the viewport, so the ResizeObserver above never fires
          // for late-loading images — catch them through capture-phase load events instead.
          // A web font that lands after kickMeasure's fonts.ready pass (font-display:swap, or a
          // family the chapter only starts using later) reflows every column without resizing
          // body's box either, so neither observer sees it.
          if (document.fonts && document.fonts.addEventListener) {
            document.fonts.addEventListener('loadingdone', scheduleRepaginate);
          }
          document.addEventListener('load', function(e) {
            var t = e.target;
            if (t && t.tagName === 'IMG') { if (S.c && S.c.darkTheme) classifyImage(t); scheduleRepaginate(); }
          }, true);
          document.addEventListener('click', function(e) {
            var sel = window.getSelection();
            if (sel && !sel.isCollapsed) return;
            var t = e.target;
            // ALL in-book links route through Kotlin (mirrors foliate's link event): a
            // same-document hash hop never reaches shouldOverrideUrlLoading — the WebView
            // just scrolls natively — so without this interception a jump link desyncs the
            // chapter index, skips the progress pipeline and never pushes the return chip.
            // Footnote/note references are tried first, before the link path below: a reference
            // whose body sits in this file pops a bubble (no navigation at all), and only the
            // ones that cannot be extracted fall through to the normal in-book jump.
            var anchorEl = t && t.closest ? t.closest('a') : null;
            // A note reference pops its body over the page instead: no chapter jump, no return
            // chip, and no onTap (the preventDefault below keeps the tap-zone handler out of it).
            // When the note cannot be extracted (cross-file target, empty body) the click falls
            // through to the plain link path and the reader jumps exactly like before.
            if (anchorEl && isNoteref(anchorEl)) {
              var note = notePayload(anchorEl, e);
              if (note) {
                e.preventDefault();
                e.stopPropagation();
                $BRIDGE.onFootnote(JSON.stringify(note));
                return;
              }
            }
            var link = anchorEl && anchorEl.getAttribute('href') ? anchorEl : null;
            if (link) {
              var u = null;
              try { u = new URL(link.getAttribute('href') || '', location.href); } catch (err) { u = null; }
              if (u && u.origin === location.origin) {
                // An anchor pointing straight at a media file is the classic full-page
                // illustration link: this reader has no image viewer, and swallowing the tap
                // makes the whole page a dead zone — treat it as a page-turn tap instead.
                if (!/\.(jpe?g|png|gif|webp|avif|svg|bmp|heic|m4a|mp[34]|webm|ogg)$/.test(u.pathname.toLowerCase())) {
                  // In-book link: the reader navigates itself so the chapter index and the
                  // anchor stay in sync (the return chip comes from that path).
                  e.preventDefault();
                  e.stopPropagation();
                  $BRIDGE.onLink(u.href);
                  return;
                }
              } else {
                // External links keep the default navigation: shouldOverrideUrlLoading blocks
                // them (the do-not-leave-the-book policy).
                return;
              }
            }
            // Non-link interactive elements keep their own behaviour.
            if (t && t.closest && t.closest('button,input,select,textarea,audio,video')) return;
            var vw = window.innerWidth || 1;
            $BRIDGE.onTap(e.clientX / vw);
          }, true);

          window.__kmApplyTheme(${config.toJs()});
        } catch (e) { console.error('kmInject: ' + (e && e.stack ? e.stack : e)); } })();
    """.trimIndent()
    }
}

/** JS bridge (@JavascriptInterface callbacks arrive on the JS thread; everything hops to the main thread). */
class EpubJsBridge(
    /** Scroll/position metrics. [loadToken] identifies the page load the report came from:
     *  a stale token means the report was emitted by the previous chapter's dying JS. */
    private val metricsCallback: (fraction: Float, loadToken: Int) -> Unit,
    private val tapCallback: (Float) -> Unit,
    /** Paged-mode measure pass result (in scrolled mode this fires once per theme apply as 1/0).
     *  [direction]: 0 = ltr, 1 = rtl, 2 = vertical. */
    private val paginationCallback: (totalPages: Int, currentPage: Int, loadToken: Int, direction: Int) -> Unit = { _, _, _, _ -> },
    /** In-book link tap routed from the page JS (same-document hash hops never reach
     *  shouldOverrideUrlLoading, so the click handler preventDefaults and reports here). */
    private val linkCallback: (url: String) -> Unit = {},
    /** A note reference was tapped and its body could be extracted from the page; the argument is
     *  the JSON record consumed by [EpubFootnote.parse]. */
    private val footnoteCallback: (json: String) -> Unit = {},
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onLink(url: String) {
        mainHandler.post { linkCallback(url) }
    }

    @JavascriptInterface
    fun onFootnote(json: String) {
        mainHandler.post { footnoteCallback(json) }
    }

    @JavascriptInterface
    fun onMetrics(fraction: String, scrollTop: Int, scrollHeight: Int, loadToken: Int) {
        val f = fraction.toFloatOrNull() ?: return
        // The callback properties must not carry the @JavascriptInterface method names: a
        // `post { onTap(x) }` would resolve to the member function instead of the property and
        // repost itself on the main thread forever, starving the queue.
        mainHandler.post { metricsCallback(f, loadToken) }
    }

    @JavascriptInterface
    fun onTap(xFraction: Float) {
        mainHandler.post { tapCallback(xFraction) }
    }

    @JavascriptInterface
    fun onPageMeasured(totalPages: Int, currentPage: Int, loadToken: Int, direction: Int) {
        mainHandler.post { paginationCallback(totalPages, currentPage, loadToken, direction) }
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
    /**
     * In-book navigation (chapter links, footnote references, anchors). The reader must resolve
     * these itself: letting the WebView load a chapter URL directly would bypass the chapter
     * index, the position restore and the progress reporting. Return value: handled.
     */
    private val onNavigate: (href: String, anchor: String?) -> Boolean = { _, _ -> true },
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

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        Log.d("EpubNav", "override url=$uri host=${uri.host} frag=${uri.fragment}")
        // External links stay blocked (mirrors the original do-not-leave-the-book policy).
        if (uri.host != EpubWeb.FAKE_HOST) return true
        // In-book link: route through the reader so the chapter index and the anchor stay in sync.
        val href = EpubWeb.hrefFromUrl(uri.toString()) ?: return true
        Log.d("EpubNav", "in-book href=$href anchor=${uri.fragment}")
        return onNavigate(href, uri.fragment)
    }

    // Inject as early as the new document can run scripts: commitVisible fires when the first
    // frame of the new page is about to paint, while onPageFinished waits for every subresource
    // (all chapter images) to settle — injecting there was what left the WebView hidden (or the
    // old page blanked) for a visible beat on image-heavy chapters. The JS side is idempotent
    // (window.__kmInjected re-applies the theme and returns), so the pageFinished fallback only
    // matters when commitVisible never comes (failed load).
    override fun onPageCommitVisible(view: WebView, url: String?) {
        onPageReady(view)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onPageReady(view)
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}

/** Create and configure the reading WebView (scroll/paged modes, external network disabled for JS). */
@SuppressLint("SetJavaScriptEnabled")
fun createEpubWebView(
    context: android.content.Context,
    bridge: EpubJsBridge,
    client: EpubWebViewClient,
    onBottomOverscroll: (() -> Unit)? = null,
    onTopOverscroll: (() -> Unit)? = null,
): EpubScrollWebView = EpubScrollWebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.loadsImagesAutomatically = true
    settings.mediaPlaybackRequiresUserGesture = true
    // Pin WebView text scaling: the reader's own font-size setting is the only sizing input —
    // Android's system font scale must not multiply through (it desyncs the paged column math).
    settings.textZoom = 100
    // The injected viewport meta pins the page at scale 1; pinch zoom would break the
    // vw-based page math (mirrors user-scalable=no on the iOS page builder)
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    webViewClient = client
    // JS console surfaces in logcat (tag EpubJs): injected-script failures would otherwise be
    // silent, and chrome://inspect stays available in debug builds
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d("EpubJs", "${message.message()} (@${message.sourceId()}:${message.lineNumber()})")
            return true
        }
    }
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
    addJavascriptInterface(bridge, EpubJs.BRIDGE)
    this.onBottomOverscroll = onBottomOverscroll
    this.onTopOverscroll = onTopOverscroll
}
