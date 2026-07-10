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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import android.view.GestureDetector
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
            delay(1000)
            val p = player ?: continue
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
            val bufferedUs = p.bufferedPosition
            val currTimeMs = System.currentTimeMillis()
            if (lastTimeMs > 0 && bufferedUs > lastBytes) {
                val deltaBytes = bufferedUs - lastBytes
                val deltaTime = (currTimeMs - lastTimeMs) / 1000f
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
            lastTimeMs = currTimeMs
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
                            setShowFastForwardButton(true)
                            setShowRewindButton(true)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL

                            val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                                    val dx = abs(e2.x - (e1?.x ?: e2.x))
                                    val dy = abs(e2.y - (e1?.y ?: e2.y))
                                    if (dx > dy && dx > 20f) {
                                        isScrubbing = true
                                        scrubFraction = (e2.x / width).coerceIn(0f, 1f)
                                        return true
                                    }
                                    return false
                                }
                            })

                            setOnTouchListener { _, event ->
                                gestureDetector.onTouchEvent(event)
                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        isScrubbing = false
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        if (isScrubbing) {
                                            isScrubbing = false
                                            val seekPos = (scrubFraction * (player?.duration ?: 0L)).toLong()
                                            player?.seekTo(seekPos)
                                        }
                                    }
                                }
                                false
                            }
                        }
                    },
                    update = { playerView ->
                        playerView.player = player
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (isScrubbing && trickplayInfo != null) {
                    Popup(
                        alignment = Alignment.TopCenter,
                        offset = IntOffset(0, with(LocalDensity.current) { (-200).dp.roundToPx() }),
                        properties = PopupProperties(focusable = false),
                    ) {
                        TrickplayPreview(
                            item = item,
                            trickplayInfo = trickplayInfo!!,
                            scrubFraction = scrubFraction,
                            repository = repository,
                        )
                    }
                }
            }
        }
    }
}
