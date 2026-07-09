package dev.jdtech.jellyfin.presentation.film.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
    var frames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // 加载 trickplay 信息并切片为独立帧
    LaunchedEffect(item.id) {
        if (repository != null) {
            try {
                val info = repository.getTrickplayInfoForItem(item.id)
                trickplayInfo = info
                if (info != null && info.thumbnailCount > 0) {
                    val maxIndex = kotlin.math.ceil(
                        info.thumbnailCount.toDouble()
                            .div(info.tileWidth * info.tileHeight)
                    ).toInt()
                    val frameList = mutableListOf<Bitmap>()

                    for (i in 0..maxIndex) {
                        try {
                            val data = repository.getTrickplayData(item.id, info.width, i)
                            if (data != null) {
                                withContext(Dispatchers.IO) {
                                    val fullBitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                                    if (fullBitmap != null) {
                                        for (offsetY in
                                            0..<info.height * info.tileHeight step info.height) {
                                            for (offsetX in
                                                0..<info.width * info.tileWidth step info.width) {
                                                val frame = Bitmap.createBitmap(
                                                    fullBitmap, offsetX, offsetY,
                                                    info.width, info.height,
                                                )
                                                frameList.add(frame)
                                                if (frameList.size >= info.thumbnailCount) break
                                            }
                                            if (frameList.size >= info.thumbnailCount) break
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                    frames = frameList
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
        if (frames.isNotEmpty() && trickplayInfo != null) {
            val info = trickplayInfo!!
            val pagerState = rememberPagerState(pageCount = { frames.size })

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 说明文字
                Text(
                    text = "← 左右滑动浏览帧 →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                )

                // 横向分页显示单个帧（像进度条预览一样）
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacings.small),
                ) { page ->
                    if (page < frames.size) {
                        val frame = frames[page]
                        Image(
                            painter = BitmapPainter(frame.asImageBitmap()),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(info.width.toFloat() / info.height.toFloat()),
                        )
                    }
                }

                // 页码指示
                val currentPage = pagerState.currentPage
                Text(
                    text = "帧 ${currentPage + 1} / ${frames.size}",
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
