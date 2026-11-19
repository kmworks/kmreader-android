package com.kmworks.kmreader.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.AuthenticationActivityDto
import com.kmworks.kmreader.core.server.ServerAdminRepository
import com.kmworks.kmreader.ui.components.relativeTimeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One page of sign-in history plus the paging cursor. */
data class AuthenticationActivityUiState(
    val entries: List<AuthenticationActivityDto> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val hasMore: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Sign-in history of the signed-in account (`/api/v2/users/me/authentication-activity`), mirroring
 * the iOS authentication activity screen: newest first, paged, with the failure reason inline.
 */
@HiltViewModel
class AuthenticationActivityViewModel @Inject constructor(
    private val repository: ServerAdminRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthenticationActivityUiState())
    val state: StateFlow<AuthenticationActivityUiState> = _state.asStateFlow()

    private var page = 0
    private var loading = false

    init {
        load(reset = true)
    }

    fun load(reset: Boolean) {
        if (loading) return
        loading = true
        if (reset) {
            page = 0
            _state.update { it.copy(refreshing = !it.entries.isEmpty(), failed = false) }
        } else {
            _state.update { it.copy(loading = true) }
        }
        viewModelScope.launch {
            val result = repository.authenticationActivity(page = page)
            _state.update { current ->
                val entries = if (reset) result.items else current.entries + result.items
                current.copy(
                    entries = entries,
                    loading = false,
                    refreshing = false,
                    hasMore = result.hasMore,
                    failed = result.items.isEmpty() && page == 0 && result.totalElements == null,
                )
            }
            if (result.items.isNotEmpty()) page += 1
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticationActivityScreen(
    onBack: () -> Unit,
    viewModel: AuthenticationActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_admin_auth_activity)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.entries.isEmpty() -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                state.failed -> CenteredMessage(R.string.server_admin_admin_required)
                state.entries.isEmpty() -> CenteredMessage(R.string.server_admin_auth_activity_empty)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(state.entries) { index, entry ->
                        AuthenticationActivityRow(entry)
                        if (index < state.entries.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        if (index == state.entries.lastIndex && state.hasMore) {
                            LoadMoreRow { viewModel.load(reset = false) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthenticationActivityRow(entry: AuthenticationActivityDto) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (entry.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (entry.success) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    if (entry.success) {
                        R.string.server_admin_auth_activity_success
                    } else {
                        R.string.server_admin_auth_activity_failure
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            val created = parseIsoMs(entry.dateTime)
            if (created != null) {
                Text(
                    text = relativeTimeLabel(created),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.source?.takeIf { it.isNotBlank() }?.let {
                AuthActivityDetail(stringResource(R.string.server_admin_auth_activity_source), it)
            }
            entry.ip?.takeIf { it.isNotBlank() }?.let {
                AuthActivityDetail(stringResource(R.string.server_admin_auth_activity_ip), it)
            }
            entry.email?.takeIf { it.isNotBlank() }?.let {
                AuthActivityDetail(stringResource(R.string.server_admin_auth_activity_email), it)
            }
            entry.apiKeyComment?.takeIf { it.isNotBlank() }?.let {
                AuthActivityDetail(stringResource(R.string.server_admin_auth_activity_api_key), it)
            }
            entry.userAgent?.takeIf { it.isNotBlank() }?.let {
                AuthActivityDetail(stringResource(R.string.server_admin_auth_activity_agent), it)
            }
            entry.error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB00020),
                )
            }
        }
    }
}

@Composable
private fun AuthActivityDetail(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
