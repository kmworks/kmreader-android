package com.kmworks.kmreader.ui.reader

import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.pdf.view.PdfView
import kotlin.math.abs

/**
 * Wraps [PdfView] and adds a discrete page-turn gesture layer.
 *
 * androidx.pdf beta01 has no paged mode and no scroll lock (verified by decompiling the
 * AARs), so paged reading is implemented here: while [discreteMode] is on and the
 * document is not pinch-zoomed, a single-finger drag past the touch slop is intercepted
 * before it reaches [PdfView] and turned into a [onNextPage]/[onPrevPage] callback on
 * release. Pinch-zoomed content keeps pan gestures and multi-touch keeps pinch; taps
 * still fall through to the Compose overlay.
 *
 * The swipe axis mirrors iOS PDFView's displayDirection (PdfDocumentView_iOS.swift):
 * LTR/RTL read along the horizontal axis (RTL flips the swipe semantics), Vertical reads
 * along the vertical axis. Drags on the inactive axis are consumed without turning, so
 * discrete mode never silently degrades into a scroll.
 */
class DiscretePdfContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Axis discrete swipes follow, mirroring iOS PDFView displayDirection. */
    enum class Axis { Horizontal, Vertical }

    val pdfView: PdfView = PdfView(context)

    /** When true, single-finger drags turn pages instead of scrolling. */
    var discreteMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Entering discrete mode from a continuous scroll can leave the viewport
            // between pages; snap to the current first page.
            if (value && pdfView.pdfDocument != null) {
                runCatching { pdfView.scrollToPage(pdfView.firstVisiblePage) }
            }
            resetGesture()
        }

    /** Swipe axis for page turns (iOS: LTR/RTL horizontal, Vertical vertical). */
    var axis: Axis = Axis.Horizontal

    /** Right-to-left reading direction: horizontal swipe directions flip. */
    var rtl: Boolean = false

    var onPrevPage: () -> Unit = {}
    var onNextPage: () -> Unit = {}

    /**
     * Fired whenever PdfView lays out a new viewport, i.e. after a scroll or a page jump.
     *
     * The callback arguments are unusable here (`firstVisiblePage` is 0 for every viewport this
     * reader lays out), so listeners have to read the position from the view themselves; it
     * still is the only reliable *timing* signal, because `PdfView` lays pages out itself and
     * never reports scrollX/scrollY, which is all `View.setOnScrollChangeListener` sees.
     */
    var onViewportPageChanged: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val turnThreshold = touchSlop * 4

    private var downX = 0f
    private var downY = 0f
    private var axisHorizontal: Boolean? = null

    /**
     * Zoom when the current gesture started, used to tell a pinch from a one-finger drag.
     *
     * This must be sampled per gesture: `PdfView` rests at fit-to-width zoom, which it
     * only applies in its first layout after a document loads (`setInitialZoomScroll()`
     * replaces the DEFAULT_INIT_ZOOM of 1.0 with `viewportWidth / contentWidth`). Any
     * baseline captured earlier — a stored field refreshed by a posted runnable — keeps
     * the pre-layout 1.0, so `zoom > baseline` is true for the whole session and the
     * paging layer never intercepts. Sampling at ACTION_DOWN also re-baselines for free
     * on every page turn, where the fit zoom changes with the page size.
     */
    private var zoomAtDown = 0f

    init {
        addView(pdfView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pdfView.addOnViewportChangedListener(
            object : PdfView.OnViewportChangedListener {
                override fun onViewportChanged(
                    firstVisiblePage: Int,
                    visiblePagesCount: Int,
                    visiblePages: SparseArray<RectF>,
                    zoom: Float,
                ) {
                    onViewportPageChanged?.invoke()
                }
            },
        )
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // PdfView.onTouchEvent unconditionally calls requestDisallowInterceptTouchEvent(true)
        // on its parent ("so that any ancestor of PdfView does not consume the touch event
        // before the library does", PdfView.kt), which would defeat this gesture layer:
        // without ignoring it, discrete mode can never intercept and always falls through
        // to scrolling. While discrete mode is on we keep the right to intercept; gestures
        // we do not claim (multi-touch pinch, zoomed pan) still reach PdfView untouched.
        if (!discreteMode) super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!discreteMode) {
            resetGesture()
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                axisHorizontal = null
                zoomAtDown = pdfView.zoom
            }
            MotionEvent.ACTION_POINTER_DOWN -> resetGesture()
            MotionEvent.ACTION_MOVE -> {
                // Pinch-zoomed pages keep pan; multi-touch keeps pinch zoom.
                if (ev.pointerCount > 1) return false
                if (zoomAtDown > 0f && pdfView.zoom > zoomAtDown * ZOOM_TOLERANCE) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                val horizontal = axisHorizontal
                if (horizontal == null && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    axisHorizontal = abs(dx) >= abs(dy)
                }
                // Intercept drags on either axis: the inactive axis is consumed as a
                // no-op so discrete mode never falls through into a scroll.
                val committed = axisHorizontal == true && abs(dx) > touchSlop ||
                    axisHorizontal == false && abs(dy) > touchSlop
                if (committed) return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A pinch started after we intercepted: drop this gesture, the next one works.
                resetGesture()
                return false
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (axisHorizontal == true && axis == Axis.Horizontal) {
                    when {
                        dx <= -turnThreshold -> if (rtl) onPrevPage() else onNextPage()
                        dx >= turnThreshold -> if (rtl) onNextPage() else onPrevPage()
                    }
                } else if (axisHorizontal == false && axis == Axis.Vertical) {
                    when {
                        dy <= -turnThreshold -> onNextPage()
                        dy >= turnThreshold -> onPrevPage()
                    }
                }
                resetGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resetGesture()
                return true
            }
        }
        return true
    }

    private fun resetGesture() {
        axisHorizontal = null
    }

    private companion object {
        /** Pinch margin over the zoom the gesture started at (per-page fit varies slightly). */
        const val ZOOM_TOLERANCE = 1.05f
    }
}
