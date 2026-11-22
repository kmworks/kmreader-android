package com.kmworks.kmreader.ui.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.Book

/** Height of the floating read capsule. One step up from the 40dp minimum so it stays reachable with
 *  a thumb while the page scrolls under it. */
internal val SeriesReadFabHeight = 56.dp

/** Content reserved after the book list in the narrow layout, so the last row can still be scrolled
 *  clear of the floating capsule. Constant while a reading target exists: adding it only when the
 *  capsule is visible would shift the list under the finger that is scrolling it. */
internal val SeriesReadFabBottomReserve = 64.dp

/**
 * Whether the floating read capsule should be shown.
 *
 * The capsule replaces the inline continue-reading capsule once the inline one has scrolled away
 * behind the frosted top bar: the detail page only wants one reading call to action on screen, and
 * the inline one is the better target while it is visible.
 *
 * @param isNarrow the narrow (phone / portrait tablet) layout; the wide rail keeps the inline capsule
 *   in view at all times and needs no floating replacement.
 * @param hasTarget whether a reading target (`continueBook`) resolved at all.
 * @param selectionActive multi-select is on; the pinned selection toolbar owns the bottom edge then.
 * @param ctaBottomPx bottom edge of the inline capsule in viewport (content box) pixels; null until
 *   the capsule has been measured.
 * @param barBottomPx bottom edge of the frosted top bar, in the same coordinate space.
 */
internal fun shouldShowReadFab(
    isNarrow: Boolean,
    hasTarget: Boolean,
    selectionActive: Boolean,
    ctaBottomPx: Float?,
    barBottomPx: Float,
): Boolean {
    if (!isNarrow || !hasTarget || selectionActive) return false
    // The threshold is "completely covered": the capsule only appears once its bottom edge has
    // reached the bar's bottom edge, so it never sits half behind the glass.
    return ctaBottomPx != null && ctaBottomPx <= barBottomPx
}

/**
 * Floating read capsule pinned to the bottom end of the narrow detail layout.
 *
 * Single line, primary tinted (the same colours as the inline capsule), so it reads as the same
 * control reappearing after the inline one scrolled away. The accessibility description carries the
 * volume and title the inline capsule shows in its second line.
 */
@Composable
internal fun SeriesReadFab(
    book: Book,
    onRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resuming = book.progress?.let { it > 0f && it < 1f } == true
    val label = stringResource(
        if (resuming) R.string.book_resume_reading else R.string.book_start_reading,
    )
    val description = stringResource(
        R.string.series_read_fab_description,
        label,
        book.number,
        book.title,
    )
    Button(
        onClick = onRead,
        modifier = modifier
            .height(SeriesReadFabHeight)
            .semantics { contentDescription = description },
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
