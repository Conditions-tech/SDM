package io.github.conditionstech.sdm.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Records what the engine reports, for assertions. */
class RecordingListener(private val pauseAfter: Long? = null) : DownloadListener {
    @Volatile var lastPartial: PartialDownload? = null
    @Volatile var lastReceived = 0L
    val maxConnections = AtomicInteger(0)
    val connectionCounts: MutableList<Int> = CopyOnWriteArrayList()
    val reachedPausePoint = CompletableDeferred<Unit>()

    override fun onPartial(partial: PartialDownload) {
        lastPartial = partial
    }

    override fun onProgress(receivedBytes: Long, totalBytes: Long?) {
        lastReceived = receivedBytes
        if (pauseAfter != null && receivedBytes >= pauseAfter) reachedPausePoint.complete(Unit)
    }

    override fun onConnections(count: Int) {
        connectionCounts.add(count)
        maxConnections.accumulateAndGet(count) { a, b -> maxOf(a, b) }
    }
}

/**
 * Starts a download, cancels it once [listener] has seen enough bytes (a pause, as the app will
 * do it: keepPartial = true), and returns what is needed to resume.
 */
fun pauseMidway(downloader: HttpDownloader, url: String, dir: File, listener: RecordingListener): PartialDownload =
    runBlocking {
        val job = async(Dispatchers.Default) { downloader.download(url, dir, listener, keepPartial = true) }
        withTimeout(10_000) { listener.reachedPausePoint.await() }
        job.cancel()
        try {
            job.await()
            error("the download finished before the pause")
        } catch (expected: CancellationException) {
        }
        listener.lastPartial!!
    }
