package com.kmworks.kmreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ReaderAdjacentBooks
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.ui.components.CoverArt
import kotlin.math.max

/**
 * End-of-book cell (mirrors the original NativeEndPagePresentation / NativeEndPageContentView):
 * the finished book as "Previous", then either the next book or the caught-up state. Portrait stacks
 * the sections with the series name between them and hides the previous cover; landscape puts them
 * side by side with a vertical divider, the series name as a header and both covers visible, the next
 * section on the leading side for right-to-left reading.
 *
 * The original continues by paging past this cell; a Compose pager cannot be paged beyond its last
 * cell, so the fixed-height slot under the next book (the offline download state in the original)
 * carries the "read next" action instead.
 *
 * When [onOpenPrevious] is non-null the finished book's card is tappable and reopens that book on its
 * own reading position; it stays inert on the current book's own end page.
 */
/** Height this card falls back to when its host measures it with an unbounded max height. */
private val EndPageFallbackHeight = 420.dp

@Composable
internal fun ReaderEndPage(
    book: Book,
    adjacent: ReaderAdjacentBooks,
    unreadCount: Int?,
    /** The read list name while a list order applies, otherwise the series title. */
    relationTitle: String,
    isRtl: Boolean,
    contentColor: Color,
    onOpenNext: (Book) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Reopens the previous book on its own progress; null makes the card inert (hidden affordance). */
    onOpenPrevious: (() -> Unit)? = null,
) {
    val next = adjacent.next
    // Caught up when the lookup finished and the series has nothing after this book
    val caughtUp = adjacent.loaded && next == null

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isPortrait = maxHeight >= maxWidth
        // A host can measure this card unbounded (a LazyColumn cell, a scroll column). verticalScroll
        // refuses an infinite max height, so the body is pinned to one screen-sized box when that
        // happens instead of taking the host's constraints as-is.
        val bodyHeight = if (constraints.hasBoundedHeight) maxHeight else EndPageFallbackHeight
        // The series name is a header above the sections in landscape and sits between them in portrait
        val showsHeader = !isPortrait && relationTitle.isNotBlank()
        val dividerWidth = (maxWidth.value * 0.78f).coerceIn(260f, 680f).dp
        val verticalDividerHeight = (max(maxWidth.value, maxHeight.value) * 0.32f)
            .coerceIn(140f, 320f)
            .dp

        Box(modifier = Modifier.fillMaxWidth().height(bodyHeight), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 40.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (showsHeader) {
                    RelationDivider(
                        title = relationTitle,
                        width = dividerWidth,
                        contentColor = contentColor,
                    )
                }
                if (isPortrait) {
                    PreviousSection(
                        book = book,
                        showsCover = false,
                        contentColor = contentColor,
                        onOpen = onOpenPrevious,
                    )
                    if (relationTitle.isNotBlank()) {
                        RelationDivider(
                            title = relationTitle,
                            width = dividerWidth,
                            contentColor = contentColor,
                        )
                    }
                    NextColumn(
                        adjacent = adjacent,
                        unreadCount = unreadCount,
                        showsCover = true,
                        contentColor = contentColor,
                        onOpenNext = onOpenNext,
                        onRetry = onRetry,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Mirrors nextOnLeadingSide: right-to-left puts the next book on the left
                        val previousSlot: @Composable () -> Unit = {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                PreviousSection(
                                    book = book,
                                    showsCover = true,
                                    contentColor = contentColor,
                                    onOpen = onOpenPrevious,
                                )
                            }
                        }
                        val nextSlot: @Composable () -> Unit = {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                NextColumn(
                                    adjacent = adjacent,
                                    unreadCount = unreadCount,
                                    showsCover = true,
                                    contentColor = contentColor,
                                    onOpenNext = onOpenNext,
                                    onRetry = onRetry,
                                )
                            }
                        }
                        val divider: @Composable () -> Unit = {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(verticalDividerHeight)
                                    .background(contentColor.copy(alpha = 0.3f)),
                            )
                        }
                        if (isRtl) {
                            nextSlot()
                            divider()
                            previousSlot()
                        } else {
                            previousSlot()
                            divider()
                            nextSlot()
                        }
                    }
                }
                if (caughtUp) {
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.common_close), color = contentColor)
                    }
                }
            }
        }
    }
}

/** The finished book: badge, cover (landscape only) and its chapter metadata; tappable when [onOpen] is set. */
@Composable
private fun PreviousSection(
    book: Book,
    showsCover: Boolean,
    contentColor: Color,
    onOpen: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            // Only a card with somewhere to go is clickable; without a callback the card stays inert
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionBadge(text = stringResource(R.string.reader_end_previous_book), contentColor = contentColor)
        SectionMetadata(
            book = book,
            unreadCount = null,
            contentColor = contentColor,
            cover = if (showsCover) {
                { BookCover(book) }
            } else {
                null
            },
        )
    }
}

/** The next book, or the caught-up block, or a retry while the sibling lookup failed. */
@Composable
private fun NextColumn(
    adjacent: ReaderAdjacentBooks,
    unreadCount: Int?,
    showsCover: Boolean,
    contentColor: Color,
    onOpenNext: (Book) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        adjacent.failed -> RetrySection(contentColor = contentColor, onRetry = onRetry)
        adjacent.next != null -> NextSection(
            next = adjacent.next,
            unreadCount = unreadCount,
            showsCover = showsCover,
            contentColor = contentColor,
            onOpenNext = onOpenNext,
        )

        !adjacent.loaded -> CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = contentColor,
            strokeWidth = 2.dp,
        )

        else -> CaughtUpSection(unreadCount = unreadCount, contentColor = contentColor)
    }
}

/** Badge, cover and chapter metadata of the next book, plus the fixed-height action slot. */
@Composable
private fun NextSection(
    next: Book,
    unreadCount: Int?,
    showsCover: Boolean,
    contentColor: Color,
    onOpenNext: (Book) -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpenNext(next) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionBadge(text = stringResource(R.string.reader_end_next_book), contentColor = contentColor)
        SectionMetadata(
            book = next,
            unreadCount = unreadCount,
            contentColor = contentColor,
            cover = if (showsCover) {
                { BookCover(next) }
            } else {
                null
            },
        )
        // Fixed-height slot under the next book. The original fills it with the offline download state
        // (and collapses it entirely for streaming readers, which download nothing ahead); there is no
        // offline store here, so the same slot carries the continuation action instead — the layout
        // stays identical in height, so offline state can be added without shifting the page.
        Box(
            modifier = Modifier
                .height(20.dp)
                .fillMaxWidth()
                .clickable { onOpenNext(next) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.reader_end_read_next),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/** Nothing after this book (the original "You're all caught up!" block). */
@Composable
private fun CaughtUpSection(
    unreadCount: Int?,
    contentColor: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.reader_end_caught_up),
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unreadCount != null && unreadCount > 0) {
            Text(
                text = stringResource(R.string.reader_end_unread_left, unreadCount),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The sibling lookup failed (offline): say so instead of claiming the series is finished. */
@Composable
private fun RetrySection(contentColor: Color, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.reader_pages_load_failed),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.common_retry), color = contentColor)
        }
    }
}

@Composable
private fun SectionBadge(text: String, contentColor: Color) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        color = contentColor.copy(alpha = 0.55f),
        maxLines = 1,
    )
}

/** Cover + title + pages + optional unread line, the shared block of both sections. */
@Composable
private fun SectionMetadata(
    book: Book,
    unreadCount: Int?,
    contentColor: Color,
    cover: (@Composable () -> Unit)?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        cover?.invoke()
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = book.titleLine,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.reader_end_pages, book.pageCount),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unreadCount != null && unreadCount > 0) {
                Text(
                    text = stringResource(R.string.reader_end_unread_left, unreadCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BookCover(book: Book) {
    CoverArt(
        title = book.title,
        hue = book.hue,
        stableId = book.id,
        modifier = Modifier.width(120.dp).height(160.dp),
    )
}

/** Series name between a hairline on each side (the original relationHeader). */
@Composable
private fun RelationDivider(title: String, width: Dp, contentColor: Color) {
    Row(
        modifier = Modifier.widthIn(max = width).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DividerLine(Modifier.weight(1f), contentColor)
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The title may take at most half of the divider, so the hairlines never collapse
            modifier = Modifier.widthIn(max = width * 0.5f),
        )
        DividerLine(Modifier.weight(1f), contentColor)
    }
}

@Composable
private fun DividerLine(modifier: Modifier, contentColor: Color) {
    Spacer(
        modifier = modifier
            .height(1.dp)
            .background(contentColor.copy(alpha = 0.3f)),
    )
}
