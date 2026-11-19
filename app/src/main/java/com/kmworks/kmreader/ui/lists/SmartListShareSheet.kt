package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.SmartListRepository
import com.kmworks.kmreader.core.data.SmartListShareTargetDto
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.ui.components.AppModalBottomSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** kmrs smart-list visibility values (server-side enum, stored as-is). */
const val SmartListVisibilityPrivate = "PRIVATE"
const val SmartListVisibilityPublic = "PUBLIC"
const val SmartListVisibilityShared = "SHARED"

/**
 * Sharing editor for one smart list (kmrs extension: `GET api/v1/smart-lists/share-targets` plus the
 * admin-only `visibility` / `sharedWithUserIds` fields of the create/update bodies).
 *
 * Loads the current list itself so callers only pass the stable id: the server owns name/summary, and
 * sending them back unchanged keeps a sharing edit from touching the stored criteria (search stays null).
 */
@HiltViewModel
class SmartListShareViewModel @Inject constructor(
    private val smartListRepository: SmartListRepository,
    private val configStore: ServerConfigStore,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        /** No real server-side list (local placeholder) or the kmrs endpoints are absent. */
        val unsupported: Boolean = false,
        val isAdmin: Boolean = false,
        val dtoId: String? = null,
        val name: String = "",
        val summary: String = "",
        val visibility: String = SmartListVisibilityPrivate,
        val selected: List<String> = emptyList(),
        val targets: List<SmartListShareTargetDto> = emptyList(),
        val saving: Boolean = false,
        val failed: Boolean = false,
    ) {
        /** SHARED without sharees is rejected by the server, so the button stays disabled. */
        val canSave: Boolean
            get() = !saving && (visibility != SmartListVisibilityShared || selected.isNotEmpty())
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var loaded = false

    fun load(stableId: Int) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val dtoId = smartListRepository.smartListDtoId(stableId)
            val dto = dtoId?.let { smartListRepository.smartListDetail(it) }
            if (dtoId == null || dto == null) {
                _state.update { it.copy(loading = false, unsupported = true) }
                return@launch
            }
            val isAdmin = configStore.config.first()?.isAdmin == true
            // share-targets is admin-only: non-admins get an empty picker and keep the list private.
            val targets = if (isAdmin) smartListRepository.shareTargets() else emptyList()
            _state.update {
                it.copy(
                    loading = false,
                    isAdmin = isAdmin,
                    dtoId = dtoId,
                    name = dto.name,
                    summary = dto.summary,
                    visibility = dto.visibility,
                    selected = dto.sharedWithUserIds,
                    targets = targets,
                )
            }
        }
    }

    fun selectVisibility(visibility: String) {
        _state.update { it.copy(visibility = visibility, failed = false) }
    }

    fun toggleTarget(id: String) {
        _state.update { state ->
            val selected = if (id in state.selected) state.selected - id else state.selected + id
            state.copy(selected = selected, failed = false)
        }
    }

    fun save(onDone: (Boolean) -> Unit) {
        val current = _state.value
        val dtoId = current.dtoId ?: return onDone(false)
        if (current.saving) return
        _state.update { it.copy(saving = true, failed = false) }
        viewModelScope.launch {
            // Sharing fields ride along on the update body; criteria stay untouched (search = null).
            val ok = smartListRepository.updateSmartList(
                dtoId = dtoId,
                name = current.name,
                summary = current.summary,
                search = null,
                visibility = current.visibility,
                sharedWithUserIds = if (current.visibility == SmartListVisibilityShared) {
                    current.selected
                } else {
                    emptyList()
                },
            )
            _state.update { it.copy(saving = false, failed = !ok) }
            onDone(ok)
        }
    }
}

/** Sharing sheet (visibility + sharee picker) for a smart list detail screen. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SmartListShareSheet(
    stableId: Int,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
    viewModel: SmartListShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(stableId) { viewModel.load(stableId) }

    AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.smartlist_share_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            when {
                state.loading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.unsupported -> Text(
                    text = stringResource(R.string.smartlist_share_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )

                !state.isAdmin -> Text(
                    text = stringResource(R.string.smartlist_share_admin_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )

                else -> {
                    ShareLabel(stringResource(R.string.smartlist_share_visibility))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VisibilityChip(
                            label = stringResource(R.string.smartlist_visibility_private),
                            selected = state.visibility == SmartListVisibilityPrivate,
                            onClick = { viewModel.selectVisibility(SmartListVisibilityPrivate) },
                        )
                        VisibilityChip(
                            label = stringResource(R.string.smartlist_visibility_public),
                            selected = state.visibility == SmartListVisibilityPublic,
                            onClick = { viewModel.selectVisibility(SmartListVisibilityPublic) },
                        )
                        VisibilityChip(
                            label = stringResource(R.string.smartlist_visibility_shared),
                            selected = state.visibility == SmartListVisibilityShared,
                            onClick = { viewModel.selectVisibility(SmartListVisibilityShared) },
                        )
                    }

                    if (state.visibility == SmartListVisibilityShared) {
                        ShareLabel(stringResource(R.string.smartlist_share_targets))
                        if (state.targets.isEmpty()) {
                            Text(
                                text = stringResource(R.string.smartlist_share_no_targets),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.targets.forEach { target ->
                                    VisibilityChip(
                                        label = target.email.ifBlank { target.id },
                                        selected = target.id in state.selected,
                                        onClick = { viewModel.toggleTarget(target.id) },
                                    )
                                }
                            }
                        }
                    }

                    if (state.failed) {
                        Text(
                            text = stringResource(R.string.smartlist_save_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(R.string.common_cancel))
                        }
                        Button(
                            onClick = {
                                viewModel.save { ok -> if (ok) onSaved() }
                            },
                            enabled = state.canSave,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisibilityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        },
    )
}

/** Section caption inside the sheet (same rhythm as the editor's section labels). */
@Composable
private fun ShareLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
