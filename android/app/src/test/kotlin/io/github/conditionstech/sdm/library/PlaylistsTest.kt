package io.github.conditionstech.sdm.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistsTest {
    /** The SQLite store's behaviour, as a map. */
    private class MemoryPlaylists : PlaylistStore {
        val rows = linkedMapOf<Long, Playlist>()
        private var next = 1L
        override fun load() = rows.values.toList()
        override fun insert(name: String): Long = next++.also { rows[it] = Playlist(it, name) }
        override fun rename(id: Long, name: String) { rows[id] = rows.getValue(id).copy(name = name) }
        override fun delete(id: Long) { rows.remove(id) }
        override fun setSongs(id: Long, songIds: List<Long>) { rows[id] = rows.getValue(id).copy(songIds = songIds) }
    }

    private val store = MemoryPlaylists()
    private val playlists = Playlists(store)

    private fun song(id: Long) = MediaEntry(id, "content://media/$id", MediaKind.AUDIO, "Song $id")

    @Test
    fun createRenameDeleteAndKeepThemSorted() {
        val road = playlists.create("  Road trip ")
        playlists.create("chill")
        assertEquals(listOf("chill", "Road trip"), playlists.all.value.map { it.name })
        playlists.rename(road, "Akash")
        assertEquals(listOf("Akash", "chill"), playlists.all.value.map { it.name })
        assertEquals("Akash", store.rows[road]!!.name)
        playlists.delete(road)
        assertEquals(listOf("chill"), playlists.all.value.map { it.name })
        assertTrue(road !in store.rows)
    }

    @Test
    fun namesAreChecked() {
        playlists.create("Chill")
        assertThrows(PlaylistException::class.java) { playlists.create("   ") }
        assertThrows(PlaylistException::class.java) { playlists.create("chill") } // same, ignoring case
        assertThrows(PlaylistException::class.java) { playlists.create("x".repeat(61)) }
        assertEquals("ab", store.rows[playlists.create("a\u0000b")]!!.name) // control characters dropped
        val id = playlists.all.value.first { it.name == "Chill" }.id
        playlists.rename(id, "CHILL") // renaming to itself in other letters is fine
    }

    @Test
    fun songsAreAddedOnceInOrderAndRemoved() {
        val id = playlists.create("Mix")
        assertEquals(2, playlists.add(id, listOf(3, 1)))
        assertEquals(1, playlists.add(id, listOf(1, 2, 2))) // 1 is there already; 2 only once
        assertEquals(listOf(3L, 1L, 2L), store.rows[id]!!.songIds)
        playlists.remove(id, 1)
        assertEquals(listOf(3L, 2L), playlists.all.value.single().songIds)
        assertEquals(0, playlists.add(999, listOf(1))) // no such playlist
    }

    @Test
    fun songsDeletedFromThePhoneDisappearFromThePlaylist() {
        val id = playlists.create("Mix")
        playlists.add(id, listOf(5, 4, 6))
        val library = listOf(song(4), song(6)) // song 5 was deleted from the phone
        assertEquals(listOf(4L, 6L), playlists.songs(playlists.all.value.single(), library).map { it.id })
    }

    @Test
    fun theyAreReadBackFromTheStore() {
        val id = playlists.create("Saved")
        playlists.add(id, listOf(7))
        val reopened = Playlists(store)
        assertEquals(listOf(Playlist(id, "Saved", listOf(7))), reopened.all.value)
    }
}
