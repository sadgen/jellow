package dev.jdtech.jellyfin.film.presentation.person

import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder

sealed interface PersonAction {
    data object NavigateBack : PersonAction

    data object NavigateHome : PersonAction

    data class NavigateToItem(val item: FindroidItem) : PersonAction

    data object ToggleViewMode : PersonAction

    data class UpdateSort(val sortBy: SortBy, val sortOrder: SortOrder) : PersonAction
}
