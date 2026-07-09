package dev.jdtech.jellyfin.presentation.film.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.repository.JellyfinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TrickplayPreviewDialog(
    item: FindroidItem,
    repository: JellyfinRepository?,
    onDismiss: () -> Unit,
) {
    var trickplayInfo by remember { mutableStateOf<dev.jdtech.jellyfin.models.FindroidTrickplayInfo?>(null) }
    var tileBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var totalTiles by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    // 加载 trickplay 信息
    LaunchedEffect(item.id) {
        if (repository != null) {
            try {
                val info = repository.getTrickplayInfoForItem(item.id)
                trickplayInfo = info
                if (info != null && info.thumbnailCount > 0) {
                    val framesPerTile = info.tileWidth * info.tileHeight
                    val tileCount = (info.thumbnailCount + framesPerTile - 1) / framesPerTile
                    totalTiles = tileCount

                    // 加载所有 tile 图片
                    val bitmaps = mutableListOf<Bitmap>()
                    for (i in 0 until tileCount.coerceAtMost(10)) { // 最多加载10个tile
                        try {
                            val data = repository.getTrickplayData(item.id, info.width, i)
                            if (data != null) {
                                withContext(Dispatchers.IO) {
                                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                                    if (bmp != null) bitmaps.add(bmp)
                                }
                            }
                        } catch (_: Exception) { }
                    }
                    tileBitmaps = bitmaps
                }
            } catch (_: Exception) { }
        }
        loaded = true
    }

    if (!loaded) return

    Card(
        onClick = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.9f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        if (tileBitmaps.isNotEmpty() && trickplayInfo != null) {
            val info = trickplayInfo!!
            val framesPerTile = info.tileWidth * info.tileHeight
            val pagerState = rememberPagerState(pageCount = { tileBitmaps.size })

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 说明文字
                Text(
                    text = "← 左右滑动预览 →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                )

                // 横向分页显示每个 tile
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacings.small),
                ) { page ->
                    if (page < tileBitmaps.size) {
                        val bitmap = tileBitmaps[page]
                        val aspectW = info.tileWidth.toFloat()
                        val aspectH = info.tileHeight.toFloat()

                        Image(
                            painter = BitmapPainter(bitmap.asImageBitmap()),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspectW / aspectH),
                        )
                    }
                }

                // 页码指示
                val currentPage = pagerState.currentPage
                val startThumb = currentPage * framesPerTile + 1
                val endThumb = ((currentPage + 1) * framesPerTile).coerceAtMost(info.thumbnailCount)
                Text(
                    text = "$startThumb - $endThumb / ${info.thumbnailCount}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(
                        start = MaterialTheme.spacings.small,
                        end = MaterialTheme.spacings.small,
                        bottom = MaterialTheme.spacings.small,
                    ),
                )

                // 点按关闭
                Text(
                    text = "点击关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = MaterialTheme.spacings.small),
                )
            }
        } else {
            // 无 trickplay 时显示海报 + 标题
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = item.images.primary,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.66f),
                )
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(MaterialTheme.spacings.small),
                )
            }
        }
    }
}
