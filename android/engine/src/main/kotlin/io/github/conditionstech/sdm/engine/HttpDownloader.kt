package io.github.conditionstech.sdm.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Proxy
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * HTTP/HTTPS downloads with resume and splitting (M14b-c; the Windows engine's design).
 *
 * One attempt: check the URL -> GET, following redirects by hand so every hop is re-checked ->
 * check the status -> choose a file name -> stream into "<name>.sdmpart" -> check the size ->
 * move to the final name without ever replacing an existing file.
 *
 * Resuming (ADR-0011). When part of the file is on disk, the request asks only for the rest:
 * `Range: bytes=N-` plus `If-Range` with the server's version tag (a strong ETag, else
 * Last-Modified), so a file that changed comes back whole. Without version tags SDM asks for
 * 64 bytes more than it needs and checks they match the end of its partial file. Only an exact
 * `206` continues; anything else starts over from byte 0 in the same partial file. Two versions
 * of a file are never stitched together. Retries continue where the last attempt stopped.
 *
 * Splitting (ADR-0012). A file of at least 2 MiB, from a server that supports ranges and gives a
 * version tag, downloads over up to 8 connections ([SegmentedDownload]).
 *
 * [dns] is the real address lookup; tests replace it. The network policy always wraps it.
 * [configureClient] is for tests only (to trust a test server's certificate); it runs before
 * SDM's own settings, so it can't switch the policy or the redirect checks off.
 */
class HttpDownloader(
    private val policy: NetworkPolicy = NetworkPolicy(),
    private val settings: DownloadSettings = DownloadSettings(),
    dns: Dns = Dns.SYSTEM,
    configureClient: (OkHttpClient.Builder) -> Unit = {},
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .apply(configureClient)
        .dns(PolicyDns(policy, dns))
        .proxy(Proxy.NO_PROXY) // a proxy would hide the real destination from the policy
        .followRedirects(false) // followed by hand: every hop is checked
        .followSslRedirects(false)
        .retryOnConnectionFailure(false) // SDM's own retries decide
        .connectTimeout(settings.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS)
        .addNetworkInterceptor { chain ->
            // Safety net: the address actually connected to, whatever led there.
            chain.connection()?.route()?.socketAddress?.address?.let {
                policy.check(it, chain.request().url.host)
            }
            chain.proceed(chain.request())
        }
        .build()

    /**
     * Downloads [url] into [directory] and returns where the file was saved.
     *
     * [resumeFrom] continues an earlier partial file (from [DownloadListener.onPartial]). With
     * [keepPartial] (the app's download list), the partial file is kept when the download is
     * cancelled (the caller decides whether that was a pause, and deletes it otherwise) or fails
     * on a server that can resume; without it, any failure deletes the partial file.
     *
     * Throws an [SdmException] ([InvalidUrlException], [BlockedDestinationException],
     * [DownloadException]) whose message is fit to show the user.
     */
    suspend fun download(
        url: String,
        directory: File,
        listener: DownloadListener = object : DownloadListener {},
        resumeFrom: PartialDownload? = null,
        keepPartial: Boolean = false,
    ): DownloadResult {
        Urls.validate(url)
        if (!directory.isDirectory) {
            throw DownloadException("The download folder does not exist: ${directory.name}")
        }
        val transfer = Transfer.start(directory, resumeFrom)
        try {
            var attempt = 1
            while (true) {
                try {
                    return attempt(url.trim(), directory, transfer, listener)
                } catch (e: DownloadException) {
                    currentCoroutineContext().ensureActive() // a cancel is not a failure
                    if (!e.retryable || attempt >= settings.maxAttempts) throw e
                    val wait = e.retryAfterSeconds?.let { (it * 1000).toLong() }
                        ?: (settings.backoffBaseMs * 2.0.pow(attempt - 1)).toLong()
                    delay(min(wait, settings.maxRetryAfterMs))
                    attempt += 1
                }
            }
        } catch (e: Throwable) { // includes cancellation
            if (!keep(e, transfer, keepPartial)) transfer.part?.delete()
            throw e
        }
    }

    private fun keep(e: Throwable, transfer: Transfer, keepPartial: Boolean): Boolean = when {
        !keepPartial -> false
        e is CancellationException -> true // the caller deletes it unless this was a pause
        else -> transfer.resumable && transfer.received > 0
    }

    /** What one download() call knows about its partial file, across attempts. */
    private class Transfer {
        var part: File? = null
        var fileName: String? = null
        var received = 0L // bytes at the start of the partial file that belong to this version
        var total: Long? = null
        var etag: String? = null
        var lastModified: String? = null
        var resumable = false
        var segments: List<Segment>? = null // set while the download is split
        var noSplit = false // after a failed split, stay on one connection

        fun partial() = PartialDownload(part!!, fileName!!, total, etag, lastModified, resumable, segments)

        /** Forget what is on disk and any split; the partial file is reused from byte 0. */
        fun startOver() {
            received = 0
            segments = null
            noSplit = true
        }

        companion object {
            fun start(directory: File, resumeFrom: PartialDownload?): Transfer {
                val fresh = Transfer()
                val from = resumeFrom ?: return fresh
                // The path comes from the history: never let it point SDM somewhere else.
                if (!isOwnPartFile(from.partFile, directory) || !from.partFile.isFile) return fresh
                val transfer = Transfer().apply {
                    part = from.partFile
                    fileName = from.fileName
                    total = from.totalBytes
                    etag = from.etag
                    lastModified = from.lastModified
                    resumable = from.resumable
                }
                val size = from.partFile.length()
                val segments = from.segments
                if (segments != null) {
                    val total = from.totalBytes
                    if (total == null || size != total || !Segments.cover(segments, total) ||
                        ifRangeValue(from.etag, from.lastModified) == null
                    ) {
                        return fresh // the saved parts map doesn't fit the partial file
                    }
                    transfer.segments = segments
                    transfer.received = segments.sumOf { it.received }
                    return transfer
                }
                transfer.received = if (from.totalBytes != null && size > from.totalBytes) 0 else size
                return transfer
            }
        }
    }

    /** How to ask for the rest of a plain (unsplit) partial file. */
    private class RangePlan(val start: Long, val overlap: Int, val ifRange: String?) {
        fun headers(): Map<String, String> =
            mapOf("Range" to "bytes=$start-") + (ifRange?.let { mapOf("If-Range" to it) } ?: emptyMap())
    }

    private fun rangePlan(transfer: Transfer): RangePlan? {
        if (transfer.part == null || transfer.received <= 0 || transfer.segments != null) return null
        val validator = ifRangeValue(transfer.etag, transfer.lastModified)
        if (validator != null) return RangePlan(transfer.received, 0, validator)
        if (transfer.received >= OVERLAP) return RangePlan(transfer.received - OVERLAP, OVERLAP, null)
        return null // too little to check: cheaper to start over
    }

    private fun shouldSplit(transfer: Transfer): Boolean {
        val total = transfer.total ?: return false
        return !transfer.noSplit &&
            settings.maxConnections > 1 &&
            total >= settings.minSplitSize &&
            transfer.resumable &&
            ifRangeValue(transfer.etag, transfer.lastModified) != null &&
            Segments.plan(total, settings.maxConnections, settings.minPartSize).size > 1
    }

    private suspend fun attempt(
        url: String,
        directory: File,
        transfer: Transfer,
        listener: DownloadListener,
    ): DownloadResult {
        if (transfer.segments != null) { // continue a split download, part by part
            try {
                runSegmented(url, transfer, null, listener)
                return finishSegmented(transfer, directory, url, null)
            } catch (e: StartOver) {
                transfer.startOver() // can't resume the parts: download from the start
            }
        }
        var plan = rangePlan(transfer)
        while (true) {
            val opened = getFollowingRedirects(url, plan?.headers().orEmpty())
            try {
                val response = opened.response
                val offset: Long? = try {
                    cancellable(opened.call) { accept(response, plan, transfer, directory) }
                } catch (e: StartOver) {
                    plan = null // can't resume: download from the start
                    transfer.received = 0
                    continue
                }
                val part = transfer.part!!
                val name = transfer.fileName!!
                val total = transfer.total
                val split = offset == 0L && shouldSplit(transfer)
                if (split && total != null) {
                    transfer.segments = Segments.plan(total, settings.maxConnections, settings.minPartSize)
                    fileOperation(directory) {
                        // Sparse on Android's file systems: instant, nothing is written.
                        RandomAccessFile(part, "rw").use { it.setLength(0); it.setLength(total) }
                    }
                }
                listener.onFileInfo(name, total)
                listener.onPartial(transfer.partial())
                val finalUrl = response.request.url.toString()
                if (split) {
                    try {
                        runSegmented(finalUrl, transfer, opened, listener)
                    } catch (e: StartOver) {
                        transfer.startOver() // the file changed: download it again on one connection
                        plan = null
                        continue
                    }
                    return finishSegmented(transfer, directory, finalUrl, response.header("Content-Type"))
                }
                val received = if (offset == null) { // 416: everything was already there
                    transfer.received
                } else {
                    cancellable(opened.call) { streamSingle(response, part, offset, transfer, listener, directory) }
                }
                if (total != null && received != total) {
                    throw DownloadException(interruptedMessage(received, total), retryable = true)
                }
                val saved = fileOperation(directory) { PartFiles.finish(part, directory, name) }
                transfer.part = null // moved: nothing left to clean up
                // With chunked encoding OkHttp itself fails if the final chunk never arrives.
                val chunked = response.header("Transfer-Encoding").orEmpty().contains("chunked", ignoreCase = true)
                return DownloadResult(
                    file = saved,
                    size = received,
                    finalUrl = finalUrl,
                    contentType = response.header("Content-Type"),
                    verifiedComplete = total != null || chunked || response.code == 206,
                )
            } finally {
                opened.response.close()
            }
        }
    }

    private suspend fun runSegmented(url: String, transfer: Transfer, first: Opened?, listener: DownloadListener) {
        val ifRange = ifRangeValue(transfer.etag, transfer.lastModified) ?: throw StartOver("no version tag")
        val part = transfer.part ?: throw StartOver("nothing to continue")
        val total = transfer.total ?: throw StartOver("unknown size")
        val segments = transfer.segments ?: throw StartOver("no parts map")
        SegmentedDownload(
            // Every part request goes through the same redirect checks and network policy.
            request = { headers -> getFollowingRedirects(url, headers) },
            partFile = part,
            total = total,
            ifRange = ifRange,
            etag = transfer.etag,
            lastModified = transfer.lastModified,
            segments = segments,
            settings = settings,
            listener = listener,
        ) { snapshot ->
            transfer.segments = snapshot
            transfer.received = snapshot.sumOf { it.received }
            listener.onPartial(transfer.partial())
        }.run(first)
    }

    private fun finishSegmented(transfer: Transfer, directory: File, finalUrl: String, contentType: String?): DownloadResult {
        val part = transfer.part!!
        val saved = fileOperation(directory) { PartFiles.finish(part, directory, transfer.fileName!!) }
        transfer.part = null // moved: nothing left to clean up
        return DownloadResult(saved, transfer.total!!, finalUrl, contentType, verifiedComplete = true)
    }

    /**
     * Checks the reply and prepares the partial file. Returns the offset to write from, or null
     * if the file is already complete (416). Throws [StartOver] when a resume can't be trusted.
     */
    private fun accept(response: Response, plan: RangePlan?, transfer: Transfer, directory: File): Long? {
        val status = response.code
        if (plan != null && status == 416) {
            if (plan.overlap == 0 && transfer.total == transfer.received) return null
            throw StartOver("the server refused the range")
        }
        if (plan != null && status == 206) {
            checkContinuation(response, plan, transfer)
            transfer.resumable = true // proven
            checkFreeSpace(directory, (transfer.total ?: 0) - transfer.received)
            return transfer.received
        }
        checkStatus(response) // anything but 200 throws here (including a 206 nobody asked for)

        // 200: the whole file, from byte 0.
        transfer.received = 0
        transfer.total = response.body.contentLength().takeIf { it >= 0 }
        transfer.etag = response.header("ETag")
        transfer.lastModified = response.header("Last-Modified")
        transfer.resumable = response.header("Accept-Ranges").orEmpty().trim().equals("bytes", ignoreCase = true)
        if (transfer.part == null) {
            transfer.fileName = FileNames.sanitize(
                FileNames.fromContentDisposition(response.header("Content-Disposition"))
                    ?: FileNames.fromUrl(response.request.url.toString()),
            )
        }
        checkFreeSpace(directory, transfer.total)
        if (transfer.part == null) {
            transfer.part = fileOperation(directory) { PartFiles.create(directory, transfer.fileName!!) }
        }
        return 0
    }

    /** A 206 reply continues the partial file only if everything about it matches. */
    private fun checkContinuation(response: Response, plan: RangePlan, transfer: Transfer) {
        val (first, last, total) = Segments.parseContentRange(response.header("Content-Range"))
            ?: throw StartOver("missing or invalid Content-Range")
        if (first != plan.start || last != total - 1) {
            throw StartOver("the server sent bytes $first-$last, not ${plan.start}-${total - 1}")
        }
        if (transfer.total != null && total != transfer.total) throw StartOver("the file's size changed")
        if (!tagsMatch(response, transfer.etag, transfer.lastModified)) throw StartOver("the file's version tags changed")
        transfer.total = total
        if (plan.overlap > 0) {
            val fresh = try {
                response.body.source().readByteArray(plan.overlap.toLong())
            } catch (e: IOException) {
                throw DownloadException(interruptedMessage(transfer.received, transfer.total), retryable = true)
            }
            val onDisk = ByteArray(plan.overlap)
            RandomAccessFile(transfer.part!!, "r").use {
                it.seek(transfer.received - plan.overlap)
                it.readFully(onDisk)
            }
            if (!fresh.contentEquals(onDisk)) throw StartOver("the last bytes on disk don't match the server's file")
        }
    }

    private fun streamSingle(
        response: Response,
        part: File,
        offset: Long,
        transfer: Transfer,
        listener: DownloadListener,
        directory: File,
    ): Long {
        transfer.received = offset
        listener.onProgress(offset, transfer.total)
        val buffer = ByteArray(settings.chunkSize)
        val source = response.body.byteStream()
        fileOperation(directory) { RandomAccessFile(part, "rw") }.use { file ->
            fileOperation(directory) {
                file.setLength(offset) // drop anything after the trusted bytes
                file.seek(offset)
            }
            while (true) {
                val read = try {
                    source.read(buffer)
                } catch (e: IOException) {
                    // Say how much arrived, as the Windows app does.
                    throw DownloadException(interruptedMessage(transfer.received, transfer.total), retryable = true)
                }
                if (read < 0) break
                fileOperation(directory) { file.write(buffer, 0, read) }
                transfer.received += read // a retry continues from here
                listener.onProgress(transfer.received, transfer.total)
            }
        }
        return transfer.received
    }

    /** GET [url], following redirects by hand; returns the open, non-redirect reply. */
    private suspend fun getFollowingRedirects(url: String, extraHeaders: Map<String, String>): Opened {
        var current = url
        repeat(settings.maxRedirects + 1) {
            val parsed = try {
                Urls.validate(current)
            } catch (e: InvalidUrlException) {
                throw InvalidUrlException("The server redirected to an address SDM refuses: ${e.message}")
            }
            policy.checkUrlHost(parsed.host)
            val request = Request.Builder()
                .url(parsed)
                .header("User-Agent", settings.userAgent)
                // "identity" asks for the file exactly as stored, so sizes match Content-Length.
                .header("Accept-Encoding", "identity")
                .apply { extraHeaders.forEach { (name, value) -> header(name, value) } }
                .build()
            val call = client.newCall(request)
            val response = cancellable(call) { translated(parsed.host) { call.execute() } }
            if (response.code !in REDIRECT_STATUSES) return Opened(call, response)
            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank()) {
                throw DownloadException("The server sent a redirect without a destination.")
            }
            val following = parsed.resolve(location.trim())
                ?: throw InvalidUrlException("The server redirected to an address SDM refuses: it is malformed.")
            if (parsed.isHttps && !following.isHttps) {
                throw DownloadException(
                    "The server redirected from a secure (https) address to an insecure (http) one. " +
                        "SDM refuses that, as browsers do.",
                )
            }
            current = following.toString()
        }
        throw DownloadException("Too many redirects (more than ${settings.maxRedirects}).")
    }

    private fun checkStatus(response: Response) {
        val status = response.code
        if (status == 200) return
        val label = "$status ${printable(response.message, 80)}".trim()
        if (status in RETRYABLE_STATUSES || status in 500..599) {
            throw DownloadException(
                "The server reported a temporary problem ($label).",
                retryable = true,
                retryAfterSeconds = parseRetryAfter(response.header("Retry-After")),
            )
        }
        if (status == 404 || status == 410) {
            throw DownloadException("The file was not found on the server ($label).")
        }
        throw DownloadException("The server refused the download ($label).")
    }

    private fun checkFreeSpace(directory: File, needed: Long?) {
        if (needed == null || needed <= 0) return
        val free = directory.usableSpace
        if (free < needed) {
            throw DownloadException(
                "Not enough storage space: the file needs ${Units.formatSize(needed.toDouble())} " +
                    "but only ${Units.formatSize(free.toDouble())} is free.",
            )
        }
    }

    companion object {
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val RETRYABLE_STATUSES = setOf(408, 429)
        const val OVERLAP = 64 // bytes re-downloaded to check a resume without version tags

        /** True if [part] is a .sdmpart file directly inside [directory]. */
        fun isOwnPartFile(part: File, directory: File): Boolean = try {
            part.name.endsWith(PartFiles.PART_SUFFIX) &&
                part.canonicalFile.parentFile == directory.canonicalFile
        } catch (e: IOException) {
            false
        }

        /** Seconds to wait from a Retry-After header (a number or an HTTP date), or null. */
        fun parseRetryAfter(value: String?, now: ZonedDateTime = ZonedDateTime.now()): Double? {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (text.all { it in '0'..'9' }) return text.toDoubleOrNull()
            val date = runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME) }
                .getOrNull() ?: return null
            return maxOf(0.0, Duration.between(now, date).toMillis() / 1000.0)
        }

        /** Server-supplied text, reduced to printable characters before it reaches the user. */
        private fun printable(text: String, limit: Int): String =
            text.filter { !it.isISOControl() && it.isDefined() }.take(limit)
    }
}
