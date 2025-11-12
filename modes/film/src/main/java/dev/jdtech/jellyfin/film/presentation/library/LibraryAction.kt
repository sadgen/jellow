package dev.jdtech.jellyfin.film.presentation.library

import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.SortBy
import org.jellyfin.sdk.model.api.SortOrder

sealed interface LibraryAction {
    data object OnRetryClick : LibraryAction
    data object OnBackClick : LibraryAction
    data class OnItemClick(val item: FindroidItem) : LibraryAction
    data class OnPlayClick(val item: FindroidItem) : LibraryAction
    data class ChangeSorting(val sortBy: SortBy, val sortOrder: SortOrder) : LibraryAction
    
    // Selection mode actions
    data object OnEnterSelectionMode : LibraryAction
    data object OnExitSelectionMode : LibraryAction
    data class OnItemLongClick(val item: FindroidItem) : LibraryAction
    data class OnItemSelectionToggle(val item: FindroidItem) : LibraryAction
    data class OnSelectAllItems(val items: List<FindroidItem>) : LibraryAction
    data object OnClearSelection : LibraryAction
    
    // Multi-selection operations
    data object OnDeleteSelectedItems : LibraryAction
    data object OnMarkSelectedAsPlayed : LibraryAction
    data object OnAddSelectedToPlaylist : LibraryAction
    data object OnFavoriteSelectedItems : LibraryAction
    
    // Refresh action
    data object OnRefresh : LibraryAction
    
    // Duplicate filter action
    data object OnToggleShowOnlyDuplicates : LibraryAction
}