package io.github.conditionstech.sdm.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryTest {
    private fun song(id: Long, title: String, artist: String? = null, album: String? = null, ms: Long = 0, added: Long = 0, size: Long = 0, folder: String = "Music/") =
        MediaEntry(id, "content://media/$id", MediaKind.AUDIO, title, artist, album, ms, size, added, folder)

    private val songs = listOf(
        song(1, "Zebra", "Ayub Bachchu", "Ferari Mon", 200_000, added = 30, size = 5, folder = "Music/"),
        song(2, "apple", "Beyoncé", "Lemonade", 300_000, added = 10, size = 9, folder = "Download/SDM/"),
        song(3, "Éclair", "<unknown>", null, 100_000, added = 20, size = 1, folder = "Music/"),
        song(4, "Banana", "Ayub Bachchu", "Ferari Mon", 250_000, added = 30, size = 7, folder = ""),
    )

    @Test
    fun sortsByTitleIgnoringCaseAndAccents() {
        assertEquals(listOf("apple", "Banana", "Éclair", "Zebra"), MediaLibrary.sorted(songs, SortOrder.TITLE).map { it.title })
    }

    @Test
    fun otherOrders() {
        assertEquals(listOf(1L, 4L, 3L, 2L), MediaLibrary.sorted(songs, SortOrder.NEWEST).map { it.id }) // ties by id
        assertEquals(listOf(2L, 4L, 1L, 3L), MediaLibrary.sorted(songs, SortOrder.LONGEST).map { it.id })
        assertEquals(listOf(2L, 4L, 1L, 3L), MediaLibrary.sorted(songs, SortOrder.LARGEST).map { it.id })
    }

    @Test
    fun groupsPutUnknownLast() {
        val artists = MediaLibrary.artists(songs)
        assertEquals(listOf("Ayub Bachchu", "Beyoncé", "Unknown artist"), artists.map { it.name })
        assertEquals(listOf("Banana", "Zebra"), artists[0].entries.map { it.title })
        assertEquals(450_000L, artists[0].durationMs)
        assertEquals(listOf("Ferari Mon", "Lemonade", "Unknown album"), MediaLibrary.albums(songs).map { it.name })
        assertEquals(listOf("Download/SDM", "Music", "Other"), MediaLibrary.folders(songs).map { it.name })
    }

    @Test
    fun searchMatchesEveryWordAnywhereIgnoringAccents() {
        assertEquals(listOf(2L), MediaLibrary.search(songs, "beyonce").map { it.id })
        assertEquals(listOf(1L, 4L), MediaLibrary.search(songs, "ayub ferari").map { it.id })
        assertEquals(listOf(3L), MediaLibrary.search(songs, "ECLAIR").map { it.id })
        assertEquals(listOf(2L), MediaLibrary.search(songs, "sdm").map { it.id }) // the folder counts
        assertEquals(songs, MediaLibrary.search(songs, "   "))
        assertEquals(emptyList<MediaEntry>(), MediaLibrary.search(songs, "ayub lemonade"))
    }

    @Test
    fun durations() {
        assertEquals("", MediaLibrary.formatDuration(0))
        assertEquals("0:07", MediaLibrary.formatDuration(7_400))
        assertEquals("3:07", MediaLibrary.formatDuration(187_000))
        assertEquals("1:02:03", MediaLibrary.formatDuration(3_723_000))
    }
}
