package com.kmworks.kmreader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.FilterPresetStore
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.SavedFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Filter presets (persisted FilterPresetStore list + save/delete). */
@HiltViewModel
class BrowsePresetsViewModel @Inject constructor(
    private val presetStore: FilterPresetStore,
) : ViewModel() {

    val presets: StateFlow<List<SavedFilter>> = presetStore.presets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(name: String, options: BookBrowseOptions) {
        viewModelScope.launch { presetStore.savePreset(name, options) }
    }

    fun delete(name: String) {
        viewModelScope.launch { presetStore.deletePreset(name) }
    }
}

/** Preset access triple (used internally by BrowseFilterChipFlow / BrowseOptionsSheet so call sites stay unwired). */
data class BrowseFilterPresets(
    val presets: List<SavedFilter>,
    val save: (String, BookBrowseOptions) -> Unit,
    val delete: (String) -> Unit,
)

/** One-liner preset accessor (the VM is Activity-scoped, so the preset list is shared across pages). */
@Composable
fun rememberBrowseFilterPresets(): BrowseFilterPresets {
    val viewModel: BrowsePresetsViewModel = hiltViewModel()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    return BrowseFilterPresets(
        presets = presets,
        save = viewModel::save,
        delete = viewModel::delete,
    )
}
