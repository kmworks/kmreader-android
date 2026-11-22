package com.kmworks.kmreader.ui.detail

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.AlternateTitleUpdateDto
import com.kmworks.kmreader.core.api.model.SeriesMetadataDto
import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import com.kmworks.kmreader.core.api.model.WebLinkUpdateDto
import com.kmworks.kmreader.core.data.toPatchJson
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.data.SeriesStatus
import com.kmworks.kmreader.ui.components.AppModalDialog
import com.kmworks.kmreader.ui.components.LockableChoiceRow
import com.kmworks.kmreader.ui.components.LockableListSection
import com.kmworks.kmreader.ui.components.LockableTextField
import com.kmworks.kmreader.ui.components.lockUpdate
import com.kmworks.kmreader.ui.components.rememberLockedField
import kotlinx.serialization.json.JsonObject

/** Server lock state for each series metadata field (taken straight from SeriesMetadataDto.xxxLock, all unlocked by default). */
data class SeriesEditLocks(
    val titleLock: Boolean = false,
    val titleSortLock: Boolean = false,
    val summaryLock: Boolean = false,
    val statusLock: Boolean = false,
    val readingDirectionLock: Boolean = false,
    val languageLock: Boolean = false,
    val publisherLock: Boolean = false,
    val ageRatingLock: Boolean = false,
    val totalBookCountLock: Boolean = false,
    val genresLock: Boolean = false,
    val tagsLock: Boolean = false,
    val linksLock: Boolean = false,
    val alternateTitlesLock: Boolean = false,
    val sharingLabelsLock: Boolean = false,
)

/** Initial values for editing series metadata (taken straight from the DTO; unchanged fields are not submitted). */
data class SeriesEditValues(
    val title: String,
    val titleSort: String,
    val summary: String,
    /** ONGOING / ENDED / ABANDONED / HIATUS */
    val status: String,
    /** LEFT_TO_RIGHT / RIGHT_TO_LEFT / VERTICAL / WEBTOON */
    val readingDirection: String,
    val language: String,
    val publisher: String,
    val ageRating: String,
    val totalBookCount: String,
    val genres: List<String>,
    val tags: List<String>,
    val links: List<Pair<String, String>>,
    val alternateTitles: List<Pair<String, String>>,
    val sharingLabels: List<String>,
    /** Server lock state of each field (all false when there is no DTO). */
    val locks: SeriesEditLocks = SeriesEditLocks(),
) {
    companion object {
        private fun statusRes(token: String): Int? = SeriesStatus.fromApiName(token)?.labelRes

        fun statusLabel(token: String, context: Context): String =
            statusRes(token)?.let { context.getString(it) } ?: token

        fun directionLabel(token: String, context: Context): String = when (token) {
            "LEFT_TO_RIGHT" -> context.getString(R.string.series_direction_ltr)
            "RIGHT_TO_LEFT" -> context.getString(R.string.series_direction_rtl)
            "VERTICAL" -> context.getString(R.string.series_direction_vertical)
            "WEBTOON" -> context.getString(R.string.series_direction_webtoon)
            else -> token
        }

        /** Fallback when there is no DTO: rebuild the editable fields from the UI model as best we can (no locks set). */
        fun fromUiSeries(series: Series): SeriesEditValues = SeriesEditValues(
            title = series.title,
            titleSort = series.title,
            summary = series.summary.orEmpty(),
            // [Series.status] is the server token, not a display label: resolve it through the enum instead of
            // comparing the localized label (that comparison always missed and silently fell back to ONGOING).
            status = series.status?.let { SeriesStatus.fromApiName(it)?.apiName } ?: "ONGOING",
            // Server token, not a display label: normalize to the four selectable values without
            // collapsing VERTICAL/WEBTOON into LTR.
            readingDirection = series.readingDirection.uppercase()
                .takeIf { it in seriesDirectionOptions } ?: "LEFT_TO_RIGHT",
            language = series.language.orEmpty(),
            publisher = series.publisher.orEmpty(),
            ageRating = series.ageRating?.toString().orEmpty(),
            totalBookCount = series.totalBookCount?.toString().orEmpty(),
            genres = series.genres,
            tags = series.tags,
            links = series.links,
            alternateTitles = emptyList(),
            sharingLabels = emptyList(),
        )
    }
}

internal val seriesStatusOptions = SeriesStatus.entries.map { it.apiName }
internal val seriesDirectionOptions = listOf("LEFT_TO_RIGHT", "RIGHT_TO_LEFT", "VERTICAL", "WEBTOON")

/**
 * Editing baseline for a series that was not opened from its detail page (the grid only carries the
 * card fields): the full server values plus the per-field locks.
 */
internal fun SeriesMetadataDto.toEditValues(): SeriesEditValues = SeriesEditValues(
    title = title,
    titleSort = titleSort,
    summary = summary,
    status = status,
    readingDirection = readingDirection,
    language = language,
    publisher = publisher,
    ageRating = ageRating?.toString().orEmpty(),
    totalBookCount = totalBookCount?.toString().orEmpty(),
    genres = genres.toList(),
    tags = tags.toList(),
    links = links.map { it.label to it.url },
    alternateTitles = alternateTitles.map { it.label to it.title },
    sharingLabels = sharingLabels.toList(),
    locks = SeriesEditLocks(
        titleLock = titleLock,
        titleSortLock = titleSortLock,
        summaryLock = summaryLock,
        statusLock = statusLock,
        readingDirectionLock = readingDirectionLock,
        languageLock = languageLock,
        publisherLock = publisherLock,
        ageRatingLock = ageRatingLock,
        totalBookCountLock = totalBookCountLock,
        genresLock = genresLock,
        tagsLock = tagsLock,
        linksLock = linksLock,
        alternateTitlesLock = alternateTitlesLock,
        sharingLabelsLock = sharingLabelsLock,
    ),
)

private enum class EditTab(@param:StringRes val labelRes: Int) {
    General(R.string.edit_tab_general),
    Title(R.string.edit_tab_title),
    Tags(R.string.edit_tab_tags),
    Links(R.string.edit_tab_links),
    Sharing(R.string.edit_tab_sharing),
}

/**
 * Edits series metadata (mirrors SeriesEditSheet):
 * five segmented tabs (general/title/tags/links/sharing labels); each field has a lock switch on the right and editing auto-locks it;
 * the initial lock state comes from the server DTO's xxxLock; saving only submits fields whose value or lock changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesEditSheet(
    series: Series,
    values: SeriesEditValues? = null,
    saving: Boolean = false,
    loadValues: (suspend () -> SeriesEditValues?)? = null,
    onSave: (JsonObject?) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // The grid pages only carry the card fields, so they hand over a loader: the editable baseline
    // (server values + locks) is fetched once instead of falling back to the lossy UI model.
    var resolvedValues by remember(values) { mutableStateOf(values) }
    var loadingValues by remember(values) { mutableStateOf(values == null && loadValues != null) }
    // Keyed on the series (not the lambda, whose identity changes every recomposition): one fetch, and
    // a failure quietly keeps the lossy UI fallback rather than retrying on every frame.
    LaunchedEffect(series.id, values) {
        if (resolvedValues == null && loadValues != null) {
            resolvedValues = loadValues() ?: resolvedValues
            loadingValues = false
        }
    }
    val initial = remember(resolvedValues, series) {
        resolvedValues ?: SeriesEditValues.fromUiSeries(series)
    }
    val initialLocks = initial.locks
    var tab by remember { mutableStateOf(EditTab.General) }

    // General
    val summary = rememberLockedField(initial.summary, initialLocks.summaryLock)
    val language = rememberLockedField(initial.language, initialLocks.languageLock)
    val publisher = rememberLockedField(initial.publisher, initialLocks.publisherLock)
    val ageRating = rememberLockedField(initial.ageRating, initialLocks.ageRatingLock)
    val totalBookCount = rememberLockedField(initial.totalBookCount, initialLocks.totalBookCountLock)
    var status by remember(initial) { mutableStateOf(initial.status) }
    var statusLocked by remember(initial) { mutableStateOf(initialLocks.statusLock) }
    var direction by remember(initial) { mutableStateOf(initial.readingDirection) }
    var directionLocked by remember(initial) { mutableStateOf(initialLocks.readingDirectionLock) }
    // Title
    val title = rememberLockedField(initial.title, initialLocks.titleLock)
    val titleSort = rememberLockedField(initial.titleSort, initialLocks.titleSortLock)
    val alternateTitles = remember(initial) { initial.alternateTitles.toMutableStateList() }
    var alternateTitlesLocked by remember(initial) { mutableStateOf(initialLocks.alternateTitlesLock) }
    // Tags / links / sharing labels
    val genres = remember(initial) { initial.genres.toMutableStateList() }
    var genresLocked by remember(initial) { mutableStateOf(initialLocks.genresLock) }
    val tags = remember(initial) { initial.tags.toMutableStateList() }
    var tagsLocked by remember(initial) { mutableStateOf(initialLocks.tagsLock) }
    val links = remember(initial) { initial.links.toMutableStateList() }
    var linksLocked by remember(initial) { mutableStateOf(initialLocks.linksLock) }
    val sharingLabels = remember(initial) { initial.sharingLabels.toMutableStateList() }
    var sharingLabelsLocked by remember(initial) { mutableStateOf(initialLocks.sharingLabelsLock) }

    AppModalDialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.series_edit_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, enabled = !saving) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
            // Segmented tabs (mirrors the segmented picker)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditTab.entries.forEach { entry ->
                    FilterChip(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
            // The body takes whatever height the pinned action row below leaves it: with a fixed height the column
            // overflowed the dialog and the unweighted action row was measured into the leftover sliver, squeezing the
            // Cancel/Save buttons. weight(fill = false) keeps the card content-sized when the tab is short.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (loadingValues) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.common_loading),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                when (tab) {
                    EditTab.General -> {
                        LockableTextField(stringResource(R.string.book_summary), summary, singleLine = false)
                        LockableChoiceRow(
                            label = stringResource(R.string.detail_field_status),
                            options = seriesStatusOptions,
                            selected = status,
                            optionLabel = { SeriesEditValues.statusLabel(it, context) },
                            locked = statusLocked,
                            onToggleLock = { statusLocked = !statusLocked },
                            onSelect = { status = it; statusLocked = true },
                        )
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
                        LockableTextField(stringResource(R.string.browse_kind_publisher), publisher)
                        LockableTextField(stringResource(R.string.browse_kind_age_rating), ageRating)
                        LockableTextField(stringResource(R.string.detail_field_total_books), totalBookCount)
                    }

                    EditTab.Title -> {
                        LockableTextField(stringResource(R.string.common_title), title)
                        LockableTextField(stringResource(R.string.detail_field_title_sort), titleSort)
                        LockableListSection(
                            title = stringResource(R.string.detail_section_alternate_titles),
                            locked = alternateTitlesLocked,
                            onToggleLock = { alternateTitlesLocked = !alternateTitlesLocked },
                            entries = alternateTitles.map { "${it.first} · ${it.second}" },
                            onRemove = { index -> alternateTitles.removeAt(index); alternateTitlesLocked = true },
                            addLabel = stringResource(R.string.detail_add_alternate_title),
                            onAdd = { label, value -> alternateTitles.add(label to value); alternateTitlesLocked = true },
                            primaryLabel = stringResource(R.string.detail_field_alternate_label),
                            secondaryLabel = stringResource(R.string.common_title),
                        )
                    }

                    EditTab.Tags -> {
                        LockableListSection(
                            title = stringResource(R.string.detail_section_genres),
                            locked = genresLocked,
                            onToggleLock = { genresLocked = !genresLocked },
                            entries = genres,
                            onRemove = { index -> genres.removeAt(index); genresLocked = true },
                            addLabel = stringResource(R.string.detail_add_genre),
                            onAdd = { value, _ -> genres.add(value); genresLocked = true },
                        )
                        LockableListSection(
                            title = stringResource(R.string.detail_section_tags),
                            locked = tagsLocked,
                            onToggleLock = { tagsLocked = !tagsLocked },
                            entries = tags,
                            onRemove = { index -> tags.removeAt(index); tagsLocked = true },
                            addLabel = stringResource(R.string.detail_add_tag),
                            onAdd = { value, _ -> tags.add(value); tagsLocked = true },
                        )
                    }

                    EditTab.Links -> {
                        LockableListSection(
                            title = stringResource(R.string.detail_section_links),
                            locked = linksLocked,
                            onToggleLock = { linksLocked = !linksLocked },
                            entries = links.map { "${it.first} · ${it.second}" },
                            onRemove = { index -> links.removeAt(index); linksLocked = true },
                            addLabel = stringResource(R.string.detail_add_link),
                            onAdd = { label, value -> links.add(label to value); linksLocked = true },
                            primaryLabel = stringResource(R.string.detail_field_link_name),
                            secondaryLabel = stringResource(R.string.detail_field_link_url),
                            primaryPlaceholder = "Wikipedia",
                            secondaryPlaceholder = "https://",
                        )
                    }

                    EditTab.Sharing -> {
                        LockableListSection(
                            title = stringResource(R.string.detail_section_sharing_labels),
                            locked = sharingLabelsLocked,
                            onToggleLock = { sharingLabelsLocked = !sharingLabelsLocked },
                            entries = sharingLabels,
                            onRemove = { index -> sharingLabels.removeAt(index); sharingLabelsLocked = true },
                            addLabel = stringResource(R.string.detail_add_sharing_label),
                            onAdd = { value, _ -> sharingLabels.add(value); sharingLabelsLocked = true },
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
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(                    onClick = {
                        onSave(
                            buildSeriesPatchBody(
                                initial = initial,
                                title = title.value,
                                titleLocked = title.locked,
                                titleSort = titleSort.value,
                                titleSortLocked = titleSort.locked,
                                summary = summary.value,
                                summaryLocked = summary.locked,
                                publisher = publisher.value,
                                publisherLocked = publisher.locked,
                                language = language.value,
                                languageLocked = language.locked,
                                ageRating = ageRating.value,
                                ageRatingLocked = ageRating.locked,
                                totalBookCount = totalBookCount.value,
                                totalBookCountLocked = totalBookCount.locked,
                                status = status,
                                statusLocked = statusLocked,
                                readingDirection = direction,
                                readingDirectionLocked = directionLocked,
                                genres = genres,
                                genresLocked = genresLocked,
                                tags = tags,
                                tagsLocked = tagsLocked,
                                links = links,
                                linksLocked = linksLocked,
                                alternateTitles = alternateTitles,
                                alternateTitlesLocked = alternateTitlesLocked,
                                sharingLabels = sharingLabels,
                                sharingLabelsLocked = sharingLabelsLocked,
                            ),
                        )
                    },
                    enabled = !saving && !loadingValues,
                    modifier = Modifier.weight(1f),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
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
 * Update body containing only the changed fields; returns null when nothing changed (no request is sent).
 * Per-field rules (mirrors the iOS per-field lock):
 * - Content: the content changed, or the lock was newly set (locking submits the current content too) → submit the current content;
 * - Lock: the lock state differs from the initial value (in either direction) → explicitly submit the current lock state (true=lock / false=unlock),
 *   unchanged → null (leave the server lock state untouched).
 * - Cleared summary / publisher / language / age rating / book count: submitted as an explicit null
 *   so Komga unsets them, like iOS writing NSNull; the DTO's default null would be omitted instead.
 *
 * The result is already the wire body (see [toPatchJson]): Komga distinguishes an explicit null from
 * an omitted field, and the typed DTO cannot.
 */
internal fun buildSeriesPatchBody(
    initial: SeriesEditValues,
    title: String,
    titleLocked: Boolean,
    titleSort: String,
    titleSortLocked: Boolean,
    summary: String,
    summaryLocked: Boolean,
    publisher: String,
    publisherLocked: Boolean,
    language: String,
    languageLocked: Boolean,
    ageRating: String,
    ageRatingLocked: Boolean,
    totalBookCount: String,
    totalBookCountLocked: Boolean,
    status: String,
    statusLocked: Boolean,
    readingDirection: String,
    readingDirectionLocked: Boolean,
    genres: List<String>,
    genresLocked: Boolean,
    tags: List<String>,
    tagsLocked: Boolean,
    links: List<Pair<String, String>>,
    linksLocked: Boolean,
    alternateTitles: List<Pair<String, String>>,
    alternateTitlesLocked: Boolean,
    sharingLabels: List<String>,
    sharingLabelsLocked: Boolean,
): JsonObject? {
    val locks = initial.locks

    val titleLockChanged = titleLocked != locks.titleLock
    val titleSortLockChanged = titleSortLocked != locks.titleSortLock
    val summaryLockChanged = summaryLocked != locks.summaryLock
    val publisherLockChanged = publisherLocked != locks.publisherLock
    val languageLockChanged = languageLocked != locks.languageLock
    val ageRatingLockChanged = ageRatingLocked != locks.ageRatingLock
    val totalBookCountLockChanged = totalBookCountLocked != locks.totalBookCountLock
    val statusLockChanged = statusLocked != locks.statusLock
    val readingDirectionLockChanged = readingDirectionLocked != locks.readingDirectionLock
    val genresLockChanged = genresLocked != locks.genresLock
    val tagsLockChanged = tagsLocked != locks.tagsLock
    val linksLockChanged = linksLocked != locks.linksLock
    val alternatesLockChanged = alternateTitlesLocked != locks.alternateTitlesLock
    val sharingLockChanged = sharingLabelsLocked != locks.sharingLabelsLock

    val titleChanged = title != initial.title || titleLockChanged
    val titleSortChanged = titleSort != initial.titleSort || titleSortLockChanged
    val summaryChanged = summary != initial.summary || summaryLockChanged
    val publisherChanged = publisher != initial.publisher || publisherLockChanged
    val languageChanged = language != initial.language || languageLockChanged
    val ageRatingChanged = ageRating != initial.ageRating || ageRatingLockChanged
    val totalBookCountChanged = totalBookCount != initial.totalBookCount || totalBookCountLockChanged
    val statusChanged = status != initial.status || statusLockChanged
    val readingDirectionChanged = readingDirection != initial.readingDirection || readingDirectionLockChanged
    val genresChanged = genres.toSet() != initial.genres.toSet() || genresLockChanged
    val tagsChanged = tags.toSet() != initial.tags.toSet() || tagsLockChanged
    val linksChanged = links != initial.links || linksLockChanged
    val alternatesChanged = alternateTitles != initial.alternateTitles || alternatesLockChanged
    val sharingChanged = sharingLabels.toSet() != initial.sharingLabels.toSet() || sharingLockChanged

    if (!(genresChanged || tagsChanged || linksChanged || alternatesChanged || sharingChanged ||
            titleChanged || titleSortChanged || summaryChanged || publisherChanged ||
            languageChanged || ageRatingChanged || totalBookCountChanged ||
            statusChanged || readingDirectionChanged)
    ) {
        return null
    }

    // Content submission: submit the current content when it changed or when the lock was newly set (locking direction); the unlocking direction submits the lock only.
    val dto = SeriesMetadataUpdateDto(
        title = title.takeIf { title != initial.title || (titleLockChanged && titleLocked) },
        titleLock = lockUpdate(titleLocked, locks.titleLock),
        titleSort = titleSort.takeIf { titleSort != initial.titleSort || (titleSortLockChanged && titleSortLocked) },
        titleSortLock = lockUpdate(titleSortLocked, locks.titleSortLock),
        summary = summary.takeIf { summary != initial.summary || (summaryLockChanged && summaryLocked) },
        summaryLock = lockUpdate(summaryLocked, locks.summaryLock),
        publisher = publisher.takeIf { publisher != initial.publisher || (publisherLockChanged && publisherLocked) },
        publisherLock = lockUpdate(publisherLocked, locks.publisherLock),
        language = language.takeIf { language != initial.language || (languageLockChanged && languageLocked) },
        languageLock = lockUpdate(languageLocked, locks.languageLock),
        ageRating = ageRating.trim().toIntOrNull()
            .takeIf { ageRating != initial.ageRating || (ageRatingLockChanged && ageRatingLocked) },
        ageRatingLock = lockUpdate(ageRatingLocked, locks.ageRatingLock),
        totalBookCount = totalBookCount.trim().toIntOrNull()
            .takeIf { totalBookCount != initial.totalBookCount || (totalBookCountLockChanged && totalBookCountLocked) },
        totalBookCountLock = lockUpdate(totalBookCountLocked, locks.totalBookCountLock),
        status = SeriesMetadataUpdateDto.Status.entries.firstOrNull { it.value == status }
            .takeIf { status != initial.status || (statusLockChanged && statusLocked) },
        statusLock = lockUpdate(statusLocked, locks.statusLock),
        readingDirection = SeriesMetadataUpdateDto.ReadingDirection.entries
            .firstOrNull { it.value == readingDirection }
            .takeIf { readingDirection != initial.readingDirection || (readingDirectionLockChanged && readingDirectionLocked) },
        readingDirectionLock = lockUpdate(readingDirectionLocked, locks.readingDirectionLock),
        genres = genres.toSet()
            .takeIf { genres.toSet() != initial.genres.toSet() || (genresLockChanged && genresLocked) },
        genresLock = lockUpdate(genresLocked, locks.genresLock),
        tags = tags.toSet()
            .takeIf { tags.toSet() != initial.tags.toSet() || (tagsLockChanged && tagsLocked) },
        tagsLock = lockUpdate(tagsLocked, locks.tagsLock),
        links = links
            .map { WebLinkUpdateDto(label = it.first, url = it.second.ifBlank { null }) }
            .takeIf { links != initial.links || (linksLockChanged && linksLocked) },
        linksLock = lockUpdate(linksLocked, locks.linksLock),
        alternateTitles = alternateTitles
            .map { AlternateTitleUpdateDto(label = it.first, title = it.second) }
            .takeIf { alternateTitles != initial.alternateTitles || (alternatesLockChanged && alternateTitlesLocked) },
        alternateTitlesLock = lockUpdate(alternateTitlesLocked, locks.alternateTitlesLock),
        sharingLabels = sharingLabels.toSet()
            .takeIf { sharingLabels.toSet() != initial.sharingLabels.toSet() || (sharingLockChanged && sharingLabelsLocked) },
        sharingLabelsLock = lockUpdate(sharingLabelsLocked, locks.sharingLabelsLock),
    )
    val cleared = buildSet {
        // Clearing a text field sends an empty string, which Komga stores as an empty value; iOS
        // submits NSNull for the same edit, so mirror it and unset the field instead.
        if (summary.isBlank() && initial.summary.isNotBlank()) add("summary")
        if (publisher.isBlank() && initial.publisher.isNotBlank()) add("publisher")
        if (language.isBlank() && initial.language.isNotBlank()) add("language")
        if (ageRating.isBlank() && initial.ageRating.isNotBlank()) add("ageRating")
        if (totalBookCount.isBlank() && initial.totalBookCount.isNotBlank()) add("totalBookCount")
    }
    return dto.toPatchJson(cleared)
}
