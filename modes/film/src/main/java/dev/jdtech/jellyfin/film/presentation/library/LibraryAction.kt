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
}