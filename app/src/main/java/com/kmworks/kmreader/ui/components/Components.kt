package com.kmworks.kmreader.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.toBitmap
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
    /** 封面加载成功回调（横向卡提取平均色 tint 用，对齐 iOS ThumbnailTintColorCache）。 */
    onCoverBitmap: ((Bitmap) -> Unit)? = null,
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
                onSuccess = { state -> onCoverBitmap?.invoke(state.result.image.toBitmap()) },
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
            if (!display.coverOnly && display.textOverlay) {
                CardTextOverlay(
                    title = series.title,
                    subtitle = series.subtitle,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        if (display.showProgressBar) {
            series.progress?.let { p ->
                if (p < 1f) ProgressBar(p, topPadding = if (display.textOverlay) 3.dp else 6.dp)
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
            if (!display.coverOnly && display.textOverlay) {
                CardTextOverlay(
                    title = book.titleLine,
                    subtitle = if (display.showBookSeriesTitle) book.seriesTitle else null,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        if (display.showProgressBar) {
            book.progress?.let { p ->
                if (p < 1f) ProgressBar(p, topPadding = if (display.textOverlay) 3.dp else 6.dp)
            }
        }
        if (!display.coverOnly && !display.textOverlay) {
            Text(
                text = book.titleLine,
                style = MaterialTheme.typography.titleSmall,
                maxLines = if (display.showBookSeriesTitle) 1 else 2,
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

/** 文字叠加（对齐 iOS CardTextOverlay）：全封面纵向渐变（上半透明 → 底部 88% 黑），
 *  圆角随封面 8dp；文字 pinned 底部，caption 白 / caption2 白 0.85，padding h8 v6。 */
@Composable
private fun CardTextOverlay(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
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
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

/** 封面平均色缓存（对齐 iOS ThumbnailTintColorCache，key = "type#id"）。 */
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

/** 对齐 iOS thumbnailTint：缩到 8×8 取平均色，HSV 饱和度 ≤0.8、亮度 clamp 到 0.2–0.45。 */
private fun computeCoverTint(bitmap: Bitmap): Color {
    val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
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

/** 横向卡配色（对齐 iOS HorizontalCardPalette）：有 tint 用白系文字，无 tint 用主题色。 */
internal class HorizontalCardPalette(
    val tinted: Boolean,
    val title: Color,
    val series: Color,
    val meta: Color,
)

/** iOS CoverAspectRatio.widthToHeight = 1/√2。 */
private const val Sqrt2CoverAspect: Float = 0.7071f

private val HorizontalCardCoverWidth = 43.dp
private val HorizontalCardShape = RoundedCornerShape(12.dp)

/**
 * 横向卡骨架（对齐 iOS HorizontalCardSkeleton）：圆角 12、封面平均色打底（无 tint 退回 surface）、
 * padding 8、封面 43dp 宽按 √2 槽位强制裁切，文字列与封面等高（≈60.8dp）。
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
    text: @Composable ColumnScope.(HorizontalCardPalette) -> Unit,
) {
    var tint by remember(tintKey) { mutableStateOf(CoverTintCache.get(tintKey)) }
    val reportTint = remember(tintKey) {
        { bitmap: Bitmap ->
            val color = computeCoverTint(bitmap)
            CoverTintCache.put(tintKey, color)
            tint = color
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
                .width(HorizontalCardCoverWidth)
                .aspectRatio(Sqrt2CoverAspect)
                .then(if (blurUnread) Modifier.blur(6.dp) else Modifier),
            stableId = coverStableId,
            contentScale = ContentScale.Crop,
            onCoverBitmap = reportTint,
        )
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .height(HorizontalCardCoverWidth / Sqrt2CoverAspect),
        ) {
            text(palette)
        }
    }
}

/**
 * 横向卡片（对齐 iOS BookHorizontalCardView）：标题「卷号 - 书名」（semibold 13，最多 2 行）
 * + 系列行 11 + 底部进度文本 10（"xx% • N 页"），无进度条控件；已读完标题降透明度。
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
    val metaText = when {
        book.progress != null && book.progress > 0f && book.progress < 1f ->
            "${(book.progress * 100).toInt()}% • ${book.pageLabel}"
        completed -> "✓ ${book.pageLabel}"
        else -> book.pageLabel
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
    ) { palette ->
        val titleColor = when {
            completed && palette.tinted -> Color.White.copy(alpha = 0.7f)
            completed -> palette.meta
            else -> palette.title
        }
        Text(
            text = book.titleLine,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = book.seriesTitle,
            fontSize = 11.sp,
            color = palette.series,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = metaText,
            fontSize = 10.sp,
            color = palette.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/** 系列列表行（列表布局用，对齐 iOS SeriesRowView）。 */
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
                .aspectRatio(seriesAspect(display))
                .coverDecoration(display, unread = series.progress == null),
            stableId = series.id,
            contentScale = coverScale(display),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = series.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                text = "已读",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
