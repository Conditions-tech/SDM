package io.github.conditionstech.sdm

import android.app.Application
import io.github.conditionstech.sdm.downloads.Downloads
import io.github.conditionstech.sdm.downloads.MediaStorePublisher
import io.github.conditionstech.sdm.downloads.SqliteHistory
import io.github.conditionstech.sdm.engine.DownloadSettings
import io.github.conditionstech.sdm.engine.HttpDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Creates the one download list the screens and the service share. */
class SdmApplication : Application() {
    lateinit var downloads: Downloads
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // Partial files live in the app's own storage; finished files are copied to Download/SDM.
        val work = File(getExternalFilesDir(null) ?: filesDir, "partial").apply { mkdirs() }
        downloads = Downloads(
            store = SqliteHistory(this),
            publisher = MediaStorePublisher(this),
            workDir = work,
            downloader = HttpDownloader(settings = DownloadSettings(userAgent = "SDM/${BuildConfig.VERSION_NAME} (Android)")),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onActive = { DownloadService.start(this) },
            onFinished = { Notifications.finished(this, it) },
        )
    }
}
