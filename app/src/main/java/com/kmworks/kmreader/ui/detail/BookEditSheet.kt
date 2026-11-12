package com.kmworks.kmreader.ui.detail

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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.kmworks.kmreader.core.api.model.WebLinkUpdateDto
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookDetailMeta
import com.kmworks.kmreader.data.Creator

/** 书籍元数据编辑草稿（对齐 iOS BookMetadataUpdate 的字段集合）。 */
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
)

/**
 * 只提交改动字段并置锁（对齐 iOS BookMetadataUpdate.toAPIDict(against:)）；
 * 全部未改动时返回 null，表示无需请求。
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

    var changed = false
    val numberSortValue = numberSort.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()

    fun <T> diff(current: T, before: T): T? = if (current != before) {
        changed = true
        current
    } else {
        null
    }

    val titleValue = diff(title.trim(), baseTitle)
    val numberValue = diff(number.trim(), originalNumber)
    val sortValue = diff(numberSortValue, originalSort)
    val releaseValue = diff(releaseDate.trim(), originalRelease)
    val isbnValue = diff(isbn.trim(), originalIsbn)
    val summaryValue = diff(summary.trim(), originalSummary)
    val authorsChanged = authors != originalAuthors
    val tagsChanged = tags != originalTags
    val linksChanged = links != originalLinks
    if (authorsChanged || tagsChanged || linksChanged) changed = true
    if (!changed) return null

    return BookMetadataUpdateDto(
        title = titleValue,
        titleLock = titleValue?.let { true },
        number = numberValue,
        numberLock = numberValue?.let { true },
        numberSort = sortValue,
        numberSortLock = sortValue?.let { true },
        releaseDate = releaseValue,
        releaseDateLock = releaseValue?.let { true },
        isbn = isbnValue,
        isbnLock = isbnValue?.let { true },
        summary = summaryValue,
        summaryLock = summaryValue?.let { true },
        authors = if (authorsChanged) {
            authors.map { AuthorUpdateDto(name = it.name, role = it.roleToken) }
        } else {
            null
        },
        authorsLock = if (authorsChanged) true else null,
        tags = if (tagsChanged) tags.toSet() else null,
        tagsLock = if (tagsChanged) true else null,
        links = if (linksChanged) {
            links.map { (label, url) -> WebLinkUpdateDto(label = label, url = url) }
        } else {
            null
        },
        linksLock = if (linksChanged) true else null,
    )
}

/** 自定义角色保留原文，预定义角色用服务端 token。 */
internal val Creator.roleToken: String
    get() = if (role == AuthorRole.Custom) {
        customRole?.takeIf { it.isNotBlank() } ?: AuthorRole.Custom.token
    } else {
        role.token
    }

private enum class BookEditTab(val label: String) {
    General("通用"), Authors("作者"), Tags("标签"), Links("链接"),
}

/**
 * 书籍元数据编辑弹层，字段对齐 KMReader/Features/Book/Views/BookEditSheet.swift：
 * 通用（标题/号码/排序号/发行日期/ISBN/摘要）、作者（姓名+角色）、标签、链接。
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by rememberSaveable { mutableStateOf(BookEditTab.General) }

    var title by rememberSaveable { mutableStateOf(book.title) }
    var number by rememberSaveable { mutableStateOf(meta?.number.orEmpty()) }
    var numberSort by rememberSaveable {
        mutableStateOf(meta?.numberSort?.let { formatNumberSort(it) }.orEmpty())
    }
    var releaseDate by rememberSaveable { mutableStateOf(meta?.releaseDate.orEmpty()) }
    var isbn by rememberSaveable { mutableStateOf(meta?.isbn.orEmpty()) }
    var summary by rememberSaveable { mutableStateOf(meta?.summary.orEmpty()) }

    val authors = remember { mutableStateListOf<Creator>().apply { addAll(meta?.authors.orEmpty()) } }
    val tags = remember { mutableStateListOf<String>().apply { addAll(meta?.tags.orEmpty()) } }
    val links = remember {
        mutableStateListOf<Pair<String, String>>().apply { addAll(meta?.links.orEmpty()) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "编辑书籍",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BookEditTab.entries.forEach { entry ->
                    FilterChip(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = { Text(entry.label) },
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
                        onTitleChange = { title = it },
                        number = number,
                        onNumberChange = { number = it },
                        numberSort = numberSort,
                        onNumberSortChange = { numberSort = it },
                        releaseDate = releaseDate,
                        onReleaseDateChange = { releaseDate = it },
                        isbn = isbn,
                        onIsbnChange = { isbn = it },
                        summary = summary,
                        onSummaryChange = { summary = it },
                    )

                    BookEditTab.Authors -> AuthorsTab(
                        authors = authors,
                        onRemove = { authors.removeAt(it) },
                        onAdd = { name, role, custom ->
                            authors.add(Creator(name = name, role = role, customRole = custom))
                        },
                    )

                    BookEditTab.Tags -> TagsTab(
                        tags = tags,
                        onRemove = { tags.removeAt(it) },
                        onAdd = { tag -> if (tag !in tags) tags.add(tag) },
                    )

                    BookEditTab.Links -> LinksTab(
                        links = links,
                        onRemove = { links.removeAt(it) },
                        onAdd = { label, url -> links.add(label to url) },
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
                ) { Text("取消") }
                Button(
                    onClick = {
                        onSave(
                            BookMetadataDraft(
                                title = title,
                                number = number,
                                numberSort = numberSort,
                                releaseDate = releaseDate,
                                isbn = isbn,
                                summary = summary,
                                authors = authors.toList(),
                                tags = tags.toList(),
                                links = links.toList(),
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
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralTab(
    title: String,
    onTitleChange: (String) -> Unit,
    number: String,
    onNumberChange: (String) -> Unit,
    numberSort: String,
    onNumberSortChange: (String) -> Unit,
    releaseDate: String,
    onReleaseDateChange: (String) -> Unit,
    isbn: String,
    onIsbnChange: (String) -> Unit,
    summary: String,
    onSummaryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = title,
        onValueChange = onTitleChange,
        label = { Text("标题") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = number,
            onValueChange = onNumberChange,
            label = { Text("号码") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = numberSort,
            onValueChange = onNumberSortChange,
            label = { Text("排序号") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    OutlinedTextField(
        value = releaseDate,
        onValueChange = onReleaseDateChange,
        label = { Text("发行日期") },
        placeholder = { Text("yyyy-MM-dd") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = isbn,
        onValueChange = onIsbnChange,
        label = { Text("ISBN") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = summary,
        onValueChange = onSummaryChange,
        label = { Text("摘要") },
        minLines = 3,
        maxLines = 10,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AuthorsTab(
    authors: List<Creator>,
    onRemove: (Int) -> Unit,
    onAdd: (String, AuthorRole, String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(AuthorRole.Writer) }
    var customRole by rememberSaveable { mutableStateOf("") }
    var roleMenuOpen by remember { mutableStateOf(false) }

    if (authors.isEmpty()) {
        Text(
            text = "暂无作者",
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
                    text = creator.roleLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onRemove(index) }) {
                Icon(Icons.Filled.Delete, contentDescription = "删除作者")
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("作者姓名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Box {
        OutlinedButton(
            onClick = { roleMenuOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("角色：${role.label}") }
        DropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
            (AuthorRole.predefined + AuthorRole.Custom).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
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
            label = { Text("自定义角色") },
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
    ) { Text("添加作者") }
}

@Composable
private fun TagsTab(
    tags: List<String>,
    onRemove: (Int) -> Unit,
    onAdd: (String) -> Unit,
) {
    var newTag by rememberSaveable { mutableStateOf("") }

    if (tags.isEmpty()) {
        Text(
            text = "暂无标签",
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
                Icon(Icons.Filled.Delete, contentDescription = "删除标签")
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newTag,
            onValueChange = { newTag = it },
            label = { Text("标签") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                onAdd(newTag.trim())
                newTag = ""
            },
            enabled = newTag.isNotBlank(),
        ) { Icon(Icons.Filled.Add, contentDescription = "添加标签") }
    }
}

@Composable
private fun LinksTab(
    links: List<Pair<String, String>>,
    onRemove: (Int) -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }

    if (links.isEmpty()) {
        Text(
            text = "暂无链接",
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
                Icon(Icons.Filled.Delete, contentDescription = "删除链接")
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text("链接名称") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("链接地址") },
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
    ) { Text("添加链接") }
}

/** 排序号显示时去掉多余的 .0。 */
private fun formatNumberSort(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()
