package dev.jdtech.jellyfin.presentation.film.viewmodels

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidEpisodeDto
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovieDto
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidMovie
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
    val status: DownloadStatus
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
            while (true) {
                updateDownloadProgress()
                delay(1000) // 每秒更新一次进度
            }
        }
    }
    
    private suspend fun updateDownloadProgress() {
        try {
            val activeDownloads = mutableListOf<DownloadItem>()
            
            // 获取所有有下载ID的源
            val sourcesWithDownloadId = getSourcesWithDownloadId()
            
            // 调试日志：显示找到的源数量
            println("DEBUG: Found ${sourcesWithDownloadId.size} sources with download IDs")
            
            // 检查每个下载项的进度
            for (sourceDto in sourcesWithDownloadId) {
                try {
                    // 获取对应的项目信息
                    println("DEBUG: Processing source ${sourceDto.id}, itemId: ${sourceDto.itemId}")
                    val item = getItemForSource(sourceDto)
                    
                    if (item != null) {
                        println("DEBUG: Found item: ${item.name}, type: ${item::class.simpleName}")
                        
                        val (status, progress) = downloader.getProgress(sourceDto.downloadId)
                        println("DEBUG: Download status: $status, progress: $progress")
                        
                        val downloadStatus = when (status) {
                            DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                            DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                            DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                            DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                            else -> {
                                println("DEBUG: Unknown download status: $status, defaulting to DOWNLOADING")
                                DownloadStatus.DOWNLOADING
                            }
                        }
                        
                        // 调试日志：显示每个下载项的状态
                        println("DEBUG: Item ${item.name} - Status: $downloadStatus, Progress: $progress%")
                        
                        // 创建 FindroidSource 对象
                        val source = FindroidSource(
                            id = sourceDto.id,
                            name = sourceDto.name,
                            type = sourceDto.type,
                            path = sourceDto.path,
                            size = try { java.io.File(sourceDto.path).length() } catch (e: Exception) { 0L },
                            mediaStreams = emptyList(),
                            downloadId = sourceDto.downloadId
                        )
                        
                        // 显示所有下载项，包括正在下载和已完成的
                        activeDownloads.add(DownloadItem(item, source, sourceDto.downloadId!!, progress, downloadStatus))
                        println("DEBUG: Successfully added download item to list")
                    } else {
                        println("DEBUG: Could not find item for source ${sourceDto.id}")
                    }
                } catch (e: Exception) {
                    // 跳过有问题的下载项
                    println("DEBUG: Error processing source ${sourceDto.id}: ${e.message}")
                    e.printStackTrace()
                    continue
                }
            }
            
            _uiState.emit(_uiState.value.copy(downloadItems = activeDownloads))
        } catch (e: Exception) {
            _uiState.emit(_uiState.value.copy(error = e.message))
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
            
            // 调试日志：显示查询结果
            println("DEBUG: Total sources: ${allSources.size}, Sources with download ID: ${sourcesWithDownloadId.size}")
            for (source in sourcesWithDownloadId) {
                println("DEBUG: Source ${source.id} - Download ID: ${source.downloadId}, Path: ${source.path}")
            }
            
            sourcesWithDownloadId
        } catch (e: Exception) {
            println("DEBUG: Error in getSourcesWithDownloadId: ${e.message}")
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
            println("DEBUG: Looking for item with ID: $itemId")
            
            // 调试：检查数据库中是否有任何电影或剧集
            val allMovies = database.getMovies()
            val allEpisodes = database.getEpisodes()
            println("DEBUG: Total movies in database: ${allMovies.size}")
            println("DEBUG: Total episodes in database: ${allEpisodes.size}")
            
            // 检查是否有匹配的电影ID
            val matchingMovies = allMovies.filter { it.id == itemId }
            println("DEBUG: Matching movies found: ${matchingMovies.size}")
            
            // 检查是否有匹配的剧集ID
            val matchingEpisodes = allEpisodes.filter { it.id == itemId }
            println("DEBUG: Matching episodes found: ${matchingEpisodes.size}")
            
            // 先尝试获取剧集（因为调试显示有匹配的剧集）
            try {
                val episode = database.getEpisode(itemId)
                if (episode != null) {
                    println("DEBUG: Found episode: ${episode.name}")
                    return episode.toFindroidEpisode(database, repository.getUserId())
                }
            } catch (e: Exception) {
                println("DEBUG: Error getting episode: ${e.message}")
                // 继续尝试获取电影
            }
            
            // 然后尝试获取电影
            try {
                val movie = database.getMovie(itemId)
                if (movie != null) {
                    println("DEBUG: Found movie: ${movie.name}")
                    return movie.toFindroidMovie(database, repository.getUserId())
                }
            } catch (e: Exception) {
                println("DEBUG: Error getting movie: ${e.message}")
            }
            
            println("DEBUG: Could not find movie or episode for itemId: $itemId")
            println("DEBUG: Available movie IDs: ${allMovies.map { it.id }}")
            println("DEBUG: Available episode IDs: ${allEpisodes.map { it.id }}")
            null
        } catch (e: Exception) {
            println("DEBUG: Error in getItemForSource: ${e.message}")
            e.printStackTrace()
            null
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