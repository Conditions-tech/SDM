package io.github.conditionstech.sdm.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A playlist: a name and songs in order, kept as the phone's media IDs (never file copies). */
data class Playlist(val id: Long, val name: String, val songIds: List<Long> = emptyList())

/** Where playlists are kept (SQLite on the phone, a map in tests). */
interface PlaylistStore {
    fun load(): List<Playlist>
    fun insert(name: String): Long
    fun rename(id: Long, name: String)
    fun delete(id: Long)
    fun setSongs(id: Long, songIds: List<Long>)
}

/** A message for the user about a playlist name. */
class PlaylistException(message: String) : Exception(message)

/**
 * Playlists (M17b): create, rename, delete, add and remove songs. Plain Kotlin, tested on the
 * PC. A song deleted from the phone simply disappears from its playlists when they are shown.
 */
class Playlists(private val store: PlaylistStore) {
    private val state = MutableStateFlow(store.load().sortedBy { it.name.lowercase() })
    val all: StateFlow<List<Playlist>> = state.asStateFlow()

    fun create(name: String): Long {
        val clean = checkName(name, except = null)
        val id = store.insert(clean)
        publish(state.value + Playlist(id, clean))
        return id
    }

    fun rename(id: Long, name: String) {
        val clean = checkName(name, except = id)
        store.rename(id, clean)
        publish(state.value.map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun delete(id: Long) {
        store.delete(id)
        publish(state.value.filterNot { it.id == id })
    }

    /** Adds songs at the end; songs already in the playlist are not added twice. Returns how many were added. */
    fun add(id: Long, songIds: List<Long>): Int {
        val playlist = find(id) ?: return 0
        val new = songIds.distinct().filterNot { it in playlist.songIds }
        if (new.isEmpty()) return 0
        update(playlist.copy(songIds = playlist.songIds + new))
        return new.size
    }

    fun remove(id: Long, songId: Long) {
        val playlist = find(id) ?: return
        update(playlist.copy(songIds = playlist.songIds - songId))
    }

    /** The playlist's songs that are still on the phone, in the playlist's order. */
    fun songs(playlist: Playlist, library: List<MediaEntry>): List<MediaEntry> {
        val byId = library.associateBy { it.id }
        return playlist.songIds.mapNotNull { byId[it] }
    }

    private fun find(id: Long) = state.value.firstOrNull { it.id == id }

    private fun update(playlist: Playlist) {
        store.setSongs(playlist.id, playlist.songIds)
        publish(state.value.map { if (it.id == playlist.id) playlist else it })
    }

    private fun publish(list: List<Playlist>) {
        state.value = list.sortedBy { it.name.lowercase() }
    }

    /** 1 to 60 characters, no control characters, not the same as another playlist (ignoring case). */
    private fun checkName(name: String, except: Long?): String {
        val clean = name.trim().filterNot { it.isISOControl() }
        if (clean.isEmpty()) throw PlaylistException("Give the playlist a name.")
        if (clean.length > MAX_NAME) throw PlaylistException("Use at most $MAX_NAME characters.")
        if (state.value.any { it.id != except && it.name.equals(clean, ignoreCase = true) }) {
            throw PlaylistException("There is already a playlist called “$clean”.")
        }
        return clean
    }

    companion object {
        const val MAX_NAME = 60
    }
}
