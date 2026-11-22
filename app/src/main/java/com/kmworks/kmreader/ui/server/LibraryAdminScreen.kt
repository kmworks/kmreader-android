package com.kmworks.kmreader.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.DirectoryListingDto
import com.kmworks.kmreader.core.api.model.LibraryCreationDto
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.api.model.LibraryUpdateDto
import com.kmworks.kmreader.core.server.LibraryMetricValues
import com.kmworks.kmreader.core.server.ServerAdminRepository
import com.kmworks.kmreader.ui.settings.SettingsPickerRow
import com.kmworks.kmreader.ui.settings.SettingsRowDivider
import com.kmworks.kmreader.ui.settings.SettingsSection
import com.kmworks.kmreader.ui.settings.SettingsSwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Every field the iOS LibraryFormSections exposes; the defaults mirror iOS LibraryCreation.createDefault. */
data class LibraryFormData(
    val name: String = "",
    val root: String = "",
    val scanInterval: String = SCAN_INTERVAL_EVERY_6H,
    val scanOnStartup: Boolean = false,
    val scanCbx: Boolean = true,
    val scanPdf: Boolean = true,
    val scanEpub: Boolean = true,
    val importComicInfoBook: Boolean = true,
    val importComicInfoSeries: Boolean = true,
    val importLocalArtwork: Boolean = true,
    val analyzeDimensions: Boolean = true,
    // Scanner
    val emptyTrashAfterScan: Boolean = false,
    val scanForceModifiedTime: Boolean = false,
    val oneshotsDirectory: String = "",
    val scanDirectoryExclusions: List<String> = DEFAULT_EXCLUSIONS,
    // Options
    val hashFiles: Boolean = true,
    val hashPages: Boolean = false,
    val hashKoreader: Boolean = false,
    val repairExtensions: Boolean = false,
    val convertToCbz: Boolean = false,
    val seriesCover: String = SERIES_COVER_FIRST,
    // Metadata
    val importComicInfoSeriesAppendVolume: Boolean = true,
    val importComicInfoCollection: Boolean = true,
    val importComicInfoReadList: Boolean = true,
    val importEpubBook: Boolean = true,
    val importEpubSeries: Boolean = true,
    val importMylarSeries: Boolean = true,
    val importBarcodeIsbn: Boolean = false,
) {
    val isValid: Boolean get() = name.isNotBlank() && root.isNotBlank()
}

internal const val SCAN_INTERVAL_DISABLED = "DISABLED"

internal const val SCAN_INTERVAL_EVERY_6H = "EVERY_6H"

internal const val SERIES_COVER_FIRST = "FIRST"

/** Same three exclusions the iOS createDefault preselects. */
private val DEFAULT_EXCLUSIONS = listOf("#recycle", "@eaDir", "@Recycle")

private val SCAN_INTERVALS = listOf(
    "DISABLED" to R.string.server_admin_interval_disabled,
    "HOURLY" to R.string.server_admin_interval_hourly,
    "EVERY_6H" to R.string.server_admin_interval_every_6h,
    "EVERY_12H" to R.string.server_admin_interval_every_12h,
    "DAILY" to R.string.server_admin_interval_daily,
    "WEEKLY" to R.string.server_admin_interval_weekly,
)

private val SERIES_COVERS = listOf(
    "FIRST" to R.string.server_admin_library_series_cover_first,
    "FIRST_UNREAD_OR_FIRST" to R.string.server_admin_library_series_cover_first_unread_or_first,
    "FIRST_UNREAD_OR_LAST" to R.string.server_admin_library_series_cover_first_unread_or_last,
    "LAST" to R.string.server_admin_library_series_cover_last,
)

/** What the library form is editing; [editId] null means "create". */
data class LibraryFormState(
    val editId: String? = null,
    val data: LibraryFormData = LibraryFormData(),
) {
    val isEdit: Boolean get() = editId != null
}

/** One directory level shown by the root picker. */
data class DirectoryBrowseState(
    val path: String? = null,
    val listing: DirectoryListingDto? = null,
    val loading: Boolean = true,
)

data class LibraryAdminUiState(
    val libraries: List<LibraryDto> = emptyList(),
    /** Per-library size/count metrics (kmrs stats, else actuator metrics); empty when unavailable. */
    val metrics: Map<String, LibraryMetricValues> = emptyMap(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val form: LibraryFormState? = null,
    val pendingDelete: LibraryDto? = null,
    val messageRes: Int? = null,
)

/**
 * Library administration (`/api/v1/libraries` create/update/delete plus the scan/analyze/trash
 * maintenance actions), mirroring the iOS ServerLibrariesView + LibraryAddSheet/LibraryEditSheet.
 */
@HiltViewModel
class LibraryAdminViewModel @Inject constructor(
    private val repository: ServerAdminRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryAdminUiState())
    val state: StateFlow<LibraryAdminUiState> = _state.asStateFlow()

    private val _browse = MutableStateFlow<DirectoryBrowseState?>(null)
    val browse: StateFlow<DirectoryBrowseState?> = _browse.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = it.libraries.isEmpty()) }
        viewModelScope.launch {
            val libraries = repository.libraries()
            _state.update { it.copy(libraries = libraries, loading = false) }
            // Metrics are optional: kmrs stats/libraries when available, else actuator metrics (admin).
            val metrics = runCatching { repository.libraryMetrics() }.getOrNull()
            if (metrics != null) {
                _state.update { it.copy(metrics = metrics.byLibrary) }
            }
        }
    }

    fun startCreate() {
        _state.update { it.copy(form = LibraryFormState()) }
    }

    fun startEdit(library: LibraryDto) {
        _state.update {
            it.copy(
                form = LibraryFormState(
                    editId = library.id,
                    data = LibraryFormData(
                        name = library.name,
                        root = library.root,
                        scanInterval = library.scanInterval.value,
                        scanOnStartup = library.scanOnStartup,
                        scanCbx = library.scanCbx,
                        scanPdf = library.scanPdf,
                        scanEpub = library.scanEpub,
                        importComicInfoBook = library.importComicInfoBook,
                        importComicInfoSeries = library.importComicInfoSeries,
                        importLocalArtwork = library.importLocalArtwork,
                        analyzeDimensions = library.analyzeDimensions,
                        emptyTrashAfterScan = library.emptyTrashAfterScan,
                        scanForceModifiedTime = library.scanForceModifiedTime,
                        oneshotsDirectory = library.oneshotsDirectory.orEmpty(),
                        scanDirectoryExclusions = library.scanDirectoryExclusions.toList().sorted(),
                        hashFiles = library.hashFiles,
                        hashPages = library.hashPages,
                        hashKoreader = library.hashKoreader,
                        repairExtensions = library.repairExtensions,
                        convertToCbz = library.convertToCbz,
                        seriesCover = library.seriesCover.value,
                        importComicInfoSeriesAppendVolume = library.importComicInfoSeriesAppendVolume,
                        importComicInfoCollection = library.importComicInfoCollection,
                        importComicInfoReadList = library.importComicInfoReadList,
                        importEpubBook = library.importEpubBook,
                        importEpubSeries = library.importEpubSeries,
                        importMylarSeries = library.importMylarSeries,
                        importBarcodeIsbn = library.importBarcodeIsbn,
                    ),
                ),
            )
        }
    }

    fun updateForm(transform: (LibraryFormData) -> LibraryFormData) {
        _state.update { current ->
            val form = current.form ?: return@update current
            current.copy(form = form.copy(data = transform(form.data)))
        }
    }

    fun closeForm() {
        _state.update { it.copy(form = null) }
        _browse.value = null
    }

    fun save() {
        val form = _state.value.form ?: return
        if (!form.data.isValid || _state.value.busy) return
        val id = form.editId
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = if (id == null) {
                repository.createLibrary(creationDto(form.data)) != null
            } else {
                repository.updateLibrary(id, updateDto(form.data))
            }
            _state.update {
                it.copy(
                    busy = false,
                    form = if (ok) null else it.form,
                    messageRes = if (ok) {
                        if (id == null) R.string.server_admin_library_created else R.string.server_admin_library_updated
                    } else {
                        R.string.server_admin_action_failed
                    },
                )
            }
            if (ok) load()
        }
    }

    fun askDelete(library: LibraryDto) {
        _state.update { it.copy(pendingDelete = library) }
    }

    fun cancelDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    fun delete(library: LibraryDto) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, pendingDelete = null) }
        viewModelScope.launch {
            val ok = repository.deleteLibrary(library.id)
            _state.update {
                it.copy(busy = false, messageRes = if (ok) R.string.server_admin_library_deleted else R.string.server_admin_action_failed)
            }
            if (ok) load()
        }
    }

    fun scan(library: LibraryDto, deep: Boolean) = runAction {
        repository.scanLibrary(library.id, deep)
    }

    fun analyze(library: LibraryDto) = runAction {
        repository.analyzeLibrary(library.id)
    }

    fun emptyTrash(library: LibraryDto) = runAction {
        repository.emptyLibraryTrash(library.id)
    }

    fun refreshMetadata(library: LibraryDto) = runAction {
        repository.refreshLibraryMetadata(library.id)
    }

    private fun runAction(block: suspend () -> Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = block()
            _state.update {
                it.copy(
                    busy = false,
                    messageRes = if (ok) R.string.server_admin_action_queued else R.string.server_admin_action_failed,
                )
            }
        }
    }

    fun openBrowse(path: String?) {
        _browse.value = DirectoryBrowseState(path = path)
        viewModelScope.launch {
            val listing = repository.directory(path)
            _browse.update { current ->
                current?.copy(listing = listing, loading = false)
            }
        }
    }

    fun closeBrowse() {
        _browse.value = null
    }

    fun consumeMessage() {
        _state.update { it.copy(messageRes = null) }
    }

    private fun creationDto(data: LibraryFormData): LibraryCreationDto = LibraryCreationDto(
        analyzeDimensions = data.analyzeDimensions,
        convertToCbz = data.convertToCbz,
        emptyTrashAfterScan = data.emptyTrashAfterScan,
        hashFiles = data.hashFiles,
        hashKoreader = data.hashKoreader,
        hashPages = data.hashPages,
        importBarcodeIsbn = data.importBarcodeIsbn,
        importComicInfoBook = data.importComicInfoBook,
        importComicInfoCollection = data.importComicInfoCollection,
        importComicInfoReadList = data.importComicInfoReadList,
        importComicInfoSeries = data.importComicInfoSeries,
        importComicInfoSeriesAppendVolume = data.importComicInfoSeriesAppendVolume,
        importEpubBook = data.importEpubBook,
        importEpubSeries = data.importEpubSeries,
        importLocalArtwork = data.importLocalArtwork,
        importMylarSeries = data.importMylarSeries,
        name = data.name.trim(),
        repairExtensions = data.repairExtensions,
        root = data.root.trim(),
        scanCbx = data.scanCbx,
        scanDirectoryExclusions = data.exclusionsToSet(),
        scanEpub = data.scanEpub,
        scanForceModifiedTime = data.scanForceModifiedTime,
        scanInterval = LibraryCreationDto.ScanInterval.entries.firstOrNull { it.value == data.scanInterval }
            ?: LibraryCreationDto.ScanInterval.DISABLED,
        scanOnStartup = data.scanOnStartup,
        scanPdf = data.scanPdf,
        seriesCover = LibraryCreationDto.SeriesCover.entries.firstOrNull { it.value == data.seriesCover }
            ?: LibraryCreationDto.SeriesCover.FIRST,
        oneshotsDirectory = data.oneshotsDirectory.trim().ifBlank { null },
    )

    /** The form now holds the whole configuration, so the update mirrors the edit form field for field. */
    private fun updateDto(data: LibraryFormData): LibraryUpdateDto = LibraryUpdateDto(
        analyzeDimensions = data.analyzeDimensions,
        convertToCbz = data.convertToCbz,
        emptyTrashAfterScan = data.emptyTrashAfterScan,
        hashFiles = data.hashFiles,
        hashKoreader = data.hashKoreader,
        hashPages = data.hashPages,
        importBarcodeIsbn = data.importBarcodeIsbn,
        importComicInfoBook = data.importComicInfoBook,
        importComicInfoCollection = data.importComicInfoCollection,
        importComicInfoReadList = data.importComicInfoReadList,
        importComicInfoSeries = data.importComicInfoSeries,
        importComicInfoSeriesAppendVolume = data.importComicInfoSeriesAppendVolume,
        importEpubBook = data.importEpubBook,
        importEpubSeries = data.importEpubSeries,
        importLocalArtwork = data.importLocalArtwork,
        importMylarSeries = data.importMylarSeries,
        name = data.name.trim(),
        oneshotsDirectory = data.oneshotsDirectory.trim().ifBlank { null },
        repairExtensions = data.repairExtensions,
        root = data.root.trim(),
        scanCbx = data.scanCbx,
        scanDirectoryExclusions = data.exclusionsToSet(),
        scanEpub = data.scanEpub,
        scanForceModifiedTime = data.scanForceModifiedTime,
        scanInterval = LibraryUpdateDto.ScanInterval.entries.firstOrNull { it.value == data.scanInterval }
            ?: LibraryUpdateDto.ScanInterval.DISABLED,
        scanOnStartup = data.scanOnStartup,
        scanPdf = data.scanPdf,
        seriesCover = LibraryUpdateDto.SeriesCover.entries.firstOrNull { it.value == data.seriesCover }
            ?: LibraryUpdateDto.SeriesCover.FIRST,
    )

    private fun LibraryFormData.exclusionsToSet(): Set<String> =
        scanDirectoryExclusions.map { it.trim() }.filter { it.isNotBlank() }.toSet()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryAdminScreen(
    onBack: () -> Unit,
    viewModel: LibraryAdminViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = onBack)

    LaunchedEffect(state.messageRes) {
        val res = state.messageRes ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(res))
        viewModel.consumeMessage()
    }

    val form = state.form
    if (form != null) {
        LibraryFormScreen(
            form = form,
            saving = state.busy,
            browse = browse,
            onUpdate = viewModel::updateForm,
            onBrowse = viewModel::openBrowse,
            onCloseBrowse = viewModel::closeBrowse,
            onDismiss = viewModel::closeForm,
            onSave = viewModel::save,
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_admin_libraries_manage)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::startCreate) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.server_admin_library_add),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.libraries.isEmpty() -> CenteredMessage(R.string.server_admin_library_empty)
                else -> Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        itemsIndexed(state.libraries, key = { _, library -> library.id }) { index, library ->
                            LibraryAdminRow(
                                library = library,
                                metrics = state.metrics[library.id],
                                enabled = !state.busy,
                                onEdit = { viewModel.startEdit(library) },
                                onDelete = { viewModel.askDelete(library) },
                                onScan = { viewModel.scan(library, deep = false) },
                                onDeepScan = { viewModel.scan(library, deep = true) },
                                onAnalyze = { viewModel.analyze(library) },
                                onEmptyTrash = { viewModel.emptyTrash(library) },
                                onRefreshMetadata = { viewModel.refreshMetadata(library) },
                            )
                            if (index < state.libraries.lastIndex) {
                                SettingsRowDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    state.pendingDelete?.let { library ->
        var typed by remember(library.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.server_admin_library_delete)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.server_admin_library_delete_confirm, library.name))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.server_admin_library_delete_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed == library.name,
                    onClick = { viewModel.delete(library) },
                ) { Text(stringResource(R.string.server_admin_library_delete)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryAdminRow(
    library: LibraryDto,
    metrics: LibraryMetricValues?,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onScan: () -> Unit,
    onDeepScan: () -> Unit,
    onAnalyze: () -> Unit,
    onEmptyTrash: () -> Unit,
    onRefreshMetadata: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = library.root,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onScan, enabled = enabled) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = stringResource(R.string.server_admin_library_scan),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.server_admin_library_more),
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.server_admin_library_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.server_admin_library_scan_deep)) },
                        onClick = { menuOpen = false; onDeepScan() },
                        leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.server_admin_library_analyze)) },
                        onClick = { menuOpen = false; onAnalyze() },
                        leadingIcon = { Icon(Icons.Filled.MonitorHeart, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.server_admin_library_empty_trash)) },
                        onClick = { menuOpen = false; onEmptyTrash() },
                        leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.server_admin_library_refresh_metadata)) },
                        onClick = { menuOpen = false; onRefreshMetadata() },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.server_admin_library_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { menuOpen = false; onDelete() },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }
        metrics?.takeIf { !it.isEmpty }?.let { values ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                values.fileSize?.let { LibraryMetricChip(Icons.Outlined.Storage, formatBytes(it)) }
                values.seriesCount?.let {
                    LibraryMetricChip(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.library_metrics_series, it))
                }
                values.booksCount?.let {
                    LibraryMetricChip(Icons.Outlined.Book, stringResource(R.string.library_metrics_books, it))
                }
            }
        }
        if (library.unavailable) {
            LibraryMetricChip(
                icon = Icons.Outlined.FolderOpen,
                text = stringResource(R.string.server_admin_library_unavailable),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LibraryMetricChip(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = color,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFormScreen(
    form: LibraryFormState,
    saving: Boolean,
    browse: DirectoryBrowseState?,
    onUpdate: ((LibraryFormData) -> LibraryFormData) -> Unit,
    onBrowse: (String?) -> Unit,
    onCloseBrowse: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val data = form.data
    var newExclusion by remember { mutableStateOf("") }
    BackHandler(onBack = onDismiss)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (form.isEdit) {
                                R.string.server_admin_library_edit_title
                            } else {
                                R.string.server_admin_library_add_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = !saving && data.isValid) {
                        Text(stringResource(R.string.server_admin_library_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSection(title = stringResource(R.string.server_admin_library_section_basic)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = data.name,
                        onValueChange = { value -> onUpdate { it.copy(name = value) } },
                        singleLine = true,
                        label = { Text(stringResource(R.string.server_admin_library_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = data.root,
                        onValueChange = { value -> onUpdate { it.copy(root = value) } },
                        singleLine = true,
                        label = { Text(stringResource(R.string.server_admin_library_root)) },
                        trailingIcon = {
                            IconButton(onClick = { onBrowse(data.root.takeIf { it.isNotBlank() }) }) {
                                Icon(
                                    Icons.Outlined.FolderOpen,
                                    contentDescription = stringResource(R.string.server_admin_library_browse),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SettingsRowDivider()
                val intervalLabelRes = SCAN_INTERVALS
                    .firstOrNull { it.first == data.scanInterval }
                    ?.second
                    ?: R.string.server_admin_interval_disabled
                SettingsPickerRow(
                    title = stringResource(R.string.server_admin_library_scan_interval),
                    selectedLabel = stringResource(intervalLabelRes),
                    options = SCAN_INTERVALS.map { (raw, res) -> raw to stringResource(res) },
                    onSelect = { raw -> onUpdate { it.copy(scanInterval = raw) } },
                    icon = Icons.Outlined.Schedule,
                )
            }
            SettingsSection(title = stringResource(R.string.server_admin_library_section_scan)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_scan_on_startup),
                    checked = data.scanOnStartup,
                    onCheckedChange = { value -> onUpdate { it.copy(scanOnStartup = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_scan_cbx),
                    checked = data.scanCbx,
                    onCheckedChange = { value -> onUpdate { it.copy(scanCbx = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_scan_pdf),
                    checked = data.scanPdf,
                    onCheckedChange = { value -> onUpdate { it.copy(scanPdf = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_scan_epub),
                    checked = data.scanEpub,
                    onCheckedChange = { value -> onUpdate { it.copy(scanEpub = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_empty_trash_after_scan),
                    checked = data.emptyTrashAfterScan,
                    onCheckedChange = { value -> onUpdate { it.copy(emptyTrashAfterScan = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_scan_force_modified_time),
                    checked = data.scanForceModifiedTime,
                    onCheckedChange = { value -> onUpdate { it.copy(scanForceModifiedTime = value) } },
                )
                SettingsRowDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = data.oneshotsDirectory,
                        onValueChange = { value -> onUpdate { it.copy(oneshotsDirectory = value) } },
                        singleLine = true,
                        label = { Text(stringResource(R.string.server_admin_library_oneshots_directory)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.server_admin_library_exclusions),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    data.scanDirectoryExclusions.forEach { exclusion ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = exclusion,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    onUpdate { it.copy(scanDirectoryExclusions = it.scanDirectoryExclusions - exclusion) }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.common_delete),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newExclusion,
                            onValueChange = { value -> newExclusion = value },
                            singleLine = true,
                            label = { Text(stringResource(R.string.server_admin_library_exclusion_add)) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            enabled = newExclusion.isNotBlank(),
                            onClick = {
                                val entry = newExclusion.trim()
                                onUpdate {
                                    it.copy(scanDirectoryExclusions = it.scanDirectoryExclusions + entry)
                                }
                                newExclusion = ""
                            },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.server_admin_library_exclusion_add))
                        }
                    }
                }
            }
            SettingsSection(title = stringResource(R.string.server_admin_library_section_options)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_hash_files),
                    checked = data.hashFiles,
                    onCheckedChange = { value -> onUpdate { it.copy(hashFiles = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_hash_pages),
                    checked = data.hashPages,
                    onCheckedChange = { value -> onUpdate { it.copy(hashPages = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_hash_koreader),
                    checked = data.hashKoreader,
                    onCheckedChange = { value -> onUpdate { it.copy(hashKoreader = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_analyze_dimensions),
                    checked = data.analyzeDimensions,
                    onCheckedChange = { value -> onUpdate { it.copy(analyzeDimensions = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_repair_extensions),
                    checked = data.repairExtensions,
                    onCheckedChange = { value -> onUpdate { it.copy(repairExtensions = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_convert_to_cbz),
                    checked = data.convertToCbz,
                    onCheckedChange = { value -> onUpdate { it.copy(convertToCbz = value) } },
                )
                SettingsRowDivider()
                SettingsPickerRow(
                    title = stringResource(R.string.server_admin_library_series_cover),
                    selectedLabel = stringResource(
                        SERIES_COVERS.firstOrNull { it.first == data.seriesCover }?.second
                            ?: R.string.server_admin_library_series_cover_first,
                    ),
                    options = SERIES_COVERS.map { (raw, res) -> raw to stringResource(res) },
                    onSelect = { raw -> onUpdate { it.copy(seriesCover = raw) } },
                    icon = Icons.Outlined.Book,
                )
            }
            SettingsSection(title = stringResource(R.string.server_admin_library_section_import)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_import_comic_info),
                    checked = data.importComicInfoBook,
                    onCheckedChange = { value -> onUpdate { it.copy(importComicInfoBook = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_import_comic_info_series),
                    checked = data.importComicInfoSeries,
                    onCheckedChange = { value -> onUpdate { it.copy(importComicInfoSeries = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_import_comic_info_append_volume),
                    checked = data.importComicInfoSeriesAppendVolume,
                    onCheckedChange = { value -> onUpdate { it.copy(importComicInfoSeriesAppendVolume = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_import_comic_info_collection),
                    checked = data.importComicInfoCollection,
                    onCheckedChange = { value -> onUpdate { it.copy(importComicInfoCollection = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_import_comic_info_readlist),
                    checked = data.importComicInfoReadList,
                    onCheckedChange = { value -> onUpdate { it.copy(importComicInfoReadList = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_import_epub_book),
                    checked = data.importEpubBook,
                    onCheckedChange = { value -> onUpdate { it.copy(importEpubBook = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_import_epub_series),
                    checked = data.importEpubSeries,
                    onCheckedChange = { value -> onUpdate { it.copy(importEpubSeries = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_import_mylar_series),
                    checked = data.importMylarSeries,
                    onCheckedChange = { value -> onUpdate { it.copy(importMylarSeries = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_import_local_artwork),
                    checked = data.importLocalArtwork,
                    onCheckedChange = { value -> onUpdate { it.copy(importLocalArtwork = value) } },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.server_admin_library_import_barcode_isbn),
                    checked = data.importBarcodeIsbn,
                    onCheckedChange = { value -> onUpdate { it.copy(importBarcodeIsbn = value) } },
                )
            }
        }
    }

    if (browse != null) {
        DirectoryBrowseDialog(
            browse = browse,
            onOpen = onBrowse,
            onDismiss = onCloseBrowse,
            onPick = { path ->
                onUpdate { it.copy(root = path) }
                onCloseBrowse()
            },
        )
    }
}

@Composable
private fun DirectoryBrowseDialog(
    browse: DirectoryBrowseState,
    onOpen: (String?) -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_admin_library_browse_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = browse.path?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.server_admin_library_browse_root),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val listing = browse.listing
                when {
                    browse.loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    listing == null -> Text(stringResource(R.string.server_admin_action_failed))
                    listing.directories.isEmpty() ->
                        Text(stringResource(R.string.server_admin_library_browse_empty))
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        listing.parent?.let { parent ->
                            items(listOf(".." to parent)) { (label, path) ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpen(path) }
                                        .padding(vertical = 8.dp),
                                )
                            }
                        }
                        items(listing.directories, key = { it.path }) { dir ->
                            Text(
                                text = dir.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpen(dir.path) }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !browse.path.isNullOrBlank(),
                onClick = { browse.path?.takeIf { it.isNotBlank() }?.let(onPick) },
            ) { Text(stringResource(R.string.server_admin_library_browse_pick)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
