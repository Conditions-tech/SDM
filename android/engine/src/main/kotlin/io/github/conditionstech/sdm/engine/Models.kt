package io.github.conditionstech.sdm.engine

import java.io.File

/** Tunable limits for one download. Defaults are for real use; tests shrink them. */
data class DownloadSettings(
    val connectTimeoutMs: Long = 15_000, // to look up the server and connect
    val readTimeoutMs: Long = 30_000, // the server may stay silent this long mid-download
    val maxAttempts: Int = 3, // first try plus retries
    val backoffBaseMs: Long = 1_000, // waits 1 s, 2 s, 4 s ... between attempts
    val maxRetryAfterMs: Long = 60_000, // cap on a server's Retry-After request
    val maxRedirects: Int = 10,
    val chunkSize: Int = 64 * 1024,
    // Splitting a download across connections (M14c, ADR-0012, as on Windows).
    val maxConnections: Int = 8,
    val minSplitSize: Long = 2L * 1024 * 1024, // smaller files use one connection
    val minPartSize: Long = 1024L * 1024,
    val dynamicSplitting: Boolean = true, // free connections take over half of the biggest part
    val snapshotEveryMs: Long = 1_000, // how often a split download's parts map is saved
    val userAgent: String = "SDM (Android)",
)

/** A finished download. */
data class DownloadResult(
    val file: File, // where the file was saved
    val size: Long, // bytes written
    val finalUrl: String, // after redirects (may contain tokens: redact before logging)
    val contentType: String?,
    /**
     * True when the server marked where the file ends (Content-Length or chunked encoding) and
     * every byte arrived. False when it only closed the connection: a connection that dropped
     * early looks exactly like a finished download, so completeness can't be confirmed.
     */
    val verifiedComplete: Boolean,
)

/** What the engine tells its caller while a download runs. Called on a background thread. */
interface DownloadListener {
    /** Once the server has answered, before any data. The name can still gain " (1)". */
    fun onFileInfo(fileName: String, totalBytes: Long?) {}

    /** After each chunk; [totalBytes] is null when the size is unknown. */
    fun onProgress(receivedBytes: Long, totalBytes: Long?) {}

    /**
     * What is needed to resume, each time it changes: once an attempt has its partial file,
     * and about once a second while a split download runs (only bytes already on disk).
     */
    fun onPartial(partial: PartialDownload) {}

    /** How many connections are downloading right now (split downloads). */
    fun onConnections(count: Int) {}
}
