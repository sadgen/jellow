package dev.jdtech.jellyfin.presentation.film.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.viewinterop.AndroidView

import android.content.Context
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

private const val PREFS_NAME = "floating_player"
private const val KEY_SPEED = "playback_speed"

private fun getSpeedPrefs(context: Context): android.content.SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
    var trickplayFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var videoSize by remember { mutableStateOf<VideoSize?>(null) }
    var bitrateText by remember { mutableStateOf("") }
    var trafficText by remember { mutableStateOf("") }
    var totalConsumedBytes by remember { mutableLongStateOf(0L) }
    var dragStartX by remember { mutableFloatStateOf(-1f) }
    var dragActive by remember { mutableStateOf(false) }
    var dragSeekPos by remember { mutableLongStateOf(0L) }
    var dragStartPosition by remember { mutableLongStateOf(0L) }
    var scrubTimeText by remember { mutableStateOf("") }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var playerDuration by remember { mutableLongStateOf(0L) }
    var containerWidth by remember { mutableFloatStateOf(0f) }
    var currentSpeed by remember { mutableFloatStateOf(1f) }
    var speedDragActive by remember { mutableStateOf(false) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var speedOverlayText by remember { mutableStateOf("") }

    LaunchedEffect(item.id) {
        totalConsumedBytes = 0L
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

            // 加载并应用保存的播放速度
            val savedSpeed = getSpeedPrefs(context).getFloat(KEY_SPEED, 1f)
            currentSpeed = savedSpeed.coerceIn(1f, 5f)
            exoPlayer.setPlaybackSpeed(currentSpeed)
        }

        if (item is FindroidSources) {
            try {
                val info = repository.getTrickplayInfoForItem(item.id)
                trickplayInfo = info
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(trickplayInfo) {
        val info = trickplayInfo ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val totalTiles = item.runtimeTicks.let { ticks ->
                ((ticks / 10_000) / info.interval.toLong()).toInt().coerceAtLeast(1)
            }
            val tilesPerSprite = info.tileWidth * info.tileHeight
            val totalSprites = (totalTiles + tilesPerSprite - 1) / tilesPerSprite

            val frames = mutableListOf<Bitmap>()
            for (i in 0 until totalSprites) {
                try {
                    val data = repository.getTrickplayData(item.id, info.width, i) ?: continue
                    val fullBitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: continue
                    // Extract individual tiles like PlayerViewModel does
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

    DisposableEffect(item.id) {
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
        var lastPositionForTraffic = 0L
        var lastTrafficUpdateMs = 0L
        while (isActive) {
            delay(250)
            val p = player ?: continue
            // Update position every 250ms for smooth progress bar
            currentPosition = p.currentPosition
            playerDuration = p.duration.coerceAtLeast(0)

            // Bitrate updates every tick (250ms)
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

            // Traffic (total consumed bytes) updates every 1s
            val now = System.currentTimeMillis()
            if (now - lastTrafficUpdateMs >= 1000) {
                val currentPos = p.currentPosition
                val deltaMs = currentPos - lastPositionForTraffic
                // Only accumulate if position moved forward normally (not a seek, delta < 5000ms)
                if (deltaMs > 0 && deltaMs < 5000) {
                    val bitrate = p.videoFormat?.bitrate ?: 0
                    if (bitrate > 0) {
                        totalConsumedBytes += (deltaMs * bitrate) / 8000
                    }
                }
                lastPositionForTraffic = currentPos
                lastTrafficUpdateMs = now

                trafficText = formatBytes(totalConsumedBytes)
            }
        }
    }

    val aspectRatio = remember(videoSize) {
        val vs = videoSize
        if (vs != null && vs.width > 0 && vs.height > 0) {
            vs.width.toFloat() / vs.height.toFloat()
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
                        Text(
                            text = buildString {
                                append(String.format("%.1f", currentSpeed))
                                append("x | ")
                                append(if (bitrateText.isNotEmpty()) bitrateText else "—")
                                append(" | ")
                                append(if (trafficText.isNotEmpty()) trafficText else "—")
                            },
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
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
                    .background(Color.Black)
                    .onGloballyPositioned { coordinates ->
                        containerWidth = coordinates.size.width.toFloat()
                    },
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
                                        dragStartY = event.y
                                        dragActive = false
                                        speedDragActive = false
                                        isScrubbing = false
                                        scrubTimeText = ""
                                        speedOverlayText = ""
                                        val p = player
                                        dragStartPosition = p?.currentPosition ?: 0L
                                        true
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (dragStartX >= 0) {
                                            val deltaX = event.x - dragStartX
                                            val deltaY = event.y - dragStartY

                                            // 判断滑动方向：水平→快进快退，垂直→调速
                                            if (!dragActive && !speedDragActive) {
                                                if (abs(deltaX) > 30f && abs(deltaX) > abs(deltaY) * 1.5f) {
                                                    dragActive = true
                                                    isScrubbing = true
                                                } else if (abs(deltaY) > 30f && abs(deltaY) > abs(deltaX) * 1.5f) {
                                                    speedDragActive = true
                                                }
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
                                            if (speedDragActive) {
                                                val p = player ?: return@setOnTouchListener true
                                                val viewHeight = height.toFloat()
                                                val dragRange = viewHeight / 3f  // 滑动1/3高度覆盖全范围
                                                // deltaY > 0 = 下滑 → 减速; deltaY < 0 = 上滑 → 加速
                                                val ratio = -deltaY / dragRange
                                                val newSpeed = (1f + ratio * 4f).coerceIn(1f, 5f)
                                                // 四舍五入到 0.5x 步进
                                                val roundedSpeed = (newSpeed * 2f).roundToInt() / 2f
                                                currentSpeed = roundedSpeed.coerceIn(1f, 5f)
                                                p.setPlaybackSpeed(currentSpeed)
                                                speedOverlayText = "${String.format("%.1f", currentSpeed)}x"
                                                // 持久化保存
                                                getSpeedPrefs(context).edit().putFloat(KEY_SPEED, currentSpeed).apply()
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
                                        if (speedDragActive) {
                                            speedDragActive = false
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

                // 调速时的叠加文字
                if (speedDragActive && speedOverlayText.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = speedOverlayText,
                            color = Color.White,
                            fontSize = 36.sp,
                        )
                    }
                }

                if (playerDuration > 0) {
                    val progress = if (isScrubbing) scrubFraction
                                  else (currentPosition.toFloat() / playerDuration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val canvasWidth = size.width
                            val canvasHeight = size.height
                            val lineY = canvasHeight / 2f
                            val strokeWidthPx = with(density) { 3.dp.toPx() }
                            drawLine(
                                color = Color.White.copy(alpha = 0.3f),
                                start = Offset(0f, lineY),
                                end = Offset(canvasWidth, lineY),
                                strokeWidth = strokeWidthPx,
                            )
                            drawLine(
                                color = Color(0xFF00A8FF),
                                start = Offset(0f, lineY),
                                end = Offset(canvasWidth * progress, lineY),
                                strokeWidth = strokeWidthPx,
                            )
                            val thumbRadiusPx = with(density) { 5.dp.toPx() }
                            drawCircle(
                                color = Color.White,
                                radius = thumbRadiusPx,
                                center = Offset(canvasWidth * progress, lineY),
                            )
                        }
                    }
                }

                if (isScrubbing) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 3.dp + 8.dp)
                            .offset { IntOffset(((scrubFraction - 0.5f) * containerWidth).roundToInt(), 0) }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (trickplayInfo != null) {
                                TrickplayPreview(
                                    item = item,
                                    trickplayInfo = trickplayInfo!!,
                                    scrubFraction = scrubFraction,
                                    trickplayFrames = trickplayFrames,
                                    modifier = Modifier.width(200.dp).height(112.dp),
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

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
