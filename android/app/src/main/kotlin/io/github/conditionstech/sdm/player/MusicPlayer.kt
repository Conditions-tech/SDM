package io.github.conditionstech.sdm.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.conditionstech.sdm.library.MediaEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the screens show about the music (M17b). */
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val index: Int = 0,
    val queue: List<String> = emptyList(), // titles, in play order of the list
) {
    val hasMedia: Boolean get() = queue.isNotEmpty()
}

/**
 * The screens' remote control for [PlaybackService]: it connects with a Media3 MediaController,
 * the same way the notification and the lock screen do, so all of them stay in step.
 */
class MusicPlayer(private val context: Context) {
    private val state = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = state.asStateFlow()
    private var controller: MediaController? = null
    private var pending: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    /** Connects to the service (starting it if needed); [then] runs once connected. */
    private fun connected(then: (MediaController) -> Unit) {
        controller?.let { return then(it) }
        pending = { controller?.let(then) }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            publish(connected)
            pending?.invoke()
            pending = null
        }, ContextCompat.getMainExecutor(context))
    }

    /** Plays [songs] in order, starting at [start]. */
    fun play(songs: List<MediaEntry>, start: MediaEntry) = connected { player ->
        val items = songs.map { song ->
            MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(Uri.parse(song.uri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artistOrUnknown)
                        .setAlbumTitle(song.albumOrUnknown)
                        .build(),
                )
                .build()
        }
        player.setMediaItems(items, songs.indexOf(start).coerceAtLeast(0), 0)
        player.prepare()
        player.play()
    }

    fun toggle() = connected { if (it.isPlaying) it.pause() else it.play() }
    fun next() = connected { it.seekToNextMediaItem() }
    fun previous() = connected { it.seekToPrevious() } // back to the start first, like most players
    fun seekTo(ms: Long) = connected { it.seekTo(ms) }
    fun jumpTo(index: Int) = connected { it.seekTo(index, 0) }
    fun toggleShuffle() = connected { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Off -> all -> one -> off. */
    fun cycleRepeat() = connected {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Where the song is now; the screens ask a few times a second while visible. */
    fun positionMs(): Long = controller?.currentPosition ?: 0

    /** Reconnects to music that is still playing (after the screen was closed). */
    fun reconnect() = connected { }

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        state.value = NowPlaying(
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            isPlaying = player.isPlaying,
            durationMs = player.duration.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            index = player.currentMediaItemIndex,
            queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.title?.toString().orEmpty() },
        )
    }
}
