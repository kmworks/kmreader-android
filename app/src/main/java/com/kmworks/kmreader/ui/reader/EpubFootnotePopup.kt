package com.kmworks.kmreader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.scale
import androidx.core.text.HtmlCompat
import androidx.core.text.parseAsHtml
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.epub.EpubFootnoteImage
import com.kmworks.kmreader.core.epub.EpubFootnoteNote
import com.kmworks.kmreader.core.epub.EpubResource
import com.kmworks.kmreader.core.epub.EpubThemeConfig
import com.kmworks.kmreader.core.epub.EpubWeb
import com.kmworks.kmreader.core.epub.computeEpubFootnotePosition
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * In-place note bubble: the body of a tapped footnote reference, floating next to the finger
 * instead of turning the page to it.
 *
 * The page already owns the styled note HTML (the JS extracted and flattened it — see the footnote
 * block in [com.kmworks.kmreader.core.epub.EpubJs]), so this only renders it: no second WebView, no
 * navigation, and the reader stays exactly where it was. That is also why the body keeps its
 * publisher emphasis but loses CSS metadata — HtmlCompat has no stylesheet engine.
 *
 * @param note the payload extracted on tap.
 * @param pageBackground the page colour the bubble floats over.
 * @param contentColor the page's foreground colour.
 * @param fetchResource synchronous resource reader for note images (called on the IO dispatcher).
 * @param onFollowLink follows a link found inside the note (`href`, `#fragment`).
 */
@Composable
internal fun EpubFootnotePopup(
    note: EpubFootnoteNote,
    themeConfig: EpubThemeConfig,
    pageBackground: Color,
    contentColor: Color,
    fetchResource: (String) -> EpubResource?,
    onDismiss: () -> Unit,
    onFollowLink: (href: String, fragment: String?) -> Unit,
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    // Paged mode has a narrow column of text, so the bubble follows it; scrolled mode gets the
    // wider card (there is more page to cover and the eye is already moving vertically).
    // A window that has not reported its size yet falls back to a phone-width guess rather than
    // collapsing the bubble to one pixel.
    val windowWidthPx = windowSize.width.takeIf { it > 0 } ?: with(density) { 360.dp.roundToPx() }
    val windowHeightPx = windowSize.height.takeIf { it > 0 } ?: with(density) { 640.dp.roundToPx() }
    val widthFraction = if (themeConfig.paged) 0.72f else 0.86f
    val widthCapPx = with(density) { (if (themeConfig.paged) 400.dp else 520.dp).roundToPx() }
    val popupWidthPx = min((windowWidthPx * widthFraction).toInt(), widthCapPx).coerceAtLeast(1)
    val maxHeightPx = min((windowHeightPx * 0.45f).toInt(), with(density) { 420.dp.roundToPx() })
    val edgeMarginPx = with(density) { 12.dp.roundToPx() }
    val anchorGapPx = with(density) { 10.dp.roundToPx() }
    val tailPx = with(density) { 8.dp.roundToPx() }.toFloat()
    // CSS line-height counts font size; TextView's multiplier counts its font metrics (~1.2 em).
    val noteLineSpacing = (themeConfig.lineHeight / 1.2f).coerceIn(1f, 1.25f)

    // The placement provider only learns the bubble height during layout (the note body decides
    // it), so the tail direction is published back into the composition from there.
    val aboveState = remember { mutableStateOf(true) }
    val positionProvider = remember(note, popupWidthPx, maxHeightPx, edgeMarginPx, anchorGapPx) {
        FootnotePositionProvider(
            anchorX = note.anchorX,
            anchorY = note.anchorY,
            edgeMarginPx = edgeMarginPx,
            anchorGapPx = anchorGapPx + tailPx.toInt(),
            above = aboveState,
        )
    }

    val textColor = contentColor
    val linkColor = if (pageBackground.luminance() > 0.5f) Color(0xFF1A5FB4) else Color(0xFF8AB4F8)
    // The bubble floats over the page, so it needs the raised fill the chrome uses: painted in the
    // page colour it had no edge at all on a dark page — black bubble, black page, one hairline at
    // 0.12 alpha — which read as the note text having been pasted onto the paragraph.
    // No shadow: the Popup window is only as large as the bubble, so anything drawn outside its
    // bounds is clipped away. The fill step and the hairline carry the separation here.
    val surface = remember(pageBackground, contentColor) {
        readerChromeSurface(pageBackground, contentColor)
    }
    val background = surface.background
    val border = surface.border

    // Note images are publication resources, not data URLs: fetch them like the web view would
    // (offline archive first) and hand the result to the TextView's ImageGetter.
    var images by remember(note.html) { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    LaunchedEffect(note.html) {
        if (note.images.isEmpty()) return@LaunchedEffect
        val maxImageWidthPx = popupWidthPx - with(density) { 28.dp.roundToPx() }
        val loaded = withContext(Dispatchers.IO) {
            note.images.mapNotNull { image ->
                decodeNoteImage(image, fetchResource, maxImageWidthPx)?.let { image.src to it }
            }.toMap()
        }
        images = loaded
    }

    val spanned = remember(note.html, images, textColor, linkColor) {
        linkifyNoteHtml(
            html = note.html,
            images = images,
            linkColor = linkColor,
            onLink = onFollowLink,
        )
    }

    val shape = remember(aboveState.value, density) {
        FootnoteBubbleShape(above = aboveState.value, tail = tailPx, corner = with(density) { 14.dp.toPx() })
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .width(with(density) { popupWidthPx.toDp() })
                .heightIn(max = with(density) { maxHeightPx.toDp() })
                .clip(shape)
                .background(background)
                .border(1.dp, border, shape)
                .verticalScroll(rememberScrollState())
                // Leaves room for the tail: the bubble body must not start under it.
                .padding(
                    top = if (aboveState.value) 12.dp else 12.dp + with(density) { tailPx.toDp() },
                    bottom = if (aboveState.value) 12.dp + with(density) { tailPx.toDp() } else 12.dp,
                    start = 14.dp,
                    end = 14.dp,
                ),
        ) {
            AndroidView(
                factory = { context ->
                    TextView(context).apply {
                        // Links open in the reader, never in a browser: the note lives inside the
                        // book, and leaving the app from a footnote tap is never what was meant.
                        movementMethod = LinkMovementMethod.getInstance()
                        isClickable = true
                        // No text selection: a selectable TextView swallows the tap that should
                        // follow a link, and selection handles in a floating bubble look broken.
                        // Line height is a CSS multiplier (1.4 em); TextView multiplies the font's
                        // own line metrics, so fold the ~1.2 em default in before applying it.
                        setLineSpacing(0f, noteLineSpacing)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, with(density) { (themeConfig.fontSizePx * 0.94f).sp.toPx() })
                        setTextColor(textColor.toArgb())
                        highlightColor = Color.Transparent.toArgb()
                    }
                },
                update = { view ->
                    view.setTextColor(textColor.toArgb())
                    view.setLineSpacing(0f, noteLineSpacing)
                    // Identity check: the spanned text is only rebuilt when the body or the
                    // loaded images change, so this avoids a re-layout on every recomposition.
                    if (view.tag !== spanned) {
                        view.tag = spanned
                        view.text = spanned
                    }
                },
            )
            if (note.truncated) {
                Text(
                    text = stringResource(R.string.epub_footnote_full_note),
                    color = linkColor,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clickable { onFollowLink(note.href, note.fragment) },
                )
            }
        }
    }
}

/** Places the bubble at the tapped reference, using the same rules as the unit-tested helper. */
private class FootnotePositionProvider(
    private val anchorX: Float,
    private val anchorY: Float,
    private val edgeMarginPx: Int,
    private val anchorGapPx: Int,
    private val above: MutableState<Boolean>,
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val position = computeEpubFootnotePosition(
            anchorX = anchorX,
            anchorY = anchorY,
            windowWidthPx = windowSize.width,
            windowHeightPx = windowSize.height,
            popupWidthPx = popupContentSize.width,
            popupHeightPx = popupContentSize.height,
            edgeMarginPx = edgeMarginPx,
            anchorGapPx = anchorGapPx,
        )
        // Publishing during layout is safe here: the value only flips when the bubble has to move
        // to the other side of the anchor, so it settles after one extra composition.
        if (above.value != position.above) above.value = position.above
        return IntOffset(position.x, position.y)
    }
}

/**
 * Rounded card with a tail pointing at the note reference (the tail side follows the placement:
 * above the finger or below it).
 */
private class FootnoteBubbleShape(
    private val above: Boolean,
    private val tail: Float,
    private val corner: Float,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val bodyTop = if (above) 0f else tail
        val bodyBottom = if (above) size.height - tail else size.height
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = bodyTop,
                    right = size.width,
                    bottom = bodyBottom,
                    radiusX = corner,
                    radiusY = corner,
                ),
            )
            val tailX = size.width / 2f
            if (above) {
                moveTo(tailX - tail, size.height - tail)
                lineTo(tailX, size.height)
                lineTo(tailX + tail, size.height - tail)
            } else {
                moveTo(tailX - tail, tail)
                lineTo(tailX, 0f)
                lineTo(tailX + tail, tail)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/** Converts the sanitised note HTML into styled text, routing links back to the reader. */
private fun linkifyNoteHtml(
    html: String,
    images: Map<String, Bitmap>,
    linkColor: Color,
    onLink: (href: String, fragment: String?) -> Unit,
): Spanned {
    val imageGetter = android.text.Html.ImageGetter { source ->
        val bitmap = images[source] ?: return@ImageGetter null
        android.graphics.drawable.BitmapDrawable(null, bitmap).apply {
            // The TextView sizes paragraphs to its own width; the drawable must not exceed it.
            setBounds(0, 0, bitmap.width, bitmap.height)
        }
    }
    val parsed = html.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, null)
    val spannable: Spannable = if (parsed is Spannable) parsed else SpannableString(parsed)
    for (span in spannable.getSpans(0, spannable.length, URLSpan::class.java)) {
        val start = spannable.getSpanStart(span)
        val end = spannable.getSpanEnd(span)
        if (start < 0 || end <= start) continue
        val flags = spannable.getSpanFlags(span)
        val url = span.url
        spannable.removeSpan(span)
        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val uri = android.net.Uri.parse(url)
                    // Only in-book targets: anything else would leave the reader mid-note.
                    val href = EpubWeb.hrefFromUrl(url) ?: return
                    onLink(href, uri.fragment)
                }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.isUnderlineText = false
                    ds.color = linkColor.toArgb()
                }
            },
            start,
            end,
            flags,
        )
    }
    return spannable
}

/** Decodes one note image, scaled down when the publication image is wider than the bubble. */
private fun decodeNoteImage(
    image: EpubFootnoteImage,
    fetchResource: (String) -> EpubResource?,
    maxWidthPx: Int,
): Bitmap? {
    val href = EpubWeb.hrefFromUrl(image.src) ?: return null
    val resource = fetchResource(href) ?: return null
    val bitmap = try {
        BitmapFactory.decodeByteArray(resource.data, 0, resource.data.size)
    } catch (e: Exception) {
        null
    } ?: return null
    if (maxWidthPx <= 0 || bitmap.width <= maxWidthPx) return bitmap
    val height = (bitmap.height * (maxWidthPx.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
    return bitmap.scale(maxWidthPx, height, true)
}
