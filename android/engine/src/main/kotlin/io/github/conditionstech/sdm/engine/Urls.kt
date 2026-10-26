package io.github.conditionstech.sdm.engine

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Checking URLs before SDM touches the network, and making them safe to log. */
object Urls {
    const val MAX_URL_LENGTH = 8192

    /**
     * The parsed URL if SDM may download from it, else [InvalidUrlException].
     *
     * This checks the URL's shape only. Whether its host is an allowed destination is the
     * network policy's job, because that needs the resolved IP address.
     */
    fun validate(url: String): HttpUrl {
        val text = url.trim()
        if (text.isEmpty()) throw InvalidUrlException("The URL is empty.")
        if (text.length > MAX_URL_LENGTH) {
            throw InvalidUrlException("The URL is longer than $MAX_URL_LENGTH characters.")
        }
        if (text.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
            throw InvalidUrlException("The URL contains spaces or control characters.")
        }
        val scheme = text.substringBefore(':', "").lowercase()
        if (!text.contains(':') || scheme !in setOf("http", "https")) {
            val shown = if (text.contains(':') && scheme.isNotEmpty()) "'$scheme:'" else "no scheme"
            throw InvalidUrlException("Only http:// and https:// URLs are supported (got $shown).")
        }
        // OkHttp's parser also refuses bad ports ("99999", "abc") and malformed hosts.
        val parsed = text.toHttpUrlOrNull()
            ?: throw InvalidUrlException("The URL's server name or port is malformed.")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw InvalidUrlException("URLs with an embedded user name or password are not supported.")
        }
        return parsed
    }

    /**
     * The URL without credentials, query string or fragment, for logs and messages. Query
     * strings often carry access tokens (signed download links), so they never reach logs.
     */
    fun redact(url: String): String {
        val parsed = url.trim().toHttpUrlOrNull() ?: return "<invalid URL>"
        val redacted = parsed.newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .build()
            .toString()
        return redacted + if (parsed.encodedQuery != null) "?<redacted>" else ""
    }
}
