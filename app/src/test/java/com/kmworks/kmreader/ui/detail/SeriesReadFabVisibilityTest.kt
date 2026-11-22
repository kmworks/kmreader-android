package com.kmworks.kmreader.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floating read capsule only has one job: reappear once the inline continue-reading capsule has
 * scrolled fully behind the frosted top bar. Getting the boundary wrong either shows two reading
 * calls to action at once or hides the only one on screen, so the rule is pinned here.
 */
class SeriesReadFabVisibilityTest {

    /** The frosted bar's bottom edge in the page box: status bar inset + the 52dp row. */
    private val barBottomPx = 104f

    private fun shouldShow(
        ctaBottomPx: Float?,
        hasTarget: Boolean = true,
        selectionActive: Boolean = false,
        isNarrow: Boolean = true,
    ): Boolean = shouldShowReadFab(
        isNarrow = isNarrow,
        hasTarget = hasTarget,
        selectionActive = selectionActive,
        ctaBottomPx = ctaBottomPx,
        barBottomPx = barBottomPx,
    )

    @Test
    fun showsOnceTheInlineCapsuleIsFullyCovered() {
        assertTrue(shouldShow(ctaBottomPx = barBottomPx))
        assertTrue(shouldShow(ctaBottomPx = barBottomPx - 64f))
    }

    @Test
    fun staysHiddenWhilePartOfTheInlineCapsuleIsStillVisible() {
        assertFalse(shouldShow(ctaBottomPx = barBottomPx + 0.5f))
        assertFalse(shouldShow(ctaBottomPx = barBottomPx + 300f))
    }

    @Test
    fun hiddenWhenNoBookCanBeRead() {
        assertFalse(shouldShow(ctaBottomPx = 10f, hasTarget = false))
    }

    @Test
    fun hiddenWhileMultiSelectIsActive() {
        assertFalse(shouldShow(ctaBottomPx = 10f, selectionActive = true))
    }

    @Test
    fun hiddenInTheWideLayout() {
        assertFalse(shouldShow(ctaBottomPx = 10f, isNarrow = false))
    }

    @Test
    fun hiddenUntilTheInlineCapsuleHasBeenMeasured() {
        assertFalse(shouldShow(ctaBottomPx = null))
    }
}
