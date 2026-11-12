package com.kmworks.kmreader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ReferentialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** BrowseOptionsSheet 的 Referential 建议值（作者/标签）。 */
data class BrowseFilterSuggestions(
    val authors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/** 筛选面板的可选值来源（Referential v2/v1 回退，无会话为空）。 */
@HiltViewModel
class BrowseFilterSuggestionsViewModel @Inject constructor(
    private val referentialRepository: ReferentialRepository,
) : ViewModel() {

    private val _suggestions = MutableStateFlow(BrowseFilterSuggestions())
    val suggestions: StateFlow<BrowseFilterSuggestions> = _suggestions.asStateFlow()

    init {
        viewModelScope.launch {
            _suggestions.value = BrowseFilterSuggestions(
                authors = referentialRepository.authors(),
                tags = referentialRepository.tags(),
            )
        }
    }
}

/** 一行取建议值（BrowseOptionsSheet 调用处用，免手写 VM 样板）。 */
@Composable
fun rememberBrowseFilterSuggestions(): BrowseFilterSuggestions {
    val viewModel: BrowseFilterSuggestionsViewModel = hiltViewModel()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    return suggestions
}
