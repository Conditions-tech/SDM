package io.github.conditionstech.sdm.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The phone's songs and videos from Android's media index (M17a, ADR-0025). With the media
 * permission it lists the whole phone; without it Android shows SDM only the files SDM saved,
 * so the same query works either way. The lists refresh when media is added or removed.
 */
class MediaStoreLibrary(private val context: Context, private val scope: CoroutineScope) {
    private val audio = MutableStateFlow<List<MediaEntry>>(emptyList())
    private val video = MutableStateFlow<List<MediaEntry>>(emptyList())
    val songs: StateFlow<List<MediaEntry>> = audio.asStateFlow()
    val videos: StateFlow<List<MediaEntry>> = video.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    init {
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        refresh()
    }

    /** The permission that lets the players see every song and video, by Android version. */
    val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    val hasFullAccess: Boolean
        get() = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            audio.value = runCatching { query(MediaKind.AUDIO) }.getOrDefault(emptyList())
            video.value = runCatching { query(MediaKind.VIDEO) }.getOrDefault(emptyList())
        }
    }

    private fun query(kind: MediaKind): List<MediaEntry> {
        val collection: Uri = if (kind == MediaKind.AUDIO) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val columns = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.TITLE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (kind == MediaKind.AUDIO) {
                add(MediaStore.Audio.Media.ARTIST)
                add(MediaStore.Audio.Media.ALBUM)
            } else {
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
            }
        }.toTypedArray()
        // Songs only (not ringtones or voice notes); hidden, pending and trashed files are skipped.
        val selection = if (kind == MediaKind.AUDIO) "${MediaStore.Audio.Media.IS_MUSIC} != 0" else null
        val result = mutableListOf<MediaEntry>()
        context.contentResolver.query(collection, columns, selection, null, null)?.use { c ->
            fun text(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
            fun long(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) } ?: 0
            while (c.moveToNext()) {
                val id = long(MediaStore.MediaColumns._ID)
                val title = text(MediaStore.MediaColumns.TITLE)?.takeIf { it.isNotBlank() }
                    ?: text(MediaStore.MediaColumns.DISPLAY_NAME) ?: "Untitled"
                result += MediaEntry(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    kind = kind,
                    title = title,
                    artist = if (kind == MediaKind.AUDIO) text(MediaStore.Audio.Media.ARTIST) else null,
                    album = if (kind == MediaKind.AUDIO) text(MediaStore.Audio.Media.ALBUM) else null,
                    durationMs = long(MediaStore.MediaColumns.DURATION),
                    sizeBytes = long(MediaStore.MediaColumns.SIZE),
                    dateAddedSeconds = long(MediaStore.MediaColumns.DATE_ADDED),
                    folder = text(MediaStore.MediaColumns.RELATIVE_PATH).orEmpty(),
                    width = long(MediaStore.MediaColumns.WIDTH).toInt(),
                    height = long(MediaStore.MediaColumns.HEIGHT).toInt(),
                )
            }
        }
        return result
    }
}
