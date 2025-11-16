package dev.jdtech.jellyfin.film.presentation.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidShow
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
class ShowViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(ShowState())
    val state = _state.asStateFlow()

    lateinit var showId: UUID

    fun loadShow(showId: UUID) {
        this.showId = showId
        viewModelScope.launch {
            try {
                val show = repository.getShow(showId)
                val nextUp = getNextUp(showId)
                val seasons = repository.getSeasons(showId)
                val actors = getActors(show)
                val director = getDirector(show)
                val writers = getWriters(show)
                _state.emit(_state.value.copy(show = show, nextUp = nextUp, seasons = seasons, actors = actors, director = director, writers = writers))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private suspend fun getNextUp(showId: UUID): FindroidEpisode? {
        val nextUpItems = repository.getNextUp(showId)
        return nextUpItems.getOrNull(0)
    }

    private suspend fun getActors(item: FindroidShow): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    private suspend fun getDirector(item: FindroidShow): FindroidItemPerson? {
        return withContext(Dispatchers.Default) {
            item.people.firstOrNull { it.type == PersonKind.DIRECTOR }
        }
    }

    private suspend fun getWriters(item: FindroidShow): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.WRITER }
        }
    }

    fun onAction(action: ShowAction) {
        when (action) {
            is ShowAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.Download -> {
                viewModelScope.launch {
                    try {
                        val show = state.value.show
                        if (show != null) {
                            // 对于剧集，我们需要下载整个剧集的所有季和集
                            // 这里先实现下载剧集元数据，后续可以扩展为批量下载
                            val source = show.sources.firstOrNull()
                            if (source != null) {
                                val (downloadId, error) = downloader.downloadItem(show, source.id)
                                if (error != null) {
                                    _state.emit(_state.value.copy(error = Exception(error.toString())))
                                } else {
                                    _state.emit(_state.value.copy(downloadInProgress = true, downloadId = downloadId))
                                    // 开始监控下载进度
                                    monitorDownloadProgress(downloadId)
                                }
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
