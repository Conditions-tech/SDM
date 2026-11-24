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

    /** M18b: save a video page's English subtitles with the video (on by default). */
    var saveSubtitles by mutableStateOf(stored.getBoolean(SAVE_SUBTITLES, true))
        private set

    fun changeSaveSubtitles(enabled: Boolean) {
        saveSubtitles = enabled
        stored.edit().putBoolean(SAVE_SUBTITLES, enabled).apply()
    }

    /** M18c: the user's own OpenSubtitles.com API key (never logged or shown in full). */
    var openSubtitlesKey by mutableStateOf(stored.getString(OS_KEY, "").orEmpty())
        private set

    /** The sign-in token (optional, raises the download limit) and the account name it is for. */
    var openSubtitlesToken by mutableStateOf(stored.getString(OS_TOKEN, null))
        private set
    var openSubtitlesUser by mutableStateOf(stored.getString(OS_USER, null))
        private set

    fun changeOpenSubtitlesKey(key: String) {
        openSubtitlesKey = key.trim()
        stored.edit().putString(OS_KEY, openSubtitlesKey).apply()
    }

    fun setOpenSubtitlesLogin(user: String?, token: String?) {
        openSubtitlesUser = user
        openSubtitlesToken = token
        stored.edit().putString(OS_USER, user).putString(OS_TOKEN, token).apply()
    }

    private companion object {
        const val VIDEO_PAGES = "video_pages_enabled"
        const val SAVE_SUBTITLES = "save_subtitles"
        const val OS_KEY = "opensubtitles_key"
        const val OS_TOKEN = "opensubtitles_token"
        const val OS_USER = "opensubtitles_user"
    }
}
