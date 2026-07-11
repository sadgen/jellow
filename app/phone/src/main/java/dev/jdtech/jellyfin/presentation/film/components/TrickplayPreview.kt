package dev.jdtech.jellyfin.presentation.film.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.repository.JellyfinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 播放器进度条风格的 trickplay 预览。
 * 根据 scrubFraction（0~1）计算对应的 sprite 图块，按需加载并缓存 sprite 图片。
 * 显示当前帧的时间戳（MM:SS 格式）。
 */
@Composable
fun TrickplayPreview(
    item: FindroidItem,
    trickplayInfo: FindroidTrickplayInfo,
    scrubFraction: Float,
    trickplayFrames: List<Bitmap>,
    repository: JellyfinRepository? = null,
    modifier: Modifier = Modifier,
) {
    val runtimeTicks = item.runtimeTicks
    val durationMs = runtimeTicks / 10_000
    val intervalMs = trickplayInfo.interval.toLong()
    val totalTiles = (durationMs / intervalMs).toInt().coerceAtLeast(1)
    val tileIndex = (scrubFraction * totalTiles).toInt().coerceIn(0, totalTiles - 1)

    // 当前时间戳
    val seekTimeSec = (scrubFraction * runtimeTicks / 10_000_000).toLong()
    val minutes = seekTimeSec / 60
    val seconds = seekTimeSec % 60
    val timeText = "%02d:%02d".format(minutes, seconds)

    var tileBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(tileIndex) {
        if (tileIndex in trickplayFrames.indices) {
            // Fast path: pre-extracted frame, instant
            tileBitmap = trickplayFrames[tileIndex]
        } else if (repository != null) {
            // Fallback: on-demand sprite fetch (ItemCard path)
            delay(80)
            withContext(Dispatchers.IO) {
                try {
                    val tilesPerSprite = trickplayInfo.tileWidth * trickplayInfo.tileHeight
                    val spriteIndex = tileIndex / tilesPerSprite
                    val tileInSprite = tileIndex % tilesPerSprite
                    val col = tileInSprite % trickplayInfo.tileWidth
                    val row = tileInSprite / trickplayInfo.tileWidth
                    val data = repository.getTrickplayData(item.id, trickplayInfo.width, spriteIndex)
                        ?: return@withContext
                    val spriteBitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                        ?: return@withContext
                    withContext(Dispatchers.Main) {
                        val tileW = trickplayInfo.width
                        val tileH = trickplayInfo.height
                        val x = col * tileW
                        val y = row * tileH
                        if (x + tileW <= spriteBitmap.width && y + tileH <= spriteBitmap.height) {
                            tileBitmap = Bitmap.createBitmap(spriteBitmap, x, y, tileW, tileH)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(width = 300.dp, height = 169.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            tileBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Text(
            text = timeText,
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .padding(top = 4.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
