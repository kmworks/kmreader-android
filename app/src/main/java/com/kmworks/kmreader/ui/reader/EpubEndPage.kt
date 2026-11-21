package com.kmworks.kmreader.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.Book

/**
 * The gesture that opens the EPUB end page: the bottom of the last chapter.
 *
 * The WebView reports the bottom edge itself (bottom overscroll), so the trigger only has to know
 * which chapter is showing. Kept as a pure function so the decision is testable without a WebView.
 */
internal object EpubEndPageTrigger {

    fun shouldShow(chapterIndex: Int, chapterCount: Int): Boolean =
        chapterCount > 0 && chapterIndex == chapterCount - 1
}

/**
 * EPUB end page (mirrors iOS EpubEndPageView): shown once the last chapter is scrolled past its
 * bottom. [onReturn] dismisses it back into the book, [onClose] leaves the reader.
 *
 * The EPUB reader has no neighbouring-book cards (the options menu carries those), so this page is
 * only the "finished" state the DIVINA end page would show for the last book of a series.
 */
@Composable
internal fun EpubEndPage(
    book: Book,
    background: Color,
    contentColor: Color,
    onReturn: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(background)
            // Swallow taps so they cannot reach the WebView behind the end page
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.epub_end_finished),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onReturn,
            colors = ButtonDefaults.buttonColors(
                containerColor = contentColor,
                contentColor = background,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp),
        ) {
            Text(stringResource(R.string.epub_end_return))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onClose,
            border = BorderStroke(1.dp, contentColor.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp),
        ) {
            Text(stringResource(R.string.common_close), color = contentColor)
        }
    }
}
