package io.github.conditionstech.sdm

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.github.conditionstech.sdm.downloads.Downloads
import io.github.conditionstech.sdm.downloads.MediaStorePublisher
import io.github.conditionstech.sdm.downloads.SqliteHistory
import io.github.conditionstech.sdm.engine.DownloadSettings
import io.github.conditionstech.sdm.engine.HttpDownloader
import io.github.conditionstech.sdm.library.MediaStoreLibrary
import io.github.conditionstech.sdm.player.MusicPlayer
import io.github.conditionstech.sdm.player.SleepTimer
import io.github.conditionstech.sdm.media.AndroidMediaConverter
import io.github.conditionstech.sdm.media.AndroidVideoTools
import io.github.conditionstech.sdm.media.Conversions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Creates the one download list the screens and the service share. */
class SdmApplication : Application() {
    lateinit var downloads: Downloads
        private set
    lateinit var conversions: Conversions
        private set
    lateinit var preferences: Preferences
        private set
    lateinit var library: MediaStoreLibrary
        private set
    lateinit var musicPlayer: MusicPlayer
        private set

    /** Lives with the app, so it keeps counting while music plays with the screen off (M17b). */
    val sleepTimer = SleepTimer(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    override fun onCreate() {
        super.onCreate()
        // Python (yt-dlp, LAME) starts once, with the app (ADR-0024).
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        Notifications.createChannels(this)
        preferences = Preferences(this)
        library = MediaStoreLibrary(this, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        musicPlayer = MusicPlayer(this)
        val mediaConverter = AndroidMediaConverter(this)
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
            videoTools = AndroidVideoTools(mediaConverter),
            av1InMp4 = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )
        conversions = Conversions(
            converter = mediaConverter,
            publisher = MediaStorePublisher(this),
            workDir = work,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onActive = { DownloadService.start(this) },
            onFinished = { Notifications.converted(this, it) },
            log = { message, error -> android.util.Log.w("SDM", message, error) },
        )
    }
}
