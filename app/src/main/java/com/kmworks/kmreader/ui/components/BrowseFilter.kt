package com.kmworks.kmreader.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.data.ActiveFilter
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.BookSortFields
import com.kmworks.kmreader.data.ReadStatus
import com.kmworks.kmreader.data.SavedFilter
import com.kmworks.kmreader.data.SeriesStatus
import com.kmworks.kmreader.data.TriState
import com.kmworks.kmreader.data.filterKey
import com.kmworks.kmreader.data.notableEntityFromFilterKey
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/** Layout mode (mirrors BrowseLayoutMode: list/grid/largeGrid), cycled. */
enum class BrowseLayout(@param:StringRes val labelRes: Int) {
    List(R.string.layout_list), Grid(R.string.layout_grid), LargeGrid(R.string.layout_large_grid);

    val next: BrowseLayout
        get() = when (this) {
            List -> Grid
            Grid -> LargeGrid
            LargeGrid -> List
        }
}

/**
 * Per-page persisted layout state (mirrors iOS per-page layout memory; stored in
 * AppSettingsStore.browseLayoutOverrides).
 * Returns the current layout and a setter; writes apply locally first and stay
 * consistent once persistence flows back.
 */
@Composable
fun rememberPersistentBrowseLayout(
    page: String,
    default: BrowseLayout,
): Pair<BrowseLayout, (BrowseLayout) -> Unit> {
    val context = LocalContext.current
    val appSettings = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ImageLoaderEntryPoint::class.java,
        ).appSettingsStore()
    }
    val scope = rememberCoroutineScope()
    // The flow factory must be remembered, otherwise every recomposition rebuilds the flow and restarts collection.
    val layoutFlow = remember(page) { appSettings.browseLayout(page) }
    // Seed with AppSettingsStore's warm snapshot: a null initial would paint the page default for a
    // frame (or a few, while the DataStore read lands) and only then jump to the stored layout —
    // which re-flows the grid and resizes every card right after the screen appears.
    val initialLayout = remember(page) { appSettings.browseLayoutName(page) }
    val stored by layoutFlow.collectAsState(initial = initialLayout)
    // This round's immediate override (instant feedback before the persisted write flows back)
    var local by remember(page) { mutableStateOf<BrowseLayout?>(null) }
    val value = local
        ?: stored?.let { name -> BrowseLayout.entries.firstOrNull { it.name == name } }
        ?: default
    return value to { next ->
        local = next
        scope.launch { appSettings.setBrowseLayout(page, next.name) }
    }
}

/**
 * Filter chip flow (mirrors the horizontal chip row of the original BookFilterView):
 * [layout] [presets] [sort] + active-condition chips; tapping any chip opens the full panel.
 */
@Composable
fun BrowseFilterChipFlow(
    options: BookBrowseOptions,
    layout: BrowseLayout,
    onLayoutChange: (BrowseLayout) -> Unit,
    onOpenOptions: () -> Unit,
    onApplyPreset: (SavedFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var presetsOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Layout toggle
        AssistChip(
            onClick = { onLayoutChange(layout.next) },
            label = { Text(stringResource(layout.labelRes)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
        )
        // Filter presets (the original savedFilter, persisted in FilterPresetStore, reused across pages)
        val browsePresets = rememberBrowseFilterPresets()
        Column {
            AssistChip(
                onClick = { presetsOpen = true },
                label = { Text(stringResource(R.string.filter_presets)) },
                leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
            )
            DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                if (browsePresets.presets.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.filter_no_presets)) },
                        enabled = false,
                        onClick = {},
                    )
                }
                browsePresets.presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name) },
                        trailingIcon = {
                            IconButton(onClick = { browsePresets.delete(preset.name) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.filter_delete_preset),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        onClick = {
                            presetsOpen = false
                            onApplyPreset(preset)
                        },
                    )
                }
            }
        }
        // Current sort (tap to open the full panel)
        AssistChip(
            onClick = onOpenOptions,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bookSortFieldLabel(options.sortField))
                    Icon(
                        if (options.sortAscending) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
            },
        )
        // Active-condition chips (negative = excluded, rendered in the error-tone variant)
        options.activeFilterLabels().forEach { filter ->
            if (filter.negative) {
                NegativeFilterChip(label = filter.label, onClick = onOpenOptions)
            } else {
                FilterChip(selected = true, onClick = onOpenOptions, label = { Text(filter.label) })
            }
        }
    }
}

/**
 * Localized active-filter chip labels (the original BookBrowseOptions.activeFilters lived
 * in the data layer with hardcoded Chinese; label resolution moved to the composable
 * call site so labels follow string resources).
 */
@Composable
fun BookBrowseOptions.activeFilterLabels(): List<ActiveFilter> {
    val context = LocalContext.current
    return buildList {
    readStatuses.forEach { (status, state) ->
        when (state) {
            TriState.Include -> add(ActiveFilter(stringResource(status.labelRes), negative = false))
            TriState.Exclude -> add(ActiveFilter(stringResource(status.labelRes), negative = true))
            TriState.Off -> Unit
        }
    }
    if (oneshot != TriState.Off) {
        add(ActiveFilter(stringResource(R.string.filter_oneshot), oneshot == TriState.Exclude))
    }
    // deleted is excluded by default (like the original); only an explicit "include deleted" counts as an active filter
    if (deleted == TriState.Include) {
        add(ActiveFilter(stringResource(R.string.filter_deleted), negative = false))
    }
    if (authors.isNotEmpty()) {
        add(ActiveFilter(authors.joinToString(if (authorLogicAnd) " ∧ " else " ∨ "), negative = false))
    }
    if (tags.isNotEmpty()) {
        add(ActiveFilter(tags.joinToString(if (tagLogicAnd) " ∧ " else " ∨ "), negative = false))
    }
    if (notableEntities.isNotEmpty()) {
        add(
            ActiveFilter(
                stringResource(
                    R.string.notable_active_filter,
                    notableEntities.joinToString(
                        if (notableEntitiesAllMatch) " ∧ " else " ∨ ",
                    ) { "${it.kindLabel(context)} · ${it.name}" },
                ),
                negative = false,
            ),
        )
    }
    if (publishers.isNotEmpty()) add(ActiveFilter(publishers.joinToString(" ∨ "), negative = false))
    if (genres.isNotEmpty()) add(ActiveFilter(genres.joinToString(" ∨ "), negative = false))
    if (ageRatings.isNotEmpty()) add(ActiveFilter(ageRatings.joinToString(" ∨ "), negative = false))
    if (languages.isNotEmpty()) add(ActiveFilter(languages.joinToString(" ∨ "), negative = false))
    if (releaseYears.isNotEmpty()) add(ActiveFilter(releaseYears.joinToString(" ∨ "), negative = false))
    if (seriesStatuses.isNotEmpty()) {
        val statusLabels = seriesStatuses.map { context.getString(it.labelRes) }
        add(
            ActiveFilter(
                statusLabels.joinToString(" ∨ "),
                negative = false,
            ),
        )
    }
    }
}

/** Excluded-state chip: error tone (mirrors the original .negative variant). */
@Composable
private fun NegativeFilterChip(label: String, onClick: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onClick,
        label = { Text(stringResource(R.string.filter_excluded, label)) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.error,
            selectedLabelColor = MaterialTheme.colorScheme.onError,
        ),
    )
}

/**
 * Filter & sort panel (mirrors the original BookBrowseOptionsSheet):
 * sort field + direction; tri-state read status; one-shot/deleted flags; author/tag
 * multi-select with ∧/∨ logic; save as preset; done.
 * The suggestion* parameters scope the Referential candidate values (empty lists hide
 * the suggestions).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseOptionsSheet(
    initialOptions: BookBrowseOptions,
    onConfirm: (BookBrowseOptions) -> Unit,
    onDismiss: () -> Unit,
    // Series-only metadata dimensions (mirrors the original SeriesSearch condition groups; only the series browse page passes true)
    showSeriesFilters: Boolean = false,
    // Scope of metadata suggestion values (mirrors the original MetadataFilterItemSource; fetched per scope when the sheet opens)
    suggestionLibraryIds: List<String>? = null,
    suggestionSeriesStableId: Int? = null,
    suggestionCollectionStableId: Int? = null,
    suggestionReadListStableId: Int? = null,
) {
    // The sheet edits a draft: a condition is queried only once the user confirms with Done, so
    // ticking several dimensions does not fire one request per tap, and closing without confirming
    // leaves the list exactly as it was (mirrors the original BrowseFilterSheet).
    var options by remember { mutableStateOf(initialOptions) }
    val onOptionsChange: (BookBrowseOptions) -> Unit = { updated -> options = updated }
    val browseSuggestions = rememberBrowseFilterSuggestions(
        libraryIds = suggestionLibraryIds,
        seriesStableId = suggestionSeriesStableId,
        collectionStableId = suggestionCollectionStableId,
        readListStableId = suggestionReadListStableId,
    )
    val authorSuggestions = browseSuggestions.authors
    val tagSuggestions = browseSuggestions.tags
    val publisherSuggestions = browseSuggestions.publishers
    val genreSuggestions = browseSuggestions.genres
    val ageRatingSuggestions = browseSuggestions.ageRatings
    val languageSuggestions = browseSuggestions.languages
    val releaseYearSuggestions = browseSuggestions.releaseYears
    val notableEntitySuggestions = browseSuggestions.notableEntities
    var showSaveDialog by remember { mutableStateOf(false) }
    // Currently open full multi-select metadata dimension (mirrors the original MetadataMultiSelectLoader navigation page)
    var pickerDimId by remember { mutableStateOf<String?>(null) }
    val browsePresets = rememberBrowseFilterPresets()
    val context = LocalContext.current
    AppModalDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            // Fixed header: title + reset + close (mirrors SheetView title/onReset)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.filter_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOptionsChange(BookBrowseOptions()) }) {
                    Text(stringResource(R.string.common_reset), color = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            val activeFilterLabels = options.activeFilterLabels()
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                // Selected-condition preview (mirrors the selected chips at the top of the original optionsSection)
                if (activeFilterLabels.isNotEmpty()) {
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            activeFilterLabels.forEach { filter ->
                                if (filter.negative) {
                                    NegativeFilterChip(label = filter.label, onClick = {})
                                } else {
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text(filter.label) },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    SheetSectionTitle(stringResource(R.string.filter_sort))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BookSortFields.forEach { field ->
                            FilterChip(
                                selected = options.sortField == field,
                                onClick = { onOptionsChange(options.copy(sortField = field)) },
                                label = { Text(bookSortFieldLabel(field)) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = options.sortAscending,
                            onClick = { onOptionsChange(options.copy(sortAscending = true)) },
                            label = { Text(stringResource(R.string.filter_ascending)) },
                        )
                        FilterChip(
                            selected = !options.sortAscending,
                            onClick = { onOptionsChange(options.copy(sortAscending = false)) },
                            label = { Text(stringResource(R.string.filter_descending)) },
                        )
                    }
                }

                item {
                    SheetSectionTitle(stringResource(R.string.filter_read_status))
                    ReadStatus.entries.forEach { status ->
                        val state = options.readStatuses[status] ?: TriState.Off
                        TriStateRow(
                            label = stringResource(status.labelRes),
                            state = state,
                            onCycle = {
                                onOptionsChange(
                                    // Mirrors the original applyReadStatusToggle: includes are mutually exclusive, excludes may coexist with includes
                                    options.copy(readStatuses = options.readStatuses.applyReadStatusToggle(status, state.next))
                                )
                            },
                        )
                    }
                }

                item {
                    SheetSectionTitle(stringResource(R.string.filter_flags))
                    TriStateRow(
                        label = stringResource(R.string.filter_oneshot),
                        state = options.oneshot,
                        onCycle = { onOptionsChange(options.copy(oneshot = options.oneshot.next)) },
                    )
                    TriStateRow(
                        label = stringResource(R.string.filter_deleted),
                        state = options.deleted,
                        onCycle = { onOptionsChange(options.copy(deleted = options.deleted.next)) },
                    )
                }

                if (showSeriesFilters) {
                    item {
                        SheetSectionTitle(stringResource(R.string.filter_series_status))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SeriesStatus.entries.forEach { status ->
                                FilterChip(
                                    selected = status in options.seriesStatuses,
                                    onClick = {
                                        onOptionsChange(
                                            options.copy(
                                                seriesStatuses = if (status in options.seriesStatuses) {
                                                    options.seriesStatuses - status
                                                } else {
                                                    options.seriesStatuses + status
                                                },
                                            ),
                                        )
                                    },
                                    label = { Text(stringResource(status.labelRes)) },
                                )
                            }
                        }
                    }
                    item {
                        MetaFilterRow(
                            title = stringResource(R.string.filter_publisher),
                            values = options.publishers,
                            logicSymbol = null,
                            onClick = { pickerDimId = "publishers" },
                        )
                    }
                    item {
                        MetaFilterRow(
                            title = stringResource(R.string.filter_genre),
                            values = options.genres,
                            logicSymbol = null,
                            onClick = { pickerDimId = "genres" },
                        )
                    }
                    item {
                        MetaFilterRow(
                            title = stringResource(R.string.filter_age_rating),
                            values = options.ageRatings,
                            logicSymbol = null,
                            onClick = { pickerDimId = "ageRatings" },
                        )
                    }
                    item {
                        MetaFilterRow(
                            title = stringResource(R.string.filter_language),
                            values = options.languages,
                            logicSymbol = null,
                            onClick = { pickerDimId = "languages" },
                        )
                    }
                    item {
                        MetaFilterRow(
                            title = stringResource(R.string.filter_release_year),
                            values = options.releaseYears,
                            logicSymbol = null,
                            onClick = { pickerDimId = "releaseYears" },
                        )
                    }
                }

                item {
                    SheetSectionTitle(stringResource(R.string.common_author))
                    MetaFilterRow(
                        title = stringResource(R.string.common_author),
                        values = options.authors,
                        logicSymbol = if (options.authorLogicAnd) "∧" else "∨",
                        onClick = { pickerDimId = "authors" },
                    )
                }

                item {
                    SheetSectionTitle(stringResource(R.string.common_tag))
                    MetaFilterRow(
                        title = stringResource(R.string.common_tag),
                        values = options.tags,
                        logicSymbol = if (options.tagLogicAnd) "∧" else "∨",
                        onClick = { pickerDimId = "tags" },
                    )
                }

                item {
                    SheetSectionTitle(stringResource(R.string.filter_notable_entities))
                    MetaFilterRow(
                        title = stringResource(R.string.filter_notable_entities),
                        values = options.notableEntities.map { "${it.kindLabel(context)} · ${it.name}" },
                        logicSymbol = if (options.notableEntitiesAllMatch) "∧" else "∨",
                        onClick = { pickerDimId = "notableEntities" },
                    )
                }
            }

            HorizontalDivider()
            // Fixed bottom button bar (mirrors the original controls: Save Filter + Done)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                OutlinedButton(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text(stringResource(R.string.filter_save_preset))
                }
                Button(
                    onClick = { onConfirm(options); onDismiss() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.common_done)) }
            }
        }
    }

    if (showSaveDialog) {
        SaveFilterDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                showSaveDialog = false
                browsePresets.save(name, options)
            },
        )
    }

    // Full multi-select metadata page (overlays the filter panel; selections write back to options live)
    pickerDimId?.let { id ->
        // The notable-entities dimension uses filterKey (kind␟name) as the value key internally; the display layer renders "kindLabel · name"
        val isNotableEntities = id == "notableEntities"
        MetadataPickerSheet(
            title = when (id) {
                "authors" -> stringResource(R.string.common_author)
                "tags" -> stringResource(R.string.common_tag)
                "publishers" -> stringResource(R.string.filter_publisher)
                "genres" -> stringResource(R.string.filter_genre)
                "ageRatings" -> stringResource(R.string.filter_age_rating)
                "languages" -> stringResource(R.string.filter_language)
                "notableEntities" -> stringResource(R.string.filter_notable_entities)
                else -> stringResource(R.string.filter_release_year)
            },
            allItems = when (id) {
                "authors" -> authorSuggestions
                "tags" -> tagSuggestions
                "publishers" -> publisherSuggestions
                "genres" -> genreSuggestions
                "ageRatings" -> ageRatingSuggestions
                "languages" -> languageSuggestions
                "notableEntities" -> notableEntitySuggestions.map { it.filterKey }
                else -> releaseYearSuggestions
            },
            selected = when (id) {
                "authors" -> options.authors
                "tags" -> options.tags
                "publishers" -> options.publishers
                "genres" -> options.genres
                "ageRatings" -> options.ageRatings
                "languages" -> options.languages
                "notableEntities" -> options.notableEntities.map { it.filterKey }
                else -> options.releaseYears
            },
            onSelectedChange = { next ->
                onOptionsChange(
                    when (id) {
                        "authors" -> options.copy(authors = next)
                        "tags" -> options.copy(tags = next)
                        "publishers" -> options.copy(publishers = next)
                        "genres" -> options.copy(genres = next)
                        "ageRatings" -> options.copy(ageRatings = next)
                        "languages" -> options.copy(languages = next)
                        "notableEntities" -> options.copy(
                            notableEntities = next.mapNotNull { notableEntityFromFilterKey(it) },
                        )
                        else -> options.copy(releaseYears = next)
                    },
                )
            },
            logicAnd = when (id) {
                "authors" -> options.authorLogicAnd
                "tags" -> options.tagLogicAnd
                "notableEntities" -> options.notableEntitiesAllMatch
                else -> null
            },
            onToggleLogic = when (id) {
                "authors" -> {
                    { onOptionsChange(options.copy(authorLogicAnd = !options.authorLogicAnd)) }
                }
                "tags" -> {
                    { onOptionsChange(options.copy(tagLogicAnd = !options.tagLogicAnd)) }
                }
                "notableEntities" -> {
                    {
                        onOptionsChange(
                            options.copy(notableEntitiesAllMatch = !options.notableEntitiesAllMatch),
                        )
                    }
                }
                else -> null
            },
            displayLabel = if (isNotableEntities) {
                { key ->
                    notableEntityFromFilterKey(key)
                        ?.let { "${it.kindLabel(context)} · ${it.name}" }
                        ?: key
                }
            } else {
                null
            },
            onDismiss = { pickerDimId = null },
        )
    }
}

@Composable
private fun SheetSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** Tri-state row: the trailing button cycles off → ✓ include → ✕ exclude. */
@Composable
private fun TriStateRow(label: String, state: TriState, onCycle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = when (state) {
                TriState.Off -> MaterialTheme.colorScheme.onSurface
                TriState.Include -> MaterialTheme.colorScheme.onSurface
                TriState.Exclude -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = state != TriState.Off,
            onClick = onCycle,
            label = {
                when (state) {
                    TriState.Off -> Text(stringResource(R.string.common_off))
                    TriState.Include -> Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.filter_include))
                    TriState.Exclude -> Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.filter_exclude))
                }
            },
        )
    }
}

/** Metadata dimension row: title + selected summary (∨/∧ joined, single-line truncated) + trailing arrow; tapping opens the full multi-select page
 * (mirrors the NavigationLink row of the original MetadataFilterSection). */
@Composable
private fun MetaFilterRow(
    title: String,
    values: List<String>,
    logicSymbol: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.padding(6.dp))
        if (values.isNotEmpty()) {
            Text(
                values.joinToString(" ${logicSymbol ?: "∨"} "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Full multi-select metadata page (mirrors the original MetadataMultiSelectLoader/MultiSelectList):
 * fixed header (reset + title + close) + logic toggle (∧ all / ∨ any, optional) + search field + full candidate list (check marks).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetadataPickerSheet(
    title: String,
    allItems: List<String>,
    selected: List<String>,
    onSelectedChange: (List<String>) -> Unit,
    logicAnd: Boolean?,
    onToggleLogic: (() -> Unit)?,
    // Value key → display text (the notable-entities dimension renders kind␟name as "kindLabel · name")
    displayLabel: (@Composable (String) -> String)? = null,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AppModalDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onSelectedChange(emptyList()) },
                    enabled = selected.isNotEmpty(),
                ) { Text(stringResource(R.string.common_reset)) }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_done))
                }
            }
            if (logicAnd != null && onToggleLogic != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    FilterChip(
                        selected = logicAnd,
                        onClick = { if (!logicAnd) onToggleLogic() },
                        label = { Text(stringResource(R.string.filter_match_all)) },
                    )
                    FilterChip(
                        selected = !logicAnd,
                        onClick = { if (logicAnd) onToggleLogic() },
                        label = { Text(stringResource(R.string.filter_match_any)) },
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.filter_search_hint)) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
            val shown = if (query.isBlank()) {
                allItems
            } else {
                allItems.filter {
                    (displayLabel?.invoke(it) ?: it).contains(query.trim(), ignoreCase = true)
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (allItems.isEmpty()) stringResource(R.string.filter_no_values) else stringResource(R.string.filter_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(shown, key = { it }) { item ->
                        val isSelected = item in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectedChange(
                                        if (isSelected) selected - item else selected + item,
                                    )
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                displayLabel?.invoke(item) ?: item,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.filter_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Save as preset (mirrors SaveFilterSheet). */
@Composable
private fun SaveFilterDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filter_save_dialog_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.filter_preset_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Mutual-exclusion rule (mirrors KMReader.applyReadStatusToggle): Include is exclusive, Exclude can coexist. */
private fun Map<ReadStatus, TriState>.applyReadStatusToggle(
    status: ReadStatus,
    next: TriState,
): Map<ReadStatus, TriState> {
    val result = this.toMutableMap()
    result[status] = next
    if (next == TriState.Include) {
        ReadStatus.entries.forEach { other ->
            if (other != status && result[other] == TriState.Include) {
                result[other] = TriState.Off
            }
        }
    }
    return result
}
