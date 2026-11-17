package com.kmworks.kmreader.ui.dashboard

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmworks.kmreader.data.ReadListContinuationUi
import com.kmworks.kmreader.ui.components.HorizontalCardContainer

/**
 * 阅读列表续读横向卡（对齐 iOS ReadListContinuationHorizontalCardView）：
 * 列表名（semibold 13，最多 2 行）+ 下一本书名 11 + 进度文本 10，无进度条控件；
 * 卡面用封面平均色 tint 打底。
 */
@Composable
fun ReadListContinuationCard(
    continuation: ReadListContinuationUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stableId: Int? = null,
) {
    HorizontalCardContainer(
        tintKey = "readList#${stableId ?: continuation.readListId}",
        coverStableId = stableId,
        coverTitle = continuation.readListName,
        hue = continuation.hue,
        blurUnread = false,
        onClick = onClick,
        modifier = modifier,
    ) { palette ->
        Text(
            text = continuation.readListName,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = continuation.nextBookTitle,
            fontSize = 11.sp,
            color = palette.series,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = continuation.progressLabel,
            fontSize = 10.sp,
            color = palette.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
