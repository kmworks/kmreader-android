package com.kmworks.kmreader.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import java.util.Locale
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
    val displayLocale = context.resources.configuration.locales[0]
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
    if (ageRatings.isNotEmpty()) add(ActiveFilter(ageRatings.joinToString(" ∨ ") { "$it+" }, negative = false))
    if (languages.isNotEmpty()) {
        add(ActiveFilter(languages.joinToString(" ∨ ") { languageDisplayName(it, displayLocale) }, negative = false))
    }
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
 * Filter & sort panel, laid out like the iOS BookBrowseOptionsSheet/SeriesBrowseOptionsSheet Form:
 * a full-height sheet with rounded section cards (Sort, Read Status, Series Status, Flags,
 * Metadata), trailing state glyphs instead of chip walls, and a fixed Save/Done bar.
 * The suggestion* parameters scope the Referential candidate values (empty lists hide
 * the suggestions).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // Follow the app's own locale (per-app language), not just the system default.
    val displayLocale = context.resources.configuration.locales[0]
    // Row order mirrors the iOS MetadataFilterSection: publisher, authors, genres, tags,
    // notable entities, languages, age ratings and release years.
    val metadataDimensions = mutableListOf<MetaDimension>()
    if (showSeriesFilters) {
        metadataDimensions += MetaDimension(
            stringResource(R.string.filter_publisher),
            options.publishers,
            null,
            "publishers",
        )
    }
    metadataDimensions += MetaDimension(
        stringResource(R.string.common_author),
        options.authors,
        if (options.authorLogicAnd) "∧" else "∨",
        "authors",
    )
    if (showSeriesFilters) {
        metadataDimensions += MetaDimension(
            stringResource(R.string.filter_genre),
            options.genres,
            null,
            "genres",
        )
    }
    metadataDimensions += MetaDimension(
        stringResource(R.string.common_tag),
        options.tags,
        if (options.tagLogicAnd) "∧" else "∨",
        "tags",
    )
    metadataDimensions += MetaDimension(
        stringResource(R.string.filter_notable_entities),
        options.notableEntities.map { "${it.kindLabel(context)} · ${it.name}" },
        if (options.notableEntitiesAllMatch) "∧" else "∨",
        "notableEntities",
    )
    if (showSeriesFilters) {
        metadataDimensions += MetaDimension(
            stringResource(R.string.filter_language),
            options.languages.map { languageDisplayName(it, displayLocale) },
            null,
            "languages",
        )
        metadataDimensions += MetaDimension(
            stringResource(R.string.filter_age_rating),
            options.ageRatings.map { "$it+" },
            null,
            "ageRatings",
        )
        metadataDimensions += MetaDimension(
            stringResource(R.string.filter_release_year),
            options.releaseYears,
            null,
            "releaseYears",
        )
    }

    AppModalDialog(onDismissRequest = onDismiss, fullHeight = true) {
        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            // Fixed header: title + reset + close (mirrors the iOS sheet navigation bar)
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

            // iOS Form body: one rounded card per dimension group, with the group label above it.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                item {
                    FilterSection(stringResource(R.string.filter_sort)) {
                        SortFieldRow(
                            selectedField = options.sortField,
                            onSelect = { field -> onOptionsChange(options.copy(sortField = field)) },
                        )
                        FilterRowDivider()
                        SortDirectionRow(
                            ascending = options.sortAscending,
                            onChange = { ascending -> onOptionsChange(options.copy(sortAscending = ascending)) },
                        )
                    }
                }

                item {
                    FilterSection(stringResource(R.string.filter_read_status)) {
                        ReadStatus.entries.forEachIndexed { index, status ->
                            if (index > 0) FilterRowDivider()
                            FilterTriStateRow(
                                label = stringResource(status.labelRes),
                                state = options.readStatuses[status] ?: TriState.Off,
                                onCycle = {
                                    // Mirrors the original applyReadStatusToggle: includes are mutually exclusive, excludes may coexist with includes
                                    onOptionsChange(
                                        options.copy(
                                            readStatuses = options.readStatuses.applyReadStatusToggle(
                                                status,
                                                (options.readStatuses[status] ?: TriState.Off).next,
                                            ),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                if (showSeriesFilters) {
                    item {
                        FilterSection(stringResource(R.string.filter_series_status)) {
                            SeriesStatus.entries.forEachIndexed { index, status ->
                                if (index > 0) FilterRowDivider()
                                FilterSelectionRow(
                                    label = stringResource(status.labelRes),
                                    selected = status in options.seriesStatuses,
                                    onToggle = {
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
                                )
                            }
                        }
                    }
                }

                item {
                    FilterSection(stringResource(R.string.filter_flags)) {
                        FilterTriStateRow(
                            label = stringResource(R.string.filter_oneshot),
                            state = options.oneshot,
                            onCycle = { onOptionsChange(options.copy(oneshot = options.oneshot.next)) },
                        )
                        FilterRowDivider()
                        FilterTriStateRow(
                            label = stringResource(R.string.filter_deleted),
                            state = options.deleted,
                            onCycle = { onOptionsChange(options.copy(deleted = options.deleted.next)) },
                        )
                    }
                }

                item {
                    FilterSection(stringResource(R.string.filter_metadata)) {
                        metadataDimensions.forEachIndexed { index, dimension ->
                            if (index > 0) FilterRowDivider()
                            MetaFilterRow(
                                title = dimension.title,
                                values = dimension.values,
                                logicSymbol = dimension.logicSymbol,
                                onClick = { pickerDimId = dimension.id },
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
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
                    Spacer(Modifier.width(8.dp))
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
            displayLabel = when {
                isNotableEntities -> { key ->
                    notableEntityFromFilterKey(key)
                        ?.let { "${it.kindLabel(context)} · ${it.name}" }
                        ?: key
                }
                id == "ageRatings" -> { value -> "$value+" }
                id == "languages" -> { value -> languageDisplayName(value, displayLocale) }
                else -> null
            },
            onDismiss = { pickerDimId = null },
        )
    }
}

/** One labeled group of rows: the iOS inset-grouped Form section on the modal's surface. */
@Composable
private fun FilterSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            // One step above the dialog surface, otherwise the card would vanish into it.
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/** Inset separator between the rows of a [FilterSection] (iOS keeps the left text gutter). */
@Composable
private fun FilterRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
    )
}

/** Sort-field menu row (iOS Form menu Picker): label left, current value + chevron right. */
@Composable
private fun SortFieldRow(selectedField: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.filter_sort_by),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // Anchored to the trailing value so the menu drops from the control, like the iOS picker.
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = bookSortFieldLabel(selectedField),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 200.dp),
            ) {
                BookSortFields.forEach { field ->
                    DropdownMenuItem(
                        text = { Text(bookSortFieldLabel(field)) },
                        onClick = {
                            expanded = false
                            onSelect(field)
                        },
                        trailingIcon = {
                            if (field == selectedField) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Direction picker (iOS segmented Picker): arrows + Ascending/Descending across the row. */
@Composable
private fun SortDirectionRow(ascending: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = ascending,
                onClick = { onChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            ) { Text(stringResource(R.string.filter_ascending)) }
            SegmentedButton(
                selected = !ascending,
                onClick = { onChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            ) { Text(stringResource(R.string.filter_descending)) }
        }
    }
}

/** Tri-state row: tapping cycles off → include → exclude; the trailing glyph carries the state (iOS). */
@Composable
private fun FilterTriStateRow(label: String, state: TriState, onCycle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCycle)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        FilterStateIcon(state)
    }
}

/** Multi-select row (series status): a check confirms the value, an empty circle leaves it off. */
@Composable
private fun FilterSelectionRow(label: String, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        FilterStateIcon(if (selected) TriState.Include else TriState.Off)
    }
}

/** Include/exclude/off glyph (mirrors iOS TriStateSelectionIcon). */
@Composable
private fun FilterStateIcon(state: TriState) {
    Icon(
        imageVector = when (state) {
            TriState.Off -> Icons.Outlined.RadioButtonUnchecked
            TriState.Include -> Icons.Filled.CheckCircle
            TriState.Exclude -> Icons.Filled.Cancel
        },
        contentDescription = when (state) {
            TriState.Off -> stringResource(R.string.common_off)
            TriState.Include -> stringResource(R.string.filter_include)
            TriState.Exclude -> stringResource(R.string.filter_exclude)
        },
        tint = when (state) {
            TriState.Off -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            TriState.Include -> MaterialTheme.colorScheme.onSurface
            TriState.Exclude -> MaterialTheme.colorScheme.error
        },
        modifier = Modifier.size(22.dp),
    )
}

/** Metadata dimension row: title + selected summary (∧/∨ joined, single line) + chevron; opens the multi-select page. */
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        if (values.isNotEmpty()) {
            Text(
                text = values.joinToString(" ${logicSymbol ?: "∨"} "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One metadata row of the filter panel and the picker page it opens. */
private data class MetaDimension(
    val title: String,
    val values: List<String>,
    val logicSymbol: String?,
    val id: String,
)

/** Localized display name for a language token (mirrors iOS LanguageCodeHelper). */
private fun languageDisplayName(code: String, displayLocale: Locale): String {
    if (code.isBlank()) return code
    val locale = Locale.forLanguageTag(code)
    if (locale.language.isEmpty()) return code
    return locale.getDisplayLanguage(displayLocale).ifBlank { code }
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
    // Value key → display text (age ratings "+", language names, and kind␟name as "kindLabel · name")
    displayLabel: ((String) -> String)? = null,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // A metadata dimension can run long; fill the dialog like the iOS pushed page instead of capping it at a square.
    AppModalDialog(onDismissRequest = onDismiss, fullHeight = true) {
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
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
            if (logicAnd != null && onToggleLogic != null) {
                // iOS segmented logic picker (All / Any) instead of two loose chips.
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    SegmentedButton(
                        selected = logicAnd,
                        onClick = { if (!logicAnd) onToggleLogic() },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.filter_match_all)) }
                    SegmentedButton(
                        selected = !logicAnd,
                        onClick = { if (logicAnd) onToggleLogic() },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.filter_match_any)) }
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
                shape = RoundedCornerShape(12.dp),
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
                // Grouped list card, matching the form cards of the filter panel and the iOS grouped List.
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(shown, key = { _, item -> item }) { index, item ->
                            if (index > 0) FilterRowDivider()
                            val isSelected = item in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectedChange(
                                            if (isSelected) selected - item else selected + item,
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .heightIn(min = 24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    displayLabel?.invoke(item) ?: item,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = stringResource(R.string.filter_selected),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
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
