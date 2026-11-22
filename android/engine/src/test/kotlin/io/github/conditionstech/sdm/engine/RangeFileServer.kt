package io.github.conditionstech.sdm.engine

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A local test server for one file that answers `Range` and `If-Range` like a real server,
 * and can be told to misbehave. Thread-safe: MockWebServer serves each connection on its own
 * thread, and a split download opens several at once.
 */
class RangeFileServer(@Volatile var data: ByteArray) : Dispatcher() {
    @Volatile var etag: String? = "\"v1\""
    @Volatile var lastModified: String? = null
    @Volatile var acceptRanges = true
    @Volatile var honourRanges = true // false: every range request gets the whole file (200)
    @Volatile var shiftRanges = false // true: sends (and labels) the bytes one past what was asked
    @Volatile var ignoreIfRange = false // true: answers ranges even when the file has changed
    @Volatile var bytesPerSecond: Long? = null // per connection
    @Volatile var dropOnceAtRangeStart: Long? = null // that range's first reply stops halfway

    /** The Range header of every request, in arrival order (null for a plain GET). */
    val ranges: MutableList<String?> = CopyOnWriteArrayList()
    val ifRanges: MutableList<String?> = CopyOnWriteArrayList()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val range = request.headers["Range"]
        val ifRange = request.headers["If-Range"]
        ranges.add(range)
        ifRanges.add(ifRange)
        val body = data
        val match = range?.let { Regex("""bytes=(\d+)-(\d*)""").matchEntire(it) }
        val tagOk = ignoreIfRange || ifRange == null || ifRange == etag || ifRange == lastModified
        if (match == null || !honourRanges || !acceptRanges || !tagOk) return reply(200, body, 0, body.size - 1L).build()
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLongOrNull()?.coerceAtMost(body.size - 1L) ?: (body.size - 1L)
        if (start >= body.size) {
            return headers(MockResponse.Builder().code(416).setHeader("Content-Range", "bytes */${body.size}")).build()
        }
        val builder = if (shiftRanges) { // honestly labelled, but not the range that was asked for
            reply(206, body, start + 1, minOf(end + 1, body.size - 1L))
        } else {
            reply(206, body, start, end)
        }
        if (dropOnceAtRangeStart == start) {
            dropOnceAtRangeStart = null
            builder.onResponseBody(SocketEffect.ShutdownConnection)
        }
        return builder.build()
    }

    private fun reply(code: Int, body: ByteArray, start: Long, end: Long): MockResponse.Builder {
        val slice = body.copyOfRange(start.toInt(), end.toInt() + 1)
        val builder = headers(MockResponse.Builder().code(code).body(Buffer().write(slice)))
        if (code == 206) {
            builder.setHeader("Content-Range", "bytes $start-$end/${body.size}")
        }
        bytesPerSecond?.let { builder.throttleBody(it / 10, 100, TimeUnit.MILLISECONDS) }
        return builder
    }

    private fun headers(builder: MockResponse.Builder): MockResponse.Builder {
        builder.setHeader("Content-Type", "application/octet-stream")
        etag?.let { builder.setHeader("ETag", it) }
        lastModified?.let { builder.setHeader("Last-Modified", it) }
        if (acceptRanges) builder.setHeader("Accept-Ranges", "bytes")
        return builder
    }
}
