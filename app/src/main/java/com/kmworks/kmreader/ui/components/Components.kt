package com.kmworks.kmreader.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.toBitmap
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.CoverUrlRegistry
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.core.download.DownloadMark
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import dagger.hilt.android.EntryPointAccessors
import java.net.URI
import kotlin.math.min

private val CoverShape = RoundedCornerShape(8.dp)

/**
 * Cover art: gray placeholder with the reading icon until the image loads.
 *
 * [hue] is kept in the data model (and this signature) for compatibility but is no longer used:
 * the placeholder is a neutral gray rather than the old per-title color block.
 *
 * Two mutually exclusive image sources:
 * - [stableId]: Komga content cover; the URL is resolved by [CoverUrlRegistry] from the stableId and
 *   always uses the session ImageLoader (authenticated). Until the session is ready the placeholder is
 *   shown, and it never falls back to the unauthenticated singleton loader.
 * - [imageUrl]: external absolute URL (Komf recognition results, tracker remote covers) goes through the
 *   singleton loader so Komga credentials are never sent to third-party image hosts.
 *
 * [fallbackImageUrl] is used when the primary source has no URL yet or fails to load. Downloads pass
 * the locally saved cover here, so a book downloaded from another server still renders its cover.
 */
@Composable
fun CoverArt(
    title: String,
    hue: Float,
    modifier: Modifier = Modifier,
    stableId: Int? = null,
    imageUrl: String? = null,
    fallbackImageUrl: String? = null,
    coverVersion: Int = 0,
    contentScale: ContentScale = ContentScale.Crop,
    /** Honour the "preserve cover ratio" preference: the cover keeps its own aspect inside the slot and hugs the
     *  bottom, leaving the slack above; otherwise it fills the slot. Mirrors iOS ThumbnailImage, whose slot is always
     *  the √2 one and whose `.alignment: .bottom` puts a wide cover on the same baseline as the text below it. */
    followThumbnailPreference: Boolean = false,
    /** Cover is an unread download: with the "blur unread covers" preference on, the blur lands on the cover itself. */
    unread: Boolean = false,
    /** Painted over the cover itself, not over the slot — iOS `framedImageCard.overlay { overlay() }`, so a
     *  letterboxed wide cover carries its corner badge on its own top-right corner instead of floating at the
     *  slot's. Callers align into this box. */
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    /** Callback after the cover loads (horizontal cards extract the average-color tint; mirrors iOS ThumbnailTintColorCache). */
    onCoverBitmap: ((Bitmap) -> Unit)? = null,
) {
    val display = LocalCardDisplay.current
    // iOS semantics (ThumbnailImage + GridCardView): the slot never moves, and a card that draws the text overlay
    // passes `preserveAspectRatioOverride: false`, i.e. it always crops to the slot whatever the "preserve cover
    // ratio" switch says. Outside overlay mode the switch decides: fit inside the slot + hug the bottom, or fill.
    val drawsOverlay = display.textOverlay && !display.coverOnly
    val letterboxAllowed = followThumbnailPreference && display.preserveAspectRatio && !drawsOverlay
    val effectiveScale = if (letterboxAllowed) ContentScale.Fit else contentScale
    val effectiveAlignment = if (letterboxAllowed) Alignment.BottomCenter else Alignment.Center
    // Intrinsic ratio of the loaded cover; null until something is on screen, which is what shows the placeholder.
    var loadedAspect by remember(stableId, imageUrl, coverVersion) { mutableStateOf<Float?>(null) }
    BoxWithConstraints(
        modifier = modifier.clip(CoverShape),
        contentAlignment = Alignment.Center,
    ) {
        // iOS keeps the slot transparent (`Color.clear`) and only shows the grey placeholder while the cover is
        // still loading. Once it lands, the slack above a wide cover is page background, never a slab.
        // Same icon as the read buttons, scaled to the cover size.
        val placeholderIconSize = with(LocalDensity.current) {
            if (constraints.hasBoundedWidth && constraints.hasBoundedHeight && constraints.maxWidth > 0) {
                (min(constraints.maxWidth, constraints.maxHeight).toDp() * 0.35f).coerceIn(16.dp, 48.dp)
            } else {
                28.dp
            }
        }
        if (loadedAspect == null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(placeholderIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val context = LocalContext.current
        val entryPoint = remember(context) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                ImageLoaderEntryPoint::class.java,
            )
        }
        val session = remember(entryPoint) { entryPoint.sessionManager() }
        val imageCaches = remember(entryPoint) { entryPoint.imageCacheStore() }
        val clients by session.clients.collectAsState()
        // Covers use the dedicated cover cache, never the reader's page-image cache.
        val sessionLoader by imageCaches.coverLoader.collectAsState()
        val externalLoader = remember(session) { session.externalImageLoader }
        val serverBaseUrl = clients?.baseUrl
        // Absolute cover URL without cache-key parameters.
        val primaryBaseUrl = remember(stableId, imageUrl, serverBaseUrl) {
            if (stableId != null) CoverUrlRegistry.url(stableId, clients) else imageUrl?.takeIf { it.isNotBlank() }
        }
        val primaryResolvedUrl = remember(primaryBaseUrl, serverBaseUrl) {
            primaryBaseUrl?.let { normalizeCoverUrl(it, serverBaseUrl) }
        }
        val fallbackResolvedUrl = remember(fallbackImageUrl, serverBaseUrl) {
            fallbackImageUrl?.takeIf { it.isNotBlank() }?.let { normalizeCoverUrl(it, serverBaseUrl) }
        }
        // A failed (or currently unresolvable) primary falls back to the local cover.
        var primaryFailed by remember(stableId, imageUrl, fallbackImageUrl, serverBaseUrl, coverVersion) {
            mutableStateOf(false)
        }
        val usingFallback = fallbackResolvedUrl != null && (primaryFailed || primaryResolvedUrl == null)
        val resolvedUrl = if (usingFallback) fallbackResolvedUrl else primaryResolvedUrl
        // Identity of the cover in the refresh-interval table (the URL path stays stable, the stamp is
        // the `cv` parameter, so a new stamp is a new Coil cache key). Local fallbacks are never stamped.
        val coverIdentity = remember(stableId, serverBaseUrl, usingFallback) {
            if (!usingFallback && stableId != null && serverBaseUrl != null) "$serverBaseUrl#cover:$stableId" else null
        }
        val stamps by imageCaches.coverStamps.stamps.collectAsState()
        val expirationDays by imageCaches.coverExpirationDays.collectAsState()
        val coverStamp = coverIdentity?.let { stamps[it] } ?: 0L
        LaunchedEffect(coverIdentity, coverStamp, expirationDays) {
            val identity = coverIdentity ?: return@LaunchedEffect
            if (coverStamp == 0L) {
                // First sighting: stamp it, which also gives the cover a stable cache key from now on.
                imageCaches.coverStamps.stamp(identity)
                return@LaunchedEffect
            }
            val refresh = imageCaches.coverStamps.refreshIfExpired(identity, expirationDays)
                ?: return@LaunchedEffect
            // The stamp changed the key: drop the stale entry so a refresh never leaves the old copy behind.
            imageCaches.evictCover(coverUrlWithVersion(primaryResolvedUrl, refresh.previous, 0))
        }
        val modelUrl = remember(resolvedUrl, coverStamp, coverVersion, usingFallback) {
            if (usingFallback) resolvedUrl else coverUrlWithVersion(resolvedUrl, coverStamp, coverVersion)
        }
        val serverOwnedImage = resolvedUrl != null && serverBaseUrl != null &&
            sameOrigin(resolvedUrl, serverBaseUrl)
        // Komga / same-origin covers must use the authenticated session loader; only real third-party URLs use the unauthenticated singleton loader.
        val loader = if (serverOwnedImage || (!usingFallback && stableId != null)) sessionLoader else externalLoader

        LaunchedEffect(stableId, imageUrl, clients, primaryResolvedUrl, usingFallback) {
            if (!usingFallback && stableId != null && clients != null && primaryResolvedUrl == null) {
                if (!CoverUrlRegistry.contains(stableId)) {
                    AppLog.w(
                        "CoverArt",
                        "cover registry miss: stableId=$stableId title=$title",
                    )
                } else {
                    AppLog.w(
                        "CoverArt",
                        "cover url missing: stableId=$stableId ref=${CoverUrlRegistry.debugRef(stableId)} title=$title",
                    )
                }
            }
            if (stableId == null && !imageUrl.isNullOrBlank() && imageUrl != primaryResolvedUrl) {
                AppLog.d("CoverArt", "normalized cover url: $imageUrl -> $primaryResolvedUrl title=$title")
            }
        }

        if (modelUrl != null && loader != null) {
            // Decode for the exact pixel box the cover is painted in. Coil's constraint-derived size is
            // resolved once, on the first measure, and an in-flight request is never re-issued when that
            // box later changes size: a cover decoded for the 3-column grid keeps being drawn upscaled
            // after a switch to the 2-column grid (visible blur) until scrolling it out of the lazy grid
            // and back recreates its painter. An explicit size puts the box size into the request
            // identity (the model equality delegate compares ImageRequest.sizeResolver), so a resize
            // re-decodes at the new size instead of reusing the small bitmap.
            val decodeBounds = if (effectiveScale != ContentScale.None &&
                constraints.hasBoundedWidth && constraints.hasBoundedHeight &&
                constraints.maxWidth > 0 && constraints.maxHeight > 0
            ) {
                constraints.maxWidth to constraints.maxHeight
            } else {
                null
            }
            // ImageRequest models bypass Coil's requestOf() wrapping untouched, so this size is kept.
            val model: Any? = remember(modelUrl, decodeBounds, effectiveScale) {
                val bounds = decodeBounds
                if (bounds == null) {
                    modelUrl
                } else {
                    ImageRequest.Builder(context)
                        .data(modelUrl)
                        .size(bounds.first, bounds.second)
                        .scale(
                            if (effectiveScale == ContentScale.Fit || effectiveScale == ContentScale.Inside) {
                                Scale.FIT
                            } else {
                                Scale.FILL
                            },
                        )
                        .build()
                }
            }
            // iOS `displayedContentSize`: fit the cover's own ratio inside the slot and hug the bottom, so a
            // wide cover ends on the same baseline as the text below it while the slack stays see-through.
            val aspect = loadedAspect?.takeIf { it > 0f }
            val letterboxedSize: DpSize? =
                if (letterboxAllowed && aspect != null &&
                    constraints.hasBoundedWidth && constraints.hasBoundedHeight &&
                    constraints.maxWidth > 0 && constraints.maxHeight > 0
                ) {
                    val density = LocalDensity.current
                    val slotWidth = constraints.maxWidth
                    val slotHeight = constraints.maxHeight
                    val slotAspect = slotWidth.toFloat() / slotHeight
                    if (aspect > slotAspect) {
                        DpSize(
                            with(density) { slotWidth.toDp() },
                            with(density) { (slotWidth / aspect).toDp() },
                        )
                    } else {
                        DpSize(
                            with(density) { (slotHeight * aspect).toDp() },
                            with(density) { slotHeight.toDp() },
                        )
                    }
                } else {
                    null
                }
            // iOS shadows the image card, not the slot: a letterboxed cover carries its shadow on its own edges.
            val coverFrame = Modifier
                .then(
                    if (letterboxedSize != null) {
                        Modifier.size(letterboxedSize.width, letterboxedSize.height)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
                .then(if (followThumbnailPreference && display.showShadow) Modifier.shadow(3.dp, CoverShape) else Modifier)
            // The overlay sits outside the clip, so a corner badge keeps its slab flush with the cover corner.
            val coverImage = Modifier
                .matchParentSize()
                .clip(CoverShape)
                .then(
                    if (followThumbnailPreference && display.blurUnreadCovers && unread) {
                        Modifier.blur(6.dp)
                    } else {
                        Modifier
                    },
                )
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(modifier = coverFrame) {
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        imageLoader = loader,
                        contentScale = effectiveScale,
                        alignment = effectiveAlignment,
                        // Server thumbnails are often displayed upscaled (large grid 152dp@3x≈456px vs a 300px source)
                        // and look blurry with the default Low sampling; mirror the high-quality resampling of iOS ImageDecodeHelper.
                        filterQuality = FilterQuality.High,
                        modifier = coverImage,
                        onSuccess = { state ->
                            val image = state.result.image
                            if (image.height > 0) loadedAspect = image.width.toFloat() / image.height.toFloat()
                            onCoverBitmap?.invoke(image.toBitmap())
                        },
                        onError = { state ->
                            if (!usingFallback && fallbackResolvedUrl != null) {
                                // The server cover is unreachable (offline, or a book from another server):
                                // swap in the cover saved with the download.
                                primaryFailed = true
                            } else {
                                AppLog.w(
                                    "CoverArt",
                                    "cover load failed: $modelUrl (${state.result.throwable.message})",
                                )
                            }
                        },
                    )
                    overlay?.invoke(this)
                }
            }
        }
    }
}

/**
 * Cover URL carrying its cache-key parameters: `cv` is the refresh-interval stamp, `v` the manual
 * "Refresh cover" counter. Both are part of Coil's cache key, so bumping either forces a refetch.
 */
private fun coverUrlWithVersion(base: String?, stamp: Long, manualVersion: Int): String? {
    if (base == null) return null
    val params = buildList {
        if (stamp > 0L) add("cv=$stamp")
        if (manualVersion > 0) add("v=$manualVersion")
    }
    if (params.isEmpty()) return base
    val separator = if (base.contains('?')) "&" else "?"
    return base + separator + params.joinToString("&")
}

/**
 * Dashboard section header, aligned with the iOS DashboardSectionLayout header: the title carries a
 * chevron and the whole title row is the "open the full section" target, so there is no separate
 * "More" button competing with the section's own cards. [trailing] keeps the card-kind control at
 * the far right (the iOS header does the same).
 */
@Composable
fun SectionHeader(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                // 10dp above/below the title line keeps the header a 48dp touch target.
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        trailing?.invoke()
    }
}

/** Grid / large card: cover + optional progress bar + one title line + subtitle. Mirrors the KMReader GridCardView skeleton. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesGridCard(
    series: Series,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Used inside horizontal group rows: reserve the slot even without a progress bar so cards in a row keep the same height (avoids row height jumps when scrolling offscreen). */
    reserveProgressSpace: Boolean = false,
) {
    val display = LocalCardDisplay.current
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            CoverArt(
                title = series.title,
                hue = series.hue,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CoverAspectRatio),
                stableId = series.id,
                followThumbnailPreference = true,
                unread = series.unreadCount > 0,
                overlay = {
                    if (display.showUnreadIndicator && series.unreadCount > 0) {
                        UnreadCountBadge(
                            count = series.unreadCount,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                },
            )
            if (!display.coverOnly && display.textOverlay) {
                CardTextOverlay(
                    title = series.title,
                    // Mirrors iOS SeriesCardView: oneshot detail line = oneshotLine + book.closed icon
                    subtitle = if (series.oneshot) series.oneshotLineText() else series.subtitleText(),
                    modifier = Modifier.matchParentSize(),
                    subtitleLeadingIcon = series.oneshot,
                    downloadedMark = seriesDownloadedMark(series),
                )
            }
        }
        if (display.showProgressBar) {
            val barTopPadding = if (display.textOverlay) 3.dp else 6.dp
            val p = series.progress?.takeIf { it < 1f }
            if (p != null) {
                ProgressBar(p, topPadding = barTopPadding)
            } else if (reserveProgressSpace) {
                Spacer(Modifier.padding(top = barTopPadding).height(3.dp))
            }
        }
        if (!display.coverOnly && !display.textOverlay) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            val downloadedMark = seriesDownloadedMark(series)
            if (series.oneshot) {
                // Mirrors iOS SeriesCardView: oneshot detail line = book.closed icon + oneshotLine, with no book count
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = series.oneshotLineText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (downloadedMark != DownloadMark.None) {
                        Spacer(Modifier.width(4.dp))
                        DownloadedMarkIcon(downloadedMark, textSize = MaterialTheme.typography.bodySmall.fontSize)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = series.subtitleText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (downloadedMark != DownloadMark.None) {
                        Spacer(Modifier.width(4.dp))
                        DownloadedMarkIcon(downloadedMark, textSize = MaterialTheme.typography.bodySmall.fontSize)
                    }
                }
            }
        }
    }
}

/** Book large card: same structure as the series card, with progress taken from the current reading position. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGridCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Used inside horizontal group rows: reserve the slot even without a progress bar so cards in a row keep the same height (avoids row height jumps when scrolling offscreen). */
    reserveProgressSpace: Boolean = false,
) {
    val display = LocalCardDisplay.current
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            CoverArt(
                title = book.seriesTitle,
                hue = book.hue,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CoverAspectRatio),
                stableId = book.id,
                followThumbnailPreference = true,
                unread = book.progress == null,
                overlay = {
                    if (book.progress == 1f) {
                        CompletedIndicatorBadge(modifier = Modifier.align(Alignment.TopEnd))
                    }
                },
            )
            if (!display.coverOnly && display.textOverlay) {
                // Mirrors iOS BookCardView: the oneshot subtitle is the author line (with a small book icon);
                // otherwise the series title is shown per settings. The status line mirrors overlayDetail's
                // statusContent (page count / progress / last-read time / colored status).
                val (statusText, statusColor) = bookCardStatusLine(book, overlay = true)
                CardTextOverlay(
                    title = book.titleLine,
                    subtitle = when {
                        book.oneshot -> book.oneshotLineText()
                        display.showBookSeriesTitle -> book.seriesTitle
                        else -> null
                    },
                    modifier = Modifier.matchParentSize(),
                    subtitleLeadingIcon = book.oneshot,
                    statusLabel = statusText,
                    statusColor = statusColor,
                    downloadedMark = bookDownloadedMark(book),
                )
            }
        }
        if (display.showProgressBar) {
            val barTopPadding = if (display.textOverlay) 3.dp else 6.dp
            val p = book.progress?.takeIf { it < 1f }
            if (p != null) {
                ProgressBar(p, topPadding = barTopPadding)
            } else if (reserveProgressSpace) {
                Spacer(Modifier.padding(top = barTopPadding).height(3.dp))
            }
        }
        if (!display.coverOnly && !display.textOverlay) {
            Text(
                text = book.titleLine,
                style = MaterialTheme.typography.titleSmall,
                // Mirrors iOS BookCardView titleLineLimit: one line for oneshot or when a subtitle is shown
                maxLines = if (book.oneshot || display.showBookSeriesTitle) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (book.oneshot) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = book.oneshotLineText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (display.showBookSeriesTitle) {
                Text(
                    text = book.seriesTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Mirrors the iOS BookCardView statusContent detail line: in progress "xx% • N pages",
            // finished "✓ relative last-read time", unread "N pages"; unavailable / abnormal media use colored status text
            val (statusText, statusColor) = bookCardStatusLine(book, overlay = false)
            val downloadedMark = bookDownloadedMark(book)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (downloadedMark != DownloadMark.None) {
                    Spacer(Modifier.width(4.dp))
                    DownloadedMarkIcon(downloadedMark, textSize = MaterialTheme.typography.bodySmall.fontSize)
                }
            }
        }
    }
}

private fun normalizeCoverUrl(url: String, serverBaseUrl: String?): String {
    if (url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true) ||
        url.startsWith("file:", ignoreCase = true)
    ) {
        return url
    }
    val base = serverBaseUrl?.trimEnd('/') ?: return url
    return when {
        url.startsWith("//") -> {
            val scheme = runCatching { URI(base).scheme }.getOrNull() ?: "https"
            "$scheme:$url"
        }
        url.startsWith("/") -> base + url
        else -> "$base/$url"
    }
}

private fun sameOrigin(url: String, serverBaseUrl: String): Boolean = runCatching {
    val image = URI(url)
    val server = URI(serverBaseUrl)
    val urlPort = image.port.takeIf { it >= 0 } ?: if (image.scheme == "https") 443 else 80
    val serverPort = server.port.takeIf { it >= 0 } ?: if (server.scheme == "https") 443 else 80
    image.scheme.equals(server.scheme, ignoreCase = true) &&
        image.host?.equals(server.host, ignoreCase = true) == true &&
        urlPort == serverPort
}.getOrDefault(false)

/**
 * The one cover slot ratio, mirroring iOS `CoverAspectRatio.widthToHeight` = 1/√2.
 *
 * iOS never moves the slot: it is always √2, and the "preserve cover ratio" preference only decides whether the
 * cover scales to fit inside it (`displayedContentSize` + `alignment: .bottom`) or fills it. Android used to move the
 * slot instead — 2:3 with the preference on, 1:1 when off — so the same library rendered at three heights, and a 1:1
 * slot holding a 2:3 cover letterboxed it.
 */
internal const val CoverAspectRatio: Float = 0.7071f

/** Badge slab: mirrors iOS CornerBadgeSlab — a dark translucent slab shaped to hug the cover's top-right corner
 *  (topTrailing radius = cover radius 8, bottomLeading radius ≈ 8, all other corners square). */
private val BadgeSlabShape = RoundedCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
private val BadgeSlabColor = Color(0xD91F1F1F) // Color(white: 0.12).opacity(0.85)

/** Unread count badge (mirrors iOS UnreadCountBadge): white monospaced semibold digits on a slab hugging the cover's top-right corner. */
@Composable
private fun UnreadCountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 24.dp)
            .background(BadgeSlabColor, BadgeSlabShape)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = Color.White,
        )
    }
}

/** Finished badge (mirrors iOS CompletedIndicator): same slab as the count badge, white checkmark. */
@Composable
private fun CompletedIndicatorBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 24.dp)
            .background(BadgeSlabColor, BadgeSlabShape)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = stringResource(R.string.common_completed),
            tint = Color.White,
            modifier = Modifier.size(11.dp),
        )
    }
}

/** Mirrors iOS MediaStatus.color: the status text colors used on cards (READY is not shown). */
internal val MediaStatusOrange = Color(0xFFF57C00)
internal val MediaStatusYellow = Color(0xFFF9A825)

/** Mirrors iOS MediaStatus.label/color: returns (label, color) when the status is not READY. */
@Composable
internal fun mediaStatusLabel(status: String): Pair<String, Color>? = when (status) {
    "READY" -> null
    "ERROR" -> stringResource(R.string.media_status_error) to MaterialTheme.colorScheme.error
    "UNSUPPORTED" -> stringResource(R.string.media_status_unsupported) to MediaStatusOrange
    "OUTDATED" -> stringResource(R.string.media_status_outdated) to MediaStatusYellow
    else -> stringResource(R.string.media_status_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
}

/** Detail line for book cards, mirroring iOS BookCardView.statusContent: deleted → "Unavailable" in red;
 *  abnormal media → colored status; in progress → "xx% • N pages"; finished → "✓ relative last-read time"
 *  (falling back to page count); unread → "N pages".
 *  With overlay=true plain text uses the overlay white scale (mirrors iOS CardOverlayTextStyle.secondaryColor). */
@Composable
private fun bookCardStatusLine(book: Book, overlay: Boolean): Pair<String, Color> {
    val quiet = if (overlay) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    if (book.deleted) return stringResource(R.string.card_unavailable) to MaterialTheme.colorScheme.error
    mediaStatusLabel(book.mediaStatus)?.let { return it }
    return when {
        book.progress != null && book.progress > 0f && book.progress < 1f ->
            "${(book.progress * 100).toInt()}% • ${book.pageLabelText()}" to quiet
        book.progress == 1f -> "✓ ${book.completedLastReadText() ?: book.pageLabelText()}" to quiet
        else -> book.pageLabelText() to quiet
    }
}

/** Mirrors iOS MediaStatus.detailColor: the small status line on detail pages (yellow is unreadable on light backgrounds, so it maps to orange). */
@Composable
internal fun mediaStatusDetailLabel(status: String): Pair<String, Color>? = when (status) {
    "READY" -> null
    "ERROR" -> stringResource(R.string.media_status_error) to MaterialTheme.colorScheme.error
    "UNSUPPORTED", "OUTDATED" -> (if (status == "UNSUPPORTED") stringResource(R.string.media_status_unsupported) else stringResource(R.string.media_status_outdated)) to MediaStatusOrange
    else -> stringResource(R.string.media_status_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
}

/** Text overlay (mirrors iOS CardTextOverlay): a full-cover vertical gradient (transparent at the top → 88% black at the bottom),
 *  with the cover's 8dp corner radius; text is pinned to the bottom with h8 v6 padding.
 *  Mirrors iOS CardOverlayTextStack: subtitle on top (caption2 ≈ 11sp, white 0.85), title below (caption ≈ 12sp white),
 *  4dp spacing; the compact line height must be explicit — the inherited 24sp line height of bodyLarge makes the gap look doubled.
 *  subtitleLeadingIcon: prefixes the oneshot subtitle with a small book icon (mirrors iOS ContentIcon.oneshot / CompactLabelStyle). */
@Composable
private fun CardTextOverlay(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    subtitleLeadingIcon: Boolean = false,
    /** Media status text (shown under the title when not READY; mirrors overlayDetail's statusContent on iOS). */
    statusLabel: String? = null,
    statusColor: Color = Color.White,
    /** Downloaded marker drawn at the trailing edge of the detail line (mirrors iOS DownloadStatusIcon). */
    downloadedMark: DownloadMark = DownloadMark.None,
) {
    Box(
        modifier = modifier
            .clip(CoverShape)
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.5f to Color.Transparent,
                    0.65f to Color.Black.copy(alpha = 0.2f),
                    0.8f to Color.Black.copy(alpha = 0.45f),
                    1.0f to Color.Black.copy(alpha = 0.88f),
                )
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (subtitleLeadingIcon) {
                        Icon(
                            Icons.Outlined.Book,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(10.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                    }
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TightTextStyle,
                        modifier = Modifier.weight(1f),
                    )
                    if (statusLabel == null && downloadedMark != DownloadMark.None) {
                        Spacer(Modifier.width(4.dp))
                        DownloadedMarkIcon(
                            mark = downloadedMark,
                            textSize = 11.sp,
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TightTextStyle,
            )
            if (statusLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusLabel,
                        color = statusColor,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TightTextStyle,
                        modifier = Modifier.weight(1f),
                    )
                    if (downloadedMark != DownloadMark.None) {
                        Spacer(Modifier.width(4.dp))
                        DownloadedMarkIcon(
                            mark = downloadedMark,
                            textSize = 11.sp,
                            tint = statusColor.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

/** Card progress bar. */
@Composable
private fun ProgressBar(progress: Float, topPadding: Dp = 6.dp) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .height(3.dp),
    )
}

/** Cover average-color cache (mirrors iOS ThumbnailTintColorCache, key = "type#id"). */
private object CoverTintCache {
    private const val MaxEntries = 128
    private val cache = LinkedHashMap<String, Color>(MaxEntries, 0.75f, true)

    @Synchronized
    fun get(key: String): Color? = cache[key]

    @Synchronized
    fun put(key: String, color: Color) {
        cache[key] = color
        while (cache.size > MaxEntries) cache.remove(cache.keys.first())
    }
}

/** Mirrors iOS thumbnailTint: downscale to 8×8 for the average color, clamp HSV saturation to ≤0.8 and brightness to 0.2–0.45. */
private fun computeCoverTint(bitmap: Bitmap): Color? {
    // The session ImageLoader produces hardware bitmaps by default, and createScaledBitmap would draw the
    // source into a software Canvas and throw "Software rendering doesn't support hardware bitmaps" — copy to software first.
    val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        bitmap
    }
    val scaled = Bitmap.createScaledBitmap(software, 8, 8, true)
    var r = 0L
    var g = 0L
    var b = 0L
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val pixel = scaled.getPixel(x, y)
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
        }
    }
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (r / 64f).toInt(),
        (g / 64f).toInt(),
        (b / 64f).toInt(),
        hsv,
    )
    hsv[1] = hsv[1].coerceAtMost(0.8f)
    hsv[2] = hsv[2].coerceIn(0.2f, 0.45f)
    return Color.hsv(hsv[0], hsv[1], hsv[2])
}

/** Horizontal card palette (mirrors iOS HorizontalCardPalette): white-scale text when tinted, theme colors otherwise. */
internal class HorizontalCardPalette(
    val tinted: Boolean,
    val title: Color,
    val series: Color,
    val meta: Color,
)

/** The iOS cover width the √2 slot is derived from. The cover itself fills [HorizontalCardTextHeight] while keeping
 *  its √2 aspect, so the extra card height widens it to ≈47.2dp instead of stretching it. */
private val HorizontalCardCoverWidth = 43.dp

/** Height of a horizontal card's text column: the √2 cover slot plus the glyph room the last design line needs.
 *  The design's four lines (2×15.5sp title + 3dp gap + 13sp series + 12sp status) nominally fill the 60.8dp slot, but a
 *  Text's layout box can be shorter than the glyphs it paints: Compose honours an explicit lineHeight for spacing while
 *  still placing the baseline on the font's own ascent, so the bottom of a CJK baseline (and any descender or full-width
 *  parenthesis) can paint a few dp below its box. The 6dp of slack is that room: without it the series line ends flush
 *  with the block edge and any glyph ink below the baseline is cut off (see [HorizontalCardContainer]).
 *  Cards in a row must not differ in height, so this cannot be derived from the text. */
private val HorizontalCardTextHeight = HorizontalCardCoverWidth / CoverAspectRatio + 6.dp

/** Tight text style with font padding disabled: in fixed-height text columns (horizontal cards / overlays)
 *  the default includeFontPadding of the first and last lines pushes content out of the visible area, so it must be off. */
internal val TightTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
private val HorizontalCardShape = RoundedCornerShape(12.dp)

/**
 * Horizontal card skeleton (mirrors iOS HorizontalCardSkeleton): corner radius 12, average cover color as the
 * background (falling back to surface without a tint), padding 8, a √2 cover filling the card height (≈47.2×66.8dp),
 * and a text column one glyph-slack taller than the √2 slot (≈66.8dp, see [HorizontalCardTextHeight]).
 *
 * The card height is fixed — the √2 slot plus the glyph slack — so every card in a row is the same size. [status]
 * owns the bottom of the text column, [block] (title + series) gets everything above it. The block is deliberately
 * not clipped: glyph ink can paint below its line box, and a clip on the block boundary shaves the bottom of the
 * series line once the status line takes its (font-dependent) height out of the column. The slack reserved above
 * [status] is what keeps that ink clear of the progress line for every font.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HorizontalCardContainer(
    tintKey: String,
    coverStableId: Int?,
    coverTitle: String,
    hue: Float,
    blurUnread: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    block: @Composable ColumnScope.(HorizontalCardPalette) -> Unit,
    status: @Composable (HorizontalCardPalette) -> Unit,
) {
    var tint by remember(tintKey) { mutableStateOf(CoverTintCache.get(tintKey)) }
    val reportTint: (Bitmap) -> Unit = remember(tintKey) {
        { bitmap: Bitmap ->
            // The tint is purely decorative; no bitmap failure may crash the card
            runCatching { computeCoverTint(bitmap) }.getOrNull()?.let { color ->
                CoverTintCache.put(tintKey, color)
                tint = color
            }
            Unit
        }
    }
    val fallback = MaterialTheme.colorScheme.surface
    val background by animateColorAsState(targetValue = tint ?: fallback, label = "horizontalCardTint")
    val palette = HorizontalCardPalette(
        tinted = tint != null,
        title = if (tint != null) Color.White else MaterialTheme.colorScheme.onSurface,
        series = if (tint != null) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface,
        meta = if (tint != null) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = modifier
            .shadow(4.dp, HorizontalCardShape)
            .clip(HorizontalCardShape)
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = coverTitle,
            hue = hue,
            modifier = Modifier
                .height(HorizontalCardTextHeight)
                .aspectRatio(CoverAspectRatio)
                .then(if (blurUnread) Modifier.blur(6.dp) else Modifier),
            stableId = coverStableId,
            contentScale = ContentScale.Crop,
            onCoverBitmap = reportTint,
        )
        Spacer(Modifier.width(8.dp))
        // The four design text lines (2×15.5sp title + 3dp gap + 13sp series + 12sp status) only fit the √2 slot with
        // the leading the design assumes, so the column keeps the slack of HorizontalCardTextHeight above the cover;
        // the card text is also pinned to at most the design's own font scale, because following a larger system font
        // size would need a taller card and cards in the same row must not differ in height.
        val density = LocalDensity.current
        val cardTextDensity = remember(density) {
            Density(density.density, density.fontScale.coerceAtMost(1f))
        }
        CompositionLocalProvider(LocalDensity provides cardTextDensity) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(HorizontalCardTextHeight),
            ) {
                // No clipToBounds here: the block's line boxes fit, but their glyph ink can end a few dp below the
                // baseline (see HorizontalCardTextHeight). Clipping the block boundary sliced exactly that ink and
                // flattened the bottom of the series line. The status line still owns the bottom because it is laid
                // out below the weighted block, and the slack in HorizontalCardTextHeight keeps ink off its glyphs.
                Column(
                    modifier = Modifier.weight(1f),
                    content = { block(palette) },
                )
                status(palette)
            }
        }
    }
}

/**
 * Horizontal card (mirrors iOS BookHorizontalCardView): title "Volume - Book title" (semibold 13, up to 2 lines)
 * + series line 11 + bottom progress text 10 ("xx% • N pages", or "✓ relative last-read time" when finished), no
 * progress bar control; the title is dimmed when finished.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookHorizontalCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val display = LocalCardDisplay.current
    val completed = book.progress == 1f
    // Mirrors iOS BookHorizontalCardView statusContent: deleted → "Unavailable" in red, abnormal media → colored status;
    // the meta line shows the relative last-read time when finished (falling back to page count)
    val statusDisplay = mediaStatusLabel(book.mediaStatus)
    val metaStatusColor = when {
        book.deleted -> MaterialTheme.colorScheme.error
        else -> statusDisplay?.second
    }
    val metaText = when {
        book.deleted -> stringResource(R.string.card_unavailable)
        statusDisplay != null -> statusDisplay.first
        book.progress != null && book.progress > 0f && book.progress < 1f ->
            "${(book.progress * 100).toInt()}% • ${book.pageLabelText()}"
        completed -> "✓ ${book.completedLastReadText() ?: book.pageLabelText()}"
        else -> book.pageLabelText()
    }
    HorizontalCardContainer(
        tintKey = "book#${book.id}",
        coverStableId = book.id,
        coverTitle = book.seriesTitle,
        hue = book.hue,
        blurUnread = display.blurUnreadCovers && book.progress == null,
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        block = { palette ->
            val titleColor = when {
                completed && palette.tinted -> Color.White.copy(alpha = 0.7f)
                completed -> palette.meta
                else -> palette.title
            }
            Text(
                text = book.titleLine,
                fontSize = 13.sp,
                lineHeight = 15.5.sp, // Explicit line height: changing only fontSize inherits bodyLarge's 24sp and clips the two-line title
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TightTextStyle, // includeFontPadding=false, otherwise the extra padding of the first/last lines pushes the bottom progress out of the column height
            )
            Spacer(Modifier.height(3.dp))
            if (book.oneshot) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Book,
                        contentDescription = null,
                        tint = palette.series,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = book.oneshotLineText(),
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        color = palette.series,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TightTextStyle,
                    )
                }
            } else {
                Text(
                    text = book.seriesTitle,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    color = palette.series,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TightTextStyle,
                )
            }
        },
        status = { palette ->
            val downloadedMark = bookDownloadedMark(book)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = metaText,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = metaStatusColor ?: palette.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TightTextStyle,
                    modifier = Modifier.weight(1f),
                )
                if (downloadedMark != DownloadMark.None) {
                    Spacer(Modifier.width(4.dp))
                    DownloadedMarkIcon(downloadedMark, textSize = 10.sp, tint = palette.meta)
                }
            }
        },
    )
}

/** Book list row (used on detail pages). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListRow(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val display = LocalCardDisplay.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = book.seriesTitle,
            hue = book.hue,
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(CoverAspectRatio),
            stableId = book.id,
            followThumbnailPreference = true,
            unread = book.progress == null,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Mirrors the iOS BookRowView status area: colored status text replaces the page count for abnormal media
            val rowStatus = mediaStatusLabel(book.mediaStatus)
            val downloadedMark = bookDownloadedMark(book)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rowStatus?.first ?: book.pageLabelText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = rowStatus?.second ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (downloadedMark != DownloadMark.None) {
                    Spacer(Modifier.width(4.dp))
                    DownloadedMarkIcon(downloadedMark, textSize = MaterialTheme.typography.bodySmall.fontSize)
                }
            }
            if (display.showProgressBar) {
                book.progress?.let { p ->
                    if (p < 1f) {
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .height(3.dp),
                        )
                    }
                }
            }
        }
        if (book.progress == 1f) {
            Text(
                text = stringResource(R.string.read_status_read),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** Series list row (list layout; mirrors iOS SeriesRowView). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesListRow(
    series: Series,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val display = LocalCardDisplay.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = series.title,
            hue = series.hue,
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(CoverAspectRatio),
            stableId = series.id,
            followThumbnailPreference = true,
            unread = series.progress == null,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val downloadedMark = seriesDownloadedMark(series)
            if (series.oneshot) {
                // Mirrors iOS SeriesRowView: oneshot line = book.closed icon + oneshotLine
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = series.oneshotLineText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (downloadedMark != DownloadMark.None) {
                        Spacer(Modifier.width(4.dp))
                        DownloadedMarkIcon(downloadedMark, textSize = MaterialTheme.typography.bodySmall.fontSize)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = series.subtitleText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (downloadedMark != DownloadMark.None) {
                        Spacer(Modifier.width(4.dp))
                        DownloadedMarkIcon(downloadedMark, textSize = MaterialTheme.typography.bodySmall.fontSize)
                    }
                }
            }
            if (display.showProgressBar) {
                series.progress?.let { p ->
                    if (p < 1f) {
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .height(3.dp),
                        )
                    }
                }
            }
        }
        if (series.progress == 1f) {
            Text(
                text = stringResource(R.string.read_status_read),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
