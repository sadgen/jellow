package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 检查 item 是否有潜在可能支持 trickplay（是电影/剧集即可）。
 */
private fun FindroidItem.canUseTrickplay(): Boolean {
    return this is FindroidSources
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

    val coroutineScope = rememberCoroutineScope()

    // Trickplay 状态
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var trickplayInfo by remember { mutableStateOf<FindroidTrickplayInfo?>(null) }
    var trickplayFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var cardWidthPx by remember { mutableFloatStateOf(0f) }
    var trickplayFetched by remember { mutableStateOf(false) }

    // 预加载 trickplayInfo：卡片进入时立即异步获取
    if (item.canUseTrickplay() && repository != null && !trickplayFetched) {
        LaunchedEffect(item.id) {
            try {
                val info = repository.getTrickplayInfoForItem(item.id)
                trickplayInfo = info
            } catch (_: Exception) {}
            trickplayFetched = true
        }
    }

    // 长按拖动时预加载所有 trickplay 单帧
    LaunchedEffect(isScrubbing) {
        if (!isScrubbing) return@LaunchedEffect
        val info = trickplayInfo ?: return@LaunchedEffect
        if (repository == null) return@LaunchedEffect
        if (trickplayFrames.isNotEmpty()) return@LaunchedEffect  // 已加载过

        withContext(Dispatchers.IO) {
            val totalTiles = item.runtimeTicks.let { ticks ->
                ((ticks / 10_000) / info.interval.toLong()).toInt().coerceAtLeast(1)
            }
            val tilesPerSprite = info.tileWidth * info.tileHeight
            val totalSprites = (totalTiles + tilesPerSprite - 1) / tilesPerSprite

            val frames = mutableListOf<Bitmap>()
            for (i in 0 until totalSprites) {
                try {
                    val data = repository!!.getTrickplayData(item.id, info.width, i) ?: continue
                    val fullBitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: continue
                    for (offsetY in 0 until info.height * info.tileHeight step info.height) {
                        for (offsetX in 0 until info.width * info.tileWidth step info.width) {
                            if (frames.size >= totalTiles) break
                            val tile = Bitmap.createBitmap(fullBitmap, offsetX, offsetY, info.width, info.height)
                            frames.add(tile)
                        }
                        if (frames.size >= totalTiles) break
                    }
                } catch (_: Exception) {}
            }
            trickplayFrames = frames
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .width(width.dp)
            .clip(MaterialTheme.shapes.small)
            .onGloballyPositioned { coords ->
                cardWidthPx = coords.size.width.toFloat()
            }
            .pointerInput(item.id) {
                detectTapGestures(onTap = { onClick(item) })
            }
            .pointerInput(item.id, repository, trickplayFetched) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startOffset ->
                        // 计算手指在卡片上的位置比例（0~1）
                        if (trickplayInfo != null && cardWidthPx > 0) {
                            scrubFraction = (startOffset.x / cardWidthPx).coerceIn(0f, 1f)
                            isScrubbing = true
                        } else if (trickplayFetched && trickplayInfo == null) {
                            // 服务端没有 trickplay 数据
                            onLongClick?.invoke()
                        } else {
                            onLongClick?.invoke()
                        }
                    },
                    onDrag = { change, _ ->
                        if (isScrubbing && cardWidthPx > 0) {
                            scrubFraction = (change.position.x / cardWidthPx).coerceIn(0f, 1f)
                        }
                    },
                    onDragEnd = {
                        isScrubbing = false
                    },
                    onDragCancel = {
                        isScrubbing = false
                    },
                )
            }
            .then(if (selected) Modifier.clip(MaterialTheme.shapes.small) else Modifier),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = if (isDuplicate) BorderStroke(2.dp, Color.Red) else null,
        ) {
            Box {
                ItemPoster(item = item, direction = direction)

                // 正在 scrubbing 时的进度条
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

                // Trickplay Popup
                if (isScrubbing && trickplayInfo != null && repository != null) {
                    Popup(
                        alignment = Alignment.TopCenter,
                        offset = IntOffset(0, with(LocalDensity.current) { (-200).dp.roundToPx() }),
                        properties = PopupProperties(focusable = false),
                    ) {
                        TrickplayPreview(
                            item = item,
                            trickplayInfo = trickplayInfo!!,
                            scrubFraction = scrubFraction,
                            trickplayFrames = trickplayFrames,
                            repository = repository,
                        )
                    }
                }
            }
        }
        if (!isScrubbing) {
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
