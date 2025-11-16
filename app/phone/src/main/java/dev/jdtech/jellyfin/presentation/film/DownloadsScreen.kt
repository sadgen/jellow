package dev.jdtech.jellyfin.presentation.film

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.presentation.film.viewmodels.DownloadsScreenViewModel
import dev.jdtech.jellyfin.presentation.film.viewmodels.DownloadItem
import dev.jdtech.jellyfin.presentation.film.viewmodels.DownloadStatus
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.core.R as CoreR
import org.jellyfin.sdk.model.api.BaseItemKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    navigateBack: () -> Unit,
    viewModel: DownloadsScreenViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.title_download)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "加载中...",
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
            
            uiState.downloadItems.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "没有下载项",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    items(uiState.downloadItems) { downloadItem ->
                        // 使用安全的下载项渲染函数
                        SafeDownloadItemCard(
                            downloadItem = downloadItem,
                            context = context,
                            onPauseClick = { viewModel.pauseDownload(downloadItem) },
                            onResumeClick = { viewModel.resumeDownload(downloadItem) },
                            onRetryClick = { viewModel.retryDownload(downloadItem) },
                            onCancelClick = { viewModel.cancelDownload(downloadItem) }
                        )
                    }
                }
            }
        }
        
        uiState.error?.let { error ->
            // 显示错误信息
            ErrorMessage(error = error)
        }
    }
}

@Composable
fun DownloadItemCard(
    downloadItem: DownloadItem,
    context: Context,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onRetryClick: () -> Unit,
    onCancelClick: () -> Unit,
    onPlayClick: () -> Unit,
    onOpenFileClick: () -> Unit
) {
    // 获取海报图片URL
    val posterUrl = downloadItem.item.images.primary?.toString() ?: ""
    
    ListItem(
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                // 显示海报图片
                if (posterUrl.isNotEmpty()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = "海报",
                        modifier = Modifier
                            .size(60.dp)
                            .padding(end = 12.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Text(
                    text = downloadItem.item.name,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        supportingContent = {
            Column {
                when (downloadItem.status) {
                    DownloadStatus.DOWNLOADING -> {
                        // 下载进度条
                        LinearProgressIndicator(
                            progress = { downloadItem.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 显示进度百分比和文件大小信息
                            Text(
                                text = "${downloadItem.progress}% (${formatFileSize(downloadItem.downloadedBytes)}/${formatFileSize(downloadItem.totalBytes)})",
                                fontWeight = FontWeight.Medium
                            )
                            
                            Text(
                                text = "下载中"
                            )
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        // 已下载的文件显示完成状态
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "已下载",
                                fontWeight = FontWeight.Medium,
                                color = Color.Green
                            )
                            
                            Text(
                                text = "已完成"
                            )
                        }
                    }
                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${downloadItem.progress}% (${formatFileSize(downloadItem.downloadedBytes)}/${formatFileSize(downloadItem.totalBytes)})",
                                fontWeight = FontWeight.Medium
                            )
                            
                            Text(
                                text = when (downloadItem.status) {
                                    DownloadStatus.FAILED -> "失败"
                                    DownloadStatus.PAUSED -> "已暂停"
                                    else -> "未知"
                                }
                            )
                        }
                    }
                }
            }
        },
        trailingContent = {
            Row {
                when (downloadItem.status) {
                    DownloadStatus.FAILED -> {
                        IconButton(onClick = onRetryClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_play),
                                contentDescription = "重试"
                            )
                        }
                    }
                    DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = { 
                            // 暂停下载
                            onPauseClick() 
                        }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_pause),
                                contentDescription = "暂停"
                            )
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        IconButton(onClick = onResumeClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_play),
                                contentDescription = "继续"
                            )
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        // 已下载的文件显示播放、显示路径和打开文件按钮
                        IconButton(onClick = onPlayClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_play),
                                contentDescription = "播放"
                            )
                        }
                        IconButton(onClick = { 
                            // 复制文件路径
                            copyFilePath(context, downloadItem.source.path)
                        }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_info),
                                contentDescription = "显示文件路径"
                            )
                        }
                        IconButton(onClick = onOpenFileClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_library),
                                contentDescription = "打开文件位置"
                            )
                        }
                    }
                }
                
                // 显示删除按钮（对于已下载的文件）或取消按钮（对于正在下载的文件）
                IconButton(onClick = onCancelClick) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_cancel),
                        contentDescription = if (downloadItem.status == DownloadStatus.COMPLETED) "删除" else "取消"
                    )
                }
            }
        }
    )
}

@Composable
private fun ErrorMessage(error: String) {
    Text(
        text = "错误: $error",
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
fun SafeDownloadItemCard(
    downloadItem: DownloadItem,
    context: Context,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onRetryClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    // 安全地渲染下载项
    DownloadItemCard(
        downloadItem = downloadItem,
        context = context,
        onPauseClick = onPauseClick,
        onResumeClick = onResumeClick,
        onRetryClick = onRetryClick,
        onCancelClick = onCancelClick,
        onPlayClick = { 
            // 播放已下载的文件
            try {
                val intent = Intent(context, PlayerActivity::class.java)
                intent.putExtra("itemId", downloadItem.item.id.toString())
                intent.putExtra("itemKind", when (downloadItem.item) {
                    is dev.jdtech.jellyfin.models.FindroidMovie -> BaseItemKind.MOVIE.serialName
                    is dev.jdtech.jellyfin.models.FindroidEpisode -> BaseItemKind.EPISODE.serialName
                    is dev.jdtech.jellyfin.models.FindroidShow -> BaseItemKind.SERIES.serialName
                    else -> BaseItemKind.MOVIE.serialName
                })
                intent.putExtra("startFromBeginning", false)
                context.startActivity(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "播放失败: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        },
        onOpenFileClick = {
            // 打开文件位置
            try {
                openFileLocation(context, downloadItem.source.path)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "无法打开文件位置",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    )
}

@Composable
fun ErrorDownloadItemCard(
    errorMessage: String,
    onRetryClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = "下载项加载失败",
                fontWeight = FontWeight.Bold
            )
        },
        supportingContent = {
            Text(
                text = errorMessage,
                color = Color.Red
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRetryClick) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_play),
                        contentDescription = "重试"
                    )
                }
                IconButton(onClick = onCancelClick) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_cancel),
                        contentDescription = "取消"
                    )
                }
            }
        }
    )
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    
    return "%.1f %s".format(
        bytes / Math.pow(1024.0, digitGroups.toDouble()),
        units[digitGroups]
    )
}

/**
 * 复制文件路径到剪贴板
 */
private fun copyFilePath(context: Context, filePath: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("文件路径", filePath)
    clipboard.setPrimaryClip(clip)
    android.widget.Toast.makeText(
        context,
        "文件路径已复制到剪贴板",
        android.widget.Toast.LENGTH_SHORT
    ).show()
}

/**
 * 打开文件位置
 */
private fun openFileLocation(context: Context, filePath: String) {
    try {
        val file = java.io.File(filePath)
        val parentDir = file.parentFile
        
        if (parentDir != null && parentDir.exists()) {
            // 创建Intent来打开文件夹
            val intent = Intent(Intent.ACTION_VIEW)
            
            // 使用FileProvider获取URI
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        parentDir
                    )
                } catch (e: Exception) {
                    Uri.fromFile(parentDir)
                }
            } else {
                Uri.fromFile(parentDir)
            }
            
            // 设置正确的MIME类型和URI
            intent.setDataAndType(uri, "resource/folder")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // 创建选择器让用户选择文件管理器应用
            val chooserIntent = Intent.createChooser(
                intent,
                "选择文件管理器"
            )
            
            try {
                context.startActivity(chooserIntent)
            } catch (e: Exception) {
                // 如果选择器失败，尝试直接启动
                try {
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    // 如果所有方法都失败，提供最实用的解决方案
                    android.widget.Toast.makeText(
                        context,
                        "已复制路径，请在文件管理器中手动访问",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    copyFilePath(context, filePath)
                }
            }
        } else {
            android.widget.Toast.makeText(
                context,
                "文件路径不存在: $filePath",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "无法打开文件位置，已复制路径到剪贴板",
            android.widget.Toast.LENGTH_LONG
        ).show()
        copyFilePath(context, filePath)
    }
}

@Preview(showBackground = true)
@Composable
private fun DownloadsScreenPreview() {
    FindroidTheme {
        DownloadsScreen(
            navigateBack = {},
        )
    }
}