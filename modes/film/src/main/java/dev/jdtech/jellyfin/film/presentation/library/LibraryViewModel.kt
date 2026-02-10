package dev.jdtech.jellyfin.film.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

@HiltViewModel
class LibraryViewModel
@Inject
constructor(
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    val repository: JellyfinRepository get() = jellyfinRepository
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()

    lateinit var parentId: UUID
    lateinit var libraryType: CollectionType

    lateinit var sortBy: SortBy
    lateinit var sortOrder: SortOrder

    fun setup(parentId: UUID, libraryType: CollectionType) {
        this.parentId = parentId
        this.libraryType = libraryType
    }

    fun loadItems() {
        val itemType =
            when (libraryType) {
                CollectionType.Movies -> listOf(BaseItemKind.MOVIE)
                CollectionType.TvShows -> listOf(BaseItemKind.SERIES)
                CollectionType.BoxSets -> listOf(BaseItemKind.BOX_SET)
                CollectionType.Mixed,
                CollectionType.Folders ->
                    listOf(BaseItemKind.FOLDER, BaseItemKind.MOVIE, BaseItemKind.SERIES)
                else -> null
            }

        val recursive = itemType == null || !itemType.contains(BaseItemKind.FOLDER)

        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))

            initSorting()

            try {
                val items =
                    jellyfinRepository
                        .getItemsPaging(
                            parentId = parentId,
                            includeTypes = itemType,
                            recursive = recursive,
                            sortBy =
                                if (
                                    libraryType == CollectionType.TvShows &&
                                        sortBy == SortBy.DATE_PLAYED
                                )
                                    SortBy.SERIES_DATE_PLAYED
                                else sortBy, // Jellyfin uses a different enum for sorting series by
                            // data played
                            sortOrder = sortOrder,
                            searchTerm = if (_state.value.isVrFilterEnabled) "VR" else null,
                        )
                        .cachedIn(viewModelScope)
                _state.emit(_state.value.copy(items = items))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private suspend fun initSorting() {
        if (!::sortBy.isInitialized || !::sortOrder.isInitialized) {
            sortBy = SortBy.fromString(appPreferences.getValue(appPreferences.sortBy))
            sortOrder = SortOrder.fromString(appPreferences.getValue(appPreferences.sortOrder))
            _state.emit(_state.value.copy(sortBy = sortBy, sortOrder = sortOrder))
        }
    }

    private fun setSorting(sortBy: SortBy, sortOrder: SortOrder) {
        this.sortBy = sortBy
        this.sortOrder = sortOrder
        viewModelScope.launch {
            _state.emit(_state.value.copy(sortBy = sortBy, sortOrder = sortOrder))
            appPreferences.setValue(appPreferences.sortBy, sortBy.toString())
            appPreferences.setValue(appPreferences.sortOrder, sortOrder.toString())
        }
    }

    fun toggleDuplicateFinder() {
        val newEnabled = !_state.value.isDuplicateFinderEnabled
        viewModelScope.launch {
            _state.emit(_state.value.copy(isDuplicateFinderEnabled = newEnabled))
        }
        if (newEnabled) {
            loadDuplicates()
        } else {
            loadItems()
        }
    }

    private fun loadDuplicates() {
        val itemType =
            when (libraryType) {
                CollectionType.Movies -> listOf(BaseItemKind.MOVIE)
                CollectionType.TvShows -> listOf(BaseItemKind.SERIES)
                CollectionType.BoxSets -> listOf(BaseItemKind.BOX_SET)
                else -> null
            }

        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                val allItems =
                    jellyfinRepository.getAllItems(
                        parentId = parentId,
                        includeTypes = itemType,
                        recursive = true,
                    )

                val duplicates =
                    allItems
                        .groupBy { it.name.substringBefore(" ").lowercase().trim() }
                        .filter { it.value.size > 1 }
                        .flatMap { it.value }

                _state.emit(_state.value.copy(items = MutableStateFlow(PagingData.from(duplicates)), isLoading = false))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e, isLoading = false))
            }
        }
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.ChangeSorting -> {
                if (action.sortBy != this.sortBy || action.sortOrder != this.sortOrder) {
                    setSorting(sortBy = action.sortBy, sortOrder = action.sortOrder)
                    if (_state.value.isDuplicateFinderEnabled) {
                        loadDuplicates()
                    } else {
                        loadItems()
                    }
                }
            }
            LibraryAction.ToggleDuplicateFinder -> toggleDuplicateFinder()
            LibraryAction.ToggleVrFilter -> {
                val newEnabled = !_state.value.isVrFilterEnabled
                viewModelScope.launch {
                    _state.emit(_state.value.copy(isVrFilterEnabled = newEnabled))
                    if (_state.value.isDuplicateFinderEnabled) {
                        loadDuplicates()
                    } else {
                        loadItems()
                    }
                }
            }
            else -> Unit
        }
    }
}
