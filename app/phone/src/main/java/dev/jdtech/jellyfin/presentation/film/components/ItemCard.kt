package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemCard(
    item: FindroidItem,
    direction: Direction,
    onClick: (FindroidItem) -> Unit,
    onPlayClick: (FindroidItem) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    isDuplicate: Boolean = false,
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
        modifier = modifier
            .width(width.dp)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = {
                    onClick(item)
                },
                onLongClick = onLongClick
            )
            .then(if (selected) Modifier.clip(MaterialTheme.shapes.small) else Modifier),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = if (isDuplicate) BorderStroke(2.dp, Color.Red) else null
        ) {
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
                
                // Show selection indicator when in selection mode
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(MaterialTheme.spacings.small)
                            .size(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color.Red  // 改为红底
                        ) {
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                
                // 添加透明播放按钮
                IconButton(
                    onClick = { onPlayClick(item) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(MaterialTheme.spacings.small)
                        .size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play),
                        contentDescription = "Play",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        Text(
            text = if (item is FindroidEpisode) item.seriesName else item.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (item is FindroidEpisode) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer 
                   else if (isDuplicate) Color.Red 
                   else MaterialTheme.colorScheme.onSurface
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
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) 
                      else if (isDuplicate) Color.Red.copy(alpha = 0.8f)
                      else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
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
