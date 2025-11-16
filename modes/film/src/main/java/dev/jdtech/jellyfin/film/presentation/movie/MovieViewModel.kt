package dev.jdtech.jellyfin.film.presentation.movie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MovieViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val videoMetadataParser: VideoMetadataParser,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(MovieState())
    val state = _state.asStateFlow()

    lateinit var movieId: UUID

    fun loadMovie(movieId: UUID) {
        this.movieId = movieId
        viewModelScope.launch {
            try {
                val movie = repository.getMovie(movieId)
                val videoMetadata = videoMetadataParser.parse(movie.sources.first())
                val actors = getActors(movie)
                val director = getDirector(movie)
                val writers = getWriters(movie)
                _state.emit(_state.value.copy(movie = movie, videoMetadata = videoMetadata, actors = actors, director = director, writers = writers))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private suspend fun getActors(item: FindroidMovie): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    private suspend fun getDirector(item: FindroidMovie): FindroidItemPerson? {
        return withContext(Dispatchers.Default) {
            item.people.firstOrNull { it.type == PersonKind.DIRECTOR }
        }
    }

    private suspend fun getWriters(item: FindroidMovie): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.WRITER }
        }
    }

    fun onAction(action: MovieAction) {
        when (action) {
            is MovieAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.Download -> {
                viewModelScope.launch {
                    try {
                        val movie = state.value.movie
                        if (movie != null) {
                            // 检查是否有可用的媒体源
                            if (movie.sources.isNotEmpty()) {
                                // 获取第一个可用的媒体源
                                val source = movie.sources.first()
                                val (downloadId, error) = downloader.downloadItem(movie, source.id)
                                if (error != null) {
                                    // 处理下载错误，可以更新UI状态显示错误信息
                                    _state.emit(_state.value.copy(error = Exception(error.toString())))
                                } else {
                                    // 下载开始成功，可以更新UI状态显示下载进度
                                    _state.emit(_state.value.copy(downloadInProgress = true, downloadId = downloadId, downloadProgress = 0))
                                    // 开始监控下载进度
                                    monitorDownloadProgress(downloadId)
                                    // 触发导航到下载页面
                                    action.onNavigateToDownloads?.invoke()
                                }
                            } else {
                                // 没有可用的媒体源，显示错误信息
                                _state.emit(_state.value.copy(error = Exception("没有可用的媒体源")))
                            }
                        }
                    } catch (e: Exception) {
                        _state.emit(_state.value.copy(error = e))
                    }
                }
            }
            else -> Unit
        }
    }

    private fun monitorDownloadProgress(downloadId: Long) {
        viewModelScope.launch {
            while (state.value.downloadInProgress) {
                val (status, progress) = downloader.getProgress(downloadId)
                when (status) {
                    android.app.DownloadManager.STATUS_RUNNING -> {
                        // 更新下载进度
                        _state.emit(_state.value.copy(downloadProgress = progress))
                    }
                    android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                        // 下载完成
                        _state.emit(_state.value.copy(
                            downloadInProgress = false,
                            downloadId = null,
                            downloadProgress = 100
                        ))
                        break
                    }
                    android.app.DownloadManager.STATUS_FAILED -> {
                        // 下载失败
                        _state.emit(_state.value.copy(
                            downloadInProgress = false,
                            downloadId = null,
                            error = Exception("Download failed")
                        ))
                        break
                    }
                    else -> {
                        // 其他状态，继续监控
                    }
                }
                // 每1秒检查一次进度
                kotlinx.coroutines.delay(1000)
            }
        }
    }
}
