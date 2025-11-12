package dev.jdtech.jellyfin.film.presentation.library

import androidx.paging.PagingData
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.SortBy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jellyfin.sdk.model.api.SortOrder

data class LibraryState(
    val items: Flow<PagingData<FindroidItem>> = emptyFlow(),
    val sortBy: SortBy = SortBy.NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val isLoading: Boolean = false,
    val error: Exception? = null,
    val selectionMode: Boolean = false,
    val selectedItems: Set<FindroidItem> = emptySet(),
    val duplicateItems: Set<FindroidItem> = emptySet(),
    val showOnlyDuplicates: Boolean = false,
    val showSnackBar: Boolean = false,
    val snackBarMessage: String = ""
)