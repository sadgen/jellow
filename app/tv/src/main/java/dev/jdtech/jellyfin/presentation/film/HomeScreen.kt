package dev.jdtech.jellyfin.presentation.film

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSuggestions
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeView
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.presentation.film.components.HomeCarousel
import dev.jdtech.jellyfin.presentation.film.components.HomeSection
import dev.jdtech.jellyfin.presentation.film.components.HomeView
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

@Composable
fun HomeScreen(
    navigateToMovie: (itemId: UUID) -> Unit,
    navigateToShow: (itemId: UUID) -> Unit,
    navigateToPlayer: (itemId: UUID, itemKind: BaseItemKind) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    isLoading: (Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(true) {
        viewModel.loadData()
    }

    LaunchedEffect(state.isLoading) {
        isLoading(state.isLoading)
    }

    HomeScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is HomeAction.OnItemClick -> {
                    when (action.item) {
                        is FindroidMovie -> navigateToMovie(action.item.id)
                        is FindroidShow -> navigateToShow(action.item.id)
                        is FindroidEpisode -> {
                            navigateToPlayer(action.item.id, BaseItemKind.EPISODE)
                        }
                    }
                }
                is HomeAction.OnPlayClick -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", action.item.id.toString())
                    intent.putExtra("itemKind", when (action.item) {
                        is FindroidMovie -> BaseItemKind.MOVIE.serialName
                        is FindroidEpisode -> BaseItemKind.EPISODE.serialName
                        is FindroidShow -> BaseItemKind.SERIES.serialName
                        else -> BaseItemKind.MOVIE.serialName
                    })
                    context.startActivity(intent)
                }
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@Composable
private fun HomeScreenLayout(
    state: HomeState,
    onAction: (HomeAction) -> Unit,
) {
    val itemsPadding = PaddingValues(
        horizontal = MaterialTheme.spacings.large,
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(top = MaterialTheme.spacings.extraSmall, bottom = MaterialTheme.spacings.large),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        state.suggestionsSection?.let { section ->
            item(key = section.id) {
                HomeCarousel(
                    items = section.items,
                    onAction = onAction,
                    modifier = Modifier
                        .animateItem()
                        .padding(itemsPadding),
                )
            }
        }
        state.resumeSection?.let { section ->
            item(key = section.id) {
                HomeSection(
                    section = section.homeSection,
                    itemsPadding = itemsPadding,
                    onAction = onAction,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        state.nextUpSection?.let { section ->
            item(key = section.id) {
                HomeSection(
                    section = section.homeSection,
                    itemsPadding = itemsPadding,
                    onAction = onAction,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        items(state.views, key = { it.id }) { view ->
            HomeView(
                view = view,
                itemsPadding = itemsPadding,
                onAction = onAction,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun HomeScreenLayoutPreview() {
    FindroidTheme {
        HomeScreenLayout(
            state = HomeState(
                suggestionsSection = dummyHomeSuggestions,
                resumeSection = dummyHomeSection,
                views = listOf(dummyHomeView),
            ),
            onAction = {},
        )
    }
}