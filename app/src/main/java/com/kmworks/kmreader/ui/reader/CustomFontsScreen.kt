package com.kmworks.kmreader.ui.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.fonts.FontRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.CustomFont
import com.kmworks.kmreader.data.SampleSystemData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Server font list state (mirrors Komga FontsApi family management). */
@HiltViewModel
class CustomFontsViewModel @Inject constructor(
    sessionManager: SessionManager,
    private val fontRepository: FontRepository,
) : ViewModel() {

    val hasSession: Boolean = sessionManager.clients.value != null

    private val _families = MutableStateFlow<List<String>?>(null)
    val families: StateFlow<List<String>?> = _families.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    init {
        refreshDownloaded()
        if (hasSession) {
            viewModelScope.launch {
                _families.value = fontRepository.families()
            }
        }
    }

    fun refreshDownloaded() {
        _downloaded.value = fontRepository.downloadedFamilies()
    }

    fun download(family: String) {
        if (family in _downloading.value) return
        viewModelScope.launch {
            _downloading.value = _downloading.value + family
            fontRepository.downloadFontFamily(family)
            _downloading.value = _downloading.value - family
            refreshDownloaded()
        }
    }

    fun delete(family: String) {
        fontRepository.deleteFontFamily(family)
        refreshDownloaded()
    }

    /** Imports a local font file (content uri returned by SAF); refreshes the downloaded set when done. */
    fun importFont(uri: Uri) {
        viewModelScope.launch {
            fontRepository.importLocalFont(uri)
            refreshDownloaded()
        }
    }
}

/** Custom font manager (mirrors CustomFontsSheet + Komga FontsApi download flow); falls back to the demo list without a session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFontsScreen(
    onBack: () -> Unit,
    viewModel: CustomFontsViewModel = hiltViewModel(),
) {
    // SAF file picker: prefer font MIME types, fall back to wildcards (some vendors do not
    // recognize font/* and would filter everything out)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFont) }
    val launchImport: () -> Unit = {
        importLauncher.launch(
            arrayOf(
                "font/*",
                "application/x-font-ttf",
                "application/x-font-otf",
                "application/x-font-truetype",
                "application/octet-stream",
                "*/*",
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fonts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        if (viewModel.hasSession) {
            RealFontList(
                viewModel = viewModel,
                onImport = launchImport,
                modifier = Modifier.padding(padding),
            )
        } else {
            DemoFontList(
                viewModel = viewModel,
                onImport = launchImport,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** Real server font families with download/delete actions. */
@Composable
private fun RealFontList(
    viewModel: CustomFontsViewModel,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val families by viewModel.families.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item {
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.fonts_import_button))
            }
        }
        item {
            Text(
                stringResource(R.string.fonts_downloaded_summary, downloaded.size, families?.size?.toString() ?: "…"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        val list = families
        if (list == null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        stringResource(R.string.fonts_loading_list),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(list, key = { it }) { family ->
                ServerFontRow(
                    family = family,
                    downloaded = family in downloaded,
                    downloading = family in downloading,
                    onDownload = { viewModel.download(family) },
                    onDelete = { viewModel.delete(family) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** One server family row: name + status + download/delete action. */
@Composable
private fun ServerFontRow(
    family: String,
    downloaded: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                family,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                when {
                    downloading -> stringResource(R.string.fonts_downloading)
                    downloaded -> stringResource(R.string.fonts_downloaded_ready)
                    else -> stringResource(R.string.fonts_not_downloaded)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            downloading -> CircularProgressIndicator(modifier = Modifier.size(22.dp))
            downloaded -> {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.fonts_downloaded),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> IconButton(onClick = onDownload) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = stringResource(R.string.common_download),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Demo fallback list (no session): sample fonts plus locally imported fonts. */
@Composable
private fun DemoFontList(
    viewModel: CustomFontsViewModel,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fonts = remember { SampleSystemData.customFonts.toMutableStateList() }
    // Imported local fonts persist in FontRepository (works without a session too)
    val imported by viewModel.downloaded.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item {
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.fonts_import_button))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.fonts_installed_total, imported.size + fonts.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        items(imported.sorted(), key = { "imported:$it" }) { family ->
            ImportedFontRow(family = family, onDelete = { viewModel.delete(family) })
            HorizontalDivider()
        }
        items(fonts, key = { it.id }) { font ->
            DemoFontRow(font = font, onDelete = { fonts.remove(font) })
            HorizontalDivider()
        }
    }
}

/** Row for a locally imported font (deletable inside the demo list without a session). */
@Composable
private fun ImportedFontRow(family: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                family,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                stringResource(R.string.fonts_imported_installed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DemoFontRow(font: CustomFont, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                font.name,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${font.fileName} · ${font.sizeLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
