package io.github.conditionstech.sdm.player

/**
 * Subtitle text (M18): reads SRT, WebVTT and YouTube's srv1 XML, writes SRT, and shifts the
 * timing. Plain Kotlin, tested on the PC. Every file is untrusted input: parsing never throws,
 * bad cues are skipped, and the size is capped.
 */
object Captions {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** Larger files are not subtitles (a 3-hour film's SRT is about 150 KB). */
    const val MAX_BYTES = 4 shl 20

    /** Up to [limit] bytes of [input], or null if it holds more (works on every Android version). */
    fun readCapped(input: java.io.InputStream, limit: Int = MAX_BYTES): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > limit) return null
        }
    }

    /** Reads [content] by its extension or, failing that, by what it looks like. */
    fun parse(content: String, extension: String = ""): List<Cue> {
        val text = content.removePrefix(Char(0xFEFF).toString()).replace("\r\n", "\n").replace('\r', '\n')
        return when {
            extension.equals("srv1", true) || text.trimStart().startsWith("<?xml") || text.contains("<transcript") -> parseSrv1(text)
            extension.equals("vtt", true) || text.trimStart().startsWith("WEBVTT") -> parseVtt(text)
            else -> parseSrt(text)
        }
    }

    private val TIMING = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val TAG = Regex("<[^>]*>")

    fun parseSrt(text: String): List<Cue> = blocks(text).mapNotNull { lines ->
        val timing = lines.indexOfFirst { TIMING.containsMatchIn(it) }
        if (timing < 0) return@mapNotNull null
        cue(lines[timing], lines.drop(timing + 1).joinToString("\n") { clean(it, keepItalics = true) })
    }

    /**
     * WebVTT. YouTube's automatic captions "roll": each cue repeats the line before it and
     * there are 10 ms filler cues, so lines already shown in the previous cue are dropped.
     */
    fun parseVtt(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        var previous = emptySet<String>()
        for (lines in blocks(text)) {
            val timing = lines.indexOfFirst { TIMING.containsMatchIn(it) }
            if (timing < 0) continue // WEBVTT header, NOTE, STYLE, REGION
            val shown = lines.drop(timing + 1).map { clean(it, keepItalics = false) }.filter { it.isNotBlank() }
            val fresh = shown.filter { it !in previous }
            if (shown.isNotEmpty()) previous = shown.toSet()
            val cue = cue(lines[timing], fresh.joinToString("\n")) ?: continue
            if (cue.endMs - cue.startMs < 50) continue
            cues += cue
        }
        return cues
    }

    private val SRV1_TEXT = Regex("""<text\b([^>]*)>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
    private val ATTRIBUTE = Regex("""(\w+)="([^"]*)"""")

    /** YouTube's `<transcript><text start="1.2" dur="3.4">…</text></transcript>`. */
    fun parseSrv1(text: String): List<Cue> = SRV1_TEXT.findAll(text).mapNotNull { match ->
        val attributes = ATTRIBUTE.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        val start = attributes["start"]?.toDoubleOrNull() ?: return@mapNotNull null
        val duration = attributes["dur"]?.toDoubleOrNull() ?: 2.0
        // srv1 text is escaped once or twice (&amp;#39;).
        val body = clean(unescape(match.groupValues[2]), keepItalics = false)
        if (body.isBlank() || start < 0) null else Cue((start * 1000).toLong(), ((start + duration) * 1000).toLong(), body)
    }.toList()

    /** The cues as an SRT file, numbered from 1. */
    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(time(cue.startMs)).append(" --> ").append(time(cue.endMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    /** Moves every cue by [deltaMs] (positive = later); cues pushed before 0 are dropped. */
    fun shift(cues: List<Cue>, deltaMs: Long): List<Cue> = cues.mapNotNull {
        val end = it.endMs + deltaMs
        if (end <= 0) null else Cue((it.startMs + deltaMs).coerceAtLeast(0), end, it.text)
    }

    /** "01:02:03,456" */
    fun time(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
    }

    /** "1:02:03.4", "02:03,456", "2:03.45" → milliseconds, or null. */
    fun parseTime(value: String): Long? {
        val parts = value.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
        if (seconds < 0 || minutes < 0 || hours < 0) return null
        return hours * 3_600_000 + minutes * 60_000 + Math.round(seconds * 1000)
    }

    private fun cue(timingLine: String, text: String): Cue? {
        val match = TIMING.find(timingLine) ?: return null
        val start = parseTime(match.groupValues[1]) ?: return null
        val end = parseTime(match.groupValues[2]) ?: return null
        if (end <= start || text.isBlank()) return null
        return Cue(start, end, text.trim())
    }

    private fun blocks(text: String): List<List<String>> =
        text.split(Regex("\n[ \t]*\n+")).map { block -> block.split('\n').filter { it.isNotBlank() } }.filter { it.isNotEmpty() }

    /** Removes markup (keeping <i> in SRT, which the player shows as italics) and entities. */
    private fun clean(line: String, keepItalics: Boolean): String {
        val stripped = TAG.replace(line) { tag ->
            if (keepItalics && tag.value.lowercase() in setOf("<i>", "</i>")) tag.value else ""
        }
        return unescape(stripped).trim()
    }

    private fun unescape(text: String): String {
        var current = text
        repeat(2) { // twice: srv1 escapes some characters twice
            current = current
                .replace(Regex("&#(\\d{1,6});")) { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: "" }
                .replace(Regex("&#x([0-9a-fA-F]{1,6});")) { m -> m.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: "" }
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&amp;", "&")
        }
        return current
    }
}
