package dev.jdtech.jellyfin.presentation.film.viewmodels

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.Downloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class DownloadItem(
    val item: FindroidItem,
    val source: FindroidSource,
    val downloadId: Long,
    val progress: Int,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long
)

enum class DownloadStatus {
    DOWNLOADING,
    COMPLETED,
    FAILED,
    PAUSED
}

@HiltViewModel
class DownloadsScreenViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
    private val database: ServerDatabaseDao,
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(DownloadsScreenState())
    val uiState = _uiState.asStateFlow()
    
    data class DownloadsScreenState(
        val downloadItems: List<DownloadItem> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null
    )
    
    init {
        startMonitoringDownloads()
    }
    
    private fun startMonitoringDownloads() {
        viewModelScope.launch {
            try {
                while (true) {
                    updateDownloadProgress()
                    delay(1000) // 每秒更新一次进度
                }
            } catch (e: Exception) {
                // 重新启动监控循环
                startMonitoringDownloads()
            }
        }
    }
    
    private suspend fun updateDownloadProgress() {
        try {
            val activeDownloads = mutableListOf<DownloadItem>()
            
            // 获取所有有下载ID的源
            val sourcesWithDownloadId = getSourcesWithDownloadId()
            
            // 检查每个下载项的进度
            for (sourceDto in sourcesWithDownloadId) {
                try {
                    // 安全检查：确保下载ID不为null
                    val downloadId = sourceDto.downloadId
                    if (downloadId == null) {
                        continue
                    }
                    
                    // 获取对应的项目信息
                    val item = getItemForSource(sourceDto)
                    
                    if (item != null) {
                        // 安全地获取下载详细信息，处理可能的异常
                        val (status, progress, bytesInfo) = try {
                            downloader.getDownloadDetails(downloadId)
                        } catch (e: Exception) {
                            // 如果获取进度失败，默认为失败状态
                            Triple(DownloadManager.STATUS_FAILED, 0, Pair(0L, 0L))
                        }
                        
                        val (downloadedBytes, totalBytes) = bytesInfo
                        
                        val downloadStatus = when (status) {
                            DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                            DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                            DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                            DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                            else -> DownloadStatus.DOWNLOADING
                        }
                        
                        // 创建 FindroidSource 对象，确保路径不为null
                        val sourcePath = sourceDto.path ?: ""
                        val source = FindroidSource(
                            id = sourceDto.id,
                            name = sourceDto.name ?: "",
                            type = sourceDto.type,
                            path = sourcePath,
                            size = try { 
                                if (sourcePath.isNotEmpty()) {
                                    java.io.File(sourcePath).length() 
                                } else {
                                    0L 
                                }
                            } catch (e: Exception) { 0L },
                            mediaStreams = emptyList(),
                            downloadId = downloadId
                        )
                        
                        // 显示所有下载项，包括正在下载和已完成的
                        activeDownloads.add(DownloadItem(item, source, downloadId, progress, downloadStatus, downloadedBytes, totalBytes))
                    }
                } catch (e: Exception) {
                    // 跳过有问题的下载项
                    continue
                }
            }
            
            _uiState.emit(_uiState.value.copy(downloadItems = activeDownloads))
        } catch (e: Exception) {
            _uiState.emit(_uiState.value.copy(error = "更新下载进度时出错: ${e.message}"))
        }
    }
    
    /**
     * 获取所有有下载ID的源
     */
    private suspend fun getSourcesWithDownloadId(): List<FindroidSourceDto> {
        return try {
            // 最简单的方法：获取所有源，然后过滤出有下载ID的
            val allSources = mutableListOf<FindroidSourceDto>()
            
            // 获取所有电影的源
            val movies = database.getMovies()
            for (movie in movies) {
                val movieSources = database.getSources(movie.id)
                allSources.addAll(movieSources)
            }
            
            // 获取所有剧集的源
            val episodes = database.getEpisodes()
            for (episode in episodes) {
                val episodeSources = database.getSources(episode.id)
                allSources.addAll(episodeSources)
            }
            
            // 过滤出有下载ID的源
            val sourcesWithDownloadId = allSources.filter { it.downloadId != null }
            
            sourcesWithDownloadId
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 根据源获取对应的项目信息
     */
    private suspend fun getItemForSource(sourceDto: FindroidSourceDto): FindroidItem? {
        return try {
            // 根据源的项目ID获取对应的项目
            val itemId = sourceDto.itemId
            
            // 先尝试获取剧集
            try {
                // 直接使用repository获取剧集
                return repository.getEpisode(itemId)
            } catch (e: Exception) {
                println("DEBUG: Error getting episode: ${e.message}")
                // 继续尝试获取电影
            }
            
            // 然后尝试获取电影
            try {
                // 直接使用repository获取电影
                return repository.getMovie(itemId)
            } catch (e: Exception) {
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    fun pauseDownload(downloadItem: DownloadItem) {
        viewModelScope.launch {
            try {
                // 暂停下载
                downloader.pauseDownload(downloadItem.item, downloadItem.source)
                
                // 更新UI状态为已暂停
                val currentState = _uiState.value
                val updatedItems = currentState.downloadItems.map { item ->
                    if (item.source.id == downloadItem.source.id) {
                        item.copy(status = DownloadStatus.PAUSED)
                    } else {
                        item
                    }
                }
                _uiState.emit(currentState.copy(downloadItems = updatedItems))
            } catch (e: Exception) {
                _uiState.emit(_uiState.value.copy(error = e.message))
            }
        }
    }

    fun resumeDownload(downloadItem: DownloadItem) {
        viewModelScope.launch {
            try {
                // 继续下载
                val (downloadId, error) = downloader.resumeDownload(downloadItem.item, downloadItem.source)
                if (error != null) {
                    _uiState.emit(_uiState.value.copy(error = error.toString()))
                } else {
                    // 更新UI状态为下载中
                    val currentState = _uiState.value
                    val updatedItems = currentState.downloadItems.map { item ->
                        if (item.source.id == downloadItem.source.id) {
                            item.copy(status = DownloadStatus.DOWNLOADING)
                        } else {
                            item
                        }
                    }
                    _uiState.emit(currentState.copy(downloadItems = updatedItems))
                }
            } catch (e: Exception) {
                _uiState.emit(_uiState.value.copy(error = e.message))
            }
        }
    }

    fun retryDownload(downloadItem: DownloadItem) {
        viewModelScope.launch {
            try {
                // 重新开始下载
                val (downloadId, error) = downloader.downloadItem(downloadItem.item, downloadItem.source.id)
                if (error != null) {
                    _uiState.emit(_uiState.value.copy(error = error.toString()))
                }
            } catch (e: Exception) {
                _uiState.emit(_uiState.value.copy(error = e.message))
            }
        }
    }
    
    fun cancelDownload(downloadItem: DownloadItem) {
        viewModelScope.launch {
            try {
                // 取消下载
                downloader.cancelDownload(downloadItem.item, downloadItem.source)
            } catch (e: Exception) {
                _uiState.emit(_uiState.value.copy(error = e.message))
            }
        }
    }
}