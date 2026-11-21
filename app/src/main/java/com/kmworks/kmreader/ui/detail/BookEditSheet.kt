package com.kmworks.kmreader.ui.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.core.api.model.AuthorUpdateDto
import com.kmworks.kmreader.core.api.model.BookMetadataUpdateDto
import com.kmworks.kmreader.core.api.model.NotableEntityUpdateDto
import com.kmworks.kmreader.core.api.model.WebLinkUpdateDto
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookDetailMeta
import com.kmworks.kmreader.data.BookMetadataLocks
import com.kmworks.kmreader.data.Creator
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.ui.components.AppModalDialog
import com.kmworks.kmreader.ui.components.LockToggle
import com.kmworks.kmreader.ui.components.LockableTextField
import com.kmworks.kmreader.ui.components.NotableEntitiesContent
import com.kmworks.kmreader.ui.components.lockUpdate
import com.kmworks.kmreader.ui.components.rememberLockedField
import com.kmworks.kmreader.ui.components.roleLabelText
import androidx.compose.ui.res.stringResource
import com.kmworks.kmreader.R

/**
 * Book metadata edit draft (mirrors the field set of iOS BookMetadataUpdate + per-field lock state).
 * xxxLocked is the UI's current lock state: initially the server lock state, editing locks automatically,
 * and it can be toggled manually (including unlocking).
 */
data class BookMetadataDraft(
    val title: String,
    val number: String,
    val numberSort: String,
    val releaseDate: String,
    val isbn: String,
    val summary: String,
    val authors: List<Creator>,
    val tags: List<String>,
    val links: List<Pair<String, String>>,
    val notableEntities: List<NotableEntity>,
    val titleLocked: Boolean = false,
    val numberLocked: Boolean = false,
    val numberSortLocked: Boolean = false,
    val releaseDateLocked: Boolean = false,
    val isbnLocked: Boolean = false,
    val summaryLocked: Boolean = false,
    val authorsLocked: Boolean = false,
    val tagsLocked: Boolean = false,
    val linksLocked: Boolean = false,
    val notableEntitiesLocked: Boolean = false,
)

/**
 * Submits only the changed fields (mirrors iOS BookMetadataUpdate.toAPIDict(against:) + per-field lock);
 * returns null when nothing changed, meaning no request is needed.
 * Per-field rules:
 * - Content: content changed, or a lock was newly set (lock direction) → submit the current content;
 *   unlock direction submits the lock only;
 * - Lock: lock state differs from the initial value (either direction) → explicitly submit the current
 *   lock state (true=lock / false=unlock); unchanged → null (leave the server lock state untouched).
 */
internal fun BookMetadataDraft.toUpdateDto(
    baseTitle: String,
    original: BookDetailMeta?,
): BookMetadataUpdateDto? {
    val originalNumber = original?.number.orEmpty()
    val originalSort = original?.numberSort
    val originalRelease = original?.releaseDate.orEmpty()
    val originalIsbn = original?.isbn.orEmpty()
    val originalSummary = original?.summary.orEmpty()
    val originalAuthors = original?.authors.orEmpty()
    val originalTags = original?.tags.orEmpty()
    val originalLinks = original?.links.orEmpty()
    val originalNotableEntities = original?.notableEntities.orEmpty()
    val locks = original?.locks ?: BookMetadataLocks()

    val titleContentChanged = title.trim() != baseTitle
    val numberContentChanged = number.trim() != originalNumber
    val numberSortValue = numberSort.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()
    val sortContentChanged = numberSortValue != originalSort
    val releaseContentChanged = releaseDate.trim() != originalRelease
    val isbnContentChanged = isbn.trim() != originalIsbn
    val summaryContentChanged = summary.trim() != originalSummary
    val authorsContentChanged = authors != originalAuthors
    val tagsContentChanged = tags != originalTags
    val linksContentChanged = links != originalLinks
    val notableEntitiesContentChanged = notableEntities != originalNotableEntities

    val titleLockChanged = titleLocked != locks.titleLock
    val numberLockChanged = numberLocked != locks.numberLock
    val sortLockChanged = numberSortLocked != locks.numberSortLock
    val releaseLockChanged = releaseDateLocked != locks.releaseDateLock
    val isbnLockChanged = isbnLocked != locks.isbnLock
    val summaryLockChanged = summaryLocked != locks.summaryLock
    val authorsLockChanged = authorsLocked != locks.authorsLock
    val tagsLockChanged = tagsLocked != locks.tagsLock
    val linksLockChanged = linksLocked != locks.linksLock
    val notableEntitiesLockChanged = notableEntitiesLocked != locks.notableEntitiesLock

    val changed = titleContentChanged || numberContentChanged || sortContentChanged ||
        releaseContentChanged || isbnContentChanged || summaryContentChanged ||
        authorsContentChanged || tagsContentChanged || linksContentChanged ||
        notableEntitiesContentChanged ||
        titleLockChanged || numberLockChanged || sortLockChanged || releaseLockChanged ||
        isbnLockChanged || summaryLockChanged || authorsLockChanged || tagsLockChanged ||
        linksLockChanged || notableEntitiesLockChanged
    if (!changed) return null

    return BookMetadataUpdateDto(
        title = title.trim().takeIf { titleContentChanged || (titleLockChanged && titleLocked) },
        titleLock = lockUpdate(titleLocked, locks.titleLock),
        number = number.trim().takeIf { numberContentChanged || (numberLockChanged && numberLocked) },
        numberLock = lockUpdate(numberLocked, locks.numberLock),
        numberSort = numberSortValue.takeIf { sortContentChanged || (sortLockChanged && numberSortLocked) },
        numberSortLock = lockUpdate(numberSortLocked, locks.numberSortLock),
        releaseDate = releaseDate.trim()
            .takeIf { releaseContentChanged || (releaseLockChanged && releaseDateLocked) },
        releaseDateLock = lockUpdate(releaseDateLocked, locks.releaseDateLock),
        isbn = isbn.trim().takeIf { isbnContentChanged || (isbnLockChanged && isbnLocked) },
        isbnLock = lockUpdate(isbnLocked, locks.isbnLock),
        summary = summary.trim().takeIf { summaryContentChanged || (summaryLockChanged && summaryLocked) },
        summaryLock = lockUpdate(summaryLocked, locks.summaryLock),
        authors = if (authorsContentChanged || (authorsLockChanged && authorsLocked)) {
            authors.map { AuthorUpdateDto(name = it.name, role = it.roleToken) }
        } else {
            null
        },
        authorsLock = lockUpdate(authorsLocked, locks.authorsLock),
        tags = tags.toSet().takeIf { tagsContentChanged || (tagsLockChanged && tagsLocked) },
        tagsLock = lockUpdate(tagsLocked, locks.tagsLock),
        links = if (linksContentChanged || (linksLockChanged && linksLocked)) {
            links.map { (label, url) -> WebLinkUpdateDto(label = label, url = url) }
        } else {
            null
        },
        linksLock = lockUpdate(linksLocked, locks.linksLock),
        notableEntities = if (notableEntitiesContentChanged ||
            (notableEntitiesLockChanged && notableEntitiesLocked)
        ) {
            notableEntities.map { NotableEntityUpdateDto(kind = it.kind, name = it.name) }
        } else {
            null
        },
        notableEntitiesLock = lockUpdate(notableEntitiesLocked, locks.notableEntitiesLock),
    )
}

/** Custom roles keep their free text; predefined roles use the server token. */
internal val Creator.roleToken: String
    get() = if (role == AuthorRole.Custom) {
        customRole?.takeIf { it.isNotBlank() } ?: AuthorRole.Custom.token
    } else {
        role.token
    }

private enum class BookEditTab(@param:StringRes val labelRes: Int) {
    General(R.string.edit_tab_general),
    Authors(R.string.edit_tab_authors),
    Tags(R.string.edit_tab_tags),
    Links(R.string.edit_tab_links),
    Entities(R.string.edit_tab_entities),
}

/**
 * Book metadata edit sheet, fields aligned with KMReader/Features/Book/Views/BookEditSheet.swift:
 * General (title/number/sort number/release date/ISBN/summary), Authors (name + role), Tags, Links.
 * Each field has a lock toggle on the right: initially the server lock state, editing locks automatically,
 * and it can be toggled manually (including unlocking).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookEditSheet(
    book: Book,
    meta: BookDetailMeta?,
    saving: Boolean = false,
    onSave: (BookMetadataDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(BookEditTab.General) }
    val locks = remember(meta) { meta?.locks ?: BookMetadataLocks() }

    val title = rememberLockedField(book.title, locks.titleLock)
    val number = rememberLockedField(meta?.number.orEmpty(), locks.numberLock)
    val numberSort = rememberLockedField(
        meta?.numberSort?.let { formatNumberSort(it) }.orEmpty(),
        locks.numberSortLock,
    )
    val releaseDate = rememberLockedField(meta?.releaseDate.orEmpty(), locks.releaseDateLock)
    val isbn = rememberLockedField(meta?.isbn.orEmpty(), locks.isbnLock)
    val summary = rememberLockedField(meta?.summary.orEmpty(), locks.summaryLock)

    val authors = remember { mutableStateListOf<Creator>().apply { addAll(meta?.authors.orEmpty()) } }
    var authorsLocked by remember(meta) { mutableStateOf(locks.authorsLock) }
    val tags = remember { mutableStateListOf<String>().apply { addAll(meta?.tags.orEmpty()) } }
    var tagsLocked by remember(meta) { mutableStateOf(locks.tagsLock) }
    val links = remember {
        mutableStateListOf<Pair<String, String>>().apply { addAll(meta?.links.orEmpty()) }
    }
    var linksLocked by remember(meta) { mutableStateOf(locks.linksLock) }
    // Notable entities (mirrors iOS NotableEntitiesEditor: add/remove locks automatically)
    val notableEntities = remember {
        mutableStateListOf<NotableEntity>().apply { addAll(meta?.notableEntities.orEmpty()) }
    }
    var notableEntitiesLocked by remember(meta) { mutableStateOf(locks.notableEntitiesLock) }

    AppModalDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.book_edit_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BookEditTab.entries.forEach { entry ->
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
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    BookEditTab.General -> GeneralTab(
                        title = title,
                        number = number,
                        numberSort = numberSort,
                        releaseDate = releaseDate,
                        isbn = isbn,
                        summary = summary,
                    )

                    BookEditTab.Authors -> AuthorsTab(
                        authors = authors,
                        locked = authorsLocked,
                        onToggleLock = { authorsLocked = !authorsLocked },
                        onRemove = { authors.removeAt(it); authorsLocked = true },
                        onAdd = { name, role, custom ->
                            authors.add(Creator(name = name, role = role, customRole = custom))
                            authorsLocked = true
                        },
                    )

                    BookEditTab.Tags -> TagsTab(
                        tags = tags,
                        locked = tagsLocked,
                        onToggleLock = { tagsLocked = !tagsLocked },
                        onRemove = { tags.removeAt(it); tagsLocked = true },
                        onAdd = { tag -> if (tag !in tags) tags.add(tag); tagsLocked = true },
                    )

                    BookEditTab.Links -> LinksTab(
                        links = links,
                        locked = linksLocked,
                        onToggleLock = { linksLocked = !linksLocked },
                        onRemove = { links.removeAt(it); linksLocked = true },
                        onAdd = { label, url -> links.add(label to url); linksLocked = true },
                    )

                    BookEditTab.Entities -> NotableEntitiesTab(
                        entities = notableEntities,
                        locked = notableEntitiesLocked,
                        onToggleLock = { notableEntitiesLocked = !notableEntitiesLocked },
                        onRemove = { notableEntities.removeAt(it); notableEntitiesLocked = true },
                        onAdd = { kind, name ->
                            val entity = NotableEntity(name = name, kind = kind)
                            if (entity !in notableEntities) notableEntities.add(entity)
                            notableEntitiesLocked = true
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                ) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = {
                        onSave(
                            BookMetadataDraft(
                                title = title.value,
                                number = number.value,
                                numberSort = numberSort.value,
                                releaseDate = releaseDate.value,
                                isbn = isbn.value,
                                summary = summary.value,
                                authors = authors.toList(),
                                tags = tags.toList(),
                                links = links.toList(),
                                titleLocked = title.locked,
                                numberLocked = number.locked,
                                numberSortLocked = numberSort.locked,
                                releaseDateLocked = releaseDate.locked,
                                isbnLocked = isbn.locked,
                                summaryLocked = summary.locked,
                                authorsLocked = authorsLocked,
                                tagsLocked = tagsLocked,
                                linksLocked = linksLocked,
                                notableEntities = notableEntities.toList(),
                                notableEntitiesLocked = notableEntitiesLocked,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
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

/** List section header row: field name + lock toggle. */
@Composable
private fun LockableTabHeader(title: String, locked: Boolean, onToggleLock: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        LockToggle(locked = locked, onToggle = onToggleLock)
    }
}

@Composable
private fun GeneralTab(
    title: com.kmworks.kmreader.ui.components.LockedField,
    number: com.kmworks.kmreader.ui.components.LockedField,
    numberSort: com.kmworks.kmreader.ui.components.LockedField,
    releaseDate: com.kmworks.kmreader.ui.components.LockedField,
    isbn: com.kmworks.kmreader.ui.components.LockedField,
    summary: com.kmworks.kmreader.ui.components.LockedField,
) {
    LockableTextField(stringResource(R.string.common_title), title)
    LockableTextField(stringResource(R.string.detail_field_number), number)
    LockableTextField(stringResource(R.string.detail_field_sort_number), numberSort)
    LockableTextField(
        stringResource(R.string.detail_field_release_date),
        releaseDate,
        placeholder = "yyyy-MM-dd",
    )
    LockableTextField(stringResource(R.string.detail_field_isbn), isbn)
    LockableTextField(stringResource(R.string.book_summary), summary, singleLine = false)
}

@Composable
private fun AuthorsTab(
    authors: List<Creator>,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: (String, AuthorRole, String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(AuthorRole.Writer) }
    var customRole by rememberSaveable { mutableStateOf("") }
    var roleMenuOpen by remember { mutableStateOf(false) }

    LockableTabHeader(stringResource(R.string.detail_section_authors), locked, onToggleLock)
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
    Box {
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
    val pending = name.isNotBlank()
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
        enabled = pending,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.detail_add_author)) }
}

@Composable
private fun TagsTab(
    tags: List<String>,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: (String) -> Unit,
) {
    var newTag by rememberSaveable { mutableStateOf("") }

    LockableTabHeader(stringResource(R.string.detail_section_tags), locked, onToggleLock)
    if (tags.isEmpty()) {
        Text(
            text = stringResource(R.string.detail_empty_tags),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    tags.forEachIndexed { index, tag ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tag,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { onRemove(index) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.detail_remove_tag))
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newTag,
            onValueChange = { newTag = it },
            label = { Text(stringResource(R.string.detail_section_tags)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                onAdd(newTag.trim())
                newTag = ""
            },
            enabled = newTag.isNotBlank(),
        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.detail_add_tag)) }
    }
}

@Composable
private fun LinksTab(
    links: List<Pair<String, String>>,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }

    LockableTabHeader(stringResource(R.string.detail_section_links), locked, onToggleLock)
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
        modifier = Modifier.fillMaxWidth(),
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

/** Notable entities tab (mirrors iOS NotableEntitiesEditor; content shared via NotableEntitiesContent). */
@Composable
private fun NotableEntitiesTab(
    entities: List<NotableEntity>,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: (kind: String, name: String) -> Unit,
) {
    LockableTabHeader(stringResource(R.string.browse_kind_notable), locked, onToggleLock)
    NotableEntitiesContent(entities = entities, onRemove = onRemove, onAdd = onAdd)
}

/** Strips the redundant .0 when showing the sort number. */
private fun formatNumberSort(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()
