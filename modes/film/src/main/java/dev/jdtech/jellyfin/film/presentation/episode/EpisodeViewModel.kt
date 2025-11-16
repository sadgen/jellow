package dev.jdtech.jellyfin.film.presentation.episode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItemPerson
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
class EpisodeViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val videoMetadataParser: VideoMetadataParser,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(EpisodeState())
    val state = _state.asStateFlow()

    lateinit var episodeId: UUID

    fun loadEpisode(episodeId: UUID) {
        this.episodeId = episodeId
        viewModelScope.launch {
            try {
                val episode = repository.getEpisode(episodeId)
                val videoMetadata = videoMetadataParser.parse(episode.sources.first())
                val actors = getActors(episode)
                _state.emit(_state.value.copy(episode = episode, videoMetadata = videoMetadata, actors = actors))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private suspend fun getActors(item: FindroidEpisode): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    fun onAction(action: EpisodeAction) {
        when (action) {
            is EpisodeAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.Download -> {
                viewModelScope.launch {
                    try {
                        val episode = state.value.episode
                        if (episode != null) {
                            val source = episode.sources.firstOrNull()
                            if (source != null) {
                                val (downloadId, error) = downloader.downloadItem(episode, source.id)
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
