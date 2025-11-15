package dev.jdtech.jellyfin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie

enum class Direction {
    HORIZONTAL,
    VERTICAL,
}

@Composable
fun ItemPoster(item: FindroidItem, direction: Direction, modifier: Modifier = Modifier) {
    var imageUri = item.images.primary

    when (direction) {
        Direction.HORIZONTAL -> {
            if (item is FindroidMovie) imageUri = item.images.backdrop
        }
        Direction.VERTICAL -> {
            when (item) {
                is FindroidEpisode -> imageUri = item.images.showPrimary
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(if (direction == Direction.HORIZONTAL) 1.77f else 0.66f)
            .background(
                MaterialTheme.colorScheme.surface,
            )
    ) {
        AsyncImage(
            model = imageUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 播放次数显示 - 左下角
        if (item.playCount >= 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.White.copy(alpha = 0.9f))
                    .padding(1.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(
                            if (item.playCount == 0) Color(0xFFFFA500).copy(alpha = 0.7f) 
                            else Color(0xFF333333).copy(alpha = 0.7f)
                        )
                ) {
                    Text(
                        text = if (item.playCount == 0) "—" else item.playCount.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}
