package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.repository.JellyfinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FloatingVideoPlayer(
    item: FindroidItem,
    repository: JellyfinRepository,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    LaunchedEffect(item.id) {
        val exoPlayer = ExoPlayer.Builder(context).build()
        player = exoPlayer

        val source = withContext(Dispatchers.IO) {
            val sources = repository.getMediaSources(item.id, true, false)
            sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                ?: sources.firstOrNull()
        }

        if (source != null) {
            val mediaItem = MediaItem.fromUri(source.path)
            val positionMs = (item.playbackPositionTicks / 10000).coerceAtLeast(0)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.seekTo(positionMs)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player?.apply {
                playWhenReady = false
                stop()
                release()
            }
            player = null
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.name,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
    ) {
        player?.let { currentPlayer ->
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = currentPlayer
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowFastForwardButton(true)
                        setShowRewindButton(true)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
