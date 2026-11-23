package io.github.conditionstech.sdm.downloads

import io.github.conditionstech.sdm.engine.PartialDownload
import io.github.conditionstech.sdm.media.VideoChoice
import java.io.File

/** Where a download stands. The same states as the Windows app's list. */
enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

/** One row of the download list, as kept in the history. */
data class DownloadItem(
    val id: Long = 0,
    val url: String,
    val fileName: String? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = null,
    val error: String? = null, // a message fit to show: from the engine or the saving step
    val savedName: String? = null, // the name in Download/SDM (Android may add " (1)")
    val savedUri: String? = null,
    val unconfirmedSize: Boolean = false, // the server never said how big the file is
    val partial: PartialDownload? = null, // what is needed to resume
    val createdAt: Long = 0,
    val videoChoice: VideoChoice? = null, // set for a video page (M15b): [url] is the page
    // Live only, never stored:
    val connections: Int = 0,
    val bytesPerSecond: Double? = null,
) {
    val active: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.DOWNLOADING
}

/** The history (SQLite on the phone, a list in tests). Called from background threads. */
interface HistoryStore {
    fun load(): List<DownloadItem>
    fun insert(item: DownloadItem): Long
    fun update(item: DownloadItem)
    fun delete(id: Long)
}

/** Copies a finished file to where the user finds it (Download/SDM on the phone). */
interface Publisher {
    class Published(val name: String, val uri: String)

    /** Never replaces an existing file; the name may gain a number. */
    fun publish(file: File, name: String, contentType: String?): Published
}
