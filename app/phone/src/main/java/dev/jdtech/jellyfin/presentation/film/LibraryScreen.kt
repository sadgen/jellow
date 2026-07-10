package dev.jdtech.jellyfin.presentation.film

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.film.presentation.library.LibraryAction
import dev.jdtech.jellyfin.film.presentation.library.LibraryState
import dev.jdtech.jellyfin.film.presentation.library.LibraryViewModel
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.components.ErrorDialog
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ErrorCard
import dev.jdtech.jellyfin.presentation.film.components.FloatingVideoPlayer
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.film.components.SortByDialog
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.GridCellsAdaptiveWithMinColumns
import dev.jdtech.jellyfin.presentation.utils.plus
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun LibraryScreen(
    libraryId: UUID,
    libraryName: String,
    libraryType: CollectionType,
    onItemClick: (item: FindroidItem) -> Unit,
    navigateBack: () -> Unit,
    onPlayClick: (item: FindroidItem) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var initialLoad by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(true) {
        viewModel.setup(parentId = libraryId, libraryType = libraryType)
        if (initialLoad) {
            viewModel.loadItems()
            initialLoad = false
        }
    }

    // 处理系统后退按钮
    BackHandler(enabled = state.selectionMode) {
        viewModel.onAction(LibraryAction.OnExitSelectionMode)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    
    // 处理SnackBar消息
    LaunchedEffect(state.showSnackBar) {
        if (state.showSnackBar) {
            snackbarHostState.showSnackbar(state.snackBarMessage)
        }
    }
    
    LibraryScreenLayout(
        libraryName = libraryName,
        state = state,
        repository = viewModel.repository,
        onAction = { action ->
            when (action) {
                is LibraryAction.OnItemClick -> onItemClick(action.item)
                is LibraryAction.OnPlayClick -> onPlayClick(action.item)
                is LibraryAction.OnBackClick -> navigateBack()
                else -> Unit
            }
            viewModel.onAction(action)
        },
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreenLayout(
    libraryName: String,
    state: LibraryState,
    repository: JellyfinRepository? = null,
    onAction: (LibraryAction) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val items = state.items.collectAsLazyPagingItems()
    var showSortByDialog by rememberSaveable { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    
    // 添加状态以控制菜单显示
    var showMenu by remember { mutableStateOf(false) }
    var floatingVideoItem by remember { mutableStateOf<FindroidItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = libraryName) },
                navigationIcon = {
                    IconButton(onClick = { onAction(LibraryAction.OnBackClick) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    if (items.itemCount > 0) {
                        IconButton(onClick = { showSortByDialog = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_down_up),
                                contentDescription = null,
                            )
                        }
                        IconButton(onClick = { onAction(LibraryAction.ToggleViewMode) }) {
                            Icon(
                                painter = painterResource(
                                    if (state.isListView) R.drawable.ic_view_grid else R.drawable.ic_view_list
                                ),
                                contentDescription = null,
                            )
                        }
                        IconButton(onClick = { onAction(LibraryAction.OnEnterSelectionMode) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                            )
                        }
                    }
                    IconButton(onClick = { onAction(LibraryAction.ToggleDuplicateFinder) }) {
                        Icon(
                            painter = painterResource(if (state.showOnlyDuplicates) R.drawable.ic_check else R.drawable.ic_sparkles),
                            contentDescription = null,
                            tint = if (state.showOnlyDuplicates) MaterialTheme.colorScheme.primary 
                                   else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    
                    // 在选择模式下显示菜单按钮
                    if (state.selectionMode && state.selectedItems.isNotEmpty()) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_dots_vertical),
                                contentDescription = "More options",
                            )
                        }
                        
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    onAction(LibraryAction.OnDeleteSelectedItems)
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_trash),
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("标记为已观看") },
                                onClick = {
                                    onAction(LibraryAction.OnMarkSelectedAsPlayed)
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_eye),
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("添加到播放列表") },
                                onClick = {
                                    onAction(LibraryAction.OnAddSelectedToPlaylist)
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_plus),
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("收藏") },
                                onClick = {
                                    onAction(LibraryAction.OnFavoriteSelectedItems)
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_heart),
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column {
                ErrorGroup(
                    loadStates = items.loadState,
                    onRefresh = {
                        onAction(LibraryAction.OnRefresh)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(innerPadding),
                )
                if (state.isListView) {
                    LazyVerticalGrid(
                        columns = GridCellsAdaptiveWithMinColumns(minSize = 180.dp, minColumns = 2),
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (state.selectionMode) {
                                    Modifier.graphicsLayer(alpha = 0.7f)
                                } else {
                                    Modifier
                                }
                            ),
                        contentPadding = innerPadding,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    ) {
                        items(
                            count = items.itemCount,
                            key = items.itemKey { it.id },
                        ) {
                            val item = items[it]
                            item?.let { item ->
                                ItemCard(
                                    item = item,
                                    direction = Direction.HORIZONTAL,
                                    onClick = {
                                        if (state.selectionMode) {
                                            onAction(LibraryAction.OnItemSelectionToggle(item))
                                        } else {
                                            onAction(LibraryAction.OnItemClick(item))
                                        }
                                    },
                                    onPlayClick = {
                                        floatingVideoItem = item
                                    },
                                    repository = repository,
                                    selected = state.selectionMode && item in state.selectedItems,
                                    isDuplicate = item in state.duplicateItems,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCellsAdaptiveWithMinColumns(minSize = 120.dp, minColumns = 3),
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (state.selectionMode) {
                                Modifier.graphicsLayer(alpha = 0.7f) // 调浅灰罩颜色
                            } else {
                                Modifier
                            }
                        ),
                    contentPadding = innerPadding,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                ) {
                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.id },
                    ) {
                        val item = items[it]
                        item?.let { item ->
                                ItemCard(
                                    item = item,
                                    direction = Direction.VERTICAL,
                                    onClick = {
                                        if (state.selectionMode) {
                                            onAction(LibraryAction.OnItemSelectionToggle(item))
                                        } else {
                                            onAction(LibraryAction.OnItemClick(item))
                                        }
                                    },
                                    onPlayClick = {
                                        floatingVideoItem = item
                                    },
                                    repository = repository,
                                    selected = state.selectionMode && item in state.selectedItems,
                                    isDuplicate = item in state.duplicateItems,
                                    modifier = Modifier.animateItem(),
                                )
                        }
                    }
                } // end grid content
                } // end else (view mode)
            }
            
            // 在选择模式下添加半透明覆盖层以增强视觉效果，但排除已选中的项目
            if (state.selectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(alpha = 0.2f)
                        .background(Color.Black)
                )
            }

            floatingVideoItem?.let { item ->
                FloatingVideoPlayer(
                    item = item,
                    repository = repository ?: return@let,
                    onDismiss = { floatingVideoItem = null },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (showSortByDialog) {
        SortByDialog(
            currentSortBy = state.sortBy,
            currentSortOrder = state.sortOrder,
            showOnlyDuplicates = state.showOnlyDuplicates,
            onUpdate = { sortBy, sortOrder ->
                onAction(LibraryAction.ChangeSorting(sortBy, sortOrder))
            },
            onToggleShowOnlyDuplicates = {
                onAction(LibraryAction.ToggleDuplicateFinder)
            },
            onDismissRequest = {
                showSortByDialog = false
            },
        )
    }
}

@Composable
private fun ErrorGroup(
    loadStates: CombinedLoadStates,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showErrorDialog by rememberSaveable { mutableStateOf(false) }

    val loadStateError =
        when {
            loadStates.refresh is LoadState.Error -> {
                loadStates.refresh as LoadState.Error
            }
            loadStates.prepend is LoadState.Error -> {
                loadStates.prepend as LoadState.Error
            }
            loadStates.append is LoadState.Error -> {
                loadStates.append as LoadState.Error
            }
            else -> null
        }

    loadStateError?.let {
        ErrorCard(
            onShowStacktrace = { showErrorDialog = true },
            onRetryClick = onRefresh,
            modifier = modifier,
        )
        if (showErrorDialog) {
            ErrorDialog(exception = it.error, onDismissRequest = { showErrorDialog = false })
        }
    }
}
