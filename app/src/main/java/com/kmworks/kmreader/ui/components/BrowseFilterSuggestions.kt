package com.kmworks.kmreader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.data.ReferentialRepository
import com.kmworks.kmreader.data.NotableEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Referential suggestion values for BrowseOptionsSheet (authors/tags/series-metadata dimensions). */
data class BrowseFilterSuggestions(
    val authors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val ageRatings: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val releaseYears: List<String> = emptyList(),
    val notableEntities: List<NotableEntity> = emptyList(),
)

/**
 * Scope of metadata suggestion values (mirrors the scoping parameters of iOS
 * MetadataFilterItemSource): library dtoId list / series dtoId / collection dtoId /
 * read list dtoId.
 */
data class BrowseFilterScope(
    val libraryIds: List<String>? = null,
    val seriesId: String? = null,
    val collectionId: String? = null,
    val readListId: String? = null,
)

/** Source of selectable values for the filter panel (Referential v2/v1 fallback, empty without a session). */
@HiltViewModel
class BrowseFilterSuggestionsViewModel @Inject constructor(
    private val referentialRepository: ReferentialRepository,
) : ViewModel() {

    private val _suggestions = MutableStateFlow(BrowseFilterSuggestions())
    val suggestions: StateFlow<BrowseFilterSuggestions> = _suggestions.asStateFlow()

    /** Re-fetches per scope every time the filter panel opens (the VM is Activity-scoped; init-once would keep stale values forever). */
    fun refresh(scope: BrowseFilterScope = BrowseFilterScope()) {
        viewModelScope.launch {
            _suggestions.value = BrowseFilterSuggestions(
                authors = referentialRepository.authors(
                    seriesId = scope.seriesId,
                    libraryIds = scope.libraryIds,
                    collectionId = scope.collectionId,
                    readListId = scope.readListId,
                ),
                // Matches the original: book filtering inside series detail/read list uses book tags, everything else uses series+book tags
                tags = if (scope.seriesId != null || scope.readListId != null) {
                    referentialRepository.bookTags(
                        seriesId = scope.seriesId,
                        readListId = scope.readListId,
                        libraryIds = scope.libraryIds,
                    )
                } else {
                    referentialRepository.tags(
                        libraryIds = scope.libraryIds,
                        collectionId = scope.collectionId,
                    )
                },
                // The original does not scope publishers
                publishers = referentialRepository.publishers(),
                genres = referentialRepository.genres(
                    libraryIds = scope.libraryIds,
                    collectionId = scope.collectionId,
                ),
                ageRatings = referentialRepository.ageRatings(
                    libraryIds = scope.libraryIds,
                    collectionId = scope.collectionId,
                ),
                languages = referentialRepository.languages(
                    libraryIds = scope.libraryIds,
                    collectionId = scope.collectionId,
                ),
                releaseYears = referentialRepository.releaseYears(
                    libraryIds = scope.libraryIds,
                    collectionId = scope.collectionId,
                ),
                // Notable entities (v3, scope parameters mirror iOS MetadataFilterItemSource)
                notableEntities = referentialRepository.notableEntities(
                    libraryIds = scope.libraryIds,
                    collectionId = scope.collectionId,
                    seriesId = scope.seriesId,
                    readListId = scope.readListId,
                ),
            )
        }
    }
}

/**
 * One-liner suggestion accessor for BrowseOptionsSheet call sites (no handwritten VM
 * boilerplate); refreshes per scope on entering composition.
 * Series/collection/read list pass their stable Int id (resolved to dtoId internally
 * via DetailIdRegistry); libraries pass dtoId lists directly.
 */
@Composable
fun rememberBrowseFilterSuggestions(
    libraryIds: List<String>? = null,
    seriesStableId: Int? = null,
    collectionStableId: Int? = null,
    readListStableId: Int? = null,
): BrowseFilterSuggestions {
    val viewModel: BrowseFilterSuggestionsViewModel = hiltViewModel()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val scope = BrowseFilterScope(
        libraryIds = libraryIds,
        seriesId = seriesStableId?.let { DetailIdRegistry.seriesDtoId(it) },
        collectionId = collectionStableId?.let { DetailIdRegistry.collectionDtoId(it) },
        readListId = readListStableId?.let { DetailIdRegistry.readListDtoId(it) },
    )
    // Refresh suggestions when the sheet opens (enters composition) so authors/tags never stick to the snapshot from the first open
    LaunchedEffect(libraryIds, seriesStableId, collectionStableId, readListStableId) {
        viewModel.refresh(scope)
    }
    return suggestions
}
