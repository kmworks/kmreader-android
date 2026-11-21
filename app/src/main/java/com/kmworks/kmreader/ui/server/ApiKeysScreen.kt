package com.kmworks.kmreader.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.ApiKeyDto
import com.kmworks.kmreader.core.server.ServerAdminRepository
import com.kmworks.kmreader.ui.settings.SettingsRowDivider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ApiKeysUiState(
    val keys: List<ApiKeyDto> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    /** Set right after creation: the only time the server returns the plaintext key. */
    val createdKey: String? = null,
    val createdComment: String? = null,
    val messageRes: Int? = null,
)

/**
 * API key management for the signed-in account (`/api/v2/users/me/api-keys`), mirroring the iOS
 * ApiKeysView: the list only carries masked keys, so a freshly created key is shown exactly once
 * with a copy action.
 */
@HiltViewModel
class ApiKeysViewModel @Inject constructor(
    private val repository: ServerAdminRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ApiKeysUiState())
    val state: StateFlow<ApiKeysUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = it.keys.isEmpty()) }
        viewModelScope.launch {
            val keys = repository.apiKeys()
            _state.update { it.copy(keys = keys, loading = false) }
        }
    }

    fun create(comment: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val created = repository.createApiKey(comment.trim())
            _state.update { current ->
                if (created == null) {
                    current.copy(busy = false, messageRes = R.string.server_admin_action_failed)
                } else {
                    current.copy(
                        busy = false,
                        createdKey = created.key,
                        createdComment = created.comment,
                        keys = current.keys + created,
                    )
                }
            }
        }
    }

    fun delete(keyId: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = repository.deleteApiKey(keyId)
            _state.update { current ->
                current.copy(
                    busy = false,
                    keys = if (ok) current.keys.filterNot { it.id == keyId } else current.keys,
                    messageRes = if (ok) null else R.string.server_admin_action_failed,
                )
            }
        }
    }

    fun dismissCreatedKey() {
        _state.update { it.copy(createdKey = null, createdComment = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(messageRes = null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(
    onBack: () -> Unit,
    viewModel: ApiKeysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var addingComment by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ApiKeyDto?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(state.messageRes) {
        val res = state.messageRes ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(res))
        viewModel.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_admin_api_keys)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { addingComment = ""; showAdd = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.server_admin_api_keys_add),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.keys.isEmpty() -> CenteredMessage(R.string.server_admin_api_keys_empty)
                else -> Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(state.keys, key = { it.id }) { key ->
                            ApiKeyRow(
                                key = key,
                                enabled = !state.busy,
                                onDelete = { pendingDelete = key },
                            )
                            if (key != state.keys.last()) {
                                SettingsRowDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) showAdd = false },
            title = { Text(stringResource(R.string.server_admin_api_keys_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = addingComment,
                        onValueChange = { addingComment = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.server_admin_api_keys_comment)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.server_admin_api_keys_comment_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAdd = false
                        viewModel.create(addingComment)
                    },
                    enabled = !state.busy && addingComment.isNotBlank(),
                ) { Text(stringResource(R.string.server_admin_api_keys_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    val createdKey = state.createdKey
    if (createdKey != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCreatedKey,
            title = { Text(stringResource(R.string.server_admin_api_keys_new_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.createdComment?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = createdKey,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    Text(
                        text = stringResource(R.string.server_admin_api_keys_new_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(createdKey))
                    viewModel.dismissCreatedKey()
                    viewModel.load()
                }) { Text(stringResource(R.string.server_admin_api_keys_copy)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissCreatedKey()
                    viewModel.load()
                }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }

    pendingDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.server_admin_api_keys_delete)) },
            text = { Text(stringResource(R.string.server_admin_api_keys_delete_confirm, key.comment)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(key.id)
                }) { Text(stringResource(R.string.server_admin_api_keys_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ApiKeyRow(key: ApiKeyDto, enabled: Boolean, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.Key,
            contentDescription = null,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = key.comment.ifBlank { stringResource(R.string.server_admin_api_keys_unnamed) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = key.key,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            parseIsoMs(key.createdDate)?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.server_admin_api_keys_created, dateTimeLabel(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.server_admin_api_keys_delete),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
