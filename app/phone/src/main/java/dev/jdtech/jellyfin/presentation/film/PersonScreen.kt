package dev.jdtech.jellyfin.presentation.film

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.core.presentation.dummy.dummyPersonDetail
import dev.jdtech.jellyfin.film.presentation.person.PersonAction
import dev.jdtech.jellyfin.film.presentation.person.PersonState
import dev.jdtech.jellyfin.film.presentation.person.PersonViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.film.components.ItemTopBar
import dev.jdtech.jellyfin.presentation.film.components.OverviewText
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import java.util.UUID

@Composable
fun PersonScreen(
    personId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToItem: (item: FindroidItem) -> Unit,
    viewModel: PersonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadPerson(personId) }

    PersonScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is PersonAction.NavigateBack -> navigateBack()
                is PersonAction.NavigateHome -> navigateHome()
                is PersonAction.NavigateToItem -> navigateToItem(action.item)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonScreenLayout(state: PersonState, onAction: (PersonAction) -> Unit) {
    val safePadding = rememberSafePadding()
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

    val paddingStart = safePadding.start + MaterialTheme.spacings.small
    val paddingTop = safePadding.top + MaterialTheme.spacings.small
    val paddingEnd = safePadding.end + MaterialTheme.spacings.small
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.small

    val itemsPadding = PaddingValues(start = paddingStart, end = paddingEnd)

    Box(modifier = Modifier.fillMaxSize()) {
        state.person?.let { person ->
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(paddingTop))
                when {
                    windowSizeClass.isWidthAtLeastBreakpoint(
                        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
                    ) -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(itemsPadding),
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            PersonImage(person)
                            Column(
                                verticalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.medium)
                            ) {
                                Text(
                                    text = person.name,
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                if (person.overview.isNotBlank()) {
                                    OverviewText(text = person.overview, maxCollapsedLines = 12)
                                }
                                // 排序和视图切换按钮
                                PersonActionButtons(
                                    isListView = state.isListView,
                                    onToggleView = { onAction(PersonAction.ToggleViewMode) },
                                )
                            }
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(itemsPadding),
                            verticalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.medium),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PersonImage(person)
                            Text(
                                text = person.name,
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            if (person.overview.isNotBlank()) {
                                OverviewText(text = person.overview, maxCollapsedLines = 4)
                            }
                            // 排序和视图切换按钮
                            PersonActionButtons(
                                isListView = state.isListView,
                                onToggleView = { onAction(PersonAction.ToggleViewMode) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(MaterialTheme.spacings.small))

                Column(
                    modifier = Modifier.fillMaxWidth().padding(itemsPadding),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                ) {
                    if (state.starredInMovies.isNotEmpty()) {
                        Text(
                            text = stringResource(CoreR.string.movies_label),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            state.starredInMovies.forEach { item ->
                                ItemCard(
                                    item = item,
                                    direction = if (state.isListView) Direction.HORIZONTAL else Direction.VERTICAL,
                                    onClick = { onAction(PersonAction.NavigateToItem(item)) },
                                    modifier = Modifier
                                        .fillMaxWidth(if (state.isListView) 0.5f else 0.33f),
                                )
                            }
                        }
                    }

                    if (state.starredInShows.isNotEmpty()) {
                        Text(
                            text = stringResource(CoreR.string.shows_label),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            state.starredInShows.forEach { item ->
                                ItemCard(
                                    item = item,
                                    direction = if (state.isListView) Direction.HORIZONTAL else Direction.VERTICAL,
                                    onClick = { onAction(PersonAction.NavigateToItem(item)) },
                                    modifier = Modifier
                                        .fillMaxWidth(if (state.isListView) 0.5f else 0.33f),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(paddingBottom))
            }
        } ?: run { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }

        ItemTopBar(
            hasBackButton = true,
            hasHomeButton = true,
            onBackClick = { onAction(PersonAction.NavigateBack) },
            onHomeClick = { onAction(PersonAction.NavigateHome) },
        )
    }
}

@Composable
private fun PersonActionButtons(
    isListView: Boolean,
    onToggleView: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        IconButton(onClick = onToggleView) {
            Icon(
                painter = painterResource(
                    if (isListView) CoreR.drawable.ic_view_grid else CoreR.drawable.ic_view_list
                ),
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun PersonImage(person: FindroidPerson, modifier: Modifier = Modifier) {
    AsyncImage(
        model = person.images.primary,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .height(320.dp)
                .aspectRatio(0.66f)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainer),
    )
}

@PreviewScreenSizes
@Composable
private fun PersonScreenLayoutPreview() {
    FindroidTheme {
        PersonScreenLayout(
            state = PersonState(person = dummyPersonDetail, starredInMovies = dummyMovies),
            onAction = {},
        )
    }
}
