package dev.jdtech.jellyfin.presentation.film.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.repository.JellyfinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun FindroidItem.canUseTrickplay(): Boolean {
    return (this as? FindroidSources)?.trickplayInfo?.isNotEmpty() == true
}

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
) {
    val width =
        when (direction) {
            Direction.HORIZONTAL -> 260
            Direction.VERTICAL -> 150
        }

    // Trickplay press-to-preview state
    var isPreviewShowing by remember { mutableStateOf(false) }
    var previewFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var previewFrameIndex by remember { mutableIntStateOf(0) }
    var previewDragAccum by remember { mutableFloatStateOf(0f) }

    // 加载 trickplay 帧
    if (isPreviewShowing && previewFrames.isEmpty() && repository != null) {
        TrickplayLoader(item = item, repository = repository) { frames ->
            previewFrames = frames
            previewFrameIndex = 0
            previewDragAccum = 0f
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(
                if (isPreviewShowing) {
                    Modifier.pointerInput(previewFrames.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { /* 按住后拖动 */ },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                previewDragAccum += dragAmount.x
                                val total = previewFrames.size
                                if (total > 0) {
                                    val delta = (previewDragAccum / 40f).toInt()
                                    previewFrameIndex =
                                        ((previewFrameIndex + delta) % total + total) % total
                                    previewDragAccum -= delta * 40f
                                }
                            },
                            onDragEnd = {
                                isPreviewShowing = false
                                previewFrames = emptyList()
                            },
                            onDragCancel = {
                                isPreviewShowing = false
                                previewFrames = emptyList()
                            },
                        )
                    }
                } else {
                    Modifier
                        .pointerInput(item.id) {
                            detectTapGestures(onTap = { onClick(item) })
                        }
                        .pointerInput(item.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    if (item.canUseTrickplay() && repository != null) {
                                        isPreviewShowing = true
                                    } else {
                                        onLongClick?.invoke()
                                    }
                                },
                                onDrag = { _, _ -> },
                                onDragEnd = {},
                                onDragCancel = {},
                            )
                        }
                }
            )
            .then(if (selected) Modifier.clip(MaterialTheme.shapes.small) else Modifier),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = if (isDuplicate) BorderStroke(2.dp, Color.Red) else null,
        ) {
            Box {
                if (isPreviewShowing && previewFrames.isNotEmpty()) {
                    // 内联 trickplay 预览
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        val frame = previewFrames[previewFrameIndex]
                        Image(
                            painter = BitmapPainter(frame.asImageBitmap()),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            text = "${previewFrameIndex + 1}/${previewFrames.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 4.dp),
                        )
                    }
                } else {
                    ItemPoster(item = item, direction = direction)

                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(MaterialTheme.spacings.small),
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
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(MaterialTheme.spacings.small),
                        )
                    }

                    if (selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(MaterialTheme.spacings.small)
                                .size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(shape = MaterialTheme.shapes.small, color = Color.Red) {
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = { onPlayClick(item) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(MaterialTheme.spacings.small)
                            .size(36.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = "Play",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        if (!isPreviewShowing) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
            Text(
                text = if (item is FindroidEpisode) item.seriesName else item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (item is FindroidEpisode) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else if (isDuplicate) Color.Red
                else MaterialTheme.colorScheme.onSurface,
            )
            if (item is FindroidEpisode) {
                Text(
                    text = stringResource(
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
}

@Composable
private fun TrickplayLoader(
    item: FindroidItem,
    repository: JellyfinRepository,
    onLoaded: (List<Bitmap>) -> Unit,
) {
    LaunchedEffect(item.id) {
        try {
            val info = repository.getTrickplayInfoForItem(item.id) ?: return@LaunchedEffect
            val maxIndex = kotlin.math.ceil(
                info.thumbnailCount.toDouble()
                    .div(info.tileWidth * info.tileHeight)
            ).toInt()
            val frameList = mutableListOf<Bitmap>()
            for (i in 0..maxIndex) {
                try {
                    val data = repository.getTrickplayData(item.id, info.width, i) ?: continue
                    withContext(Dispatchers.IO) {
                        val fullBitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                        if (fullBitmap != null) {
                            for (offsetY in 0..<info.height * info.tileHeight step info.height) {
                                for (offsetX in 0..<info.width * info.tileWidth step info.width) {
                                    val frame = Bitmap.createBitmap(fullBitmap, offsetX, offsetY, info.width, info.height)
                                    frameList.add(frame)
                                    if (frameList.size >= info.thumbnailCount) break
                                }
                                if (frameList.size >= info.thumbnailCount) break
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
            if (frameList.isNotEmpty()) onLoaded(frameList)
        } catch (_: Exception) { }
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
