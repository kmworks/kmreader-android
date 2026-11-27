package com.kmworks.kmreader.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kmworks.kmreader.ui.components.GlassTopBar
import com.kmworks.kmreader.ui.components.GlassTopBarContentHeight
import com.kmworks.kmreader.ui.components.glassPageContentWindowInsets
import com.kmworks.kmreader.ui.components.glassTopBarTopPadding
import com.kmworks.kmreader.ui.components.hasScrolled
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookDetailMeta
import com.kmworks.kmreader.data.NotableEntityKnownKinds
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.ui.components.AgeRatingBadge
import com.kmworks.kmreader.ui.components.ConfirmDeleteDialog
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionLToR
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.platform.LocalUriHandler
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.kmworks.kmreader.ui.components.mediaStatusDetailLabel
import com.kmworks.kmreader.ui.components.CoverArt
import com.kmworks.kmreader.ui.components.CoverAspectRatio
import com.kmworks.kmreader.ui.components.notableEntityIcon
import com.kmworks.kmreader.ui.components.MetaChip
import com.kmworks.kmreader.ui.components.DetailAlternateTitles
import com.kmworks.kmreader.ui.components.KmDropdownMenu
import com.kmworks.kmreader.ui.lists.AddToListSheet
import com.kmworks.kmreader.ui.lists.ListSelection

private val WideLayoutBreakpoint = 960.dp
private val HeroCoverWidth = 168.dp
private val ActionCardMaxWidth = 480.dp
private val ActionCardShape = RoundedCornerShape(16.dp)

/** Wire tokens of the metadata-filter route parsed by LibraryScreen (internal routing values,
 *  display labels are localized at render time). */
private const val FILTER_KIND_AUTHOR = "author"
private const val FILTER_KIND_TAG = "tag"
private const val FILTER_KIND_NOTABLE = "notable"
private const val FILTER_KIND_PUBLISHER = "publisher"
private const val FILTER_KIND_GENRE = "genre"

/** Actions of the detail page "More" menu (mirrors the entries of the BookDetailView toolbar
 *  Menu). */
enum class BookMenuAction {
    Share, Edit, Analyze, Refresh, Delete
}

/** Book detail page (mirrors BookDetailView.swift): cover hero, metadata rows, action card,
 *  summary and the more menu. */
@Composable
fun BookDetailScreen(
    book: Book,
    seriesTitle: String?,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSeries: () -> Unit = {},
    onMarkReadToggle: (Book) -> Unit = {},
    onDownload: (Book) -> Unit = {},
    onAddToReadList: (Book) -> Unit = {},
    onMenuAction: (BookMenuAction) -> Unit = {},
    onOpenReadList: (Int) -> Unit = {},
    onReadIncognito: () -> Unit = {},
    onBrowseFilter: (String) -> Unit = {},
) {
    BookDetailBody(
        book = book,
        seriesTitle = seriesTitle,
        onBack = onBack,
        onRead = onRead,
        onOpenSeries = onOpenSeries,
        onMarkReadToggle = onMarkReadToggle,
        onDownload = onDownload,
        onAddToReadList = onAddToReadList,
        onMenuAction = onMenuAction,
        onOpenReadList = onOpenReadList,
        onReadIncognito = onReadIncognito,
        onBrowseFilter = onBrowseFilter,
        modifier = modifier,
    )
}

/** Shared skeleton of the book/one-shot detail pages (mirrors BookDetailContentView; a null
 *  seriesTitle means the one-shot form). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookDetailBody(
    book: Book,
    seriesTitle: String?,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    onOpenSeries: () -> Unit,
    onMarkReadToggle: (Book) -> Unit,
    onDownload: (Book) -> Unit,
    onAddToReadList: (Book) -> Unit,
    onMenuAction: (BookMenuAction) -> Unit,
    onOpenReadList: (Int) -> Unit = {},
    onReadIncognito: () -> Unit = {},
    onBrowseFilter: (String) -> Unit = {},
    // True when hosted inside AppModalDialog (ReaderDetailSheet): the Scaffold must be transparent
    // so the dialog's surfaceContainerHigh card shows through (otherwise it paints the page
    // `background` over the card — pure black in the dark theme — and the dialog shows a different
    // tone than every other sheet), and the action card steps up one tonal level to stay visible.
    inModal: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: BookDetailViewModel = hiltViewModel(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    var addToReadListOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // real data: resolve DTO id from registry, fetch book; fall back to sample data
    val state by viewModel.uiState.collectAsState()
    val readLists by viewModel.readLists.collectAsState()
    LaunchedEffect(book.id) { viewModel.load(book.id) }
    val baseBook = state.book ?: book
    // design mock: mark-read / download toggle locally for instant feedback
    var progressToggled by remember(book.id) { mutableStateOf(false) }
    // Download state: the real file state wins (VM file scan); without a session a local demo
    // toggle is used
    val vmDownloaded by viewModel.downloaded.collectAsState()
    var localDownloaded by remember(book.id) { mutableStateOf(false) }
    val downloaded = vmDownloaded ?: localDownloaded
    val meta = state.meta
    val progress = if (progressToggled) {
        if (baseBook.progress == 1f) null else 1f
    } else {
        baseBook.progress
    }
    val displayed = baseBook.copy(progress = progress)
    // Display-only summary fallback (the meta keeps the raw value so the edit sheet starts empty)
    val summaryFallback = "${displayed.seriesTitle} · ${displayed.title}"

    // The frosted bar floats inside the page box instead of occupying a Scaffold slot, so the page
    // scrolls underneath it and whatever passes under the row is frosted (same as the tab roots).
    val barHazeState = rememberHazeState()
    val railScrollState = rememberScrollState()
    val detailScrollState = rememberScrollState()
    val narrowScrollState = rememberScrollState()
    val barSheetVisible = railScrollState.hasScrolled() || detailScrollState.hasScrolled() || narrowScrollState.hasScrolled()
    // Bar content shared by the floating glass bar (full page) and the fixed sheet header (modal).
    val barNavigationIcon: @Composable () -> Unit = {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
            )
        }
    }
    val barActions: @Composable RowScope.() -> Unit = {
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.common_more),
                )
            }
            KmDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_share)) },
                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        viewModel.bookWebUrl()?.let { url ->
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    send,
                                    context.getString(R.string.book_share_chooser),
                                ),
                            )
                        }
                        onMenuAction(BookMenuAction.Share)
                    },
                )
                if (state.isAdmin) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_edit_metadata)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            editOpen = true
                            onMenuAction(BookMenuAction.Edit)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.book_analyze)) },
                        leadingIcon = { Icon(Icons.Outlined.Analytics, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            viewModel.analyze { }
                            onMenuAction(BookMenuAction.Analyze)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.book_refresh_metadata)) },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            viewModel.refreshMetadata { }
                            onMenuAction(BookMenuAction.Refresh)
                        },
                    )
                }
                if (state.isAdmin) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.book_add_to_readlist)) },
                        leadingIcon = { Icon(Icons.Outlined.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            viewModel.ensureReadListsLoaded()
                            addToReadListOpen = true
                            onAddToReadList(displayed)
                        },
                    )
                }
                val completed = displayed.progress == 1f
                val hasStarted = displayed.progress != null
                if (!completed) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.book_mark_read)) },
                        leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            if (!viewModel.setRead(true)) {
                                progressToggled = true
                            }
                            onMarkReadToggle(displayed)
                        },
                    )
                }
                if (hasStarted) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.book_mark_unread)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            if (!viewModel.setRead(false)) {
                                progressToggled = false
                            }
                            onMarkReadToggle(displayed)
                        },
                    )
                }
                if (state.isAdmin) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.common_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            deleteOpen = true
                            onMenuAction(BookMenuAction.Delete)
                        },
                    )
                }
            }
        }
    }

    // The content box has to start under the status bar: if the Scaffold insets it instead, the
    // scrollers stop at the status bar and the bar has no backdrop for that strip (hard seam).
    Scaffold(
        modifier = modifier,
        containerColor = if (inModal) Color.Transparent else MaterialTheme.colorScheme.background,
        contentWindowInsets = glassPageContentWindowInsets(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (inModal) {
                // Modal host (ReaderDetailSheet): the floating glass bar has no page behind it to
                // frost (the card itself is the surface) and its corner-hugging icons fight the
                // card's 28dp rounded corners, so the sheet gets a fixed header in the card's own
                // tone instead — invisible at rest, covering scrolled content, with a hairline
                // divider that fades in once content scrolls under it (the boundary the glass
                // plate provides on the full page).
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(2f),
                ) {
                    Column {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(GlassTopBarContentHeight)
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                barNavigationIcon()
                                Spacer(Modifier.weight(1f))
                                barActions()
                            }
                        }
                        AnimatedVisibility(visible = barSheetVisible) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            } else {
                GlassTopBar(
                    title = "",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(2f),
                    sheetVisible = barSheetVisible,
                    hazeState = barHazeState,
                    navigationIcon = barNavigationIcon,
                    actions = barActions,
                )
            }
            if (maxWidth >= WideLayoutBreakpoint) {
                // Wide golden split: info column maxOf(maxWidth*0.382f, 340.dp), both columns
                // scroll independently
                // One haze source over the whole split instead of one per pane: the bar's backdrop has to
                // be the full width of the page, and a scroller that is only as tall as its own pane
                // leaves the strip behind the bar empty whenever the other pane is the one scrolling.
                Row(modifier = Modifier.fillMaxSize().hazeSource(barHazeState)) {
                    Column(
                        modifier = Modifier
                            .width(maxOf(this@BoxWithConstraints.maxWidth * 0.382f, 340.dp))
                            .fillMaxHeight()
                            .verticalScroll(railScrollState)
                            .padding(
                                start = 24.dp,
                                end = 24.dp,
                                top = glassTopBarTopPadding() + 16.dp,
                                bottom = 16.dp,
                            ),
                    ) {
                        BookHero(
                            displayed,
                            seriesTitle,
                            meta,
                            ageRating = state.seriesAgeRating,
                            centered = true,
                            onOpenSeries = onOpenSeries,
                            onBrowseFilter = onBrowseFilter,
                            seriesMeta = state.oneshotEditable,
                            localCover = state.localCoverFile,
                        )
                        BookActionCard(
                            book = displayed,
                            downloaded = downloaded,
                            showNumber = seriesTitle != null,
                            onRead = onRead,
                            onDownloadToggle = {
                                // Real data enqueues a download; demo data toggles locally
                                if (!viewModel.downloadBook()) {
                                    localDownloaded = !localDownloaded
                                }
                                onDownload(displayed)
                            },
                            onReadIncognito = onReadIncognito,
                            inModal = inModal,
                        )
                        DetailTimestamps(
                            created = meta?.created,
                            lastModified = meta?.lastModified,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(detailScrollState)
                            .padding(
                                start = 24.dp,
                                end = 24.dp,
                                top = glassTopBarTopPadding() + 16.dp,
                                bottom = 16.dp,
                            ),
                    ) {
                        meta?.let {
                            BookSecondaryContent(
                                it,
                                onBrowseFilter = onBrowseFilter,
                                seriesMeta = state.oneshotEditable,
                                summaryFallback = summaryFallback,
                            )
                        }
                        if (state.readLists.isNotEmpty()) {
                            BookMembershipChips(
                                entries = state.readLists,
                                onOpen = onOpenReadList,
                            )
                        }
                    }
                }
            } else {
                // Narrow: hero + action card stay centered (same as the wide rail); only the section under the
                // summary (tag chips, media info, read-list chips) is left aligned so it lines up with the rows
                // around it instead of floating in the middle of the page.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(narrowScrollState)
                        .hazeSource(barHazeState)
                        .padding(top = glassTopBarTopPadding() + 16.dp, bottom = 16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BookHero(
                            displayed,
                            seriesTitle,
                            meta,
                            ageRating = state.seriesAgeRating,
                            centered = true,
                            onOpenSeries = onOpenSeries,
                            onBrowseFilter = onBrowseFilter,
                            seriesMeta = state.oneshotEditable,
                            localCover = state.localCoverFile,
                        )
                        BookActionCard(
                            book = displayed,
                            downloaded = downloaded,
                            showNumber = seriesTitle != null,
                            onRead = onRead,
                            onDownloadToggle = {
                                // Real data enqueues a download; demo data toggles locally
                                if (!viewModel.downloadBook()) {
                                    localDownloaded = !localDownloaded
                                }
                                onDownload(displayed)
                            },
                            onReadIncognito = onReadIncognito,
                            inModal = inModal,
                        )
                        DetailTimestamps(
                            created = meta?.created,
                            lastModified = meta?.lastModified,
                        )
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        meta?.let {
                            BookSecondaryContent(
                                it,
                                onBrowseFilter = onBrowseFilter,
                                seriesMeta = state.oneshotEditable,
                                summaryFallback = summaryFallback,
                            )
                        }
                        if (state.readLists.isNotEmpty()) {
                            BookMembershipChips(
                                entries = state.readLists,
                                centered = false,
                                onOpen = onOpenReadList,
                            )
                        }
                    }
                }
            }
        }
    }

    // "Add to Read List" picker: real list data, selection calls the API (empty without a session).
    // The selection seeds a read list created from here (Komga refuses a read list without books).
    if (addToReadListOpen) {
        AddToListSheet(
            collections = emptyList(),
            readLists = readLists ?: emptyList(),
            selection = ListSelection(bookIds = listOf(book.id)),
            onCreateReadList = viewModel::createReadListEntry,
            onSelect = { entry ->
                viewModel.addToReadList(entry)
                addToReadListOpen = false
            },
            onDismiss = { addToReadListOpen = false },
        )
    }

    if (editOpen) {
        if (displayed.oneshot) {
            // oneshot: combined series+book editing (mirrors iOS OneShotDetailView →
            // OneshotEditSheet)
            OneshotEditSheet(
                book = displayed,
                meta = meta,
                seriesValues = state.oneshotEditable,
                seriesTitleFallback = seriesTitle ?: displayed.seriesTitle,
                saving = saving,
                onSave = { draft, seriesUpdate ->
                    saving = true
                    viewModel.saveOneshotMetadata(draft, seriesUpdate) { ok ->
                        saving = false
                        if (ok) {
                            editOpen = false
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.detail_save_failed))
                            }
                        }
                    }
                },
                onDismiss = { if (!saving) editOpen = false },
            )
        } else {
            BookEditSheet(
                book = displayed,
                meta = meta,
                saving = saving,
                onSave = { draft ->
                    saving = true
                    viewModel.saveMetadata(draft) { ok ->
                        saving = false
                        if (ok) {
                            editOpen = false
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.detail_save_failed))
                            }
                        }
                    }
                },
                onDismiss = { if (!saving) editOpen = false },
            )
        }
    }

    if (deleteOpen) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.book_delete_title),
            message = stringResource(R.string.book_delete_message, displayed.title),
            onConfirm = {
                deleteOpen = false
                viewModel.deleteBook { ok -> if (ok) onBack() }
            },
            onDismiss = { deleteOpen = false },
        )
    }
}

/** Hero: cover, owning-series entry, title, author chips, metadata rows (mirrors the book form of
 *  DetailHeroView). [ageRating] is the owning series' rating, shown only on the one-shot detail
 *  (mirrors iOS OneShotDetailContentView). [seriesMeta] carries the owning series metadata and is
 *  non-null only on the one-shot detail: its publisher joins the creator chips and its language /
 *  reading direction join the metadata rows, as on iOS. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookHero(
    book: Book,
    seriesTitle: String?,
    meta: BookDetailMeta?,
    ageRating: Int?,
    centered: Boolean,
    onOpenSeries: () -> Unit,
    onBrowseFilter: (String) -> Unit = {},
    seriesMeta: SeriesEditValues? = null,
    /** Cover saved with the download; only painted when the server thumbnail is unavailable. */
    localCover: File? = null,
) {
    val localCoverUrl = remember(localCover) {
        localCover?.let { Uri.fromFile(it).toString() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        CoverArt(
            title = seriesTitle ?: book.title,
            hue = book.hue,
            stableId = book.id,
            fallbackImageUrl = localCoverUrl,
            modifier = Modifier
                .width(HeroCoverWidth)
                .aspectRatio(CoverAspectRatio),
            followThumbnailPreference = true,
        )
        Spacer(Modifier.height(16.dp))
        if (seriesTitle != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenSeries)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CollectionsBookmark,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = seriesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            ageRating?.takeIf { it > 0 }?.let { rating ->
                AgeRatingBadge(rating)
            }
        }
        val authors = meta?.authors.orEmpty()
        val publisher = seriesMeta?.publisher?.takeIf { it.isNotBlank() }
        if (authors.isNotEmpty() || publisher != null) {
            // Mirrors iOS OneShotDetailContentView.creatorItems: the publisher comes first on the
            // one-shot page, then the authors ordered by role (the role only decides the icon and
            // ordering, the label shows the name only).
            val creatorChips = buildList {
                if (publisher != null) {
                    add(
                        ChipItem(
                            text = publisher,
                            icon = Icons.Outlined.Business,
                            value = publisher,
                            filterKind = FILTER_KIND_PUBLISHER,
                        ),
                    )
                }
                authors.sortedBy { it.role.ordinal }.forEach { creator ->
                    add(
                        ChipItem(
                            text = creator.name,
                            icon = creator.role.icon(),
                            value = creator.name,
                        ),
                    )
                }
            }
            CollapsibleChipFlow(
                items = creatorChips,
                limit = 4,
                centered = centered,
                onClick = { item -> onBrowseFilter("${item.filterKind} · ${item.value}") },
            )
        }
        val notableEntities = meta?.notableEntities.orEmpty()
        if (notableEntities.isNotEmpty()) {
            // Notable-entity chips (mirrors NotableEntityChipFlows): grouped by kind (known kinds
            // first, custom ones alphabetically), icons person/person.2/mappin/tag; a tap routes
            // the three-part "notable · kind · name" to the browse filter
            val entityChips = notableEntities
                .sortedWith(
                    compareBy(
                        { NotableEntityKnownKinds.indexOf(it.kind).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                        { it.kind },
                        { it.name },
                    ),
                )
                .map { entity ->
                    ChipItem(
                        text = entity.name,
                        icon = notableEntityIcon(entity.kind),
                        value = "${entity.kind} · ${entity.name}",
                    )
                }
            CollapsibleChipFlow(
                items = entityChips,
                limit = 6,
                centered = centered,
                onClick = { item -> onBrowseFilter("$FILTER_KIND_NOTABLE · ${item.value}") },
            )
        }
        val heroMeta = buildList {
            if (meta != null) {
                add(
                    Icons.Filled.DateRange to
                        stringResource(R.string.book_release_date, meta.releaseDate),
                )
            }
            if (seriesMeta != null) {
                // One-shot: the series-side language / reading direction belong to the hero row
                // group (mirrors OneShotDetailContentView's DetailHeroMetadataGroup)
                seriesMeta.language.takeIf { it.isNotBlank() }?.let { add(Icons.Filled.Public to it) }
                readingDirectionMeta(seriesMeta.readingDirection)?.let { add(it) }
            }
            if (meta != null) {
                meta.isbn?.takeIf { it.isNotBlank() }?.let { add(Icons.Outlined.QrCode to it) }
            }
            add(
                Icons.AutoMirrored.Filled.MenuBook to
                    pluralStringResource(R.plurals.book_pages, book.pageCount, book.pageCount),
            )
            if (meta != null) {
                add(Icons.Outlined.Storage to meta.sizeLabel)
                add(Icons.Outlined.Description to meta.format)
            }
        }
        if (centered) {
            // Mirrors DetailHeroMetadataGroup / the series detail: the centered form wraps
            // horizontally instead of giving each item a full row.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                heroMeta.forEach { (icon, text) -> MetaRow(icon = icon, text = text) }
            }
        } else {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                heroMeta.forEach { (icon, text) -> MetaRow(icon = icon, text = text) }
            }
        }
    }
}

/** Action card: number/page count/read state/download state + the primary button and secondary
 *  actions (mirrors DetailActionCard + BookActionsSection).
 *  The iOS BookActionsSection has only three actions (primary read button + peek + download);
 *  every other action lives in the toolbar menu. */
@Composable
internal fun BookActionCard(
    book: Book,
    downloaded: Boolean,
    showNumber: Boolean,
    onRead: (Book) -> Unit,
    onDownloadToggle: () -> Unit,
    onReadIncognito: () -> Unit = {},
    // Inside a modal dialog the page `surface` would sit BELOW the dialog's surfaceContainerHigh
    // (inverted hierarchy); step up one tonal level to keep the card lighter than its backdrop.
    inModal: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = ActionCardMaxWidth)
                .background(
                    if (inModal) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    ActionCardShape,
                )
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Mirrors iOS BookDetailContentView: on a media error the status row replaces the
                // number/page count (tinted with detailColor)
                val mediaStatus = mediaStatusDetailLabel(book.mediaStatus)
                if (mediaStatus != null) {
                    Icon(
                        imageVector = when (book.mediaStatus) {
                            "ERROR" -> Icons.Outlined.ErrorOutline
                            "OUTDATED" -> Icons.Outlined.Schedule
                            else -> Icons.Outlined.HelpOutline
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = mediaStatus.second,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = mediaStatus.first,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = mediaStatus.second,
                    )
                } else {
                    if (showNumber) {
                        Text(
                            text = "#${book.number}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = pluralStringResource(
                            R.plurals.book_pages,
                            book.pageCount,
                            book.pageCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Mirrors iOS: marked deleted on the server → red "Unavailable"
                if (book.deleted) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.book_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (downloaded) {
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.book_downloaded),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.book_downloaded),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    book.progress == 1f -> {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.common_completed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    book.progress != null -> {
                        val currentPage = (book.progress * book.pageCount).toInt().coerceAtLeast(1)
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                R.string.book_progress_pages,
                                currentPage,
                                book.pageCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Icon(
                            Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.common_unread),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Mirrors iOS DetailActionCard: the last read date sits under the read state row
            book.lastReadAtMs?.takeIf { !book.deleted }?.let { readAt ->
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.book_last_read, mediumDate(readAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ReadingActionButton(book = book, onRead = { onRead(book) })
            Spacer(Modifier.height(8.dp))
            // secondary row: peek (incognito read) + download toggle (mirrors BookActionsSection)
            // Mirrors iOS optimizedControlSize(.mini): mini capsule (height 28, 13pt text,
            // small icons)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Filled like the rest of the detail actions; an outlined capsule under the white
                // primary button read as a different control family rather than the quieter sibling.
                FilledTonalButton(
                    onClick = onReadIncognito,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    shape = CircleShape,
                    colors = detailSecondaryButtonColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(
                        Icons.Outlined.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.book_peek), fontSize = 13.sp, maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onDownloadToggle,
                    enabled = !downloaded,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    shape = CircleShape,
                    colors = detailSecondaryButtonColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(
                        if (downloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            if (downloaded) R.string.book_downloaded else R.string.common_download,
                        ),
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Secondary actions: peek + download toggle (mirrors BookActionsSection, outlined
            // capsules)
        }
    }
}

/** Primary button: primary-colored capsule, action line 15sp semibold on top, detail line
 *  12sp/0.85 below (mirrors ReadingActionButton: label="Resume/Start Reading",
 *  detail="Page N · %"/"N pages"; the book title is not repeated). */
@Composable
private fun ReadingActionButton(book: Book, onRead: () -> Unit) {
    val started = book.progress != null && book.progress != 1f
    val percent = ((book.progress ?: 0f) * 100).toInt()
    val currentPage = ((book.progress ?: 0f) * book.pageCount).toInt().coerceAtLeast(1)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onRead),
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = stringResource(
                        if (started) R.string.book_resume_reading else R.string.book_start_reading,
                    ),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (started) {
                        stringResource(R.string.book_page_progress, currentPage, percent)
                    } else {
                        pluralStringResource(R.plurals.book_pages, book.pageCount, book.pageCount)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Right/bottom content: summary, tag chips, media info (mirrors ExpandableSummaryView +
 *  BookMediaInfoSection). [seriesMeta] is non-null only on the one-shot detail and contributes the
 *  genres, the alternate titles and the book links (mirrors iOS OneShotDetailContentView).
 *  [summaryFallback] is shown when the book has no summary: the meta itself stays raw so the edit
 *  sheet never offers a fabricated summary. */
@Composable
internal fun BookSecondaryContent(
    meta: BookDetailMeta,
    onBrowseFilter: (String) -> Unit = {},
    seriesMeta: SeriesEditValues? = null,
    summaryFallback: String = "",
) {
    Text(
        text = stringResource(R.string.book_summary),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp),
    )
    Text(
        text = meta.summary.ifBlank { summaryFallback },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    val genres = seriesMeta?.genres.orEmpty()
    if (genres.isNotEmpty()) {
        // One-shot: genres belong to the owning series (mirrors DetailChipFlow(genreItems))
        CollapsibleChipFlow(
            items = genres.map { genre ->
                ChipItem(
                    text = genre,
                    icon = Icons.Outlined.TheaterComedy,
                    value = genre,
                    filterKind = FILTER_KIND_GENRE,
                )
            },
            limit = 6,
            centered = false,
            onClick = { item -> onBrowseFilter("${item.filterKind} · ${item.value}") },
        )
    }
    if (meta.tags.isNotEmpty()) {
        // Tag chips: a tap jumps to the tag-filtered browse view (mirrors
        // DetailChipFlow(tagItems))
        CollapsibleChipFlow(
            items = meta.tags.map { tag ->
                ChipItem(
                    text = tag,
                    icon = Icons.Outlined.Sell,
                    value = tag,
                    filterKind = FILTER_KIND_TAG,
                )
            },
            limit = 6,
            centered = false,
            onClick = { item -> onBrowseFilter("${item.filterKind} · ${item.value}") },
        )
    }
    if (meta.links.isNotEmpty()) {
        // External link chips (mirrors DetailChipFlow(linkItems)): the whole row is not collapsible
        // on iOS either
        val uriHandler = LocalUriHandler.current
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            meta.links.forEach { (label, url) ->
                MetaChip(
                    text = label,
                    icon = Icons.Outlined.Link,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { runCatching { uriHandler.openUri(url) } },
                )
            }
        }
    }
    // One-shot alternate titles (mirrors iOS SeriesAlternateTitlesView). Shared with the series page.
    DetailAlternateTitles(items = seriesMeta?.alternateTitles.orEmpty())
    Text(
        text = stringResource(R.string.book_media_info),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp),
    )
    Column(modifier = Modifier.padding(top = 8.dp)) {
        MetaRow(icon = Icons.Outlined.Description, text = meta.format)
        MetaRow(icon = Icons.Outlined.Storage, text = meta.sizeLabel)
        MetaRow(icon = Icons.Outlined.Folder, text = meta.filePath)
    }
    meta.mediaComment?.takeIf { it.isNotBlank() }?.let { comment ->
        // Mirrors iOS BookMediaInfoSection: an orange warning icon with the comment in red
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color(0xFFFFA726),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = comment,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Data of a tappable chip: label, icon, the value reported to the browse filter, and the filter
 *  kind prepended to it ([filterKind] · [value]). */
private data class ChipItem(
    val text: String,
    val icon: ImageVector?,
    val value: String,
    val filterKind: String = FILTER_KIND_AUTHOR,
)

/** Collapsible chip flow: shows the first [limit] items by default, the rest expands in place via
 *  a "+N" chip (mirrors DetailChipFlow). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollapsibleChipFlow(
    items: List<ChipItem>,
    limit: Int,
    centered: Boolean = false,
    onClick: (ChipItem) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) items else items.take(limit)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(
            8.dp,
            if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visible.forEach { item ->
            MetaChip(
                text = item.text,
                icon = item.icon,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onClick(item) },
            )
        }
        if (!expanded && items.size > limit) {
            MetaChip(
                text = "+${items.size - limit}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { expanded = true },
            )
        }
    }
}

/** Quiet created/modified caption under the action card (mirrors DetailTimestampsView: caption
 *  size, secondary color, no chip chrome). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailTimestamps(
    created: String?,
    lastModified: String?,
    centered: Boolean = true,
) {
    val rows = buildList {
        created?.take(10)?.let { add(Icons.Outlined.Event to stringResource(R.string.lists_created_at, it)) }
        lastModified?.take(10)?.let {
            add(Icons.Outlined.Schedule to stringResource(R.string.lists_modified_at, it))
        }
    }
    if (rows.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(
                12.dp,
                if (centered) Alignment.CenterHorizontally else Alignment.Start,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rows.forEach { (icon, text) -> MetaRow(icon = icon, text = text) }
        }
    }
}

/** Reading-direction hero row (icon + localized label) of the one-shot page; null when unset or
 *  unknown (mirrors ReadingDirection.fromString.icon / displayName). */
@Composable
private fun readingDirectionMeta(direction: String): Pair<ImageVector, String>? =
    when (direction.uppercase()) {
        "RIGHT_TO_LEFT" -> Icons.AutoMirrored.Filled.FormatTextdirectionRToL to
            stringResource(R.string.series_direction_rtl)

        "VERTICAL" -> Icons.Filled.SwapVert to stringResource(R.string.series_direction_vertical)
        "WEBTOON" -> Icons.Filled.ViewStream to stringResource(R.string.series_direction_webtoon)
        "LEFT_TO_RIGHT" -> Icons.AutoMirrored.Filled.FormatTextdirectionLToR to
            stringResource(R.string.series_direction_ltr)

        else -> null
    }

/** Medium date of an epoch-millis timestamp (mirrors iOS formattedMediumDate). */
private fun mediumDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Metadata chip: icon + label, optionally tappable (the shared chip of every page). */

/** Owning read list chips, mirrors BookDetailReadListsView. */
@Composable
private fun BookMembershipChips(
    entries: List<ListEntry>,
    centered: Boolean = false,
    onOpen: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.browse_scope_readlists),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(
                8.dp,
                if (centered) Alignment.CenterHorizontally else Alignment.Start,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onOpen(entry.id) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Author role icons, mirrors the AuthorRole.icon of SeriesDetailScreen. */
private fun AuthorRole.icon(): ImageVector = when (this) {
    AuthorRole.Writer -> Icons.Outlined.Create
    AuthorRole.Penciller -> Icons.Outlined.Brush
    AuthorRole.Inker -> Icons.Outlined.EditNote
    AuthorRole.Colorist -> Icons.Outlined.Palette
    AuthorRole.Letterer -> Icons.Outlined.TextFormat
    AuthorRole.Cover -> Icons.Outlined.Photo
    AuthorRole.Editor -> Icons.Outlined.ContentCut
    AuthorRole.Translator -> Icons.Filled.Public
    AuthorRole.Custom -> Icons.Outlined.Person
}

/** Metadata row: icon + one line of text (same as the MetaRow of SeriesDetailScreen). */
@Composable
private fun MetaRow(
    icon: ImageVector?,
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}
