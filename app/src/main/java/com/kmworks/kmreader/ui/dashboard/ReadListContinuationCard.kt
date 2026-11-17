package com.kmworks.kmreader.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.ReadListContinuationUi
import com.kmworks.kmreader.ui.components.HorizontalCardContainer
import com.kmworks.kmreader.ui.components.TightTextStyle

/**
 * Read-list continuation horizontal card (aligned with iOS ReadListContinuationHorizontalCardView):
 * list name (semibold 13, up to 2 lines) + next book title 11 + progress text 10, no progress-bar control;
 * the card body is tinted with the cover's average color.
 */
@Composable
fun ReadListContinuationCard(
    continuation: ReadListContinuationUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stableId: Int? = null,
) {
    HorizontalCardContainer(
        // With a cover book, use the book# namespace (aligned with iOS, sharing the tint cache
        // with book horizontal cards); fall back to the readList# key only without a cover.
        tintKey = stableId?.let { "book#$it" } ?: "readList#${continuation.readListId}",
        coverStableId = stableId,
        coverTitle = continuation.readListName,
        hue = continuation.hue,
        blurUnread = false,
        onClick = onClick,
        modifier = modifier,
    ) { palette ->
        Column(modifier = Modifier.align(Alignment.TopStart)) {
            Text(
                text = continuation.readListName,
                fontSize = 13.sp,
                lineHeight = 15.5.sp, // matches BookHorizontalCard, avoiding overflow from a large inherited default line height
                fontWeight = FontWeight.SemiBold,
                color = palette.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TightTextStyle, // disables includeFontPadding so first/last-line padding cannot push the bottom progress out of the column height
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = continuation.nextBookTitle,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                color = palette.series,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TightTextStyle,
            )
        }
        // Progress line: book % while in progress, then list counts (aligned with ReadListContinuationProgressText).
        val listLabel = stringResource(
            R.string.dashboard_continuation_read_count,
            continuation.booksRead,
            continuation.bookCount,
        )
        val progressText = continuation.bookProgress?.let { "${(it * 100).toInt()}% • $listLabel" }
            ?: listLabel
        Text(
            text = progressText,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = palette.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TightTextStyle,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}
