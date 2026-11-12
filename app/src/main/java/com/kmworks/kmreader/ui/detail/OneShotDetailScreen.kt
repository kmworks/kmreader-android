package com.kmworks.kmreader.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kmworks.kmreader.data.Book

/** 单发详情页（对齐 OneShotDetailView.swift）：与书籍详情同构，无系列归属与卷号。 */
@Composable
fun OneShotDetailScreen(
    book: Book,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    modifier: Modifier = Modifier,
    onMarkReadToggle: (Book) -> Unit = {},
    onDownload: (Book) -> Unit = {},
    onAddToReadList: (Book) -> Unit = {},
    onMenuAction: (BookMenuAction) -> Unit = {},
) {
    BookDetailBody(
        book = book,
        seriesTitle = null,
        onBack = onBack,
        onRead = onRead,
        onOpenSeries = {},
        onMarkReadToggle = onMarkReadToggle,
        onDownload = onDownload,
        onAddToReadList = onAddToReadList,
        onMenuAction = onMenuAction,
        modifier = modifier,
    )
}
