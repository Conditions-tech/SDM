package io.github.conditionstech.sdm.downloads

import io.github.conditionstech.sdm.engine.SdmException
import io.github.conditionstech.sdm.engine.Urls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Finding the link in text another app shared with SDM (Share → SDM, M14e). The text is
 * untrusted: apps often share "Title https://..." or several lines, so SDM takes the first
 * http(s) address that passes the same URL check as Add URL. The user still confirms it in the
 * Add URL dialog; nothing downloads by itself.
 */
object SharedLinks {
    const val MAX_SHARED_TEXT = 16 * 1024 // longer shares are cut, never scanned whole

    private val CANDIDATE = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    // Hosts whose links are pages, never files (M15c): a share from their apps means "this video".
    private val VIDEO_SITES = setOf(
        "youtube.com", "youtu.be", "vimeo.com", "dailymotion.com", "twitch.tv", "facebook.com",
        "instagram.com", "tiktok.com", "x.com", "twitter.com", "reddit.com", "soundcloud.com",
    )

    /**
     * True when [url] looks like a page with a video rather than a file: a known video site, or
     * an address whose last part has no file extension ("/watch", "/video/123"). Only a guess
     * to preselect the choice in Add URL; the user can change it.
     */
    fun looksLikeVideoPage(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val host = parsed.host.removePrefix("www.").removePrefix("m.")
        if (VIDEO_SITES.any { host == it || host.endsWith(".$it") }) return true
        val last = parsed.pathSegments.lastOrNull().orEmpty()
        return '.' !in last
    }

    /** The first acceptable link in [text], or null. */
    fun firstLink(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (match in CANDIDATE.findAll(text.take(MAX_SHARED_TEXT))) {
            // Trailing punctuation belongs to the sentence, not the address: "see https://x/a.zip."
            val link = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            try {
                Urls.validate(link)
                return link
            } catch (e: SdmException) {
                continue
            }
        }
        return null
    }
}
