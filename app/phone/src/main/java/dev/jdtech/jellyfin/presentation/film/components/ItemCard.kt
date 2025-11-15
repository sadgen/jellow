package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.repository.JellyfinRepository
import kotlinx.coroutines.launch

@Composable
fun ItemCard(
    item: FindroidItem,
    direction: Direction,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
    repository: JellyfinRepository? = null,
    onPlayClick: ((FindroidItem) -> Unit)? = null,
) {
    val width =
        when (direction) {
            Direction.HORIZONTAL -> 260
            Direction.VERTICAL -> 150
        }

    val coroutineScope = rememberCoroutineScope()

    // Trickplay scrub state
    var isScrubbing by remember { mutableStateOf(false) }
    var isActuallyDragging by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var cardWidthPx by remember { mutableFloatStateOf(0f) }
    var trickplayInfo by remember { mutableStateOf<FindroidTrickplayInfo?>(null) }
    var runtimeTicks by remember { mutableStateOf(0L) }

    Column(
        modifier =
            modifier
                .width(width.dp)
                .clip(MaterialTheme.shapes.small)
                .onGloballyPositioned { coords ->
                    cardWidthPx = coords.size.width.toFloat()
                }
                .pointerInput(item, repository) {
                    detectTapGestures(
                        onTap = { onClick(item) },
                    )
                }
                .then(
                    if (repository != null) {
                        Modifier.pointerInput(item, repository) {
                            detectDragGesturesAfterShortLongPress(
                                onDragStart = { offset ->
                                    isActuallyDragging = true
                                    // Fetch trickplay info on-demand
                                    if (cardWidthPx > 0) {
                                        scrubFraction = (offset.x / cardWidthPx).coerceIn(0f, 1f)
                                    }
                                    val sources = item as? FindroidSources
                                    val existingInfo = sources?.trickplayInfo?.values?.firstOrNull()
                                    val itemRuntimeTicks = sources?.runtimeTicks ?: item.runtimeTicks

                                    if (existingInfo != null && itemRuntimeTicks > 0) {
                                        trickplayInfo = existingInfo
                                        runtimeTicks = itemRuntimeTicks
                                        isScrubbing = true
                                    } else if (itemRuntimeTicks > 0) {
                                        // Fetch from API
                                        coroutineScope.launch {
                                            val info = repository.getTrickplayInfoForItem(item.id)
                                            if (info != null && isActuallyDragging) {
                                                trickplayInfo = info
                                                runtimeTicks = itemRuntimeTicks
                                                isScrubbing = true
                                            }
                                        }
                                    }
                                },
                                onDragEnd = { 
                                    isScrubbing = false
                                    isActuallyDragging = false
                                },
                                onDragCancel = { 
                                    isScrubbing = false
                                    isActuallyDragging = false
                                },
                                onDrag = { change, _ ->
                                    if (cardWidthPx > 0 && isActuallyDragging) {
                                        scrubFraction = (change.position.x / cardWidthPx).coerceIn(0f, 1f)
                                        isScrubbing = true // Ensure visible if we move even before data returns? 
                                        // Actually isScrubbing is controlled by data availability.
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                )
    ) {
        Surface(shape = MaterialTheme.shapes.small) {
            Box {
                ItemPoster(item = item, direction = direction)
                if (isScrubbing) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(scrubFraction)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Row(
                    modifier =
                        Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                ) {
                    if (item.isDownloaded()) DownloadedBadge()
                    if (item.played) PlayedBadge()
                    if (item.playCount > 0) PlayCountBadge(count = item.playCount)
                    item.unplayedItemCount?.takeIf { it > 0 }?.let { ItemCountBadge(it) }
                }
                if (direction == Direction.HORIZONTAL) {
                    ProgressBar(
                        item = item,
                        width = width,
                        modifier =
                            Modifier.align(Alignment.BottomStart)
                                .padding(MaterialTheme.spacings.small),
                    )
                }
                // Quick play button
                if (item.canPlay && onPlayClick != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(MaterialTheme.spacings.small)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                color = Color.Black.copy(alpha = 0.45f),
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { onPlayClick(item) })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = "Play",
                            tint = Color.White.copy(alpha = 0.95f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // Trickplay preview popup
                if (isScrubbing && trickplayInfo != null && repository != null) {
                    Popup(
                        alignment = Alignment.TopCenter,
                        offset = IntOffset(0, with(LocalDensity.current) { (-200).dp.roundToPx() }),
                        properties = PopupProperties(focusable = false),
                    ) {
                        TrickplayPreview(
                            itemId = item.id,
                            trickplayInfo = trickplayInfo!!,
                            runtimeTicks = runtimeTicks,
                            scrubFraction = scrubFraction,
                            repository = repository,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        Text(
            text = if (item is FindroidEpisode) item.seriesName else item.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (item is FindroidEpisode) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item is FindroidEpisode) {
            Text(
                text =
                    stringResource(
                        id = R.string.episode_name_extended,
                        item.parentIndexNumber,
                        item.indexNumber,
                        item.name,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
fun PlayCountBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = null,
                modifier = Modifier.height(10.dp),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovie() {
    FindroidTheme { ItemCard(item = dummyMovie, direction = Direction.HORIZONTAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovieVertical() {
    FindroidTheme { ItemCard(item = dummyMovie, direction = Direction.VERTICAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisode() {
    FindroidTheme { ItemCard(item = dummyEpisode, direction = Direction.HORIZONTAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisodeVertical() {
    FindroidTheme { ItemCard(item = dummyEpisode, direction = Direction.VERTICAL, onClick = {}) }
}

suspend fun PointerInputScope.detectDragGesturesAfterShortLongPress(
    onDragStart: (Offset) -> Unit = { },
    onDragEnd: (Offset) -> Unit = { },
    onDragCancel: () -> Unit = { },
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // AwaitPointerEventScope.withTimeoutOrNull returns null on timeout
        val timeoutReached = withTimeoutOrNull(200) {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.any { it.changedToUp() && it.id == down.id }) {
                    // Finger lifted before timeout
                    return@withTimeoutOrNull true
                }
                if (event.changes.any { it.positionChange() != Offset.Zero && it.id == down.id }) {
                    // Finger moved too much
                    return@withTimeoutOrNull true
                }
            }
        } == null

        if (timeoutReached) {
            // Timeout reached! Finger is still down and hasn't moved significantly.
            onDragStart.invoke(down.position)
            if (
                drag(down.id) {
                    onDrag(it, it.positionChange())
                    it.consume()
                }
            ) {
                onDragEnd.invoke(down.position)
            } else {
                onDragCancel.invoke()
            }
        }
    }
}
