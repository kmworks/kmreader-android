package com.kmworks.kmreader.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.SeriesGridCard

private val searchScopes = listOf("全部", "系列", "书籍", "阅读列表")

/** 搜索标签：搜索框 + 范围 chips + 结果网格。对齐 KMReader browse(search role) 标签。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(onOpenSeries: (Series) -> Unit) {
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(searchScopes.first()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                actions = {
                    // 库范围切换（全部/已钉选/单库），对应 LibraryScopeMenu；过滤搜索结果
                    IconButton(onClick = { }) {
                        Icon(Icons.Outlined.Checklist, contentDescription = "库范围")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("搜索系列、书籍、作者…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                searchScopes.forEach { s ->
                    FilterChip(
                        selected = s == scope,
                        onClick = { scope = s },
                        label = { Text(s) },
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(SampleData.searchResults, key = { it.id }) { series ->
                    SeriesGridCard(
                        series = series,
                        onClick = { onOpenSeries(series) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
