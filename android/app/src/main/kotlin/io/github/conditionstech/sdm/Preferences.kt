package io.github.conditionstech.sdm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** SDM's few settings, kept on the phone. */
class Preferences(context: Context) {
    private val stored = context.getSharedPreferences("sdm", Context.MODE_PRIVATE)

    /** Video pages with yt-dlp (M15b): off until the user turns them on and accepts the notice. */
    var videoPagesEnabled by mutableStateOf(stored.getBoolean(VIDEO_PAGES, false))
        private set

    fun setVideoPages(enabled: Boolean) {
        videoPagesEnabled = enabled
        stored.edit().putBoolean(VIDEO_PAGES, enabled).apply()
    }

    private companion object {
        const val VIDEO_PAGES = "video_pages_enabled"
    }
}
