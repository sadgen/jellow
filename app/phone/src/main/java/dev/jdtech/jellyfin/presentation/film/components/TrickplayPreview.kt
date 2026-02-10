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
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TrickplayPreview(
    itemId: UUID,
    trickplayInfo: FindroidTrickplayInfo,
    runtimeTicks: Long,
    scrubFraction: Float,
    repository: JellyfinRepository,
    modifier: Modifier = Modifier,
) {
    val durationMs = runtimeTicks / 10_000
    val intervalMs = trickplayInfo.interval.toLong()
    val totalTiles = (durationMs / intervalMs).toInt().coerceAtLeast(1)
    val tilesPerSprite = trickplayInfo.tileWidth * trickplayInfo.tileHeight
    val tileIndex = (scrubFraction * totalTiles).toInt().coerceIn(0, totalTiles - 1)
    val spriteIndex = tileIndex / tilesPerSprite
    val tileInSprite = tileIndex % tilesPerSprite
    val col = tileInSprite % trickplayInfo.tileWidth
    val row = tileInSprite / trickplayInfo.tileWidth

    val seekTimeSec = (scrubFraction * runtimeTicks / 10_000_000).toLong()
    val minutes = seekTimeSec / 60
    val seconds = seekTimeSec % 60
    val timeText = "%02d:%02d".format(minutes, seconds)

    var tileBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Cache sprite sheets to avoid re-downloading when scrubbing within the same sheet
    var cachedSpriteIndex by remember { mutableStateOf(-1) }
    var cachedSpriteBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(spriteIndex, tileInSprite) {
        withContext(Dispatchers.IO) {
            try {
                val spriteBitmap = if (spriteIndex == cachedSpriteIndex && cachedSpriteBitmap != null) {
                    cachedSpriteBitmap!!
                } else {
                    val data = repository.getTrickplayData(itemId, trickplayInfo.width, spriteIndex)
                        ?: return@withContext
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                        ?: return@withContext
                    cachedSpriteBitmap = bitmap
                    cachedSpriteIndex = spriteIndex
                    bitmap
                }

                val tileW = trickplayInfo.width
                val tileH = trickplayInfo.height
                val x = col * tileW
                val y = row * tileH

                if (x + tileW <= spriteBitmap.width && y + tileH <= spriteBitmap.height) {
                    tileBitmap = Bitmap.createBitmap(spriteBitmap, x, y, tileW, tileH)
                }
            } catch (_: Exception) {}
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
