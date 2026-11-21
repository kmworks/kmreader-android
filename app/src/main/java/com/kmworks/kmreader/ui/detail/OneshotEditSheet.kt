package com.kmworks.kmreader.ui.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookDetailMeta
import com.kmworks.kmreader.data.BookMetadataLocks
import com.kmworks.kmreader.data.Creator
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.ui.components.AppModalDialog
import com.kmworks.kmreader.ui.components.LockToggle
import com.kmworks.kmreader.ui.components.LockableChoiceRow
import com.kmworks.kmreader.ui.components.LockableTextField
import com.kmworks.kmreader.ui.components.NotableEntitiesContent
import com.kmworks.kmreader.ui.components.rememberLockedField
import com.kmworks.kmreader.ui.components.roleLabelText
import androidx.compose.ui.res.stringResource
import com.kmworks.kmreader.R

private enum class OneshotEditTab(@param:StringRes val labelRes: Int) {
    General(R.string.edit_tab_general),
    Authors(R.string.edit_tab_authors),
    Tags(R.string.edit_tab_tags),
    Links(R.string.edit_tab_links),
    Entities(R.string.edit_tab_entities),
    Sharing(R.string.edit_tab_sharing),
}

/** Fallback when the series-side initial values are missing (no session / series fetch failed): fields stay empty and unchanged fields are not submitted. */
private fun fallbackSeriesValues(seriesTitle: String) = SeriesEditValues(
    title = seriesTitle,
    titleSort = seriesTitle,
    summary = "",
    status = "ONGOING",
    readingDirection = "LEFT_TO_RIGHT",
    language = "",
    publisher = "",
    ageRating = "",
    totalBookCount = "",
    genres = emptyList(),
    tags = emptyList(),
    links = emptyList(),
    alternateTitles = emptyList(),
    sharingLabels = emptyList(),
)

/**
 * Merged one-shot edit sheet, mirroring KMReader/Features/OneShot/OneshotEditSheet.swift:
 * five segmented tabs (general/authors/tags/links/sharing labels) that edit series and book fields together;
 * the save callback produces both the book draft and the series update body (each holds only changed fields, null when nothing changed),
 * and the ViewModel saves the book before the series (mirroring the iOS saveChanges order).
 *
 * Field ownership (mirrors iOS):
 * - Book: title/summary/release date/ISBN, authors, tags, links;
 * - Series: sort title/publisher/age rating, reading direction/language, genres, sharing labels.
 *
 * Lock state is UI state: initial = the server DTO's xxxLock, editing content auto-sets the lock, and the lock icon can be toggled manually (including unlocking);
 * on save, the current value is explicitly submitted only when the lock state differs from the initial value (in either direction); untouched locks are not sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneshotEditSheet(
    book: Book,
    meta: BookDetailMeta?,
    seriesValues: SeriesEditValues?,
    seriesTitleFallback: String,
    saving: Boolean = false,
    onSave: (BookMetadataDraft, SeriesMetadataUpdateDto?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val initial = remember(seriesValues, seriesTitleFallback) {
        seriesValues ?: fallbackSeriesValues(seriesTitleFallback)
    }
    val bookLocks = remember(meta) { meta?.locks ?: BookMetadataLocks() }
    val seriesLocks = remember(initial) { initial.locks }
    var tab by rememberSaveable { mutableStateOf(OneshotEditTab.General) }

    // Book fields (mirrors the iOS bookMetadataUpdate): initial lock state = server lock state, editing auto-sets the lock
    val bookTitle = rememberLockedField(book.title, bookLocks.titleLock)
    val bookSummary = rememberLockedField(meta?.summary.orEmpty(), bookLocks.summaryLock)
    val releaseDate = rememberLockedField(meta?.releaseDate.orEmpty(), bookLocks.releaseDateLock)
    val isbn = rememberLockedField(meta?.isbn.orEmpty(), bookLocks.isbnLock)
    val authors = remember { mutableStateListOf<Creator>().apply { addAll(meta?.authors.orEmpty()) } }
    var authorsLocked by remember(meta) { mutableStateOf(bookLocks.authorsLock) }
    val bookTags = remember { mutableStateListOf<String>().apply { addAll(meta?.tags.orEmpty()) } }
    var bookTagsLocked by remember(meta) { mutableStateOf(bookLocks.tagsLock) }
    val links = remember {
        mutableStateListOf<Pair<String, String>>().apply { addAll(meta?.links.orEmpty()) }
    }
    var linksLocked by remember(meta) { mutableStateOf(bookLocks.linksLock) }
    // Notable entities (book side, mirrors the iOS NotableEntitiesEditor: add/remove auto-locks)
    val notableEntities = remember {
        mutableStateListOf<NotableEntity>().apply { addAll(meta?.notableEntities.orEmpty()) }
    }
    var notableEntitiesLocked by remember(meta) { mutableStateOf(bookLocks.notableEntitiesLock) }

    // Series fields (mirrors the iOS seriesMetadataUpdate)
    val titleSort = rememberLockedField(initial.titleSort, seriesLocks.titleSortLock)
    val publisher = rememberLockedField(initial.publisher, seriesLocks.publisherLock)
    val ageRating = rememberLockedField(initial.ageRating, seriesLocks.ageRatingLock)
    val language = rememberLockedField(initial.language, seriesLocks.languageLock)
    var direction by remember(initial) { mutableStateOf(initial.readingDirection) }
    var directionLocked by remember(initial) { mutableStateOf(seriesLocks.readingDirectionLock) }
    val genres = remember { mutableStateListOf<String>().apply { addAll(initial.genres) } }
    var genresLocked by remember(initial) { mutableStateOf(seriesLocks.genresLock) }
    val sharingLabels = remember { mutableStateListOf<String>().apply { addAll(initial.sharingLabels) } }
    var sharingLabelsLocked by remember(initial) { mutableStateOf(seriesLocks.sharingLabelsLock) }

    AppModalDialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.detail_edit_oneshot),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, enabled = !saving) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            // Segmented tabs (mirrors the iOS segmented picker)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OneshotEditTab.entries.forEach { entry ->
                    FilterChip(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    OneshotEditTab.General -> {
                        OneshotGroupCard(title = stringResource(R.string.detail_basic_info)) {
                            LockableTextField(stringResource(R.string.common_title), bookTitle)
                            LockableTextField(stringResource(R.string.detail_field_title_sort), titleSort)
                            LockableTextField(
                                stringResource(R.string.detail_field_release_date),
                                releaseDate,
                                placeholder = "yyyy-MM-dd",
                            )
                            LockableTextField(stringResource(R.string.detail_field_isbn), isbn)
                            LockableTextField(stringResource(R.string.browse_kind_publisher), publisher)
                            LockableTextField(stringResource(R.string.browse_kind_age_rating), ageRating)
                            LockableTextField(stringResource(R.string.book_summary), bookSummary, singleLine = false)
                        }
                        OneshotGroupCard(title = stringResource(R.string.detail_reading_settings)) {
                            LockableChoiceRow(
                                label = stringResource(R.string.detail_field_reading_direction),
                                options = seriesDirectionOptions,
                                selected = direction,
                                optionLabel = { SeriesEditValues.directionLabel(it, context) },
                                locked = directionLocked,
                                onToggleLock = { directionLocked = !directionLocked },
                                onSelect = { direction = it; directionLocked = true },
                            )
                            LockableTextField(stringResource(R.string.browse_kind_language), language)
                        }
                    }

                    OneshotEditTab.Authors -> OneshotGroupCard(
                        title = stringResource(R.string.detail_section_authors),
                        locked = authorsLocked,
                        onToggleLock = { authorsLocked = !authorsLocked },
                    ) {
                        OneshotAuthorsContent(
                            authors = authors,
                            onRemove = { authors.removeAt(it); authorsLocked = true },
                            onAdd = { name, role, custom ->
                                authors.add(Creator(name = name, role = role, customRole = custom))
                                authorsLocked = true
                            },
                        )
                    }

                    OneshotEditTab.Tags -> {
                        OneshotGroupCard(
                            title = stringResource(R.string.detail_section_genres),
                            locked = genresLocked,
                            onToggleLock = { genresLocked = !genresLocked },
                        ) {
                            OneshotListContent(
                                entries = genres,
                                label = stringResource(R.string.detail_section_genres),
                                emptyText = stringResource(R.string.detail_empty_genres),
                                removeContentDescription = stringResource(R.string.detail_remove_genre),
                                addContentDescription = stringResource(R.string.detail_add_genre),
                                onRemove = { genres.removeAt(it); genresLocked = true },
                                onAdd = { value ->
                                    if (value !in genres) genres.add(value)
                                    genresLocked = true
                                },
                            )
                        }
                        OneshotGroupCard(
                            title = stringResource(R.string.detail_section_tags),
                            locked = bookTagsLocked,
                            onToggleLock = { bookTagsLocked = !bookTagsLocked },
                        ) {
                            OneshotListContent(
                                entries = bookTags,
                                label = stringResource(R.string.detail_section_tags),
                                emptyText = stringResource(R.string.detail_empty_tags),
                                removeContentDescription = stringResource(R.string.detail_remove_tag),
                                addContentDescription = stringResource(R.string.detail_add_tag),
                                onRemove = { bookTags.removeAt(it); bookTagsLocked = true },
                                onAdd = { value ->
                                    if (value !in bookTags) bookTags.add(value)
                                    bookTagsLocked = true
                                },
                            )
                        }
                    }

                    OneshotEditTab.Links -> OneshotGroupCard(
                        title = stringResource(R.string.detail_section_links),
                        locked = linksLocked,
                        onToggleLock = { linksLocked = !linksLocked },
                    ) {
                        OneshotLinksContent(
                            links = links,
                            onRemove = { links.removeAt(it); linksLocked = true },
                            onAdd = { label, url -> links.add(label to url); linksLocked = true },
                        )
                    }

                    OneshotEditTab.Entities -> OneshotGroupCard(
                        title = stringResource(R.string.browse_kind_notable),
                        locked = notableEntitiesLocked,
                        onToggleLock = { notableEntitiesLocked = !notableEntitiesLocked },
                    ) {
                        NotableEntitiesContent(
                            entities = notableEntities,
                            onRemove = { notableEntities.removeAt(it); notableEntitiesLocked = true },
                            onAdd = { kind, name ->
                                val entity = NotableEntity(name = name, kind = kind)
                                if (entity !in notableEntities) notableEntities.add(entity)
                                notableEntitiesLocked = true
                            },
                        )
                    }

                    OneshotEditTab.Sharing -> OneshotGroupCard(
                        title = stringResource(R.string.detail_section_sharing_labels),
                        locked = sharingLabelsLocked,
                        onToggleLock = { sharingLabelsLocked = !sharingLabelsLocked },
                    ) {
                        OneshotListContent(
                            entries = sharingLabels,
                            label = stringResource(R.string.detail_section_sharing_labels),
                            emptyText = stringResource(R.string.detail_empty_sharing_labels),
                            removeContentDescription = stringResource(R.string.detail_remove_sharing_label),
                            addContentDescription = stringResource(R.string.detail_add_sharing_label),
                            onRemove = { sharingLabels.removeAt(it); sharingLabelsLocked = true },
                            onAdd = { value ->
                                if (value !in sharingLabels) sharingLabels.add(value)
                                sharingLabelsLocked = true
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = {
                        val draft = BookMetadataDraft(
                            title = bookTitle.value,
                            number = meta?.number.orEmpty(),
                            numberSort = meta?.numberSort?.let { formatOneshotNumberSort(it) }.orEmpty(),
                            releaseDate = releaseDate.value,
                            isbn = isbn.value,
                            summary = bookSummary.value,
                            authors = authors.toList(),
                            tags = bookTags.toList(),
                            links = links.toList(),
                            titleLocked = bookTitle.locked,
                            // One-shots do not edit number/sort number: the lock state keeps its initial value, so no lock is submitted
                            numberLocked = bookLocks.numberLock,
                            numberSortLocked = bookLocks.numberSortLock,
                            releaseDateLocked = releaseDate.locked,
                            isbnLocked = isbn.locked,
                            summaryLocked = bookSummary.locked,
                            authorsLocked = authorsLocked,
                            tagsLocked = bookTagsLocked,
                            linksLocked = linksLocked,
                            notableEntities = notableEntities.toList(),
                            notableEntitiesLocked = notableEntitiesLocked,
                        )
                        // Series side: fields not edited by this sheet pass the initial value + initial lock state (lock submission is null, nothing is touched)
                        val seriesDto = buildSeriesUpdateDto(
                            initial = initial,
                            title = initial.title,
                            titleLocked = seriesLocks.titleLock,
                            titleSort = titleSort.value,
                            titleSortLocked = titleSort.locked,
                            summary = initial.summary,
                            summaryLocked = seriesLocks.summaryLock,
                            publisher = publisher.value,
                            publisherLocked = publisher.locked,
                            language = language.value,
                            languageLocked = language.locked,
                            ageRating = ageRating.value,
                            ageRatingLocked = ageRating.locked,
                            totalBookCount = initial.totalBookCount,
                            totalBookCountLocked = seriesLocks.totalBookCountLock,
                            status = initial.status,
                            statusLocked = seriesLocks.statusLock,
                            readingDirection = direction,
                            readingDirectionLocked = directionLocked,
                            genres = genres,
                            genresLocked = genresLocked,
                            tags = initial.tags,
                            tagsLocked = seriesLocks.tagsLock,
                            links = initial.links,
                            linksLocked = seriesLocks.linksLock,
                            alternateTitles = initial.alternateTitles,
                            alternateTitlesLocked = seriesLocks.alternateTitlesLock,
                            sharingLabels = sharingLabels,
                            sharingLabelsLocked = sharingLabelsLocked,
                        )
                        onSave(draft, seriesDto)
                    },
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        }
    }
}

/**
 * Grouped Form-style card (16dp rounded surfaceContainerHigh, mirrors KomfGroupCard in KomfIdentifySheet).
 * When locked/onToggleLock are provided, a lock toggle is shown on the right of the title row.
 */
@Composable
private fun OneshotGroupCard(
    title: String,
    locked: Boolean? = null,
    onToggleLock: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                )
                if (locked != null && onToggleLock != null) {
                    LockToggle(locked = locked, onToggle = onToggleLock)
                }
            }
            content()
        }
    }
}

/** Author list + add row (mirrors AuthorsTab in BookEditSheet). */
@Composable
private fun OneshotAuthorsContent(
    authors: List<Creator>,
    onRemove: (Int) -> Unit,
    onAdd: (String, AuthorRole, String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(AuthorRole.Writer) }
    var customRole by rememberSaveable { mutableStateOf("") }
    var roleMenuOpen by remember { mutableStateOf(false) }

    if (authors.isEmpty()) {
        Text(
            text = stringResource(R.string.detail_empty_authors),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    authors.forEachIndexed { index, creator ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = creator.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = creator.roleLabelText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onRemove(index) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.detail_remove_author))
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.detail_author_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Box(modifier = Modifier.padding(vertical = 6.dp)) {
        OutlinedButton(
            onClick = { roleMenuOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.detail_role_format, stringResource(role.labelRes))) }
        DropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
            (AuthorRole.predefined + AuthorRole.Custom).forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        role = option
                        roleMenuOpen = false
                    },
                )
            }
        }
    }
    if (role == AuthorRole.Custom) {
        OutlinedTextField(
            value = customRole,
            onValueChange = { customRole = it },
            label = { Text(stringResource(R.string.detail_custom_role)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        onClick = {
            onAdd(
                name.trim(),
                role,
                if (role == AuthorRole.Custom) customRole.trim().ifBlank { null } else null,
            )
            name = ""
            customRole = ""
        },
        enabled = name.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) { Text(stringResource(R.string.detail_add_author)) }
}

/** Single-value list section (genres/tags/sharing labels): rows + delete + add. */
@Composable
private fun OneshotListContent(
    entries: List<String>,
    label: String,
    emptyText: String,
    removeContentDescription: String,
    addContentDescription: String,
    onRemove: (Int) -> Unit,
    onAdd: (String) -> Unit,
) {
    var newValue by rememberSaveable { mutableStateOf("") }

    if (entries.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    entries.forEachIndexed { index, entry ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { onRemove(index) }) {
                Icon(Icons.Filled.Delete, contentDescription = removeContentDescription)
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newValue,
            onValueChange = { newValue = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                onAdd(newValue.trim())
                newValue = ""
            },
            enabled = newValue.isNotBlank(),
        ) { Icon(Icons.Filled.Add, contentDescription = addContentDescription) }
    }
}

/** Link list + add row (mirrors LinksTab in BookEditSheet). */
@Composable
private fun OneshotLinksContent(
    links: List<Pair<String, String>>,
    onRemove: (Int) -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }

    if (links.isEmpty()) {
        Text(
            text = stringResource(R.string.detail_empty_links),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    links.forEachIndexed { index, (linkLabel, linkUrl) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = linkLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = linkUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onRemove(index) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.detail_remove_link))
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text(stringResource(R.string.detail_field_link_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.detail_field_link_url)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
    Button(
        onClick = {
            onAdd(label.trim(), url.trim())
            label = ""
            url = ""
        },
        enabled = label.isNotBlank() && url.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.detail_add_link)) }
}

/** Strips the redundant .0 when showing the sort number (same as BookEditSheet.formatNumberSort). */
private fun formatOneshotNumberSort(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()
