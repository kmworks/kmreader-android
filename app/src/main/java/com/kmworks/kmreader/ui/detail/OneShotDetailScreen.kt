package com.kmworks.kmreader.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.ui.RootViewModel
import kotlinx.coroutines.launch

/** 单发详情页（对齐 OneShotDetailView.swift）：与书籍详情同构，无系列归属与卷号。 */
@Composable
fun OneShotDetailScreen(
    book: Book,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    onOpenSeries: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onMarkReadToggle: (Book) -> Unit = {},
    onDownload: (Book) -> Unit = {},
    onAddToReadList: (Book) -> Unit = {},
    onMenuAction: (BookMenuAction) -> Unit = {},
    onReadIncognito: () -> Unit = {},
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    BookDetailBody(
        book = book,
        seriesTitle = null,
        onBack = onBack,
        onRead = onRead,
        onOpenSeries = {
            scope.launch {
                rootViewModel.seriesStableIdForBook(book.id)?.let(onOpenSeries)
            }
        },
        onMarkReadToggle = onMarkReadToggle,
        onDownload = onDownload,
        onAddToReadList = onAddToReadList,
        onMenuAction = onMenuAction,
        onReadIncognito = onReadIncognito,
        modifier = modifier,
    )
}
