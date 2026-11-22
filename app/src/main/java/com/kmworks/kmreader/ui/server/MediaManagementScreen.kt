package com.kmworks.kmreader.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.core.api.model.PageHashKnownDto
import com.kmworks.kmreader.core.api.model.PageHashMatchDto
import com.kmworks.kmreader.core.api.model.PageHashUnknownDto
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.core.data.libraryChanges
import com.kmworks.kmreader.core.server.ServerAdminRepository
import com.kmworks.kmreader.ui.components.mediaStatusDetailLabel
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Sections of the media-maintenance page, mirroring the iOS MediaManagementView list. */
enum class MediaSection { Analysis, MissingPosters, Duplicates, Known, Unknown }

/** Triage action recorded for a duplicate page hash (mirrors iOS PageHashAction). */
enum class PageHashAction(val raw: String) {
    DeleteAuto("DELETE_AUTO"),
    DeleteManual("DELETE_MANUAL"),
    Ignore("IGNORE"),
}

data class LibraryFilterOption(val id: String, val name: String)

data class MediaUiState(
    val section: MediaSection = MediaSection.Analysis,
    val isAdmin: Boolean = false,
    val loading: Boolean = true,
    val libraries: List<LibraryFilterOption> = emptyList(),
    val selectedLibraryIds: Set<String> = emptySet(),
    val filterError: Boolean = true,
    val filterUnsupported: Boolean = true,
    val filterActions: Set<PageHashAction> = setOf(PageHashAction.DeleteAuto, PageHashAction.DeleteManual),
    val books: List<BookDto> = emptyList(),
    val known: List<PageHashKnownDto> = emptyList(),
    val unknown: List<PageHashUnknownDto> = emptyList(),
    val processedHashes: Set<String> = emptySet(),
    val hasMore: Boolean = false,
    val busy: Boolean = false,
    val matchesHash: String? = null,
    val matches: List<PageHashMatchDto> = emptyList(),
    val matchesLoading: Boolean = false,
    val pendingDeleteMatches: PageHashKnownDto? = null,
    val pendingAutoDelete: PageHashKnownDto? = null,
    val messageRes: Int? = null,
)

/**
 * Media maintenance, aligned with the iOS media screens: analyzer issues (`media status error /
 * unsupported`), books without a poster, duplicate files grouped by hash, and known/unknown duplicate
 * pages with their triage actions (ignore / manual delete / auto delete), match listing and deletion.
 */
@HiltViewModel
class MediaManagementViewModel @Inject constructor(
    private val repository: ServerAdminRepository,
    private val contentChangeBus: ContentChangeBus,
) : ViewModel() {

    private val _state = MutableStateFlow(MediaUiState())
    val state: StateFlow<MediaUiState> = _state.asStateFlow()

    private var page = 0
    private var loaded = false

    init {
        viewModelScope.launch {
            val admin = repository.isAdmin()
            _state.update { it.copy(isAdmin = admin) }
            if (!admin) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val libraries = repository.libraries().map { LibraryFilterOption(it.id, it.name) }
            _state.update { it.copy(libraries = libraries) }
            load(reset = true)
        }
        // The library filter shows names: re-read them when the library configuration changes elsewhere.
        viewModelScope.launch {
            contentChangeBus.libraryChanges().collect {
                val libraries = runCatching { repository.libraries() }.getOrNull() ?: return@collect
                _state.update { it.copy(libraries = libraries.map { lib -> LibraryFilterOption(lib.id, lib.name) }) }
            }
        }
    }

    fun select(section: MediaSection) {
        if (section == _state.value.section) return
        page = 0
        loaded = false
        _state.update {
            it.copy(
                section = section,
                books = emptyList(),
                known = emptyList(),
                unknown = emptyList(),
                processedHashes = emptySet(),
                loading = true,
                hasMore = false,
            )
        }
        load(reset = true)
    }

    fun toggleError() {
        _state.update { it.copy(filterError = !it.filterError) }
        reload()
    }

    fun toggleUnsupported() {
        _state.update { it.copy(filterUnsupported = !it.filterUnsupported) }
        reload()
    }

    fun toggleLibrary(libraryId: String) {
        _state.update {
            val next = if (libraryId in it.selectedLibraryIds) {
                it.selectedLibraryIds - libraryId
            } else {
                it.selectedLibraryIds + libraryId
            }
            it.copy(selectedLibraryIds = next)
        }
        reload()
    }

    fun clearLibraries() {
        if (_state.value.selectedLibraryIds.isEmpty()) return
        _state.update { it.copy(selectedLibraryIds = emptySet()) }
        reload()
    }

    fun toggleAction(action: PageHashAction) {
        _state.update {
            val next = if (action in it.filterActions) {
                it.filterActions - action
            } else {
                it.filterActions + action
            }
            it.copy(filterActions = next)
        }
        reload()
    }

    fun load(reset: Boolean) {
        if (!reset && (loaded || _state.value.loading || _state.value.busy)) return
        if (reset) {
            page = 0
            loaded = false
            _state.update { it.copy(loading = true, hasMore = false) }
        }
        val snapshot = _state.value
        val section = snapshot.section
        val actions = snapshot.filterActions.map { it.raw }
        if (section == MediaSection.Known && actions.isEmpty()) {
            _state.update { it.copy(known = emptyList(), loading = false, hasMore = false) }
            return
        }
        val statuses = buildList {
            if (snapshot.filterError) add("ERROR")
            if (snapshot.filterUnsupported) add("UNSUPPORTED")
        }
        if (section == MediaSection.Analysis && statuses.isEmpty()) {
            _state.update { it.copy(books = emptyList(), loading = false, hasMore = false) }
            return
        }
        viewModelScope.launch {
            when (section) {
                MediaSection.Analysis,
                MediaSection.MissingPosters,
                MediaSection.Duplicates -> {
                    val paged = when (section) {
                        MediaSection.Analysis -> repository.mediaAnalysisBooks(
                            statuses = statuses,
                            libraryIds = snapshot.selectedLibraryIds.toList().ifEmpty { null },
                            page = page,
                        )
                        MediaSection.MissingPosters -> repository.missingPosterBooks(page)
                        else -> repository.duplicateBooks(page, size = DUPLICATE_PAGE_SIZE)
                    }
                    _state.update {
                        it.copy(
                            books = if (reset) paged.items else it.books + paged.items,
                            hasMore = paged.hasMore,
                            loading = false,
                        )
                    }
                    page += 1
                    loaded = !paged.hasMore
                }
                MediaSection.Known -> {
                    val paged = repository.knownPageHashes(page, size = HASH_PAGE_SIZE, actions = actions)
                    _state.update {
                        it.copy(
                            known = if (reset) paged.items else it.known + paged.items,
                            hasMore = paged.hasMore,
                            loading = false,
                        )
                    }
                    page += 1
                    loaded = !paged.hasMore
                }
                MediaSection.Unknown -> {
                    val paged = repository.unknownPageHashes(page, size = HASH_PAGE_SIZE)
                    _state.update {
                        it.copy(
                            unknown = if (reset) paged.items else it.unknown + paged.items,
                            hasMore = paged.hasMore,
                            loading = false,
                        )
                    }
                    page += 1
                    loaded = !paged.hasMore
                }
            }
        }
    }

    fun openMatches(hash: String) {
        _state.update { it.copy(matchesHash = hash, matches = emptyList(), matchesLoading = true) }
        viewModelScope.launch {
            val paged = repository.pageHashMatches(hash)
            _state.update { it.copy(matches = paged.items, matchesLoading = false) }
        }
    }

    fun closeMatches() {
        _state.update { it.copy(matchesHash = null, matches = emptyList(), matchesLoading = false) }
    }

    fun thumbnailUrl(bookId: String, pageNumber: Int): String? =
        repository.pageThumbnailUrl(bookId, pageNumber)

    fun bookThumbnailUrl(bookId: String): String? = repository.bookThumbnailUrl(bookId)

    /** Known-hash action change (auto delete asks for confirmation first on the UI side). */
    fun updateKnownAction(entry: PageHashKnownDto, action: PageHashAction) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = repository.updatePageHash(entry.hash, entry.propertySize, action.raw)
            _state.update {
                it.copy(
                    busy = false,
                    messageRes = if (ok) R.string.server_admin_action_queued else R.string.server_admin_action_failed,
                )
            }
            if (ok) reload()
        }
    }

    fun askDeleteMatches(entry: PageHashKnownDto) {
        _state.update { it.copy(pendingDeleteMatches = entry) }
    }

    fun cancelDeleteMatches() {
        _state.update { it.copy(pendingDeleteMatches = null) }
    }

    fun confirmDeleteMatches() {
        val entry = _state.value.pendingDeleteMatches ?: return
        _state.update { it.copy(pendingDeleteMatches = null, busy = true) }
        viewModelScope.launch {
            val ok = repository.deleteAllPageHashMatches(entry.hash)
            _state.update {
                it.copy(
                    busy = false,
                    messageRes = if (ok) {
                        R.string.server_admin_media_matches_deleted
                    } else {
                        R.string.server_admin_action_failed
                    },
                )
            }
            if (ok) reload()
        }
    }

    fun askAutoDelete(entry: PageHashKnownDto) {
        _state.update { it.copy(pendingAutoDelete = entry) }
    }

    fun cancelAutoDelete() {
        _state.update { it.copy(pendingAutoDelete = null) }
    }

    fun confirmAutoDelete() {
        val entry = _state.value.pendingAutoDelete ?: return
        _state.update { it.copy(pendingAutoDelete = null) }
        updateKnownAction(entry, PageHashAction.DeleteAuto)
    }

    /** Triage one unknown hash; the row stays hidden until the list is exhausted (like iOS). */
    fun markUnknown(entry: PageHashUnknownDto, action: PageHashAction) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = repository.updatePageHash(entry.hash, entry.propertySize, action.raw)
            if (!ok) {
                _state.update {
                    it.copy(busy = false, messageRes = R.string.server_admin_action_failed)
                }
                return@launch
            }
            _state.update {
                it.copy(busy = false, processedHashes = it.processedHashes + entry.hash)
            }
            if (_state.value.unknown.all { it.hash in _state.value.processedHashes }) {
                reload()
            }
        }
    }

    /** Bulk triage of the rows still visible (iOS "Ignore / Manual Delete / Auto Delete Remaining"). */
    fun markAllUnknown(action: PageHashAction) {
        if (_state.value.busy) return
        val snapshot = _state.value
        val visible = snapshot.unknown.filter { it.hash !in snapshot.processedHashes }
        if (visible.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            for (entry in visible) {
                val ok = repository.updatePageHash(entry.hash, entry.propertySize, action.raw)
                if (!ok) {
                    _state.update {
                        it.copy(busy = false, messageRes = R.string.server_admin_action_failed)
                    }
                    return@launch
                }
                _state.update { it.copy(processedHashes = it.processedHashes + entry.hash) }
            }
            _state.update { it.copy(busy = false) }
            reload()
        }
    }

    fun deleteMatch(match: PageHashMatchDto) {
        val hash = _state.value.matchesHash ?: return
        viewModelScope.launch {
            val ok = repository.deletePageHashMatch(hash, match.bookId, match.pageNumber)
            _state.update {
                if (ok) {
                    it.copy(
                        matches = it.matches.filterNot { row ->
                            row.bookId == match.bookId && row.pageNumber == match.pageNumber
                        },
                        messageRes = R.string.server_admin_media_match_deleted,
                    )
                } else {
                    it.copy(messageRes = R.string.server_admin_action_failed)
                }
            }
        }
    }

    fun consumeMessage() {
        if (_state.value.messageRes == null) return
        _state.update { it.copy(messageRes = null) }
    }

    /** Reloads the current section after a mutation (filters and paging restart from the first page). */
    private fun reload() {
        page = 0
        loaded = false
        load(reset = true)
    }

    private companion object {
        const val DUPLICATE_PAGE_SIZE = 50
        const val HASH_PAGE_SIZE = 10
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaManagementScreen(
    onBack: () -> Unit,
    viewModel: MediaManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_admin_media)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.isAdmin) {
                CenteredMessage(R.string.server_admin_admin_required)
                return@Column
            }
            SectionChips(state.section) { viewModel.select(it) }
            MediaFilterBar(state, viewModel)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> MediaList(state, viewModel)
                }
            }
        }
    }

    state.matchesHash?.let { hash ->
        MatchesDialog(
            matches = state.matches,
            loading = state.matchesLoading,
            thumbnailUrl = viewModel::thumbnailUrl,
            onDelete = viewModel::deleteMatch,
            onDismiss = viewModel::closeMatches,
        )
    }

    state.pendingDeleteMatches?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDeleteMatches,
            title = { Text(stringResource(R.string.server_admin_media_delete_matches_confirm)) },
            text = {
                Text(
                    stringResource(
                        R.string.server_admin_media_delete_matches_message,
                        entry.matchCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteMatches) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDeleteMatches) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    state.pendingAutoDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::cancelAutoDelete,
            title = { Text(stringResource(R.string.server_admin_media_auto_delete_confirm)) },
            text = { Text(stringResource(R.string.server_admin_media_auto_delete_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmAutoDelete) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelAutoDelete) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionChips(selected: MediaSection, onSelect: (MediaSection) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionChip(
            labelRes = R.string.server_admin_media_analysis,
            selected = selected == MediaSection.Analysis,
            onClick = { onSelect(MediaSection.Analysis) },
        )
        SectionChip(
            labelRes = R.string.server_admin_media_missing_posters,
            selected = selected == MediaSection.MissingPosters,
            onClick = { onSelect(MediaSection.MissingPosters) },
        )
        SectionChip(
            labelRes = R.string.server_admin_media_duplicates,
            selected = selected == MediaSection.Duplicates,
            onClick = { onSelect(MediaSection.Duplicates) },
        )
        SectionChip(
            labelRes = R.string.server_admin_media_known,
            selected = selected == MediaSection.Known,
            onClick = { onSelect(MediaSection.Known) },
        )
        SectionChip(
            labelRes = R.string.server_admin_media_unknown,
            selected = selected == MediaSection.Unknown,
            onClick = { onSelect(MediaSection.Unknown) },
        )
    }
}

@Composable
private fun SectionChip(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(labelRes)) })
}

@Composable
private fun MediaFilterBar(state: MediaUiState, viewModel: MediaManagementViewModel) {
    when (state.section) {
        MediaSection.Analysis -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionChip(
                    labelRes = R.string.server_admin_media_error,
                    selected = state.filterError,
                    onClick = viewModel::toggleError,
                )
                SectionChip(
                    labelRes = R.string.server_admin_media_unsupported,
                    selected = state.filterUnsupported,
                    onClick = viewModel::toggleUnsupported,
                )
            }
            if (state.libraries.isEmpty()) return
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionChip(
                    labelRes = R.string.server_admin_media_all_libraries,
                    selected = state.selectedLibraryIds.isEmpty(),
                    onClick = viewModel::clearLibraries,
                )
                state.libraries.forEach { library ->
                    FilterChip(
                        selected = library.id in state.selectedLibraryIds,
                        onClick = { viewModel.toggleLibrary(library.id) },
                        label = { Text(library.name) },
                    )
                }
            }
        }
        MediaSection.Known -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PageHashAction.entries.forEach { action ->
                    FilterChip(
                        selected = action in state.filterActions,
                        onClick = { viewModel.toggleAction(action) },
                        label = { Text(stringResource(action.labelRes())) },
                    )
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun MediaList(state: MediaUiState, viewModel: MediaManagementViewModel) {
    when (state.section) {
        MediaSection.Known -> KnownList(state, viewModel)
        MediaSection.Unknown -> UnknownList(state, viewModel)
        MediaSection.Duplicates -> DuplicatesList(state, viewModel)
        else -> BookList(state, viewModel)
    }
}

@Composable
private fun BookList(state: MediaUiState, viewModel: MediaManagementViewModel) {
    val libraryNames = remember(state.libraries) { state.libraries.associate { it.id to it.name } }
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ImageLoaderEntryPoint::class.java)
    }
    val pageLoader by entryPoint.imageCacheStore().pageLoader.collectAsStateWithLifecycle()
    val coverLoader by entryPoint.imageCacheStore().coverLoader.collectAsStateWithLifecycle()
    val loader = pageLoader ?: coverLoader
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (state.books.isEmpty()) {
            item { EmptyHint(R.string.server_admin_media_no_issues) }
        }
        items(state.books, key = { it.id }) { book ->
            AnalysisBookRow(
                book = book,
                libraryName = libraryNames[book.libraryId],
                thumbnailUrl = viewModel.bookThumbnailUrl(book.id),
                loader = loader,
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        if (state.hasMore) {
            item { LoadMoreRow(onLoadMore = { viewModel.load(reset = false) }) }
        }
    }
}

@Composable
private fun DuplicatesList(state: MediaUiState, viewModel: MediaManagementViewModel) {
    val groups = remember(state.books) { state.books.groupBy { it.fileHash } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (groups.isEmpty()) {
            item { EmptyHint(R.string.server_admin_media_no_duplicates) }
        }
        groups.forEach { (hash, books) ->
            item(key = "hash-$hash") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${hash.take(16)}…",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.server_admin_media_group_files, books.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(books, key = { it.id }) { book ->
                DuplicateBookRow(book)
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        if (state.hasMore) {
            item { LoadMoreRow(onLoadMore = { viewModel.load(reset = false) }) }
        }
    }
}

@Composable
private fun KnownList(state: MediaUiState, viewModel: MediaManagementViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (state.known.isEmpty()) {
            item { EmptyHint(R.string.server_admin_media_no_known) }
        }
        items(state.known, key = { it.hash }) { entry ->
            KnownHashCard(
                entry = entry,
                onOpenMatches = { viewModel.openMatches(entry.hash) },
                onDeleteMatches = { viewModel.askDeleteMatches(entry) },
                onAutoDelete = { viewModel.askAutoDelete(entry) },
                onAction = { action -> viewModel.updateKnownAction(entry, action) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        if (state.hasMore) {
            item { LoadMoreRow(onLoadMore = { viewModel.load(reset = false) }) }
        }
    }
}

@Composable
private fun UnknownList(state: MediaUiState, viewModel: MediaManagementViewModel) {
    val visible = state.unknown.filter { it.hash !in state.processedHashes }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (visible.isEmpty() && !state.hasMore) {
            item { EmptyHint(R.string.server_admin_media_no_unknown) }
        }
        items(visible, key = { it.hash }) { entry ->
            UnknownHashCard(
                entry = entry,
                onOpenMatches = { viewModel.openMatches(entry.hash) },
                onAction = { action -> viewModel.markUnknown(entry, action) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        if (state.hasMore) {
            item { LoadMoreRow(onLoadMore = { viewModel.load(reset = false) }) }
        }
        if (visible.isNotEmpty()) {
            item {
                BulkActions(visible.size) { action -> viewModel.markAllUnknown(action) }
            }
        }
    }
}

@Composable
private fun BulkActions(count: Int, onAction: (PageHashAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.server_admin_media_bulk_actions),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onAction(PageHashAction.Ignore) }) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.server_admin_media_ignore_remaining, count),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            OutlinedButton(onClick = { onAction(PageHashAction.DeleteManual) }) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.server_admin_media_manual_remaining, count),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            OutlinedButton(onClick = { onAction(PageHashAction.DeleteAuto) }) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.server_admin_media_auto_remaining, count),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun KnownHashCard(
    entry: PageHashKnownDto,
    onOpenMatches: () -> Unit,
    onDeleteMatches: () -> Unit,
    onAutoDelete: () -> Unit,
    onAction: (PageHashAction) -> Unit,
) {
    val action = PageHashAction.entries.firstOrNull { it.raw == entry.action.value }
        ?: PageHashAction.Ignore
    val size = entry.propertySize?.takeIf { it > 0 }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${entry.hash.take(16)}…",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ActionBadge(action)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            size?.let {
                StatBlock(R.string.server_admin_media_size, formatBytes(it))
            }
            StatBlock(R.string.server_admin_media_matches, entry.matchCount.toString())
            if (entry.deleteCount > 0) {
                StatBlock(R.string.server_admin_media_deleted, entry.deleteCount.toString())
            }
        }
        if (size != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (entry.deleteCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.server_admin_media_saved,
                            formatBytes(size * entry.deleteCount),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF2E7D32),
                    )
                }
                if (entry.matchCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.server_admin_media_can_save,
                            formatBytes(size * entry.matchCount),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.matchCount > 0) {
                OutlinedButton(onClick = onOpenMatches) {
                    Text(stringResource(R.string.server_admin_media_matches_button, entry.matchCount))
                }
            }
            Spacer(Modifier.weight(1f))
            if (action == PageHashAction.DeleteManual && entry.matchCount > 0) {
                OutlinedButton(onClick = onDeleteMatches) {
                    Text(stringResource(R.string.server_admin_media_delete_matches))
                }
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (action != PageHashAction.Ignore) {
                OutlinedButton(onClick = { onAction(PageHashAction.Ignore) }) {
                    Text(stringResource(PageHashAction.Ignore.labelRes()))
                }
            }
            if (action != PageHashAction.DeleteManual) {
                OutlinedButton(onClick = { onAction(PageHashAction.DeleteManual) }) {
                    Text(stringResource(PageHashAction.DeleteManual.labelRes()))
                }
            }
            if (action != PageHashAction.DeleteAuto && entry.propertySize != null) {
                OutlinedButton(onClick = onAutoDelete) {
                    Text(stringResource(PageHashAction.DeleteAuto.labelRes()))
                }
            }
        }
    }
}

@Composable
private fun UnknownHashCard(
    entry: PageHashUnknownDto,
    onOpenMatches: () -> Unit,
    onAction: (PageHashAction) -> Unit,
) {
    val size = entry.propertySize?.takeIf { it > 0 }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "${entry.hash.take(16)}…",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            size?.let { StatBlock(R.string.server_admin_media_size, formatBytes(it)) }
            StatBlock(R.string.server_admin_media_matches, entry.matchCount.toString())
            if (size != null && entry.matchCount > 0) {
                StatBlock(
                    R.string.server_admin_media_can_save_short,
                    formatBytes(size * entry.matchCount),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.matchCount > 0) {
                OutlinedButton(onClick = onOpenMatches) {
                    Text(stringResource(R.string.server_admin_media_matches_button, entry.matchCount))
                }
            }
            Spacer(Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onAction(PageHashAction.Ignore) }) {
                Text(stringResource(PageHashAction.Ignore.labelRes()))
            }
            OutlinedButton(onClick = { onAction(PageHashAction.DeleteManual) }) {
                Text(stringResource(PageHashAction.DeleteManual.labelRes()))
            }
            if (entry.propertySize != null) {
                OutlinedButton(onClick = { onAction(PageHashAction.DeleteAuto) }) {
                    Text(stringResource(PageHashAction.DeleteAuto.labelRes()))
                }
            }
        }
    }
}

@Composable
private fun MatchesDialog(
    matches: List<PageHashMatchDto>,
    loading: Boolean,
    thumbnailUrl: (String, Int) -> String?,
    onDelete: (PageHashMatchDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ImageLoaderEntryPoint::class.java)
    }
    val pageLoader by entryPoint.imageCacheStore().pageLoader.collectAsStateWithLifecycle()
    val coverLoader by entryPoint.imageCacheStore().coverLoader.collectAsStateWithLifecycle()
    val loader = pageLoader ?: coverLoader

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_admin_media_matches_title)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        text = {
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                matches.isEmpty() -> Text(stringResource(R.string.server_admin_media_no_matches))
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(matches, key = { "${it.bookId}-${it.pageNumber}" }) { match ->
                        MatchRow(
                            match = match,
                            thumbnailUrl = thumbnailUrl(match.bookId, match.pageNumber),
                            loader = loader,
                            onDelete = { onDelete(match) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
    )
}

@Composable
private fun MatchRow(
    match: PageHashMatchDto,
    thumbnailUrl: String?,
    loader: coil3.ImageLoader?,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(width = 44.dp, height = 62.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnailUrl != null && loader != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    imageLoader = loader,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = stringResource(R.string.server_admin_media_page, match.pageNumber),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = match.fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    stringResource(R.string.server_admin_media_page, match.pageNumber),
                    match.mediaType,
                    formatBytes(match.fileSize),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.server_admin_media_delete_match),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AnalysisBookRow(
    book: BookDto,
    libraryName: String?,
    thumbnailUrl: String?,
    loader: coil3.ImageLoader?,
) {
    val status = mediaStatusDetailLabel(book.media.status)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(width = 44.dp, height = 62.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnailUrl != null && loader != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    imageLoader = loader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = book.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                status?.let { (label, color) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                    )
                }
                if (book.media.mediaType.isNotBlank()) {
                    Text(
                        text = book.media.mediaType,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatBytes(book.sizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (book.media.comment.isNotBlank()) {
                Text(
                    text = book.media.comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (libraryName != null) {
                Text(
                    text = libraryName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (book.deleted) {
                Text(
                    text = stringResource(R.string.server_admin_media_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DuplicateBookRow(book: BookDto) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = book.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = book.url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOf(book.seriesTitle, formatBytes(book.sizeBytes)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.deleted) {
            Text(
                text = stringResource(R.string.server_admin_media_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StatBlock(labelRes: Int, value: String) {
    Column {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActionBadge(action: PageHashAction) {
    val color = when (action) {
        PageHashAction.DeleteAuto -> Color(0xFF2E7D32)
        PageHashAction.DeleteManual -> Color(0xFFEF6C00)
        PageHashAction.Ignore -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = stringResource(action.labelRes()),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun EmptyHint(messageRes: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun PageHashAction.labelRes(): Int = when (this) {
    PageHashAction.DeleteAuto -> R.string.server_admin_media_auto_delete
    PageHashAction.DeleteManual -> R.string.server_admin_media_manual_delete
    PageHashAction.Ignore -> R.string.server_admin_media_ignore
}
