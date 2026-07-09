package dev.jdtech.jellyfin.presentation.film.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.repository.JellyfinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TrickplayPreviewDialog(
    item: FindroidItem,
    repository: JellyfinRepository?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var trickplayInfo by remember { mutableStateOf<dev.jdtech.jellyfin.models.FindroidTrickplayInfo?>(null) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var totalImages by remember { mutableIntStateOf(0) }
    var isFullRow by remember { mutableStateOf(false) }
    
    // 加载 trickplay 信息
    LaunchedEffect(item.id) {
        if (repository != null) {
            try {
                val info = repository.getTrickplayInfoForItem(item.id)
                trickplayInfo = info
                totalImages = info?.thumbnailCount ?: 0
                // 加载第一张
                if (info != null && totalImages > 0) {
                    val data = repository.getTrickplayData(item.id, info.width, 0)
                    if (data != null) {
                        withContext(Dispatchers.IO) {
                            currentBitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }
    
    // 如果没 trickplay 数据，显示简单的信息弹窗
    if (totalImages == 0) {
        Card(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .aspectRatio(0.66f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = item.images.primary,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(MaterialTheme.spacings.small),
                    )
                }
            }
        }
        return
    }
    
    // 有 trickplay 数据则显示小窗+滑动
    Card(
        onClick = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .aspectRatio(1.2f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                isFullRow = false
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                if (!isFullRow) {
                                    val threshold = 30f
                                    if (dragAmount > threshold) {
                                        currentIndex = (currentIndex + 1).coerceAtMost(totalImages - 1)
                                        isFullRow = true
                                        scope.launch {
                                            loadTrickplayFrame(repository, item, trickplayInfo, currentIndex) { bitmap ->
                                                currentBitmap = bitmap
                                            }
                                        }
                                    } else if (dragAmount < -threshold) {
                                        currentIndex = (currentIndex - 1).coerceAtLeast(0)
                                        isFullRow = true
                                        scope.launch {
                                            loadTrickplayFrame(repository, item, trickplayInfo, currentIndex) { bitmap ->
                                                currentBitmap = bitmap
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (currentBitmap != null) {
                    androidx.compose.foundation.Image(
                        painter = BitmapPainter(currentBitmap!!.asImageBitmap()),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(text = "正在加载预览...", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacings.small))
            Text(
                text = "${currentIndex + 1} / $totalImages",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = MaterialTheme.spacings.small),
            )
        }
    }
}

private suspend fun loadTrickplayFrame(
    repository: JellyfinRepository?,
    item: FindroidItem,
    info: dev.jdtech.jellyfin.models.FindroidTrickplayInfo?,
    index: Int,
    onResult: (Bitmap?) -> Unit,
) {
    if (repository == null || info == null) return
    try {
        val data = repository.getTrickplayData(item.id, info.width, index)
        if (data != null) {
            withContext(Dispatchers.IO) {
                onResult(BitmapFactory.decodeByteArray(data, 0, data.size))
            }
        }
    } catch (_: Exception) { }
}
