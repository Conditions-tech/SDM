package io.github.conditionstech.sdm.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.min
import kotlin.math.pow

/**
 * Downloading one file over several connections at once (M14c, ADR-0012, the Windows design).
 *
 * The first connection is the ordinary request: it starts at byte 0 while the extra
 * connections ask for their own parts with `Range: bytes=a-b` and `If-Range`. A part is
 * accepted only from a `206` whose Content-Range is exactly `a-b/total`, with the same version
 * tags. Every part writes into one pre-sized file at its own offset.
 *
 * Fallback: if an extra connection's first reply can't be trusted, or it can't connect, while
 * the first connection is still open, the extras stop and the first one, whose reply is the
 * whole file, carries on to the end. To keep that possible, the first connection waits at the
 * end of its own part until every extra part is confirmed.
 *
 * Once all parts are confirmed, a dropped part retries its own remaining range; the others keep
 * going. Dynamic splitting: whenever a connection is free, the part with the most bytes left
 * gives its second half to a new connection, if both halves keep at least `minPartSize`. A reply
 * that can't be trusted after that point (the file changed) raises [StartOver].
 *
 * Unlike Windows (one asyncio thread), the parts run on several threads, so every change to
 * the parts map, and every write, happens under [lock]: a split can never move a part's end
 * while that part is writing.
 */
internal class SegmentedDownload(
    private val request: suspend (Map<String, String>) -> Opened,
    private val partFile: File,
    private val total: Long,
    private val ifRange: String,
    private val etag: String?,
    private val lastModified: String?,
    segments: List<Segment>,
    private val settings: DownloadSettings,
    private val listener: DownloadListener,
    private val onSnapshot: (List<Segment>) -> Unit,
) {
    /** One segment while it downloads (a mutable copy of a [Segment]). */
    private class Part(val start: Long, var end: Long, var received: Long) {
        val nextByte get() = start + received
        val done get() = nextByte > end
        fun frozen() = Segment(start, end, received)
    }

    private val lock = Any()
    private var parts = segments.map { Part(it.start, it.end, it.received) }.toMutableList()
    private val jobs = LinkedHashMap<Job, Part>()
    private var first: Part? = null
    private var firstJob: Job? = null
    private var firstAlive = false
    private var fallenBack = false
    private var unconfirmed = 0
    private val confirmed = CompletableDeferred<Unit>()
    private var reportedConnections = 0
    private lateinit var scope: CoroutineScope
    private lateinit var channel: FileChannel

    private val received: Long get() = synchronized(lock) { parts.sumOf { it.received } }

    fun snapshot(): List<Segment> = synchronized(lock) { parts.sortedBy { it.start }.map { it.frozen() } }

    /** Downloads every unfinished part. Throws [StartOver] or [DownloadException] on failure. */
    suspend fun run(firstReply: Opened?) {
        FileChannel.open(partFile.toPath(), StandardOpenOption.WRITE).use { file ->
            channel = file
            try {
                coroutineScope {
                    val saver = launch { saveRegularly() }
                    try {
                        runParts(firstReply)
                    } finally {
                        saver.cancel() // the scope still waits for a disk sync in progress
                    }
                }
            } finally {
                save() // also on pause or cancel: what arrived must be resumable
            }
        }
        if (synchronized(lock) { !parts.all { it.done } }) {
            throw DownloadException(interruptedMessage(received, total), retryable = true)
        }
    }

    // ----- coordinating the parts -----

    private suspend fun runParts(firstReply: Opened?) = coroutineScope {
        scope = this
        synchronized(lock) {
            for (part in parts.toList()) {
                if (part.done) continue
                var reply: Opened? = null
                if (firstReply != null && first == null && part.nextByte == 0L) {
                    first = part
                    reply = firstReply
                }
                val job = start(part, reply)
                if (reply != null) {
                    firstJob = job
                    firstAlive = true
                }
            }
            unconfirmed = jobs.keys.count { it !== firstJob }
            // With no whole-file first connection (a resume) there is nothing to fall back to,
            // so there is nothing to wait for either.
            if (unconfirmed == 0 || firstReply == null) confirmed.complete(Unit)
            help(finishing = false) // a resume may have fewer unfinished parts than connections
        }
    }

    private suspend fun runPart(part: Part, reply: Opened?) {
        var opened = reply
        var isFirst = reply != null
        var confirmedHere = isFirst // the first connection's reply needs no confirmation
        var attempt = 0
        while (!isDone(part)) {
            try {
                if (opened == null) {
                    opened = try {
                        requestPart(part)
                    } catch (e: NotTrusted) {
                        if (!confirmedHere && canFallBack()) {
                            fallBack(e.message.orEmpty())
                            return
                        }
                        throw StartOver(e.message.orEmpty())
                    }
                    if (!confirmedHere) {
                        confirmedHere = true
                        confirm()
                    }
                }
                val current = opened
                opened = null
                try {
                    cancellable(current.call) { stream(part, current.response, mayWait = isFirst) }
                } finally {
                    current.response.close()
                }
                if (isDone(part)) {
                    synchronized(lock) { help(finishing = true) } // this connection is free
                    return
                }
                throw DownloadException(interruptedMessage(received, total), retryable = true)
            } catch (e: DownloadException) {
                currentCoroutineContext().ensureActive() // a cancel is not a failure
                if (!e.retryable) throw e
                opened?.response?.close()
                opened = null
                if (isFirst) { // the whole-file reply is gone: no more fallback from here
                    isFirst = false
                    synchronized(lock) { firstAlive = false }
                }
                if (!confirmedHere && canFallBack()) {
                    fallBack("an extra connection failed")
                    return
                }
                attempt += 1
                if (attempt >= settings.maxAttempts) {
                    throw DownloadException(interruptedMessage(received, total), retryable = true)
                }
                val wait = (settings.backoffBaseMs * 2.0.pow(attempt - 1)).toLong()
                delay(min(wait, settings.maxRetryAfterMs))
            }
        }
    }

    private fun isDone(part: Part) = synchronized(lock) { part.done }

    /** Call with [lock] held. */
    private fun start(part: Part, reply: Opened?): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { runPart(part, reply) }
        jobs[job] = part
        job.invokeOnCompletion { synchronized(lock) { reportConnections() } }
        job.start()
        reportConnections()
        return job
    }

    /** Gives free connections the second half of the part with the most bytes left. Call with [lock] held. */
    private fun help(finishing: Boolean) {
        if (!settings.dynamicSplitting || fallenBack || !confirmed.isCompleted) return
        // Only once every part is confirmed, so fallback stays simple.
        var active = jobs.keys.count { it.isActive } - if (finishing) 1 else 0
        while (active < settings.maxConnections) {
            val candidates = parts.filter { !it.done }
            val (index, middle) = Segments.pickSplit(
                candidates.map { it.nextByte to it.end },
                settings.minPartSize,
            ) ?: break
            val old = candidates[index]
            val new = Part(middle, old.end, 0)
            old.end = middle - 1 // the old connection stops there (it is at least minPartSize away)
            parts.add(new)
            start(new, null)
            active += 1
        }
    }

    /** Call with [lock] held. */
    private fun reportConnections() {
        // A part that just finished hands its connection to a new part a moment before its own
        // job ends: it is not counted twice.
        val count = jobs.count { (job, part) -> job.isActive && !part.done }
        if (count > 0 && count != reportedConnections) {
            reportedConnections = count
            listener.onConnections(count)
        }
    }

    private fun canFallBack(): Boolean = synchronized(lock) {
        // Possible only while the first connection still holds the whole-file reply, which it
        // keeps open until every extra part is confirmed.
        firstAlive && !fallenBack && !confirmed.isCompleted
    }

    private suspend fun fallBack(reason: String) {
        val current = currentCoroutineContext().job
        synchronized(lock) {
            val whole = first ?: throw StartOver(reason)
            fallenBack = true
            whole.end = total - 1 // the first connection now owns the whole file
            parts = mutableListOf(whole)
            for (job in jobs.keys) {
                if (job !== firstJob && job !== current && job.isActive) job.cancel()
            }
            confirmed.complete(Unit) // wakes the first connection if it waits at its boundary
        }
    }

    private fun confirm() = synchronized(lock) {
        unconfirmed -= 1
        if (unconfirmed <= 0) confirmed.complete(Unit)
    }

    // ----- one connection -----

    private suspend fun requestPart(part: Part): Opened {
        // Remember exactly what was asked: while the request is in flight, dynamic splitting may
        // shrink `part.end`, and the reply must be checked against the request.
        val (firstByte, lastByte) = synchronized(lock) { part.nextByte to part.end }
        val opened = request(mapOf("Range" to "bytes=$firstByte-$lastByte", "If-Range" to ifRange))
        val status = opened.response.code
        if (status == 408 || status == 429 || status in 500..599) { // temporary: retry this part
            opened.response.close()
            throw DownloadException("The server reported a temporary problem ($status).", retryable = true)
        }
        try {
            if (status != 206) throw NotTrusted("the server answered $status to a range request")
            val range = Segments.parseContentRange(opened.response.header("Content-Range"))
            if (range != Triple(firstByte, lastByte, total)) {
                throw NotTrusted("the server sent a different range than requested")
            }
            if (!tagsMatch(opened.response, etag, lastModified)) throw NotTrusted("the file's version tags changed")
        } catch (e: NotTrusted) {
            opened.response.close()
            throw e
        }
        return opened
    }

    private suspend fun stream(part: Part, response: Response, mayWait: Boolean) {
        val source = response.body.byteStream()
        val buffer = ByteArray(settings.chunkSize)
        while (true) {
            val read = try {
                source.read(buffer)
            } catch (e: IOException) {
                throw DownloadException(interruptedMessage(received, total), retryable = true)
            }
            if (read < 0) return
            var offset = 0
            while (offset < read) {
                val wrote = synchronized(lock) {
                    val room = part.end + 1 - part.nextByte
                    if (room <= 0) {
                        null
                    } else {
                        val count = min(room, (read - offset).toLong()).toInt()
                        write(part.nextByte, buffer, offset, count)
                        part.received += count
                        count
                    }
                }
                if (wrote == null) {
                    if (mayWait && !confirmed.isCompleted) {
                        confirmed.await() // until the extras are confirmed
                        continue // after a fallback, `part.end` has moved to the file's end
                    }
                    return // this part is complete: stop reading (the connection closes)
                }
                offset += wrote
                listener.onProgress(received, total)
            }
        }
    }

    /** Call with [lock] held. */
    private fun write(position: Long, data: ByteArray, offset: Int, count: Int) {
        val buffer = ByteBuffer.wrap(data, offset, count)
        var at = position
        fileOperation(partFile.parentFile) {
            while (buffer.hasRemaining()) at += channel.write(buffer, at)
        }
    }

    // ----- saving the parts map -----

    /**
     * Regularly: make the data durable, then report the parts map. The map is captured
     * *before* the sync, so it only ever claims bytes that are already on disk.
     */
    private suspend fun saveRegularly() {
        while (true) {
            delay(settings.snapshotEveryMs)
            val snapshot = snapshot()
            withContext(Dispatchers.IO) { runCatching { channel.force(false) } }
            onSnapshot(snapshot)
        }
    }

    private fun save() {
        val snapshot = snapshot()
        runCatching { channel.force(false) }
        onSnapshot(snapshot)
    }
}
