package com.kmworks.kmreader.core.epub

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs

/**
 * Reading WebView for both EPUB flow styles.
 *
 * Scrolled mode reports drags past the top/bottom document edges (the chapter seams: bottom of the
 * last chapter opens the end page, any other edge crossing switches chapters). Android delivers
 * those as overscroll clamps, which a plain WebView discards.
 *
 * Paged mode mirrors the iOS PagedScrollEpubViewController: the document is a horizontal strip of
 * pages driven exclusively by injected `scrollTo` calls, so user scrolling must not reach the
 * WebView. Horizontal drags are intercepted past the touch slop (the WebView receives a cancel so
 * it aborts its own scroll/click tracking) and reported on release as a swipe; taps pass through
 * untouched so links and the tap-zone click listener keep working.
 */
class EpubScrollWebView(context: Context) : WebView(context) {

    /** Called repeatedly while the document is dragged past its bottom edge (scrolled mode). */
    var onBottomOverscroll: (() -> Unit)? = null

    /** Called repeatedly while the document is dragged past its top edge (scrolled mode). */
    var onTopOverscroll: (() -> Unit)? = null

    /** Paged layout: horizontal drags become page turns instead of WebView scrolling. */
    var pagedMode: Boolean = false

    /** Horizontal swipe release in paged mode: total drag delta x and release velocity (px/s). */
    var onSwipe: ((dx: Float, velocityX: Float) -> Unit)? = null

    /** Live drag in paged mode: delta x from the touch-down point, for finger-following pages. */
    var onDrag: ((dx: Float) -> Unit)? = null

    /**
     * Mouse wheel tick in paged mode: `true` = forward. Wheel scrolling is meaningless once the
     * strip is page-aligned, so each tick turns a page instead (mirrors desktop readers).
     */
    var onWheelTurn: ((forward: Boolean) -> Unit)? = null

    private var lastWheelTurnAt = 0L

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            pagedMode &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                val now = SystemClock.uptimeMillis()
                if (now - lastWheelTurnAt >= 500L) {
                    lastWheelTurnAt = now
                    onWheelTurn?.invoke(v < 0f)
                }
                return true
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastDx = 0f
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null

    // Scrolled-mode chapter seam (mirrors the iOS boundaryOverscrollThreshold = 48pt flow):
    // the reader drags PAST the document edge by the threshold, gets a light haptic when the
    // seam arms, and the chapter switch commits on RELEASE — a fling or an accidental graze
    // of the edge never switches chapters.
    private val seamThresholdPx = 48f * context.resources.displayMetrics.density
    private var seamPull = 0f
    private var seamDirection = 0 // 1 = past the bottom edge (next), -1 = past the top (previous)
    private var seamArmed = false
    private var lastTouchY = 0f

    private fun resetSeamPull() {
        seamPull = 0f
        seamDirection = 0
        seamArmed = false
    }

    private fun accumulateSeamPull(direction: Int, amount: Float) {
        if (seamDirection != direction) {
            seamDirection = direction
            seamPull = 0f
        }
        seamPull += amount
        if (!seamArmed && seamPull >= seamThresholdPx) {
            seamArmed = true
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        // Seam detection lives in the touch path now (threshold + release confirm); a bare
        // clamped fling into the edge must not switch chapters on its own.
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pagedMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetSeamPull()
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = lastTouchY - event.y // > 0: finger moves up, toward the bottom edge
                    lastTouchY = event.y
                    val atBottom = !canScrollVertically(1)
                    val atTop = !canScrollVertically(-1)
                    when {
                        dy > 0f && atBottom -> accumulateSeamPull(1, dy)
                        dy < 0f && atTop -> accumulateSeamPull(-1, -dy)
                        // Dragging back into the document drains the pull; mid-document resets it.
                        seamDirection == 1 && dy < 0f -> {
                            seamPull = (seamPull + dy).coerceAtLeast(0f)
                            if (seamPull < seamThresholdPx) seamArmed = false
                        }
                        seamDirection == -1 && dy > 0f -> {
                            seamPull = (seamPull - dy).coerceAtLeast(0f)
                            if (seamPull < seamThresholdPx) seamArmed = false
                        }
                        !atBottom && !atTop -> resetSeamPull()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (seamArmed) {
                        if (seamDirection == 1) onBottomOverscroll?.invoke() else onTopOverscroll?.invoke()
                    }
                    resetSeamPull()
                }
                MotionEvent.ACTION_CANCEL -> resetSeamPull()
            }
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!dragging) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    // Paged mode intercepts EVERY drag, not just horizontal ones: anything that
                    // reaches the WebView would pan the page strip freely (the "still scrollable"
                    // bug). Vertical drags settle back as a no-op on release.
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        dragging = true
                        // Take the gesture over: the WebView aborts its own scroll/click tracking.
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        velocityTracker?.clear()
                        velocityTracker?.addMovement(event)
                        onDrag?.invoke(dx)
                        return true
                    }
                    // Below the slop (tap in progress): the WebView handles it.
                    return super.onTouchEvent(event)
                }
                lastDx = event.x - downX
                onDrag?.invoke(lastDx)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val dx = event.x - downX
                    velocityTracker?.recycle()
                    velocityTracker = null
                    onSwipe?.invoke(dx, vx)
                    return true
                }
                velocityTracker?.recycle()
                velocityTracker = null
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_CANCEL -> {
                // The gesture died mid-drag: settle the page as if released with no velocity
                if (dragging) onSwipe?.invoke(lastDx, 0f)
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                return super.onTouchEvent(event)
            }
        }
        return super.onTouchEvent(event)
    }
}
