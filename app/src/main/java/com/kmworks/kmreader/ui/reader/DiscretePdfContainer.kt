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
 * AARs; see docs/pdf-native-reader-plan.md §3), so paged reading is implemented here:
 * while [discreteMode] is on and the document is not pinch-zoomed, a single-finger drag
 * past the touch slop is intercepted before it reaches [PdfView] and turned into a
 * [onNextPage]/[onPrevPage] callback on release. Zoomed content keeps pan gestures and
 * multi-touch keeps pinch; taps still fall through to the Compose overlay.
 */
class DiscretePdfContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

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

    /** Right-to-left reading direction: horizontal swipe directions flip. */
    var rtl: Boolean = false

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
                if (pdfView.zoom > pdfView.minZoom + ZOOM_EPSILON) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                val horizontal = axisHorizontal
                if (horizontal == null && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    axisHorizontal = abs(dx) >= abs(dy)
                }
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
                if (axisHorizontal == true) {
                    when {
                        dx <= -turnThreshold -> if (rtl) onPrevPage() else onNextPage()
                        dx >= turnThreshold -> if (rtl) onNextPage() else onPrevPage()
                    }
                } else {
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
        const val ZOOM_EPSILON = 0.01f
    }
}
