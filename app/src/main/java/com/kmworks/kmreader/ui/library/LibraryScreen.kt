package com.kmworks.kmreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.data.filterKey
import com.kmworks.kmreader.ui.components.EmptyState
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BookListRow
import com.kmworks.kmreader.ui.components.BrowseLayout
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import com.kmworks.kmreader.ui.components.LocalNavOverlaySpace
import com.kmworks.kmreader.ui.components.rememberBrowseFilterSuggestions
import com.kmworks.kmreader.ui.components.ContextMenuSheet
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import com.kmworks.kmreader.ui.components.LibraryPinSheet
import com.kmworks.kmreader.ui.components.SeriesGridCard
import com.kmworks.kmreader.ui.components.SeriesListRow
import com.kmworks.kmreader.ui.components.libraryScopeLabel
import com.kmworks.kmreader.ui.components.libraryMetricsCaption
import com.kmworks.kmreader.ui.components.rememberPersistentBrowseLayout
import com.kmworks.kmreader.ui.components.bookContextMenuItems
import com.kmworks.kmreader.ui.components.seriesContextMenuItems
import com.kmworks.kmreader.ui.detail.BookEditSheet
import com.kmworks.kmreader.ui.detail.SeriesEditSheet
import com.kmworks.kmreader.ui.lists.AddToListSheet
import com.kmworks.kmreader.ui.util.shareText

private enum class ContentType(@param:StringRes val labelRes: Int) {
    Series(R.string.common_series),
    Books(R.string.browse_scope_books),
}

/** Wire tokens of the metadata-filter route shared with the detail screens (they emit, this
 *  screen consumes). Internal routing values only; display labels are localized at render time. */
private const val FILTER_KIND_AUTHOR = "author"
private const val FILTER_KIND_TAG = "tag"
private const val FILTER_KIND_PUBLISHER = "publisher"
private const val FILTER_KIND_GENRE = "genre"
private const val FILTER_KIND_AGE_RATING = "ageRating"
private const val FILTER_KIND_LANGUAGE = "language"
private const val FILTER_KIND_RELEASE_YEAR = "releaseYear"
private const val FILTER_KIND_NOTABLE = "notable"

/** Library: content-first browsing (Apple Books style). Top = content type chips (series/books,
 *  with counts), below it the scope summary (scope title + size caption); the scope
 *  (all/pinned/single library) is switched in place from the toolbar menu and defaults to pinned.
 *  Mirrors KMReader LibraryBrowseView + BrowseContentView. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    activeFilter: String? = null,
    onClearFilter: () -> Unit = {},
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit = {},
    onVisitSeries: (Book) -> Unit = {},
    onReadBook: (Book, incognito: Boolean) -> Unit = { _, _ -> },
) {
    var contentType by remember { mutableStateOf(ContentType.Series) }
    // Layout persisted per content type (mirrors iOS seriesBrowseLayout / bookBrowseLayout,
    // default grid)
    val (seriesLayout, setSeriesLayout) = rememberPersistentBrowseLayout("librarySeries", BrowseLayout.Grid)
    val (booksLayout, setBooksLayout) = rememberPersistentBrowseLayout("libraryBooks", BrowseLayout.Grid)
    val layout = if (contentType == ContentType.Series) seriesLayout else booksLayout
    val setLayout = if (contentType == ContentType.Series) setSeriesLayout else setBooksLayout
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var showPinSheet by remember { mutableStateOf(false) }
    // Target of the "Add to Collection/Read List" sheet
    var addTarget by remember { mutableStateOf<Any?>(null) }
    var addToCollectionOpen by remember { mutableStateOf(false) }
    var addToReadListOpen by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    // Long-press context menu target (mirrors the original card contextMenu)
    var menuSeries by remember { mutableStateOf<Series?>(null) }
    var menuBook by remember { mutableStateOf<Book?>(null) }
    // Edit/delete sheet targets (entered from the context menu)
    var editSeries by remember { mutableStateOf<Series?>(null) }
    var editBook by remember { mutableStateOf<Book?>(null) }
    var deleteSeriesTarget by remember { mutableStateOf<Series?>(null) }
    var deleteBookTarget by remember { mutableStateOf<Book?>(null) }
    val context = LocalContext.current

    val viewModel: LibraryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    // real content only; empty state when there is no session or nothing loaded
    val series = state.series ?: emptyList()
    val allBooks = state.books ?: emptyList()
    // Display name of the current scope (mirrors iOS LibraryScopeMenu)
    val scopeLabel = libraryScopeLabel(state.scope, state.libraries)

    // Target of the metadata-chip jumps from detail pages: routes like "author · X" and
    // "notable · kind · X" are translated into real browse filters applied to the query.
    // Mirrors iOS NavDestination.metadataFilter: switching chips replaces the previous condition
    // wholesale (revoke first, then add) instead of accumulating.
    var appliedFilter by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(activeFilter) {
        val applied = appliedFilter
        var current = state.browseOptions
        if (applied != null) {
            // Revoke the previously applied condition (notable entities are stored as filterKey,
            // other dimensions store the value itself)
            current = when (applied.first) {
                FILTER_KIND_AUTHOR -> current.copy(authors = current.authors - applied.second)
                FILTER_KIND_TAG -> current.copy(tags = current.tags - applied.second)
                FILTER_KIND_PUBLISHER -> current.copy(publishers = current.publishers - applied.second)
                FILTER_KIND_GENRE -> current.copy(genres = current.genres - applied.second)
                FILTER_KIND_AGE_RATING -> current.copy(ageRatings = current.ageRatings - applied.second)
                FILTER_KIND_LANGUAGE -> current.copy(languages = current.languages - applied.second)
                FILTER_KIND_RELEASE_YEAR -> current.copy(releaseYears = current.releaseYears - applied.second)
                FILTER_KIND_NOTABLE -> current.copy(
                    notableEntities = current.notableEntities
                        .filterNot { it.filterKey == applied.second },
                )
                else -> current
            }
            appliedFilter = null
        }
        val parts = activeFilter?.split("·")?.map { it.trim() }.orEmpty()
        val kind = parts.getOrNull(0).orEmpty()
        // Two-part: "author · X" etc.; three-part: "notable · kind · X" (kind = entity kind token)
        val value: String
        val next: BookBrowseOptions
        when {
            parts.size == 3 && kind == FILTER_KIND_NOTABLE && parts[2].isNotBlank() -> {
                val entity = NotableEntity(
                    name = parts[2],
                    kind = parts[1],
                )
                value = entity.filterKey
                next = if (entity !in current.notableEntities) {
                    current.copy(notableEntities = current.notableEntities + entity)
                } else {
                    current
                }
            }
            parts.size == 2 && parts[1].isNotBlank() -> {
                value = parts[1]
                next = when (kind) {
                    FILTER_KIND_AUTHOR -> if (value !in current.authors) {
                        current.copy(authors = current.authors + value)
                    } else {
                        current
                    }
                    FILTER_KIND_TAG -> if (value !in current.tags) {
                        current.copy(tags = current.tags + value)
                    } else {
                        current
                    }
                    FILTER_KIND_PUBLISHER -> if (value !in current.publishers) {
                        current.copy(publishers = current.publishers + value)
                    } else {
                        current
                    }
                    FILTER_KIND_GENRE -> if (value !in current.genres) {
                        current.copy(genres = current.genres + value)
                    } else {
                        current
                    }
                    FILTER_KIND_AGE_RATING -> if (value !in current.ageRatings) {
                        current.copy(ageRatings = current.ageRatings + value)
                    } else {
                        current
                    }
                    FILTER_KIND_LANGUAGE -> if (value !in current.languages) {
                        current.copy(languages = current.languages + value)
                    } else {
                        current
                    }
                    FILTER_KIND_RELEASE_YEAR -> if (value !in current.releaseYears) {
                        current.copy(releaseYears = current.releaseYears + value)
                    } else {
                        current
                    }
                    else -> current
                }
            }
            else -> {
                if (applied != null) viewModel.setBrowseOptions(current)
                return@LaunchedEffect
            }
        }
        if (next != current || applied != null) viewModel.setBrowseOptions(next)
        appliedFilter = kind to value
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    TextButton(onClick = { scopeMenuOpen = true }) {
                        Text(scopeLabel)
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = stringResource(R.string.library_switch_scope),
                        )
                    }
                    DropdownMenu(
                        expanded = scopeMenuOpen,
                        onDismissRequest = { scopeMenuOpen = false },
                    ) {
                        // Mirrors iOS LibraryScopeMenu: Pinned (when pins exist) / All Libraries /
                        // single libraries / pin editing
                        if (state.pinnedIds.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_scope_pinned)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.PushPin, contentDescription = null)
                                },
                                onClick = {
                                    viewModel.setScope(AppSettingsStore.SCOPE_PINNED)
                                    scopeMenuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_all_libraries)) },
                            onClick = {
                                viewModel.setScope(AppSettingsStore.SCOPE_ALL)
                                scopeMenuOpen = false
                            },
                        )
                        state.libraries.forEach { library ->
                            DropdownMenuItem(
                                text = { Text(library.name) },
                                onClick = {
                                    viewModel.setScope(AppSettingsStore.scopeForLibrary(library.id))
                                    scopeMenuOpen = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_pin_libraries)) },
                            onClick = {
                                scopeMenuOpen = false
                                showPinSheet = true
                            },
                        )
                    }
                    IconButton(onClick = { setLayout(layout.next) }) {
                        Icon(
                            Icons.Filled.GridView,
                            contentDescription = stringResource(R.string.library_layout_cd, stringResource(layout.labelRes)),
                        )
                    }
                    IconButton(onClick = { showOptionsSheet = true }) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = stringResource(R.string.browse_filter),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContentType.entries.forEach { type ->
                    // Mirrors the iOS library titles: show the server-side totals, falling back to
                    // the loaded count before paged data arrives
                    val count = if (type == ContentType.Series) {
                        state.seriesTotal ?: series.size.toLong()
                    } else {
                        state.booksTotal ?: allBooks.size.toLong()
                    }
                    FilterChip(
                        selected = contentType == type,
                        onClick = { contentType = type },
                        label = {
                            Text(stringResource(R.string.library_content_count, stringResource(type.labelRes), count))
                        },
                    )
                }
            }
            Text(
                text = if (state.hasSession) {
                    pluralStringResource(
                        R.plurals.library_scope_libraries,
                        state.libraryCount,
                        scopeLabel,
                        state.libraryCount,
                    )
                } else {
                    scopeLabel
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // Volume/count caption (mirrors iOS LibraryMetricsText); hidden when no source answered.
            libraryMetricsCaption(state.scope, state.metricsByLibrary, state.metricsTotal)?.let { caption ->
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // Target of the metadata-chip jumps from detail pages: show the active metadata filter
            // (publisher/author/genre/tag/notable entity)
            activeFilter?.let { filter ->
                val parts = filter.split("·").map { it.trim() }
                val chipLabel = when {
                    parts.size == 3 && parts[0] == FILTER_KIND_NOTABLE ->
                        "${stringResource(R.string.browse_kind_notable)} · ${notableKindLabel(parts[1])} · ${parts[2]}"
                    parts.size == 2 -> "${filterKindLabel(parts[0])} · ${parts[1]}"
                    else -> filter
                }
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FilterChip(
                        selected = true,
                        onClick = onClearFilter,
                        label = { Text(chipLabel) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.browse_clear_filter),
                                modifier = Modifier.padding(2.dp),
                            )
                        },
                    )
                }
            }
            if (!state.hasSession) {
                EmptyState(
                    title = stringResource(R.string.library_offline_title),
                    subtitle = stringResource(R.string.library_offline_subtitle),
                    modifier = Modifier.weight(1f),
                )
            } else {
                // Series/books keep independent scroll positions; load the next page at the bottom
                val seriesGridState = rememberLazyGridState()
                val booksGridState = rememberLazyGridState()
                val gridState = if (contentType == ContentType.Series) seriesGridState else booksGridState
                val seriesListState = rememberLazyListState()
                val booksListState = rememberLazyListState()
                val listState = if (contentType == ContentType.Series) seriesListState else booksListState
                val gridShouldLoadMore by remember(contentType, gridState) {
                    derivedStateOf {
                        val info = gridState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                            ?: return@derivedStateOf false
                        info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
                    }
                }
                val listShouldLoadMore by remember(contentType, listState) {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                            ?: return@derivedStateOf false
                        info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
                    }
                }
                val shouldLoadMore = if (layout == BrowseLayout.List) listShouldLoadMore else gridShouldLoadMore
                val hasMore = when (contentType) {
                    ContentType.Series -> state.seriesHasMore
                    ContentType.Books -> state.booksHasMore
                }
                // loadedCount joins the key: keep requesting pages while still at the bottom after
                // a page was appended
                val loadedCount = if (contentType == ContentType.Series) series.size else allBooks.size
                LaunchedEffect(shouldLoadMore, contentType, hasMore, loadedCount) {
                    if (!shouldLoadMore || !hasMore) return@LaunchedEffect
                    when (contentType) {
                        ContentType.Series -> viewModel.loadMoreSeries()
                        ContentType.Books -> viewModel.loadMoreBooks()
                    }
                }
                when (layout) {
                    BrowseLayout.List -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + LocalNavOverlaySpace.current),
                    ) {
                        when (contentType) {
                            ContentType.Series -> items(series.size, key = { series[it].id }) { index ->
                                val item = series[index]
                                SeriesListRow(
                                    series = item,
                                    onClick = { onOpenSeries(item) },
                                    onLongClick = { menuSeries = item },
                                )
                            }
                            ContentType.Books -> items(allBooks.size, key = { allBooks[it].id }) { index ->
                                val book = allBooks[index]
                                BookListRow(
                                    book = book,
                                    onClick = { onReadBook(book, false) },
                                    onLongClick = { menuBook = book },
                                )
                            }
                        }
                        if (hasMore) {
                            item(key = "load-more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (layout == BrowseLayout.LargeGrid) 152.dp else 110.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 16.dp,
                            end = 16.dp,
                            bottom = 16.dp + LocalNavOverlaySpace.current,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (contentType) {
                            ContentType.Series -> items(series, key = { it.id }) { item ->
                                SeriesGridCard(
                                    series = item,
                                    onClick = { onOpenSeries(item) },
                                    modifier = Modifier.fillMaxWidth(),
                                    onLongClick = { menuSeries = item },
                                )
                            }
                            ContentType.Books -> items(allBooks, key = { it.id }) { book ->
                                BookGridCard(
                                    book = book,
                                    onClick = { onReadBook(book, false) },
                                    modifier = Modifier.fillMaxWidth(),
                                    onLongClick = { menuBook = book },
                                )
                            }
                        }
                        if (hasMore) {
                            item(
                                key = "load-more",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOptionsSheet) {
        BrowseOptionsSheet(
            options = state.browseOptions,
            onOptionsChange = viewModel::setBrowseOptions,
            onDismiss = { showOptionsSheet = false },
            showSeriesFilters = true,
            suggestionLibraryIds = resolveLibraryScopeIds(state.scope, state.pinnedIds),
        )
    }
    if (showPinSheet) {
        LibraryPinSheet(
            libraries = state.libraries,
            pinnedIds = state.pinnedIds,
            onSave = { ids ->
                viewModel.setPinnedLibraries(ids)
                showPinSheet = false
            },
            onDismiss = { showPinSheet = false },
        )
    }
    menuSeries?.let { series ->
        ContextMenuSheet(
            items = seriesContextMenuItems(
                pinned = false,
                hasStarted = series.progress != null,
                completed = series.progress == 1f,
                downloaded = false,
                onMarkRead = { viewModel.markSeriesRead(series, true) },
                onMarkUnread = { viewModel.markSeriesRead(series, false) },
                onDownload = { viewModel.downloadSeries(series.id) },
                onAddToCollection = {
                    addTarget = series
                    addToCollectionOpen = true
                },
                onAddToReadList = {
                    addTarget = series
                    addToReadListOpen = true
                },
                onEdit = { editSeries = series },
                onRefreshCover = { viewModel.refreshCover(series.id) },
                onShare = {
                    viewModel.seriesWebUrl(series.id)?.let { url -> shareText(context, url) }
                },
                onDelete = { deleteSeriesTarget = series },
            ),
            onDismiss = { menuSeries = null },
        )
    }
    menuBook?.let { book ->
        ContextMenuSheet(
            items = bookContextMenuItems(
                hasStarted = book.progress != null,
                completed = book.progress == 1f,
                downloaded = false,
                oneshot = book.oneshot,
                onOpenSeries = { onVisitSeries(book) },
                onRead = { onReadBook(book, false) },
                onReadIncognito = { onReadBook(book, true) },
                onOpenDetails = { onOpenBook(book) },
                onMarkRead = { viewModel.markBookRead(book, true) },
                onMarkUnread = { viewModel.markBookRead(book, false) },
                onDownload = { viewModel.downloadBook(book.id, book.title, book.pageCount) },
                onAddToReadList = {
                    addTarget = book
                    addToReadListOpen = true
                },
                onEdit = { editBook = book },
                onAnalyze = { viewModel.analyzeBook(book.id) {} },
                onRefresh = { viewModel.refreshBookMetadata(book.id) {} },
                onShare = {
                    viewModel.bookWebUrl(book.id)?.let { url -> shareText(context, url) }
                },
                onDelete = { deleteBookTarget = book },
            ),
            onDismiss = { menuBook = null },
        )
    }
    // Edit series/book metadata (mirrors SeriesEditSheet/BookEditSheet; closes directly when
    // unchanged)
    editSeries?.let { series ->
        SeriesEditSheet(
            series = series,
            onSave = { dto ->
                viewModel.saveSeriesMetadata(series.id, dto) { ok ->
                    if (ok) editSeries = null
                }
            },
            onDismiss = { editSeries = null },
        )
    }
    editBook?.let { book ->
        BookEditSheet(
            book = book,
            meta = null,
            onSave = { draft ->
                viewModel.saveBookMetadata(book.id, book.title, draft) { ok ->
                    if (ok) editBook = null
                }
            },
            onDismiss = { editBook = null },
        )
    }
    // Delete confirmation (deletes the files on the server, destructive)
    deleteSeriesTarget?.let { series ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.series_delete_files_title),
            message = stringResource(R.string.series_delete_files_message, series.title),
            onConfirm = { viewModel.deleteSeries(series.id) {} },
            onDismiss = { deleteSeriesTarget = null },
        )
    }
    deleteBookTarget?.let { book ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.book_delete_files_title),
            message = stringResource(R.string.book_delete_files_message, book.title),
            onConfirm = { viewModel.deleteBook(book.id) {} },
            onDismiss = { deleteBookTarget = null },
        )
    }
    // "Add to Collection/Read List" pickers: real list data (empty without a session)
    if (addToCollectionOpen) {
        (addTarget as? Series)?.let { series ->
            AddToListSheet(
                collections = state.collections ?: emptyList(),
                readLists = emptyList(),
                onCreateReadList = viewModel::createReadListEntry,
                onCreateCollection = viewModel::createCollectionEntry,
                onSelect = { entry ->
                    viewModel.addSeriesToCollection(entry, series)
                    addToCollectionOpen = false
                    addTarget = null
                },
                onDismiss = {
                    addToCollectionOpen = false
                    addTarget = null
                },
            )
        }
    }
    if (addToReadListOpen) {
        when (val target = addTarget) {
            is Series -> AddToListSheet(
                collections = emptyList(),
                readLists = state.readLists ?: emptyList(),
                onCreateReadList = viewModel::createReadListEntry,
                onCreateCollection = viewModel::createCollectionEntry,
                onSelect = { entry ->
                    // Komga read lists accept books only: appending a series adds its books
                    // one by one
                    viewModel.addSeriesToReadList(entry, target)
                    addToReadListOpen = false
                    addTarget = null
                },
                onDismiss = {
                    addToReadListOpen = false
                    addTarget = null
                },
            )
            is Book -> AddToListSheet(
                collections = emptyList(),
                readLists = state.readLists ?: emptyList(),
                onCreateReadList = viewModel::createReadListEntry,
                onCreateCollection = viewModel::createCollectionEntry,
                onSelect = { entry ->
                    viewModel.addBookToReadList(entry, target)
                    addToReadListOpen = false
                    addTarget = null
                },
                onDismiss = {
                    addToReadListOpen = false
                    addTarget = null
                },
            )
            else -> Unit
        }
    }
}

/** The pin-editing sheet and library-scope resolution/labeling live in
 *  ui/components/LibraryScope.kt (shared by dashboard/search/library). */

/** Localized display label of a metadata-filter route token. */
@Composable
private fun filterKindLabel(kind: String): String = when (kind) {
    FILTER_KIND_AUTHOR -> stringResource(R.string.common_author)
    FILTER_KIND_TAG -> stringResource(R.string.common_tag)
    FILTER_KIND_PUBLISHER -> stringResource(R.string.browse_kind_publisher)
    FILTER_KIND_GENRE -> stringResource(R.string.browse_kind_genre)
    FILTER_KIND_AGE_RATING -> stringResource(R.string.browse_kind_age_rating)
    FILTER_KIND_LANGUAGE -> stringResource(R.string.browse_kind_language)
    FILTER_KIND_RELEASE_YEAR -> stringResource(R.string.browse_kind_release_year)
    FILTER_KIND_NOTABLE -> stringResource(R.string.browse_kind_notable)
    else -> kind
}

/** Localized display label of a notable-entity kind token (mirrors iOS kindDisplayName). */
@Composable
private fun notableKindLabel(kind: String): String = when (kind) {
    "character" -> stringResource(R.string.browse_kind_character)
    "team" -> stringResource(R.string.browse_kind_team)
    "location" -> stringResource(R.string.browse_kind_location)
    else -> kind
}
