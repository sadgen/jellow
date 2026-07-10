package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

import android.view.MotionEvent
import androidx.media3.common.VideoSize
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.repository.JellyfinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun FloatingVideoPlayer(
    item: FindroidItem,
    repository: JellyfinRepository,
    onDismiss: () -> Unit,
    initialOffsetY: Float? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val resolvedOffsetY = initialOffsetY ?: with(density) { 120.dp.toPx() }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(resolvedOffsetY) }
    var trickplayInfo by remember { mutableStateOf<FindroidTrickplayInfo?>(null) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var videoSize by remember { mutableStateOf<VideoSize?>(null) }
    var bitrateText by remember { mutableStateOf("") }
    var trafficText by remember { mutableStateOf("") }
    var dragStartX by remember { mutableFloatStateOf(-1f) }
    var dragActive by remember { mutableStateOf(false) }
    var dragSeekPos by remember { mutableLongStateOf(0L) }
    var dragStartPosition by remember { mutableLongStateOf(0L) }
    var scrubTimeText by remember { mutableStateOf("") }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var playerDuration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(item.id) {
        val exoPlayer = ExoPlayer.Builder(context).build()
        player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }
        })

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

        if (item is FindroidSources) {
            try {
                val info = repository.getTrickplayInfoForItem(item.id)
                trickplayInfo = info
            } catch (_: Exception) {}
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

    LaunchedEffect(player) {
        var lastBytes = 0L
        var lastTimeMs = 0L
        while (isActive) {
            delay(250)
            val p = player ?: continue
            // Update position every 250ms for smooth progress bar
            currentPosition = p.currentPosition
            playerDuration = p.duration.coerceAtLeast(0)

            // Bitrate and traffic still update every 1s
            val now = System.currentTimeMillis()
            val format = p.videoFormat
            if (format != null && format.bitrate > 0) {
                bitrateText = if (format.bitrate >= 1_000_000) {
                    String.format("%.1f Mbps", format.bitrate / 1_000_000f)
                } else {
                    "${format.bitrate / 1_000} Kbps"
                }
            } else {
                bitrateText = ""
            }
            if (now - lastTimeMs >= 1000) {
                val bufferedUs = p.bufferedPosition
                if (lastTimeMs > 0 && bufferedUs > lastBytes) {
                    val deltaBytes = bufferedUs - lastBytes
                    val deltaTime = (now - lastTimeMs) / 1000f
                    if (deltaTime > 0) {
                        val speedMbps = (deltaBytes * 8f) / (deltaTime * 1_000_000f)
                        if (speedMbps > 0.1f) {
                            trafficText = String.format("%.1f Mbps", speedMbps)
                        } else {
                            val speedKBps = (deltaBytes) / (deltaTime * 1000f)
                            trafficText = String.format("%.0f KB/s", speedKBps)
                        }
                    }
                }
                lastBytes = bufferedUs
                lastTimeMs = now
            }
        }
    }

    val aspectRatio = remember(videoSize) {
        if (videoSize != null && videoSize!!.width > 0 && videoSize!!.height > 0) {
            videoSize!!.width.toFloat() / videoSize!!.height.toFloat()
        } else {
            16f / 9f
        }
    }

    Box(modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }) {
        Column(modifier = modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF1A1A1A),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .padding(horizontal = 12.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    offsetX += dragAmount.x
                                    offsetY += dragAmount.y
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (bitrateText.isNotEmpty() || trafficText.isNotEmpty()) {
                            Text(
                                text = buildString {
                                    if (bitrateText.isNotEmpty()) append(bitrateText)
                                    if (bitrateText.isNotEmpty() && trafficText.isNotEmpty()) append(" | ")
                                    if (trafficText.isNotEmpty()) append(trafficText)
                                },
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = item.name,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painter = painterResource(R.drawable.ic_x),
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
                    .aspectRatio(aspectRatio)
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL

                            setOnTouchListener { _, event ->
                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        dragStartX = event.x
                                        dragActive = false
                                        isScrubbing = false
                                        scrubTimeText = ""
                                        val p = player
                                        dragStartPosition = p?.currentPosition ?: 0L
                                        true
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (dragStartX >= 0) {
                                            val deltaX = event.x - dragStartX
                                            if (!dragActive && abs(deltaX) > 30f) {
                                                dragActive = true
                                                isScrubbing = true
                                            }
                                            if (dragActive) {
                                                val p = player ?: return@setOnTouchListener true
                                                val duration = p.duration
                                                if (duration > 0) {
                                                    val seekMultiplier = duration.toFloat() / width.toFloat()
                                                    val newPos = (dragStartPosition + (deltaX * seekMultiplier).toLong()).coerceIn(0, duration)
                                                    dragSeekPos = newPos
                                                    scrubFraction = newPos.toFloat() / duration.toFloat()
                                                    val targetSec = newPos / 1000
                                                    val currentSec = dragStartPosition / 1000
                                                    val diffSec = targetSec - currentSec
                                                    val sign = if (diffSec >= 0) "+" else ""
                                                    scrubTimeText = when {
                                                        duration >= 3600_000 -> "${sign}%02d:%02d:%02d [%02d:%02d:%02d]".format(
                                                            abs(diffSec) / 3600, (abs(diffSec) % 3600) / 60, abs(diffSec) % 60,
                                                            targetSec / 3600, (targetSec % 3600) / 60, targetSec % 60
                                                        )
                                                        else -> "${sign}%02d:%02d [%02d:%02d]".format(
                                                            abs(diffSec) / 60, abs(diffSec) % 60,
                                                            targetSec / 60, targetSec % 60
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        true
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        if (dragActive) {
                                            isScrubbing = false
                                            player?.seekTo(dragSeekPos)
                                            dragActive = false
                                            scrubTimeText = ""
                                        }
                                        dragStartX = -1f
                                        true
                                    }
                                    else -> true
                                }
                            }
                        }
                    },
                    update = { playerView ->
                        playerView.player = player
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (playerDuration > 0) {
                    val progress = if (isScrubbing) scrubFraction
                                  else (currentPosition.toFloat() / playerDuration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .matchParentSize()
                                .background(Color(0xFF00A8FF))
                        )
                    }
                }

                if (isScrubbing) {
                    Popup(
                        alignment = Alignment.TopCenter,
                        offset = IntOffset(0, with(LocalDensity.current) { 48.dp.roundToPx() }),
                        properties = PopupProperties(focusable = false),
                        onDismissRequest = {},
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (trickplayInfo != null) {
                                TrickplayPreview(
                                    item = item,
                                    trickplayInfo = trickplayInfo!!,
                                    scrubFraction = scrubFraction,
                                    repository = repository,
                                )
                            }
                            Text(
                                text = scrubTimeText,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .background(
                                        color = Color.Black.copy(alpha = 0.75f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
