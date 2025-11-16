package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.UiText

interface Downloader {
    suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int = 0,
    ): Pair<Long, UiText?>

    suspend fun pauseDownload(item: FindroidItem, source: FindroidSource)

    suspend fun resumeDownload(item: FindroidItem, source: FindroidSource): Pair<Long, UiText?>

    suspend fun cancelDownload(item: FindroidItem, source: FindroidSource)

    suspend fun deleteItem(item: FindroidItem, source: FindroidSource)

    suspend fun getProgress(downloadId: Long?): Pair<Int, Int>

    suspend fun getDownloadDetails(downloadId: Long?): Triple<Int, Int, Pair<Long, Long>>
}
