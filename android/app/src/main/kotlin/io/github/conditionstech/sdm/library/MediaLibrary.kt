package io.github.conditionstech.sdm.library

import java.text.Normalizer
import java.util.Locale

/** Music or video (M17, ADR-0025). */
enum class MediaKind { AUDIO, VIDEO }

/**
 * One song or video on the phone, as Android's media index (MediaStore) describes it. Every
 * text field comes from the file's own tags, so it is shown as plain text and never trusted.
 */
data class MediaEntry(
    val id: Long,
    val uri: String,
    val kind: MediaKind,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val dateAddedSeconds: Long = 0,
    val folder: String = "", // e.g. "Download/SDM/" or "Music/"
    val width: Int = 0,
    val height: Int = 0,
) {
    val artistOrUnknown: String get() = artist?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist"
    val albumOrUnknown: String get() = album?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown album"
}

/** A group of songs shown as one row: an album, an artist or a folder. */
data class MediaGroup(val name: String, val entries: List<MediaEntry>) {
    val durationMs: Long get() = entries.sumOf { it.durationMs }
}

enum class SortOrder(val label: String) { TITLE("Title"), NEWEST("Newest"), LONGEST("Longest"), LARGEST("Largest") }

/** Sorting, grouping and search for the Music and Videos tabs: plain Kotlin, tested on the PC. */
object MediaLibrary {
    fun sorted(entries: List<MediaEntry>, order: SortOrder): List<MediaEntry> = when (order) {
        SortOrder.TITLE -> entries.sortedWith(compareBy({ key(it.title) }, { it.id }))
        SortOrder.NEWEST -> entries.sortedWith(compareByDescending<MediaEntry> { it.dateAddedSeconds }.thenBy { it.id })
        SortOrder.LONGEST -> entries.sortedWith(compareByDescending<MediaEntry> { it.durationMs }.thenBy { it.id })
        SortOrder.LARGEST -> entries.sortedWith(compareByDescending<MediaEntry> { it.sizeBytes }.thenBy { it.id })
    }

    fun albums(entries: List<MediaEntry>): List<MediaGroup> = group(entries) { it.albumOrUnknown }

    fun artists(entries: List<MediaEntry>): List<MediaGroup> = group(entries) { it.artistOrUnknown }

    fun folders(entries: List<MediaEntry>): List<MediaGroup> = group(entries) { it.folder.trimEnd('/').ifEmpty { "Other" } }

    /**
     * Entries whose title, artist, album or folder contains every word of [query], ignoring
     * case and accents ("beyonce" finds "Beyoncé"). An empty query keeps everything.
     */
    fun search(entries: List<MediaEntry>, query: String): List<MediaEntry> {
        val words = key(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return entries
        return entries.filter { entry ->
            val text = key(listOfNotNull(entry.title, entry.artist, entry.album, entry.folder).joinToString(" "))
            words.all { it in text }
        }
    }

    /** "3:07", "1:02:03"; "" when unknown. */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val total = ms / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    private fun group(entries: List<MediaEntry>, name: (MediaEntry) -> String): List<MediaGroup> =
        entries.groupBy(name)
            .map { (groupName, items) -> MediaGroup(groupName, sorted(items, SortOrder.TITLE)) }
            .sortedWith(compareBy({ it.name.startsWith("Unknown") || it.name == "Other" }, { key(it.name) }))

    /** Lower case without accents, for sorting and searching. */
    private fun key(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
}
