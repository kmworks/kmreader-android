package com.kmworks.kmreader.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kmworks.kmreader.data.ReaderViewItem
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Mirrors the original NativeCoverPageView.TransitionMetrics (iOS Cover transition).
private const val CommitDistanceRatio = 0.18f // commitDistanceRatio: release past 18% of the page turns it
private const val CommitVelocityThreshold = 700f // commitVelocityThreshold, px/s
private const val OverscrollResistance = 0.2f // overscrollResistance: rubber-band scale at a book seam
private const val BoundaryFeedbackMinimumOffset = 8f // boundaryFeedbackMinimumOffset, px (scaled)
private const val DirectionalDragBias = 4f // directionalDragBias: horizontal must win by this much
private const val CancelThreshold = 0.5f // cancelThreshold: offsets at/below this count as "not moved"
private const val TransitionDurationMs = 300 // gestureAnimationDuration (0.3s, curveEaseOut)

/**
 * The page-turn surface the reader drives regardless of the transition style: the paged modes share
 * one set of effects (page restore, progress reporting, preloading, jump, re-anchoring), and the
 * style only changes how a target index is reached. The [androidx.compose.foundation.pager.PagerState]
 * adapter below keeps Scroll/Vertical on the stock pager; [CoverDeckState] implements the same
 * surface for the cover transition.
 */
internal interface ReaderTurnState {
    /** The index on screen right now (what tap navigation, solo actions and menus see). */
    val currentIndex: Int

    /** The index that last finished a turn (what progress/preload/unread effects observe). */
    val settledIndex: Int

    /** True while a finger is turning the page or a programmed turn is still animating. */
    val isScrolling: Boolean

    /** Jump without animation (slider, page jump, layout re-anchor). */
    suspend fun scrollTo(index: Int)

    /** Turn with animation when the target is adjacent; snap otherwise (mirrors the original
     * handleNavigationTarget: |Δ|==1 animates, anything else rebuilds around the target). */
    suspend fun animateTo(index: Int)
}

internal class PagerTurnState(
    private val pagerState: androidx.compose.foundation.pager.PagerState,
) : ReaderTurnState {
    override val currentIndex: Int get() = pagerState.currentPage
    override val settledIndex: Int get() = pagerState.settledPage
    override val isScrolling: Boolean get() = pagerState.isScrollInProgress
    override suspend fun scrollTo(index: Int) = pagerState.scrollToPage(index)
    override suspend fun animateTo(index: Int) = pagerState.animateScrollToPage(index)
}

/**
 * State machine of the cover transition, a 1:1 port of the original NativeCoverDeckState +
 * NativeCoverPageView.Coordinator semantics:
 *
 * - The deck holds three logical slots: back (previous item), front (current), middle (next).
 *   A drag never rebinds content: it only picks a target and moves one slot; the slot contents
 *   rotate after the commit (`rotateAfterCommit`), so a page can never change under the finger.
 * - Forward (target = front + 1): the front page is the moving, elevated one and slides away,
 *   revealing the next page underneath.
 * - Backward (target = front - 1): the previous page slides in on top of the front page, starting
 *   off-screen at the edge the finger came from.
 * - At a book seam there is no target: the drag rubber-bands invisibly (the offset is only kept
 *   for the release threshold, as in the original) and the leftover is reported through
 *   [onBoundaryOverscroll], which is what the continuation pull uses to open the adjacent book.
 *
 * All offsets live in plain state read from graphicsLayer lambdas, so a drag frame never
 * recomposes — it only re-records the two affected layers.
 */
internal class CoverDeckState(
    initialIndex: Int,
    private val scope: CoroutineScope,
) : ReaderTurnState {

    // Environment synced from composition every pass (drag maths reads these, composition never does).
    var itemCount by mutableIntStateOf(0)
    var rtl by mutableStateOf(false)
    var zoomed by mutableStateOf(false)

    /** Boundary callbacks, wired by the reader: leftover drag at a seam, release, and the haptic hit. */
    var onBoundaryOverscroll: ((distancePx: Float, forward: Boolean) -> Unit)? = null
    var onBoundaryRelease: (() -> Unit)? = null
    var onBoundaryHit: (() -> Unit)? = null

    var extentPx by mutableFloatStateOf(0f)
        private set

    /** The item in the front slot (the committed page). */
    var frontIndex by mutableIntStateOf(initialIndex)
        private set

    /** The index progress reporting observes: it only moves once a turn is fully committed. */
    var settledIndexState by mutableIntStateOf(initialIndex)
        private set

    /** The item a transition is heading to (null = idle, mirrors the original transitionDirection). */
    var targetIndex by mutableStateOf<Int?>(null)
        private set

    /** Translation of the moving slot in px (the original dragOffset). */
    var dragOffset by mutableFloatStateOf(0f)
        private set

    var animating by mutableStateOf(false)
        private set
    var dragging by mutableStateOf(false)
        private set

    /** Scaled rubber-band offset at a seam; kept for the release threshold, never rendered. */
    private var rubberOffset = 0f
    private var lastTranslation = 0f
    private var generation = 0
    private var transitionJob: Job? = null

    override val currentIndex: Int get() = frontIndex
    override val settledIndex: Int get() = settledIndexState
    override val isScrolling: Boolean get() = dragging || animating

    private val lastIndex: Int get() = (itemCount - 1).coerceAtLeast(0)

    fun setExtent(px: Float) {
        if (px <= 0f || extentPx == px) return
        val hadExtent = extentPx > 0f
        extentPx = px
        if (hadExtent && dragging) {
            // A viewport change (rotation) distorts the accumulated translation; cancel the drag
            // instead of committing against a coordinate space that no longer applies (mirrors
            // handleContainerLayout resetting the drag state).
            cancelDragImmediately()
        }
    }

    /** A finger came down. False when the deck must not take the gesture (zoomed or mid-animation,
     * mirroring applyPanRecognizerState). */
    fun beginDrag(): Boolean {
        if (animating || zoomed || itemCount <= 0) return false
        transitionJob?.cancel()
        generation++
        dragging = true
        rubberOffset = 0f
        lastTranslation = 0f
        return true
    }

    /** The finger moved. [translation] is the gesture's total horizontal travel, [delta] the latest step. */
    fun dragBy(translation: Float, delta: Float) {
        if (!dragging || animating) return
        lastTranslation = translation
        // adjacentOffset: LTR turns forward on a leftward drag, RTL on a rightward one
        val directionOffset = if (rtl) {
            if (translation > 0f) 1 else -1
        } else {
            if (translation < 0f) 1 else -1
        }
        val target = frontIndex + directionOffset
        if (target !in 0..lastIndex) {
            // Book seam: nothing to turn to. The original rubber-bands an invisible offset and only
            // feeds the release threshold; the leftover distance goes to the continuation pull.
            targetIndex = null
            rubberOffset = translation * OverscrollResistance
            onBoundaryOverscroll?.invoke(abs(delta), directionOffset == 1)
            return
        }
        targetIndex = target
        dragOffset = if (directionOffset == 1) {
            translation
        } else {
            // backwardInteractiveOffset: the previous page enters from the edge the finger came
            // from and can never overshoot its resting place
            val translationSign = if (translation >= 0f) 1f else -1f
            val start = -translationSign * extentPx
            if (start < 0f) {
                (start + translation).coerceIn(start, 0f)
            } else {
                (start + translation).coerceIn(0f, start)
            }
        }
    }

    /** The finger lifted. [velocityPx] is the gesture's horizontal velocity in px/s. */
    fun endDrag(velocityPx: Float) {
        if (!dragging) return
        dragging = false
        val target = targetIndex
        if (target == null) {
            // A rubber-banded drag with no page to land on is a boundary hit (haptic + release)
            if (abs(rubberOffset) > BoundaryFeedbackMinimumOffset) onBoundaryHit?.invoke()
            rubberOffset = 0f
            onBoundaryRelease?.invoke()
            return
        }
        val shouldCommit =
            abs(lastTranslation) > extentPx * CommitDistanceRatio ||
                abs(velocityPx) > CommitVelocityThreshold
        if (shouldCommit) {
            commit(target, animate = true)
        } else {
            cancelTransition()
        }
    }

    /** The gesture died without a lift (second finger, viewport change): snap back, no animation
     * (mirrors resetDragStateImmediately). */
    fun cancelDragImmediately() {
        if (!dragging && targetIndex == null) return
        dragging = false
        transitionJob?.cancel()
        generation++
        resetTransition()
        onBoundaryRelease?.invoke()
    }

    /** The gesture ended before the touch slop: nothing was ever dragged. */
    fun abortDrag() {
        if (!dragging) return
        dragging = false
        rubberOffset = 0f
        onBoundaryRelease?.invoke()
    }

    override suspend fun scrollTo(index: Int) {
        // The original rebuilds the deck around the target for non-adjacent navigation
        transitionJob?.cancel()
        generation++
        dragging = false
        frontIndex = index.coerceIn(0, lastIndex)
        resetTransition()
        settledIndexState = frontIndex
    }

    override suspend fun animateTo(index: Int) {
        val target = index.coerceIn(0, lastIndex)
        if (target == frontIndex) return
        if (abs(target - frontIndex) == 1) {
            commit(target, animate = true)
        } else {
            scrollTo(target)
        }
    }

    fun release() {
        transitionJob?.cancel()
        onBoundaryOverscroll = null
        onBoundaryRelease = null
        onBoundaryHit = null
    }

    private fun commit(target: Int, animate: Boolean) {
        if (target !in 0..lastIndex || target == frontIndex) {
            cancelTransition()
            return
        }
        transitionJob?.cancel()
        val token = ++generation
        val forward = target > frontIndex
        // transitionDirectionSign: forward moves the page against the reading direction
        val directionSign = if (forward) {
            if (rtl) 1f else -1f
        } else {
            if (rtl) -1f else 1f
        }
        val endOffset = if (forward) directionSign * extentPx else 0f
        targetIndex = target
        if (!forward && abs(dragOffset) < CancelThreshold) {
            // A programmatic backward turn starts with the previous page fully off-screen
            dragOffset = -directionSign * extentPx
        }
        animating = true
        if (!animate || extentPx <= 0f) {
            completeTransition(target)
            return
        }
        transitionJob = scope.launch {
            animate(
                initialValue = dragOffset,
                targetValue = endOffset,
                animationSpec = tween(TransitionDurationMs, easing = FastOutSlowInEasing),
            ) { value, _ ->
                dragOffset = value
            }
            if (token == generation) completeTransition(target)
        }
    }

    private fun cancelTransition() {
        transitionJob?.cancel()
        val token = ++generation
        val target = targetIndex
        // The incoming previous page slides back off the edge it entered from
        val dest = if (target != null && target < frontIndex) {
            val sign = if (rtl) -1f else 1f
            -sign * extentPx
        } else {
            0f
        }
        animating = true
        transitionJob = scope.launch {
            animate(
                initialValue = dragOffset,
                targetValue = dest,
                animationSpec = tween(TransitionDurationMs, easing = FastOutSlowInEasing),
            ) { value, _ ->
                dragOffset = value
            }
            if (token == generation) resetTransition()
        }
    }

    /** rotateAfterCommit: the slots rotate only now, so the page under the finger never changed
     * identity during the gesture; the settled index (progress/preload) moves last. */
    private fun completeTransition(target: Int) {
        frontIndex = target
        resetTransition()
        settledIndexState = target
    }

    private fun resetTransition() {
        targetIndex = null
        animating = false
        dragging = false
        dragOffset = 0f
        rubberOffset = 0f
    }
}

/**
 * Whether the slot's content may react to input — the port of `NativeCoverSlotView`'s
 * `isUserInteractionEnabled = isVisible && isActive`. Only the deck's front slot is provided `true`.
 *
 * A Compose UI has no occlusion: a slot composed *under* an opaque sibling is still hit-tested, and
 * `zIndex` only orders drawing. Without this flag the two idle neighbour slots keep their real
 * content interactive, so on the last page of a book (where the middle slot holds
 * [ReaderViewItem.End]) a tap meant for the visible page fires the covered "read next" card
 * instead — the deck's own tap handling never sees the up, because the card consumes it. The same
 * goes for a failed page's retry button and the end page's scroll.
 *
 * Deliberately *not* solved with a pointer-input consumer on the covered slot: consuming a change
 * makes the deck's own `positionChange()` read zero (Compose reports a consumed move as no move),
 * which is exactly what broke dragging before `fix(reader): hidden deck slots must not swallow the
 * deck's own drags`. Gating the content leaves the gesture stream untouched.
 */
internal val LocalCoverSlotInteractive = compositionLocalOf { true }

/**
 * The three-slot cover stack (mirrors NativeCoverPageView/NativeCoverSlotView). Slots are keyed by
 * the item they show, so after a commit the composable that already holds the decoded page moves
 * with its item instead of being rebound — the slot rotation the original gets from reusing three
 * UIViews.
 *
 * Invariant: **every in-range slot is composed at all times**, including the two neighbours the user
 * cannot see. The neighbours' page images are requested (and decoded at display size) here while
 * idle, which is what makes a turn reveal an already-rendered page instead of one that starts
 * loading at the moment the front page slides away. Compose paints an `alpha = 0` layer as nothing
 * while still composing it, so the invisibility costs nothing and must not be "optimized" into
 * `if (visible)` / a lazy slot list: that reintroduces the first-frame flash the iOS cover deck
 * fixed in `fix(reader): eliminate cover-mode page-turn flash`. (Also why the slots never hide via
 * `alpha = 0` *animations*: only the moving slot's translation and shadow animate.)
 *
 * [pageContent]'s boolean tells the content whether it is the active front slot; it is the value the
 * enhancement priority and the interaction flag are derived from.
 */
@Composable
internal fun CoverDeck(
    state: CoverDeckState,
    items: List<ReaderViewItem>,
    rtl: Boolean,
    zoomed: Boolean,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    pageContent: @Composable (ReaderViewItem, Boolean) -> Unit,
) {
    SideEffect {
        state.itemCount = items.size
        state.rtl = rtl
        state.zoomed = zoomed
    }
    val viewConfiguration = LocalViewConfiguration.current
    Box(
        modifier = modifier
            .onSizeChanged { state.setExtent(it.width.toFloat()) }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!state.beginDrag()) return@awaitEachGesture
                    val tracker = VelocityTracker()
                    var totalX = 0f
                    var totalY = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            if (dragging) state.endDrag(tracker.calculateVelocity().x) else state.abortDrag()
                            break
                        }
                        if (event.changes.count { it.pressed } > 1) {
                            // A second finger is the pinch zoom: hand the gesture over
                            if (dragging) state.cancelDragImmediately() else state.abortDrag()
                            break
                        }
                        val dx = change.positionChange().x
                        val dy = change.positionChange().y
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!dragging) {
                            totalX += dx
                            totalY += dy
                            if (abs(totalX) > viewConfiguration.touchSlop &&
                                abs(totalX) > abs(totalY) + DirectionalDragBias
                            ) {
                                dragging = true
                                change.consume()
                                state.dragBy(totalX, dx)
                            } else if (abs(totalY) > viewConfiguration.touchSlop) {
                                state.abortDrag()
                                break
                            }
                        } else {
                            totalX += dx
                            change.consume()
                            state.dragBy(totalX, dx)
                        }
                    }
                }
            },
    ) {
        val front = state.frontIndex
        val target = state.targetIndex
        val forward = target != null && target > front
        val backward = target != null && target < front
        // Role mapping mirrors the original slotRenderState:
        // - idle: only the front slot is visible and active
        // - forward: the front slot (moving, elevated) slides over the middle slot (next, static)
        // - backward: the back slot (previous) slides in over the front slot (static, still active)
        val backIndex = front - 1
        val middleIndex = front + 1
        val slots = buildList {
            if (backIndex in items.indices) {
                add(
                    CoverSlotSpec(
                        index = backIndex,
                        visible = backward,
                        moving = backward,
                        z = if (backward) 1f else -1f,
                    ),
                )
            }
            if (middleIndex in items.indices) {
                add(
                    CoverSlotSpec(
                        index = middleIndex,
                        visible = forward,
                        moving = false,
                        z = if (forward) 0f else -1f,
                    ),
                )
            }
            add(
                CoverSlotSpec(
                    index = front,
                    visible = true,
                    moving = forward,
                    z = if (forward) 1f else 0f,
                ),
            )
        }
        for (spec in slots) {
            val item = items[spec.index]
            val isFront = spec.index == front
            key(item) {
                // A slot the user can see but must not touch (the page revealed under a forward
                // turn, or the page sliding in over the front page): swallow its touches so an
                // end-page button cannot fire mid-transition. The front slot stays interactive at
                // all times, and hidden slots swallow nothing — the original maps this straight
                // from isUserInteractionEnabled = isVisible && isActive (a hidden UIView is not
                // hit-tested; an alpha=0 composable still is, which would eat the deck's drags).
                val swallowTouches = spec.visible && spec.index != front
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(spec.z)
                        // A covered slot is rendered for preload only: clear its semantics so
                        // TalkBack cannot read page numbers or end-page copy that is not on screen
                        // (the port of `accessibilityElementsHidden` on the iOS cover slot).
                        .then(if (isFront) Modifier else Modifier.clearAndSetSemantics { })
                        .graphicsLayer {
                            translationX = if (spec.moving) state.dragOffset else 0f
                            alpha = if (spec.visible) 1f else 0f
                            // The lifted page casts a shadow while it travels (the original raises
                            // its shadow opacity/radius once the offset passes the cancel threshold)
                            shadowElevation =
                                if (spec.moving && abs(state.dragOffset) > CancelThreshold) 6.dp.toPx() else 0f
                            shape = RectangleShape
                            clip = false
                        }
                        .background(backgroundColor)
                        .then(if (swallowTouches) ConsumeTouchesModifier else Modifier),
                ) {
                    // Only the front slot's content reacts to input; a neighbour's actions stay
                    // inert while it waits underneath (see LocalCoverSlotInteractive).
                    CompositionLocalProvider(LocalCoverSlotInteractive provides isFront) {
                        pageContent(item, isFront)
                    }
                }
            }
        }
    }
}

private class CoverSlotSpec(
    val index: Int,
    val visible: Boolean,
    val moving: Boolean,
    val z: Float,
)

private val ConsumeTouchesModifier = Modifier.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { if (it.pressed) it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}
