package dev.jdtech.jellyfin.film.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
                val items = jellyfinRepository.getItemsPaging(
                    parentId = parentId,
                    includeTypes = itemType,
                    recursive = recursive,
                    sortBy = if (libraryType == CollectionType.TvShows && sortBy == SortBy.DATE_PLAYED) SortBy.SERIES_DATE_PLAYED else sortBy, // Jellyfin uses a different enum for sorting series by data played
                    sortOrder = sortOrder,
                )
                .map { pagingData ->
                    if (_state.value.showOnlyDuplicates && _state.value.duplicateItems.isNotEmpty()) {
                        pagingData.filter { item -> item in _state.value.duplicateItems }
                    } else {
                        pagingData
                    }
                }
                .cachedIn(viewModelScope)
                
                _state.emit(_state.value.copy(items = items))
                
                // 检测重复项
                detectDuplicateItems()
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private suspend fun detectDuplicateItems() {
        try {
            val items = jellyfinRepository.getItems(
                parentId = parentId,
                recursive = true
            )
            
            val duplicateItems = mutableSetOf<FindroidItem>()
            val namePrefixes = mutableMapOf<String, MutableList<FindroidItem>>()
            
            // 收集所有项目并按名称前缀分组
            items.forEach { item ->
                val prefix = item.name.substringBefore(' ', item.name)
                namePrefixes.getOrPut(prefix) { mutableListOf() }.add(item)
            }
            
            // 标记重复项
            namePrefixes.values.forEach { itemsWithSamePrefix ->
                if (itemsWithSamePrefix.size > 1) {
                    duplicateItems.addAll(itemsWithSamePrefix)
                }
            }
            
            // 更新状态
            _state.emit(_state.value.copy(duplicateItems = duplicateItems))
            
            // 移除自动刷新调用以避免无限循环
            // 如果启用了仅显示重复项，则在切换选项时手动刷新数据
        } catch (e: Exception) {
            // 忽略检测错误，不影响主要功能
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
            is LibraryAction.OnEnterSelectionMode -> {
                viewModelScope.launch {
                    _state.emit(_state.value.copy(selectionMode = true))
                }
            }
            is LibraryAction.OnExitSelectionMode -> {
                viewModelScope.launch {
                    _state.emit(_state.value.copy(selectionMode = false, selectedItems = emptySet()))
                }
            }
            is LibraryAction.OnItemLongClick -> {
                viewModelScope.launch {
                    val selectedItems = _state.value.selectedItems.toMutableSet()
                    if (selectedItems.contains(action.item)) {
                        selectedItems.remove(action.item)
                    } else {
                        selectedItems.add(action.item)
                    }
                    _state.emit(_state.value.copy(
                        selectionMode = true,
                        selectedItems = selectedItems
                    ))
                }
            }
            is LibraryAction.OnItemSelectionToggle -> {
                viewModelScope.launch {
                    val selectedItems = _state.value.selectedItems.toMutableSet()
                    if (selectedItems.contains(action.item)) {
                        selectedItems.remove(action.item)
                    } else {
                        selectedItems.add(action.item)
                    }
                    _state.emit(_state.value.copy(selectedItems = selectedItems))
                }
            }
            is LibraryAction.OnSelectAllItems -> {
                viewModelScope.launch {
                    _state.emit(_state.value.copy(selectedItems = action.items.toSet()))
                }
            }
            is LibraryAction.OnClearSelection -> {
                viewModelScope.launch {
                    _state.emit(_state.value.copy(selectedItems = emptySet()))
                }
            }
            is LibraryAction.OnDeleteSelectedItems -> {
                viewModelScope.launch {
                    // 执行删除操作
                    val selectedItems = _state.value.selectedItems
                    val itemIds = selectedItems.map { it.id }
                    val success = jellyfinRepository.deleteItems(itemIds)
                    
                    if (success) {
                        // 显示删除成功的消息
                        _state.emit(_state.value.copy(
                            selectionMode = false,
                            selectedItems = emptySet(),
                            showSnackBar = true,
                            snackBarMessage = "删除成功"
                        ))
                        
                        // 重新加载数据以确保UI同步
                        loadItems()
                    } else {
                        // 删除失败，显示错误消息
                        _state.emit(_state.value.copy(
                            selectionMode = false,
                            selectedItems = emptySet(),
                            showSnackBar = true,
                            snackBarMessage = "删除失败"
                        ))
                    }
                    
                    // 延迟重置SnackBar状态
                    delay(3000)
                    _state.emit(_state.value.copy(
                        showSnackBar = false,
                        snackBarMessage = ""
                    ))
                }
            }
            is LibraryAction.OnMarkSelectedAsPlayed -> {
                viewModelScope.launch {
                    // 标记为已观看
                    val selectedItems = _state.value.selectedItems
                    selectedItems.forEach { item ->
                        viewModelScope.launch {
                            jellyfinRepository.markAsPlayed(item.id)
                        }
                    }
                    // 操作完成后退出选择模式
                    _state.emit(_state.value.copy(
                        selectionMode = false,
                        selectedItems = emptySet()
                    ))
                    // 移除自动刷新以避免与其他功能冲突
                }
            }
            is LibraryAction.OnAddSelectedToPlaylist -> {
                viewModelScope.launch {
                    // 添加到播放列表
                    val selectedItems = _state.value.selectedItems
                    // 这里应该实现添加到播放列表的逻辑
                    // 例如：playlistRepository.addItemsToPlaylist(selectedItems)
                    
                    // 操作完成后退出选择模式
                    _state.emit(_state.value.copy(
                        selectionMode = false,
                        selectedItems = emptySet()
                    ))
                }
            }
            is LibraryAction.OnFavoriteSelectedItems -> {
                viewModelScope.launch {
                    // 收藏项目
                    val selectedItems = _state.value.selectedItems
                    selectedItems.forEach { item ->
                        viewModelScope.launch {
                            jellyfinRepository.markAsFavorite(item.id)
                        }
                    }
                    // 操作完成后退出选择模式
                    _state.emit(_state.value.copy(
                        selectionMode = false,
                        selectedItems = emptySet()
                    ))
                }
            }
            is LibraryAction.ToggleDuplicateFinder -> {
                viewModelScope.launch {
                    val newShowOnlyDuplicates = !_state.value.showOnlyDuplicates
                    _state.emit(_state.value.copy(showOnlyDuplicates = newShowOnlyDuplicates))
                    loadItems()
                }
            }
            is LibraryAction.ToggleViewMode -> {
                viewModelScope.launch {
                    _state.emit(_state.value.copy(isListView = !_state.value.isListView))
                }
            }
            else -> Unit
        }
    }
}