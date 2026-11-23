package com.kmworks.kmreader.ui.reader

import android.content.Context
import android.util.AttributeSet
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
                syncReferenceZoom()
            }
            resetGesture()
        }

    /** Swipe axis for page turns (iOS: LTR/RTL horizontal, Vertical vertical). */
    var axis: Axis = Axis.Horizontal

    /** Right-to-left reading direction: horizontal swipe directions flip. */
    var rtl: Boolean = false

    /**
     * Resting fit-zoom baseline. PdfView rests at fit-to-width zoom, which is well above
     * `minZoom`, so pinch detection must compare against the resting zoom, not `minZoom`
     * (comparing against `minZoom` is always true and disables interception entirely).
     */
    private var referenceZoom = 0f

    /**
     * Captures the current zoom as the resting baseline. Call after programmatic changes
     * that reset the zoom (document swaps, page turns, presentation changes); posted so
     * it reads the zoom after the view has applied them.
     */
    fun syncReferenceZoom() {
        pdfView.post {
            val zoom = pdfView.zoom
            if (zoom > 0f) referenceZoom = zoom
        }
    }

    var onPrevPage: () -> Unit = {}
    var onNextPage: () -> Unit = {}

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val turnThreshold = touchSlop * 4

    private var downX = 0f
    private var downY = 0f
    private var axisHorizontal: Boolean? = null

    init {
        addView(pdfView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
            }
            MotionEvent.ACTION_POINTER_DOWN -> resetGesture()
            MotionEvent.ACTION_MOVE -> {
                // Pinch-zoomed pages keep pan; multi-touch keeps pinch zoom.
                if (ev.pointerCount > 1) return false
                if (isZoomedIn()) return false
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
                // A programmatic page turn re-fits the zoom; re-baseline so slightly
                // different page sizes do not read as a pinch.
                syncReferenceZoom()
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

    private fun isZoomedIn(): Boolean =
        referenceZoom > 0f && pdfView.zoom > referenceZoom * ZOOM_TOLERANCE

    private companion object {
        /** Pinch margin over the resting fit-zoom (per-page fit varies slightly). */
        const val ZOOM_TOLERANCE = 1.05f
    }
}
