package com.kmworks.kmreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.Book

/** PDF reader skeleton (mirrors PdfReaderView / PdfControlsOverlayView): doc page placeholder + DIVINA-style chrome. */
@Composable
fun PdfReaderScreen(book: Book, onBack: () -> Unit) {
    val pageCount = book.pageCount.coerceAtLeast(1)
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var pdfSettings by remember { mutableStateOf(PdfReaderSettings()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { chromeVisible = !chromeVisible },
    ) {
        if (pdfSettings.continuousScroll) {
            // Continuous scroll: stacked page placeholders
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val window = ((currentPage - 2).coerceAtLeast(1))..((currentPage + 3).coerceAtMost(pageCount))
                window.forEach { page ->
                    PdfPagePlaceholder(page = page)
                }
            }
        } else {
            // Paged: single page placeholder
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                PdfPagePlaceholder(page = currentPage)
            }
        }

        // Mini progress bar while chrome is hidden (mirrors showProgressBarWhileReading)
        if (!chromeVisible && pdfSettings.showProgressWhileReading) {
            LinearProgressIndicator(
                progress = { currentPage / pageCount.toFloat() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = Color.White.copy(alpha = 0.8f),
                trackColor = Color.White.copy(alpha = 0.2f),
            )
        }

        // Top bar (mirrors PdfControlsOverlayView top bar)
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        book.seriesTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Text(
                        "第 ${book.number} 册 · PDF",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("连续滚动", modifier = Modifier.weight(1f))
                                    if (pdfSettings.continuousScroll) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                pdfSettings = pdfSettings.copy(continuousScroll = !pdfSettings.continuousScroll)
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("阅读器设置") },
                            onClick = {
                                menuExpanded = false
                                showSettings = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("跳页") },
                            onClick = {
                                menuExpanded = false
                                showPageJump = true
                            },
                        )
                    }
                }
            }
        }

        // Bottom bar: slider + page capsule
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Slider(
                    value = currentPage.toFloat(),
                    onValueChange = { currentPage = it.toInt().coerceIn(1, pageCount) },
                    valueRange = 1f..pageCount.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                PageJumpCapsule(
                    page = currentPage,
                    pageCount = pageCount,
                    onClick = { showPageJump = true },
                )
            }
        }
    }

    if (showSettings) {
        PdfPreferencesSheet(
            settings = pdfSettings,
            onSettingsChange = { pdfSettings = it },
            onDismiss = { showSettings = false },
        )
    }
    if (showPageJump) {
        ReaderPageJumpSheet(
            currentPage = currentPage,
            pageCount = pageCount,
            onJump = {
                currentPage = it.coerceIn(1, pageCount)
                showPageJump = false
            },
            onDismiss = { showPageJump = false },
        )
    }
}

/** Gray document page placeholder. */
@Composable
private fun PdfPagePlaceholder(page: Int) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(0.72f)
            .background(Color(0xFF2A2A2C), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "PDF 第 $page 页",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}
