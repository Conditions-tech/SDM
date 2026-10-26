package io.github.conditionstech.sdm.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.math.min
import kotlin.math.pow

/**
 * HTTP/HTTPS download over one connection (M14b; resume and splitting come in M14c).
 *
 * One attempt: check the URL -> GET, following redirects by hand so every hop is re-checked ->
 * check the status -> choose a file name -> stream into "<name>.sdmpart" -> check the size ->
 * move to the final name without ever replacing an existing file. Failures that might pass
 * (timeouts, 5xx, dropped connections) are retried, from the start for now.
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
     * Throws an [SdmException] ([InvalidUrlException], [BlockedDestinationException],
     * [DownloadException]) whose message is fit to show the user. Cancelling the coroutine stops
     * the download; the partial file is deleted on any failure.
     */
    suspend fun download(
        url: String,
        directory: File,
        listener: DownloadListener = object : DownloadListener {},
    ): DownloadResult {
        Urls.validate(url)
        if (!directory.isDirectory) {
            throw DownloadException("The download folder does not exist: ${directory.name}")
        }
        val transfer = Transfer()
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
            transfer.part?.delete()
            throw e
        }
    }

    /** What one download() call knows about its partial file, across attempts. */
    private class Transfer {
        var part: File? = null
        var fileName: String? = null
        var received = 0L
    }

    private suspend fun attempt(
        url: String,
        directory: File,
        transfer: Transfer,
        listener: DownloadListener,
    ): DownloadResult {
        val (call, response) = getFollowingRedirects(url)
        return response.use {
            cancellable(call) { acceptAndStream(response, directory, transfer, listener) }
        }
    }

    private fun acceptAndStream(
        response: Response,
        directory: File,
        transfer: Transfer,
        listener: DownloadListener,
    ): DownloadResult {
        checkStatus(response)
        val body = response.body
        val total = body.contentLength().takeIf { it >= 0 }
        if (transfer.part == null) {
            val name = FileNames.sanitize(
                FileNames.fromContentDisposition(response.header("Content-Disposition"))
                    ?: FileNames.fromUrl(response.request.url.toString()),
            )
            checkFreeSpace(directory, total)
            transfer.fileName = name
            transfer.part = fileOperation(directory) { PartFiles.create(directory, name) }
        } else {
            checkFreeSpace(directory, total)
        }
        val part = transfer.part!!
        val name = transfer.fileName!!
        listener.onFileInfo(name, total)

        transfer.received = 0 // no resume yet (M14c): every attempt starts from byte 0
        listener.onProgress(0, total)
        val buffer = ByteArray(settings.chunkSize)
        val source = body.byteStream()
        fileOperation(directory) { FileOutputStream(part, false) }.use { out ->
            while (true) {
                val read = try {
                    source.read(buffer)
                } catch (e: IOException) {
                    throw DownloadException(interruptedMessage(transfer.received, total), retryable = true)
                }
                if (read < 0) break
                fileOperation(directory) { out.write(buffer, 0, read) }
                transfer.received += read
                listener.onProgress(transfer.received, total)
            }
        }
        if (total != null && transfer.received != total) {
            throw DownloadException(interruptedMessage(transfer.received, total), retryable = true)
        }
        val saved = fileOperation(directory) { PartFiles.finish(part, directory, name) }
        transfer.part = null // moved: nothing left to clean up
        // With chunked encoding OkHttp itself fails if the final chunk never arrives.
        val chunked = response.header("Transfer-Encoding").orEmpty().contains("chunked", ignoreCase = true)
        return DownloadResult(
            file = saved,
            size = transfer.received,
            finalUrl = response.request.url.toString(),
            contentType = response.header("Content-Type"),
            verifiedComplete = total != null || chunked,
        )
    }

    /** GET [url], following redirects by hand; returns the call and its open, non-redirect reply. */
    private suspend fun getFollowingRedirects(url: String): Pair<Call, Response> {
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
                .build()
            val call = client.newCall(request)
            val response = cancellable(call) { translated(parsed.host) { call.execute() } }
            if (response.code !in REDIRECT_STATUSES) return call to response
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

    /**
     * Runs blocking OkHttp work on an I/O thread; cancelling the coroutine cancels [call], which
     * unblocks a read that is waiting for the server.
     */
    private suspend fun <T> cancellable(call: Call, block: () -> T): T = coroutineScope {
        var finished = false // set before the watcher stops, so a normal end never cancels
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                if (!finished) call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            finished = true
            watcher.cancel()
        }
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

        /** Seconds to wait from a Retry-After header (a number or an HTTP date), or null. */
        fun parseRetryAfter(value: String?, now: ZonedDateTime = ZonedDateTime.now()): Double? {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (text.all { it in '0'..'9' }) return text.toDoubleOrNull()
            val date = runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME) }
                .getOrNull() ?: return null
            return maxOf(0.0, Duration.between(now, date).toMillis() / 1000.0)
        }

        fun interruptedMessage(received: Long, total: Long?): String {
            var amount = Units.formatSize(received.toDouble())
            if (total != null) amount += " of ${Units.formatSize(total.toDouble())}"
            return "The download was interrupted after $amount."
        }

        /** Server-supplied text, reduced to printable characters before it reaches the user. */
        private fun printable(text: String, limit: Int): String =
            text.filter { !it.isISOControl() && it.isDefined() }.take(limit)

        /** Turns network exceptions into [DownloadException]s with user-readable messages. */
        private inline fun <T> translated(host: String, block: () -> T): T = try {
            block()
        } catch (e: SdmException) {
            throw e
        } catch (e: UnknownHostException) {
            throw DownloadException(
                "Could not find the server $host. Check the address and your connection.",
                retryable = true,
            )
        } catch (e: SSLException) {
            throw DownloadException("Could not set up a secure connection to $host (certificate or TLS problem).")
        } catch (e: ConnectException) {
            throw DownloadException("Could not connect to $host.", retryable = true)
        } catch (e: InterruptedIOException) { // includes SocketTimeoutException
            throw DownloadException("The server took too long to respond.", retryable = true)
        } catch (e: IOException) {
            throw DownloadException("The download was interrupted by a network error.", retryable = true)
        }

        /** Turns storage exceptions into [DownloadException]s with user-readable messages. */
        private inline fun <T> fileOperation(directory: File, block: () -> T): T = try {
            block()
        } catch (e: SdmException) {
            throw e
        } catch (e: IOException) {
            val reason = e.message.orEmpty()
            if (reason.contains("ENOSPC") || reason.contains("No space left", ignoreCase = true) ||
                reason.contains("not enough space", ignoreCase = true)
            ) {
                throw DownloadException("Not enough storage space in ${directory.name}.")
            }
            throw DownloadException("Could not write the file in ${directory.name}.")
        }
    }
}
