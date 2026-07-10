package dev.jdtech.jellyfin.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.toFindroidCollection
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidItem
import dev.jdtech.jellyfin.models.toFindroidMovie
import dev.jdtech.jellyfin.models.toFindroidPerson
import dev.jdtech.jellyfin.models.toFindroidSeason
import dev.jdtech.jellyfin.models.toFindroidSegment
import dev.jdtech.jellyfin.models.toFindroidShow
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.models.toFindroidTrickplayInfo
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceOptionsDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PlayAccess
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder as ItemSortOrder
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.TranscodingProfile
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.UserConfiguration
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.request.GetPersonsRequest
import timber.log.Timber
import kotlin.math.roundToInt

// Helper to get transcoding bitrate in bps, checking both new (string) and old (int) preferences
fun getTranscodingBitrateBps(prefs: AppPreferences): Int {
    // Try the new string preference first (from dropdown selector)
    val stringValue = prefs.getValue(prefs.playerTranscodingBitrateSelect)
    if (stringValue != null && stringValue.toFloatOrNull() != null) {
        return (stringValue.toFloat() * 1_000_000f).roundToInt()
    }
    // Fall back to the old int preference (from in-player dialog)
    return prefs.getValue(prefs.playerTranscodingBitrate) * 1_000_000
}

class JellyfinRepositoryImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : JellyfinRepository {
    override suspend fun getPublicSystemInfo(): PublicSystemInfo =
        withContext(Dispatchers.IO) { jellyfinApi.systemApi.getPublicSystemInfo().content }

    override suspend fun getUserViews(): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            jellyfinApi.viewsApi.getUserViews(jellyfinApi.userId!!).content.items
        }

    override suspend fun getEpisode(itemId: UUID): FindroidEpisode =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toFindroidEpisode(this@JellyfinRepositoryImpl, database)!!
        }

    override suspend fun getMovie(itemId: UUID, fields: List<ItemFields>?): FindroidMovie =
        withContext(Dispatchers.IO) {
            val dto = if (fields != null) {
                val items = jellyfinApi.itemsApi.getItems(
                    userId = jellyfinApi.userId!!,
                    ids = listOf(itemId),
                    fields = fields,
                    limit = 1,
                ).content.items
                items.firstOrNull()
                    ?: jellyfinApi.userLibraryApi.getItem(itemId, jellyfinApi.userId!!).content
            } else {
                jellyfinApi.userLibraryApi.getItem(itemId, jellyfinApi.userId!!).content
            }
            dto.toFindroidMovie(this@JellyfinRepositoryImpl, database)
        }

    override suspend fun getShow(itemId: UUID): FindroidShow =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toFindroidShow(this@JellyfinRepositoryImpl)
        }

    override suspend fun getSeason(itemId: UUID): FindroidSeason =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toFindroidSeason(this@JellyfinRepositoryImpl)
        }

    override suspend fun getLibraries(): List<FindroidCollection> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi.getItems(jellyfinApi.userId!!).content.items.mapNotNull {
                it.toFindroidCollection(this@JellyfinRepositoryImpl)
            }
        }

    override suspend fun getItem(itemId: UUID): FindroidItem? =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId = itemId, userId = jellyfinApi.userId!!)
                .content
                .toFindroidItem(this@JellyfinRepositoryImpl)
        }

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int?,
        limit: Int?,
        searchTerm: String?,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    includeItemTypes = includeTypes,
                    recursive = recursive,
                    searchTerm = searchTerm,
                    sortBy = listOf(ItemSortBy.fromName(sortBy.sortString)),
                    sortOrder = listOf(ItemSortOrder.fromName(sortOrder.sortString)),
                    startIndex = startIndex,
                    limit = limit,
                )
                .content
                .items
                .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getAllItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            val allItems = mutableListOf<FindroidItem>()
            var startIndex = 0
            val limit = 300 // Fetch in chunks

            while (true) {
                val items =
                    jellyfinApi.itemsApi
                        .getItems(
                            jellyfinApi.userId!!,
                            parentId = parentId,
                            includeItemTypes = includeTypes,
                            recursive = recursive,
                            startIndex = startIndex,
                            limit = limit,
                        )
                        .content
                        .items

                if (items.isEmpty()) break

                allItems.addAll(
                    items.mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
                )

                if (items.size < limit) break
                startIndex += limit
            }
            allItems
        }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        searchTerm: String?,
    ): Flow<PagingData<FindroidItem>> {
        return Pager(
                config = PagingConfig(pageSize = 10, enablePlaceholders = false),
                pagingSourceFactory = {
                    ItemsPagingSource(this, parentId, includeTypes, recursive, sortBy, sortOrder, searchTerm)
                },
            )
            .flow
    }

    override suspend fun getPerson(personId: UUID): FindroidPerson =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(personId, jellyfinApi.userId!!)
                .content
                .toFindroidPerson(this@JellyfinRepositoryImpl)
        }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    personIds = personIds,
                    includeItemTypes = includeTypes,
                    recursive = recursive,
                )
                .content
                .items
                .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getFavoriteItems(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    filters = listOf(ItemFilter.IS_FAVORITE),
                    includeItemTypes =
                        listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                    recursive = true,
                )
                .content
                .items
                .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getSearchItems(query: String): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    searchTerm = query,
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    recursive = true,
                )
                .content
                .items
                .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getSuggestions(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.suggestionsApi
                .getSuggestions(
                    jellyfinApi.userId!!,
                    limit = 6,
                    type = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                )
                .content
                .items
                .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getResumeItems(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getResumeItems(
                    jellyfinApi.userId!!,
                    limit = 12,
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
                )
                .content
                .items
                .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getLatestMedia(parentId: UUID): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getLatestMedia(jellyfinApi.userId!!, parentId = parentId, limit = 20)
                .content
                .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getPersons(limit: Int): List<FindroidPerson> =
        withContext(Dispatchers.IO) {
            val request = GetPersonsRequest(
                limit = limit,
                fields = listOf(ItemFields.ITEM_COUNTS, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
                enableImages = true,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
                userId = jellyfinApi.userId,
            )
            jellyfinApi.personsApi
                .getPersons(request)
                .content
                .items
                .mapNotNull { it.toFindroidPerson(this@JellyfinRepositoryImpl) }
        }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                jellyfinApi.showsApi.getSeasons(seriesId, jellyfinApi.userId!!).content.items.map {
                    it.toFindroidSeason(this@JellyfinRepositoryImpl)
                }
            } else {
                database.getSeasonsByShowId(seriesId).map {
                    it.toFindroidSeason(database, jellyfinApi.userId!!)
                }
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> =
        withContext(Dispatchers.IO) {
            jellyfinApi.showsApi
                .getNextUp(
                    jellyfinApi.userId!!,
                    limit = 24,
                    seriesId = seriesId,
                    enableResumable = false,
                )
                .content
                .items
                .mapNotNull { it.toFindroidEpisode(this@JellyfinRepositoryImpl) }
        }

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<FindroidEpisode> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                jellyfinApi.showsApi
                    .getEpisodes(
                        seriesId,
                        jellyfinApi.userId!!,
                        seasonId = seasonId,
                        fields = fields,
                        startItemId = startItemId,
                        limit = limit,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidEpisode(this@JellyfinRepositoryImpl, database) }
            } else {
                database.getEpisodesBySeasonId(seasonId).map {
                    it.toFindroidEpisode(database, jellyfinApi.userId!!)
                }
            }
        }

    override suspend fun getMediaSources(itemId: UUID, includePath: Boolean, forceTranscode: Boolean): List<FindroidSource> =
        withContext(Dispatchers.IO) {
            val sources = mutableListOf<FindroidSource>()
            sources.addAll(
                jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(
                        itemId,
                        PlaybackInfoDto(
                            userId = jellyfinApi.userId!!,
                            deviceProfile =
                                if (forceTranscode || appPreferences.getValue(appPreferences.playerTranscoding)) {
                                    val bitrate = getTranscodingBitrateBps(appPreferences)
                                    DeviceProfile(
                                        name = "Android Transcode",
                                        maxStaticBitrate = 0,
                                        maxStreamingBitrate = bitrate,
                                        codecProfiles = emptyList(),
                                        containerProfiles = emptyList(),
                                        directPlayProfiles = emptyList(),
                                        transcodingProfiles =
                                            listOf(
                                                TranscodingProfile(
                                                    container = "ts",
                                                    type = DlnaProfileType.VIDEO,
                                                    audioCodec = "aac,mp3,opus,flac,vorbis",
                                                    videoCodec = "h264,hevc,vp9",
                                                    protocol = MediaStreamProtocol.HLS,
                                                    context = EncodingContext.STREAMING,
                                                    conditions = emptyList(),
                                                ),
                                                TranscodingProfile(
                                                    container = "mp4",
                                                    type = DlnaProfileType.VIDEO,
                                                    audioCodec = "aac,mp3,opus,flac,vorbis",
                                                    videoCodec = "h264,hevc,vp9",
                                                    protocol = MediaStreamProtocol.HTTP,
                                                    context = EncodingContext.STATIC,
                                                    conditions = emptyList(),
                                                ),
                                            ),
                                        subtitleProfiles =
                                            listOf(
                                                SubtitleProfile(
                                                    "srt",
                                                    SubtitleDeliveryMethod.EXTERNAL
                                                ),
                                                SubtitleProfile(
                                                    "ass",
                                                    SubtitleDeliveryMethod.EXTERNAL
                                                ),
                                            ),
                                    )
                                } else {
                                    DeviceProfile(
                                        name = "Direct play all",
                                        maxStaticBitrate = 1_000_000_000,
                                        maxStreamingBitrate = 1_000_000_000,
                                        codecProfiles = emptyList(),
                                        containerProfiles = emptyList(),
                                        directPlayProfiles = emptyList(),
                                        transcodingProfiles = emptyList(),
                                        subtitleProfiles =
                                            listOf(
                                                SubtitleProfile(
                                                    "srt",
                                                    SubtitleDeliveryMethod.EXTERNAL
                                                ),
                                                SubtitleProfile(
                                                    "ass",
                                                    SubtitleDeliveryMethod.EXTERNAL
                                                ),
                                            ),
                                    )
                                },
                            maxStreamingBitrate = if (forceTranscode || appPreferences.getValue(appPreferences.playerTranscoding)) {
                                getTranscodingBitrateBps(appPreferences)
                            } else {
                                1_000_000_000
                            },
                        ),
                    )
                    .content
                    .mediaSources
                    .map { it.toFindroidSource(this@JellyfinRepositoryImpl, itemId, includePath, forceTranscode) }
            )
            sources.addAll(database.getSources(itemId).map { it.toFindroidSource(database) })
            sources
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String, forceTranscode: Boolean): String =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.videosApi.getVideoStreamUrl(
                    itemId,
                    static = true,
                    mediaSourceId = mediaSourceId,
                )
            } catch (e: Exception) {
                Timber.e(e)
                ""
            }
        }

    override suspend fun getAdditionalParts(itemId: UUID): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            try {
                val videos =
                    try {
                        jellyfinApi.videosApi.getAdditionalPart(itemId, jellyfinApi.userId!!)
                            .content
                            .items
                    } catch (e: Exception) {
                        emptyList()
                    }

                val items =
                    try {
                        jellyfinApi.itemsApi.getItems(
                            userId = jellyfinApi.userId!!,
                            parentId = itemId
                        ).content.items
                    } catch (e: Exception) {
                        emptyList()
                    }

                val combined = (videos + items).distinctBy { it.id }
                Timber.d("GET_ADDITIONAL_PARTS: ID=$itemId. Found ${videos.size} videos, ${items.size} items.")
                combined.forEach { Timber.d("PART: ${it.name} (${it.id}) - Type: ${it.type}") }

                combined
                    .sortedWith(compareBy({ it.indexNumber }, { it.name }))
                    .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
            } catch (e: Exception) {
                Timber.e(e)
                emptyList()
            }
        }

    override suspend fun getSegments(itemId: UUID): List<FindroidSegment> =
        withContext(Dispatchers.IO) {
            val databaseSegments = database.getSegments(itemId).map { it.toFindroidSegment() }

            if (databaseSegments.isNotEmpty()) {
                return@withContext databaseSegments
            }

            try {
                val apiSegments =
                    jellyfinApi.mediaSegmentsApi.getItemSegments(itemId).content.items.map {
                        it.toFindroidSegment()
                    }

                return@withContext apiSegments
            } catch (e: Exception) {
                Timber.e(e)
                return@withContext emptyList()
            }
        }

    override suspend fun getTrickplayInfoForItem(itemId: UUID): FindroidTrickplayInfo? =
        withContext(Dispatchers.IO) {
            try {
                val item = jellyfinApi.userLibraryApi
                    .getItem(itemId = itemId, userId = jellyfinApi.userId!!)
                    .content
                val trickplay = item.trickplay ?: return@withContext null
                val resolutions = trickplay.values.firstOrNull() ?: return@withContext null
                val bestResolution = resolutions[resolutions.keys.max()]
                    ?: return@withContext null
                bestResolution.toFindroidTrickplayInfo()
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                try {
                    val sources = File(context.filesDir, "trickplay/$itemId").listFiles()
                    if (sources != null) {
                        return@withContext File(sources.first(), index.toString()).readBytes()
                    }
                } catch (_: Exception) {}

                return@withContext jellyfinApi.trickplayApi
                    .getTrickplayTileImage(itemId, width, index)
                    .content
            } catch (_: Exception) {
                return@withContext null
            }
        }

    override suspend fun postCapabilities() {
        Timber.d("Sending capabilities")
        withContext(Dispatchers.IO) {
            jellyfinApi.sessionApi.postCapabilities(
                playableMediaTypes = listOf(MediaType.VIDEO),
                supportedCommands =
                    listOf(
                        GeneralCommandType.VOLUME_UP,
                        GeneralCommandType.VOLUME_DOWN,
                        GeneralCommandType.TOGGLE_MUTE,
                        GeneralCommandType.SET_AUDIO_STREAM_INDEX,
                        GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
                        GeneralCommandType.MUTE,
                        GeneralCommandType.UNMUTE,
                        GeneralCommandType.SET_VOLUME,
                        GeneralCommandType.DISPLAY_MESSAGE,
                        GeneralCommandType.PLAY,
                        GeneralCommandType.PLAY_STATE,
                        GeneralCommandType.PLAY_NEXT,
                        GeneralCommandType.PLAY_MEDIA_SOURCE,
                    ),
                supportsMediaControl = true,
            )
        }
    }

    override suspend fun postPlaybackStart(itemId: UUID) {
        Timber.d("Sending start $itemId")
        withContext(Dispatchers.IO) {
            jellyfinApi.playStateApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = itemId,
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                )
            )
        }
    }

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) {
        Timber.d("Sending stop $itemId")
        withContext(Dispatchers.IO) {
            when {
                playedPercentage < 10 -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, false)
                }
                playedPercentage > 90 -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, true)
                }
                else -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
                    database.setPlayed(jellyfinApi.userId!!, itemId, false)
                }
            }
            try {
                jellyfinApi.playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(itemId = itemId, positionTicks = positionTicks, failed = false)
                )
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        Timber.d("Posting progress of $itemId, position: $positionTicks")
        withContext(Dispatchers.IO) {
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
            try {
                jellyfinApi.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = itemId,
                        canSeek = true,
                        isPaused = isPaused,
                        isMuted = false,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        positionTicks = positionTicks,
                    )
                )
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, true)
            try {
                jellyfinApi.userLibraryApi.markFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, false)
            try {
                jellyfinApi.userLibraryApi.unmarkFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, true)
            try {
                jellyfinApi.playStateApi.markPlayedItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, false)
            try {
                jellyfinApi.playStateApi.markUnplayedItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    // 添加删除项目的方法实现
    override suspend fun deleteItems(itemIds: List<UUID>): Boolean = withContext(Dispatchers.IO) {
        try {
            // 对每个项目分别调用删除方法
            for (itemId in itemIds) {
                // 使用HTTP DELETE请求直接删除项目
                val url = "${jellyfinApi.api.baseUrl}/Items/$itemId"
                val requestBuilder = Request.Builder()
                    .url(url)
                    .delete()
                
                // 添加认证头
                val token = jellyfinApi.api.accessToken
                if (token != null) {
                    requestBuilder.addHeader("Authorization", "MediaBrowser Token=\"$token\"")
                    requestBuilder.addHeader("X-Emby-Token", token)
                }
                
                // 添加用户ID到请求头
                jellyfinApi.userId?.let { userId ->
                    requestBuilder.addHeader("X-Emby-UserId", userId.toString())
                }
                
                // 发送请求
                val client = OkHttpClient()
                val request = requestBuilder.build()
                val response = client.newCall(request).execute()
                
                // 检查响应状态
                if (!response.isSuccessful) {
                    Timber.e("Failed to delete item: $itemId, status code: ${response.code}")
                    return@withContext false
                }
            }
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete items: $itemIds")
            return@withContext false
        }
    }

    override fun getBaseUrl() = jellyfinApi.api.baseUrl.orEmpty()

    override suspend fun updateDeviceName(name: String) {
        withContext(Dispatchers.IO) {
            jellyfinApi.jellyfin.deviceInfo?.id?.let { id ->
                jellyfinApi.devicesApi.updateDeviceOptions(
                    id,
                    DeviceOptionsDto(0, customName = name),
                )
            }
        }
    }

    override suspend fun getUserConfiguration(): UserConfiguration =
        withContext(Dispatchers.IO) { jellyfinApi.userApi.getCurrentUser().content.configuration!! }

    override suspend fun getDownloads(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<FindroidItem>()
            items.addAll(
                database
                    .getMoviesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toFindroidMovie(database, jellyfinApi.userId!!) }
            )
            items.addAll(
                database
                    .getShowsByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toFindroidShow(database, jellyfinApi.userId!!) }
            )
            items
        }

    override fun getUserId(): UUID {
        return jellyfinApi.userId!!
    }
}
