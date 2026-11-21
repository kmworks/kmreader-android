package com.kmworks.kmreader.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.ui.components.AppModalDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.komf.KomfSearchResultDto
import com.kmworks.kmreader.core.komf.parseKomfProviderLinks
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.CoverArt

/**
 * Identify with komf (mirrors KomfIdentifySheet):
 * search field (prefilled with the series title, 300ms debounce in the VM) → Provider Links section
 * (parsed back from the series links, aggregated into a single row when there are several) →
 * results section (40×60 cover + title + meta + provider capsule); selecting one submits identify → the job
 * enters polling, the sheet closes as soon as the submit succeeds, and completion/failure is surfaced by the
 * detail screen's snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomfIdentifySheet(
    series: Series,
    books: List<Book>,
    ready: Boolean,
    searching: Boolean,
    searchFailed: Boolean,
    results: List<KomfSearchResultDto>,
    onSearch: (String) -> Unit,
    onIdentify: (
        provider: String,
        providerSeriesId: String,
        onSubmitted: () -> Unit,
        onFailed: () -> Unit,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(series.title) }
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var submitFailed by remember { mutableStateOf(false) }

    // Mirrors iOS KomfIdentifySheet:29-33: for one-shots without series links, fall back to the book's metadata.links
    val providerLinks = remember(series, books) {
        val links = if (series.oneshot && series.links.isEmpty()) {
            books.firstOrNull()?.links.orEmpty()
        } else {
            series.links
        }
        parseKomfProviderLinks(links)
    }

    // 300ms debounce lives in the owner; forward every query change (the owner clears results on an empty query).
    // Re-run once the ids are ready so the pre-filled title is searched without the user editing it.
    LaunchedEffect(query, ready) {
        if (ready) onSearch(query)
    }

    val submit: (String, String, String) -> Unit = { key, provider, providerSeriesId ->
        pendingKey = key
        submitFailed = false
        onIdentify(
            provider,
            providerSeriesId,
            { onDismiss() },
            {
                pendingKey = null
                submitFailed = true
            },
        )
    }

    AppModalDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                // The identify job polling keeps changing the content height; a fixed height keeps the anchor
                // stable and avoids jitter while dragging
                .fillMaxHeight(0.9f)
                .padding(bottom = 20.dp),
        ) {
            // Fixed header (mirrors iOS SheetView: the title does not scroll with the content)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 8.dp),
            ) {
                Text(
                    stringResource(R.string.komf_identify_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.komf_search_placeholder)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        // Rounded search bar mirroring iOS .searchable
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }

                if (submitFailed) {
                    item {
                        Text(
                            stringResource(R.string.komf_submit_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }

                // Provider Links (mirrors: provider direct links parsed back from the series metadata.links;
                // when there are more than one, the first row aggregates them and submits the first)
                if (providerLinks.isNotEmpty()) {
                    item { KomfSectionTitle(stringResource(R.string.komf_provider_links)) }
                    item {
                        KomfGroupCard {
                            if (providerLinks.size > 1) {
                                val first = providerLinks.first()
                                KomfProviderRow(
                                    icon = {
                                        Icon(Icons.Filled.CloudSync, contentDescription = null)
                                    },
                                    title = stringResource(R.string.komf_aggregate_providers),
                                    subtitle = stringResource(
                                        R.string.komf_merge_provider_links,
                                        providerLinks.size,
                                    ),
                                    pending = pendingKey == first.key,
                                    enabled = pendingKey == null,
                                    onClick = { submit(first.key, first.provider, first.providerSeriesId) },
                                )
                            }
                            providerLinks.forEach { link ->
                                KomfProviderRow(
                                    icon = {
                                        Icon(Icons.Filled.Link, contentDescription = null)
                                    },
                                    title = link.displayName,
                                    subtitle = link.providerSeriesId,
                                    pending = pendingKey == link.key,
                                    enabled = pendingKey == null,
                                    onClick = { submit(link.key, link.provider, link.providerSeriesId) },
                                )
                            }
                        }
                    }
                }

                // Results section (mirrors resultsSection: loading / failure + retry / no results / list)
                item { KomfSectionTitle(stringResource(R.string.komf_search_results)) }
                item {
                    KomfGroupCard {
                        when {
                            (!ready || searching) && results.isEmpty() -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                            searchFailed -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.komf_search_failed),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = { onSearch(query) }) {
                                        Text(stringResource(R.string.common_retry))
                                    }
                                }
                            }
                            query.isNotBlank() && results.isEmpty() && !searching -> {
                                Text(
                                    stringResource(R.string.komf_no_results),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 10.dp),
                                )
                            }
                            else -> {
                                results.forEach { result ->
                                    KomfResultRow(
                                        title = result.title,
                                        metaText = result.metaText,
                                        provider = result.provider,
                                        imageUrl = result.imageUrl,
                                        fallbackHue = series.hue,
                                        pending = pendingKey == result.key,
                                        enabled = pendingKey == null,
                                        onClick = { submit(result.key, result.provider, result.resultId) },
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

/** Grouped Form-style card (mirrors the iOS inset-grouped section).
 *  Its color must be one step above the sheet background (surfaceContainerLow), otherwise the card is invisible. */
@Composable
private fun KomfGroupCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun KomfResultRow(
    title: String,
    metaText: String,
    provider: String,
    imageUrl: String?,
    fallbackHue: Float,
    pending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        // Transparent: drawn on top of KomfGroupCard to avoid the default surface color
        // (one step darker than the card) forming a dark block
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Covers go through the shared CoverArt (authenticated ImageLoader + placeholder fallback),
            // consistent with the rest of the app
            CoverArt(
                title = title,
                hue = fallbackHue,
                imageUrl = imageUrl,
                modifier = Modifier
                    .width(40.dp)
                    .height(60.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaText.isNotEmpty()) {
                    Text(
                        metaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (pending) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        provider,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun KomfSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun KomfProviderRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    pending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pending) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}
