package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.kmworks.kmreader.core.data.CoverUrlRegistry
import com.kmworks.kmreader.core.data.ImageLoaderEntryPoint
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import dagger.hilt.android.EntryPointAccessors
import java.net.URI

private val CoverShape = RoundedCornerShape(8.dp)

/**
 * 封面：纯色块 + 首字占位。
 *
 * 两种取图方式，互不重叠：
 * - [stableId]：Komga 内容封面，URL 由 [CoverUrlRegistry] 按 stableId 解析，始终用会话 ImageLoader
 *   （带认证）。会话就绪前先显示占位，绝不退回到无认证的单例 loader。
 * - [imageUrl]：外部绝对地址（Komf 识别结果、tracker 远程封面），走单例 loader，
 *   避免把 Komga 凭据发送到第三方图片站。
 */
@Composable
fun CoverArt(
    title: String,
    hue: Float,
    modifier: Modifier = Modifier,
    stableId: Int? = null,
    imageUrl: String? = null,
    coverVersion: Int = 0,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        modifier = modifier
            .clip(CoverShape)
            .background(Color.hsv(hue, 0.42f, 0.60f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(1),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White.copy(alpha = 0.85f),
        )
        val context = LocalContext.current
        val entryPoint = remember(context) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                ImageLoaderEntryPoint::class.java,
            )
        }
        val session = remember(entryPoint) { entryPoint.sessionManager() }
        val clients by session.clients.collectAsState()
        val sessionLoader by session.imageLoader.collectAsState()
        val externalLoader = remember(context) { SingletonImageLoader.get(context) }
        val serverBaseUrl = clients?.baseUrl
        val resolvedUrl = remember(stableId, imageUrl, serverBaseUrl, coverVersion) {
            val base = if (stableId != null) {
                CoverUrlRegistry.url(stableId, clients)
            } else {
                imageUrl
            }
            val absolute = base?.let { normalizeCoverUrl(it, serverBaseUrl) }
            if (absolute != null && coverVersion > 0) {
                // 「刷新封面」：附加版本参数换缓存键，强制重新拉取服务端封面
                val separator = if (absolute.contains('?')) "&" else "?"
                "$absolute${separator}v=$coverVersion"
            } else {
                absolute
            }
        }
        val serverOwnedImage = resolvedUrl != null && serverBaseUrl != null &&
            sameOrigin(resolvedUrl, serverBaseUrl)
        // Komga/同域封面必须走带认证的会话 loader；真正的第三方地址才用无认证单例 loader。
        val loader = if (stableId != null || serverOwnedImage) sessionLoader else externalLoader

        LaunchedEffect(stableId, imageUrl, clients, resolvedUrl) {
            if (stableId != null && clients != null && resolvedUrl == null) {
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
            if (stableId == null && !imageUrl.isNullOrBlank() && imageUrl != resolvedUrl) {
                AppLog.d("CoverArt", "normalized cover url: $imageUrl -> $resolvedUrl title=$title")
            }
        }

        if (resolvedUrl != null && loader != null) {
            AsyncImage(
                model = resolvedUrl,
                contentDescription = null,
                imageLoader = loader,
                contentScale = contentScale,
                // 服务端缩略图常被放大显示（大网格 152dp@3x≈456px vs 300px 源），
                // 默认 Low 采样发糊；对齐 iOS ImageDecodeHelper 的高质量重采样。
                filterQuality = FilterQuality.High,
                modifier = Modifier.matchParentSize(),
                onError = { state ->
                    AppLog.w(
                        "CoverArt",
                        "cover load failed: $resolvedUrl (${state.result.throwable.message})",
                    )
                },
            )
        }
    }
}

/** 分区标题行：左侧标题，右侧「更多」。对应 KMReader DashboardSectionLayout 的 header。 */
@Composable
fun SectionHeader(
    title: String,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
        TextButton(onClick = onMore) { Text("更多") }
    }
}

/** 网格/大卡：封面 + 可选进度条 + 标题一行 + 副标题。对应 KMReader GridCardView 骨架。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesGridCard(
    series: Series,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
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
                    .aspectRatio(seriesAspect(display))
                    .coverDecoration(display, unread = series.unreadCount > 0),
                stableId = series.id,
                contentScale = coverScale(display),
            )
            if (display.showUnreadIndicator && series.unreadCount > 0) {
                UnreadCountBadge(
                    count = series.unreadCount,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            if (display.textOverlay) {
                CardTextOverlay(
                    title = series.title,
                    subtitle = series.subtitle,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        if (display.showProgressBar) {
            series.progress?.let { p ->
                if (p < 1f) ProgressBar(p)
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
            Text(
                text = series.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 书籍大卡：与系列卡同构，进度来自当前阅读位置。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGridCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
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
                    .aspectRatio(seriesAspect(display))
                    .coverDecoration(display, unread = book.progress == null),
                stableId = book.id,
                contentScale = coverScale(display),
            )
            if (book.progress == 1f) {
                CompletedIndicatorBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
            if (display.textOverlay) {
                CardTextOverlay(
                    title = book.number,
                    subtitle = if (display.showBookSeriesTitle) book.seriesTitle else null,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        if (display.showProgressBar) {
            book.progress?.let { p ->
                if (p < 1f) ProgressBar(p)
            }
        }
        if (!display.coverOnly && !display.textOverlay) {
            Text(
                text = book.number,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (display.showBookSeriesTitle) {
                Text(
                    text = book.seriesTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun normalizeCoverUrl(url: String, serverBaseUrl: String?): String {
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
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

/** 卡片封面宽高比：保持原始比例时用漫画封面比，否则方形裁切。 */
private fun seriesAspect(display: CardDisplayOptions): Float =
    if (display.preserveAspectRatio) 2f / 3f else 1f

private fun coverScale(display: CardDisplayOptions): ContentScale =
    if (display.preserveAspectRatio) ContentScale.Crop else ContentScale.Fit

/** 封面装饰：投影 + 未读模糊（对齐 thumbnailShowShadow / thumbnailBlurUnreadCovers）。 */
private fun Modifier.coverDecoration(display: CardDisplayOptions, unread: Boolean): Modifier =
    this
        .then(if (display.showShadow) Modifier.shadow(3.dp, CoverShape) else Modifier)
        .then(if (display.blurUnreadCovers && unread) Modifier.blur(6.dp) else Modifier)

/** 角标底板：对齐 iOS CornerBadgeSlab——深色半透明 slab，形状贴合封面右上角
 *  （topTrailing 圆角 = 封面圆角 8，bottomLeading 圆角 ≈ 8，其余直角）。 */
private val BadgeSlabShape = RoundedCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
private val BadgeSlabColor = Color(0xD91F1F1F) // Color(white: 0.12).opacity(0.85)

/** 未读数字角标（对齐 iOS UnreadCountBadge）：白色等宽数字 semibold，底板贴合封面右上角。 */
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

/** 已读完角标（对齐 iOS CompletedIndicator）：与数字角标同底板，白色 checkmark。 */
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
            contentDescription = "已读完",
            tint = Color.White,
            modifier = Modifier.size(11.dp),
        )
    }
}

/** 文字叠加在封面底部（对齐 cardTextOverlayMode）。 */
@Composable
private fun CardTextOverlay(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 卡片进度条。 */
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

/** 横向卡片：左封面小图 + 右文本块 + 进度。对应 KMReader horizontal 卡片（继续阅读等分区）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookHorizontalCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val display = LocalCardDisplay.current
    Row(
        modifier = modifier
            .clip(CoverShape)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            title = book.seriesTitle,
            hue = book.hue,
            modifier = Modifier
                .width(52.dp)
                .aspectRatio(seriesAspect(display))
                .coverDecoration(display, unread = book.progress == null),
            stableId = book.id,
            contentScale = coverScale(display),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "${book.seriesTitle} ${book.number}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.pageLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (display.showProgressBar) {
                book.progress?.let { p ->
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
}

/** 书籍列表行（详情页用）。 */
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
                .aspectRatio(seriesAspect(display))
                .coverDecoration(display, unread = book.progress == null),
            stableId = book.id,
            contentScale = coverScale(display),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.pageLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                text = "已读",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
