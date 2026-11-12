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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.SampleSystemData

/** EPUB reader skeleton (mirrors EpubReaderView): text page placeholder + DIVINA-style chrome. */
@Composable
fun EpubReaderScreen(book: Book, onBack: () -> Unit) {
    val pageCount = book.pageCount.coerceAtLeast(1)
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var epubSettings by remember { mutableStateOf(EpubReaderSettings()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { chromeVisible = !chromeVisible },
    ) {
        // Text page placeholder
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "EPUB 正文占位",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                "第 ${currentPage} 屏 · ${epubSettings.theme} · ${epubSettings.fontSizeSp.toInt()} sp",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // Top bar (mirrors DIVINA chrome)
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
                        "第 ${book.number} 册 · EPUB",
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
                    EpubReaderOptionsMenu(
                        expanded = menuExpanded,
                        onOpenSettings = {
                            menuExpanded = false
                            showSettings = true
                        },
                        onOpenToc = {
                            menuExpanded = false
                            showToc = true
                        },
                        onOpenPageJump = {
                            menuExpanded = false
                            showPageJump = true
                        },
                        onDismiss = { menuExpanded = false },
                    )
                }
            }
        }

        // Bottom bar: progress slider + page capsule (mirrors DIVINA chrome)
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
        EpubSettingsSheet(
            settings = epubSettings,
            onSettingsChange = { epubSettings = it },
            customFonts = SampleSystemData.customFonts,
            onOpenCustomFonts = { /* wiring point: open CustomFontsScreen */ },
            onDismiss = { showSettings = false },
        )
    }
    if (showToc) {
        ReaderTocSheet(
            currentPage = currentPage,
            onJump = {
                currentPage = it.coerceIn(1, pageCount)
                showToc = false
            },
            onDismiss = { showToc = false },
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

/** Overflow menu for the EPUB chrome (settings / toc / page jump). */
@Composable
private fun EpubReaderOptionsMenu(
    expanded: Boolean,
    onOpenSettings: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenPageJump: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("阅读器设置") }, onClick = onOpenSettings)
        DropdownMenuItem(text = { Text("目录") }, onClick = onOpenToc)
        DropdownMenuItem(text = { Text("跳页") }, onClick = onOpenPageJump)
    }
}
