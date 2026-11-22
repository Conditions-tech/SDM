package io.github.conditionstech.sdm.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// Helpers shared by the single-connection and the split download.

/** An open reply, with the call that can cancel it. The caller closes [response]. */
internal class Opened(val call: Call, val response: Response)

/** A reply can't continue what is on disk: download the file from byte 0 instead. */
internal class StartOver(reason: String) : Exception(reason)

/** A reply that can't serve a part (wrong status, range or version tags). */
internal class NotTrusted(reason: String) : Exception(reason)

/**
 * Runs blocking OkHttp work on an I/O thread; cancelling the coroutine cancels [call], which
 * unblocks a read that is waiting for the server.
 */
internal suspend fun <T> cancellable(call: Call, block: suspend () -> T): T = coroutineScope {
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

/** False if the reply carries an ETag or Last-Modified that differs from the known one. */
internal fun tagsMatch(response: Response, etag: String?, lastModified: String?): Boolean =
    listOf("ETag" to etag, "Last-Modified" to lastModified).all { (header, known) ->
        val sent = response.header(header)
        sent.isNullOrEmpty() || known.isNullOrEmpty() || sent == known
    }

/** The version tag to send with If-Range: a strong ETag, else Last-Modified, else null. */
internal fun ifRangeValue(etag: String?, lastModified: String?): String? =
    etag?.takeIf { it.isNotEmpty() && !it.startsWith("W/") } ?: lastModified?.ifEmpty { null }

internal fun interruptedMessage(received: Long, total: Long?): String {
    var amount = Units.formatSize(received.toDouble())
    if (total != null) amount += " of ${Units.formatSize(total.toDouble())}"
    return "The download was interrupted after $amount."
}

/** Turns network exceptions into [DownloadException]s with user-readable messages. */
internal inline fun <T> translated(host: String, block: () -> T): T = try {
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
internal inline fun <T> fileOperation(directory: File, block: () -> T): T = try {
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
