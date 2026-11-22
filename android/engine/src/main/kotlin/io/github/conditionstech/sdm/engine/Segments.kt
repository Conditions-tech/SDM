package io.github.conditionstech.sdm.engine

import java.io.File

/**
 * One part of a split download (ADR-0012): bytes [start]..[end] (inclusive), of which the
 * first [received] are on disk. Parts sit end to end over the whole file with no gaps.
 */
data class Segment(val start: Long, val end: Long, val received: Long = 0) {
    val size: Long get() = end - start + 1
    val nextByte: Long get() = start + received
    val done: Boolean get() = received >= size
}

/** What is needed to resume a download later (ADR-0011), e.g. after the app was closed. */
data class PartialDownload(
    val partFile: File,
    val fileName: String,
    val totalBytes: Long?,
    val etag: String?,
    val lastModified: String?,
    val resumable: Boolean,
    val segments: List<Segment>? = null, // set while the download is split
)

/** Planning parts, checking a saved parts map, and dynamic splitting (the Windows rules). */
object Segments {
    /**
     * Splits [total] bytes into at most [maxConnections] equal parts of at least [minPart];
     * the last part takes the remainder. One segment when splitting isn't worth it.
     */
    fun plan(total: Long, maxConnections: Int, minPart: Long): List<Segment> {
        require(total > 0) { "total must be positive" }
        val count = maxOf(1L, minOf(maxConnections.toLong(), total / maxOf(1L, minPart))).toInt()
        val base = total / count
        var start = 0L
        return List(count) { index ->
            val end = if (index == count - 1) total - 1 else start + base - 1
            Segment(start, end).also { start = end + 1 }
        }
    }

    /** True if the segments sit end to end over exactly [0, total) with sane progress. */
    fun cover(segments: List<Segment>, total: Long): Boolean {
        if (segments.isEmpty() || total <= 0) return false
        var expectedStart = 0L
        for (segment in segments) {
            if (segment.start != expectedStart || segment.end < segment.start) return false
            if (segment.received !in 0..segment.size) return false
            expectedStart = segment.end + 1
        }
        return expectedStart == total
    }

    /** The parts map as saved in the history: "[[start,end,received],...]", as on Windows. */
    fun encode(segments: List<Segment>): String =
        segments.joinToString(",", "[", "]") { "[${it.start},${it.end},${it.received}]" }

    private val ROW = Regex("""\[(\d{1,18}),(\d{1,18}),(\d{1,18})]""")

    /**
     * Reads a saved parts map; null if it is missing, malformed, or doesn't fit the file. The
     * text comes from the database, so nothing in it is trusted until checked.
     */
    fun decode(text: String?, total: Long?): List<Segment>? {
        if (text.isNullOrEmpty() || total == null || total <= 0) return null
        val compact = text.filterNot { it == ' ' }
        if (!compact.startsWith("[") || !compact.endsWith("]")) return null
        val inner = compact.substring(1, compact.length - 1)
        val rows = ROW.findAll(inner).toList()
        if (rows.isEmpty() || rows.size > 10_000) return null
        // The rows must be the whole text, separated by single commas: nothing else hidden in it.
        if (rows.joinToString(",") { it.value } != inner) return null
        val segments = rows.map { row ->
            val (start, end, received) = row.destructured
            Segment(start.toLong(), end.toLong(), received.toLong())
        }
        return segments.takeIf { cover(it, total) }
    }

    /**
     * Dynamic splitting: which part to halve for a free connection, and where. [parts] are
     * (next byte still needed, last byte) of the parts still downloading. The part with the
     * most bytes left gives its second half away, if both halves keep at least [minPart].
     * Returns (index into [parts], first byte of the new part), or null. Ties: the earliest.
     */
    fun pickSplit(parts: List<Pair<Long, Long>>, minPart: Long): Pair<Int, Long>? {
        var best: Pair<Int, Long>? = null
        var bestLeft = 0L
        parts.forEachIndexed { index, (nextByte, last) ->
            val left = last - nextByte + 1
            if (left >= 2 * minPart && left > bestLeft) {
                best = index to nextByte + left / 2
                bestLeft = left
            }
        }
        return best
    }

    private val CONTENT_RANGE = Regex("""bytes (\d{1,18})-(\d{1,18})/(\d{1,18})""")

    /** "bytes 100-199/1000" -> (100, 199, 1000); null for anything malformed or unusable. */
    fun parseContentRange(value: String?): Triple<Long, Long, Long>? {
        val match = CONTENT_RANGE.matchEntire(value?.trim() ?: return null) ?: return null
        val (first, last, total) = match.destructured
        val range = Triple(first.toLong(), last.toLong(), total.toLong())
        return range.takeIf { it.first <= it.second && it.second < it.third }
    }
}
