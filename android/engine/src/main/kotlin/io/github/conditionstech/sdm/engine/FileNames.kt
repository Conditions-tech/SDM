package io.github.conditionstech.sdm.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.Normalizer

/**
 * Choosing a safe file name for a download (ADR-0008, the same rules as the Windows app).
 *
 * Names come from untrusted sources (the Content-Disposition header or the URL), so they are
 * reduced to one valid path component. The Windows rules are kept on Android: files are often
 * copied to a PC or an SD card (FAT/exFAT forbids the same characters).
 */
object FileNames {
    const val FALLBACK_NAME = "download"

    /** Default limit in UTF-8 bytes: Android's file systems allow 255; leaves room for suffixes. */
    const val MAX_NAME_BYTES = 200

    // Characters Windows and FAT forbid, plus ASCII control characters.
    private val INVALID_CHARS = Regex("[<>:\"/\\\\|?*\\x00-\\x1f\\x7f]")

    // Lone UTF-16 surrogates can't be encoded in a file name (a paired emoji is one code point).
    private val SURROGATES = Regex("[\\ud800-\\udfff]")

    // Bidirectional-text controls: "invoice\u202etxt.exe" *displays* as "invoiceexe.txt".
    private val BIDI_CONTROLS = Regex("[\\u061c\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")

    // Device names Windows reserves regardless of extension ("CON.txt" is still the console).
    private val RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL") +
        "123456789¹²³".flatMap { listOf("COM$it", "LPT$it") }

    // RFC 6266 / RFC 8187: filename*=charset'language'percent-encoded-value
    private val FILENAME_STAR =
        Regex("""(?:^|;)\s*filename\*\s*=\s*([^';\s]*)'[^']*'([^;\s]+)""", RegexOption.IGNORE_CASE)

    // filename="quoted \"value\"" or filename=token
    private val FILENAME_PLAIN =
        Regex("""(?:^|;)\s*filename\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^;\s]+))""", RegexOption.IGNORE_CASE)

    /** The file name in a Content-Disposition header, or null. `filename*` wins (RFC 6266). */
    fun fromContentDisposition(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        FILENAME_STAR.find(value)?.let { match ->
            val charset = runCatching { Charset.forName(match.groupValues[1].ifEmpty { "utf-8" }) }
                .getOrDefault(Charsets.UTF_8)
            val name = percentDecode(match.groupValues[2], charset)
            if (name.isNotEmpty()) return name
        }
        val plain = FILENAME_PLAIN.find(value) ?: return null
        val quoted = plain.groups[1]?.value
        val name = quoted?.replace(Regex("""\\(.)"""), "$1") ?: plain.groupValues[2]
        return name.ifEmpty { null }
    }

    /** The URL's last path segment, percent-decoded, or null if there is none. */
    fun fromUrl(url: String): String? =
        url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.ifEmpty { null }

    /**
     * Turns an untrusted name into one safe file name (no folders, no device names), at most
     * [maxBytes] UTF-8 bytes long ([maxBytes] must be at least 12).
     */
    fun sanitize(name: String?, maxBytes: Int = MAX_NAME_BYTES): String {
        if (name.isNullOrEmpty()) return FALLBACK_NAME
        // Keep only the last component, so "../../evil.apk" or "C:\x\evil.exe" cannot escape.
        var clean = name.split('/', '\\').last()
        clean = Normalizer.normalize(clean, Normalizer.Form.NFC)
        clean = BIDI_CONTROLS.replace(clean, "")
        clean = INVALID_CHARS.replace(clean, "_")
        clean = SURROGATES.replace(clean, "_")
        // Windows silently drops trailing dots and spaces, which would change the chosen name.
        clean = clean.trim().trimEnd('.', ' ')
        if (clean.isEmpty()) return FALLBACK_NAME
        if (clean.substringBefore('.').trimEnd(' ').uppercase() in RESERVED_NAMES) clean = "_$clean"

        if (utf8Length(clean) > maxBytes) {
            var (stem, ext) = splitExtension(clean)
            if (ext.isEmpty() || utf8Length(ext) > minOf(16, maxBytes / 2)) {
                stem = clean
                ext = ""
            }
            stem = truncateUtf8(stem, maxBytes - utf8Length(ext)).trimEnd('.', ' ')
            clean = stem.ifEmpty { FALLBACK_NAME } + ext
        }
        return clean
    }

    /** Collision variant of a name: ("a.txt", 0) -> "a.txt"; ("a.txt", 2) -> "a (2).txt". */
    fun numbered(name: String, number: Int): String {
        if (number == 0) return name
        val (stem, ext) = splitExtension(name)
        return "$stem ($number)$ext"
    }

    /** Like Python's os.path.splitext: "a.tar.gz" -> ("a.tar", ".gz"); ".bashrc" has none. */
    fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || name.substring(0, dot).all { it == '.' }) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }

    fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        var end = text.length
        while (end > 0 && utf8Length(text.substring(0, end)) > maxBytes) {
            end = text.offsetByCodePoints(end, -1) // never split an emoji's surrogate pair
        }
        return text.substring(0, end)
    }

    /** %XX sequences decoded as bytes in [charset]; bad sequences stay as typed. No '+' rule. */
    private fun percentDecode(text: String, charset: Charset): String {
        val out = StringBuilder()
        val run = ByteArrayOutputStream() // consecutive %XX bytes, decoded together
        var i = 0
        while (i < text.length) {
            if (text[i] == '%' && isHexPair(text, i + 1)) {
                run.write(text.substring(i + 1, i + 3).toInt(16))
                i += 3
                continue
            }
            if (run.size() > 0) {
                out.append(String(run.toByteArray(), charset))
                run.reset()
            }
            out.append(text[i])
            i += 1
        }
        if (run.size() > 0) out.append(String(run.toByteArray(), charset))
        return out.toString()
    }

    private fun isHexPair(text: String, at: Int): Boolean =
        at + 1 < text.length && text[at].isHexDigit() && text[at + 1].isHexDigit()

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
