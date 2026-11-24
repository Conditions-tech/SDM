package io.github.conditionstech.sdm.downloads

import io.github.conditionstech.sdm.engine.DownloadListener
import io.github.conditionstech.sdm.engine.HttpDownloader
import io.github.conditionstech.sdm.engine.PartialDownload
import io.github.conditionstech.sdm.engine.SdmException
import io.github.conditionstech.sdm.engine.Urls
import io.github.conditionstech.sdm.engine.FileNames
import io.github.conditionstech.sdm.media.ConversionException
import io.github.conditionstech.sdm.media.ConvertFormat
import io.github.conditionstech.sdm.media.VideoChoice
import io.github.conditionstech.sdm.media.VideoPageException
import io.github.conditionstech.sdm.media.PageInfo
import io.github.conditionstech.sdm.media.VideoPages
import io.github.conditionstech.sdm.player.Captions
import io.github.conditionstech.sdm.media.VideoTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The download list: the history, the engine and the screen's state in one place (the Android
 * counterpart of the Windows app's controller). No Android imports, so its tests run on the PC.
 *
 * Downloads stream into [workDir] (the app's own storage, as `.sdmpart` files) and, once
 * complete, are copied to Download/SDM by the [publisher]; the private copy is then deleted.
 * Nothing resumes by itself when the app starts: downloads that were running are shown as
 * paused (as on Windows). SDM never opens a downloaded file.
 */
class Downloads(
    private val store: HistoryStore,
    private val publisher: Publisher,
    private val workDir: File,
    private val downloader: HttpDownloader,
    private val scope: CoroutineScope,
    private val onActive: () -> Unit = {}, // a download started: keep the app running
    private val onFinished: (DownloadItem) -> Unit = {}, // completed or failed: tell the user
    private val maxRunning: Int = 3, // the queue limit (M14e): the rest wait as "Waiting"
    private val videoTools: VideoTools? = null, // video pages (M15b), when the app has them
    private val av1InMp4: Boolean = false, // Android 14+ can put AV1 video into MP4
    private val clock: () -> Long = System::currentTimeMillis,
    /** M18b: save the page's English subtitles with a video (a setting, read at each download). */
    private val wantSubtitles: () -> Boolean = { false },
    /** Receives a saved video's address (Download/SDM) and its subtitles. */
    private val onSubtitles: (savedUri: String, cues: List<Captions.Cue>) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val jobs = HashMap<Long, Job>()
    private val pausing = HashSet<Long>()
    private val videoProgress = HashMap<Long, VideoProgress>() // in memory only (ADR-0024)
    private val state = MutableStateFlow(loadHistory())

    /** The list, newest first. */
    val items: StateFlow<List<DownloadItem>> = state.asStateFlow()

    private fun loadHistory(): List<DownloadItem> = store.load().map { item ->
        if (item.active) item.copy(state = DownloadState.PAUSED).also(store::update) else item
    }.sortedByDescending { it.createdAt }

    /** Adds and starts a download. Throws [io.github.conditionstech.sdm.engine.InvalidUrlException] for a bad address. */
    fun add(url: String, videoChoice: VideoChoice? = null): Long {
        val clean = url.trim()
        Urls.validate(clean)
        val name = if (videoChoice == null) FileNames.fromUrl(clean) else null // a page's title comes later
        val item = DownloadItem(url = clean, fileName = name, createdAt = clock(), videoChoice = videoChoice)
        val id = store.insert(item)
        state.update { listOf(item.copy(id = id)) + it }
        pump()
        onActive()
        return id
    }

    fun pause(id: Long) = synchronized(lock) {
        val job = jobs[id]
        if (job != null) {
            pausing.add(id)
            job.cancel()
        } else if (find(id)?.state == DownloadState.QUEUED) {
            change(id, store = true) { it.copy(state = DownloadState.PAUSED) }
        }
    }

    /** Resumes a paused download, or tries a failed one again. */
    fun resume(id: Long) {
        val item = find(id) ?: return
        if (item.state == DownloadState.PAUSED || item.state == DownloadState.FAILED) {
            change(id, store = true) { it.copy(state = DownloadState.QUEUED, error = null) }
            pump()
            onActive()
        }
    }

    /** Stops a download and deletes what arrived. */
    fun cancel(id: Long) = synchronized(lock) {
        val job = jobs[id]
        if (job != null) {
            job.cancel() // not a pause: run() deletes the partial file
        } else {
            val item = find(id) ?: return
            if (item.state == DownloadState.PAUSED || item.state == DownloadState.FAILED ||
                item.state == DownloadState.QUEUED
            ) {
                deletePartial(item.partial)
                forgetVideo(id)
                change(id, store = true) { it.copy(state = DownloadState.CANCELLED, partial = null) }
            }
        }
    }

    /** Removes the entry from the list; a saved file in Download/SDM is kept. */
    fun remove(id: Long) {
        val item = find(id) ?: return
        synchronized(lock) { jobs[id] }?.cancel()
        if (item.state != DownloadState.COMPLETED) deletePartial(item.partial)
        forgetVideo(id)
        store.delete(id)
        state.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * The only place downloads start (as on Windows, ADR-0013): waiting entries start, oldest
     * first, while fewer than [maxRunning] are running. Called whenever a slot may have freed.
     */
    private fun pump() = synchronized(lock) {
        val waiting = state.value.filter { it.state == DownloadState.QUEUED && it.id !in jobs }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
        for (item in waiting) {
            if (jobs.size >= maxRunning) break
            start(item.id)
        }
    }

    /** Call with [lock] held. */
    private fun start(id: Long) {
        change(id, store = true) { it.copy(state = DownloadState.DOWNLOADING, error = null) }
        val job = scope.launch { run(id) }
        jobs[id] = job
        job.invokeOnCompletion {
            synchronized(lock) { jobs.remove(id) }
            pump() // a slot is free
        }
    }

    private suspend fun run(id: Long) {
        val item = find(id) ?: return
        val progress = ProgressThrottle(clock)
        val listener = object : DownloadListener {
            override fun onFileInfo(fileName: String, totalBytes: Long?) {
                change(id, store = true) { it.copy(fileName = fileName, totalBytes = totalBytes) }
            }

            override fun onProgress(receivedBytes: Long, totalBytes: Long?) {
                val speed = progress.sample(receivedBytes) ?: return // at most a few times a second
                change(id) { it.copy(receivedBytes = receivedBytes, totalBytes = totalBytes, bytesPerSecond = speed) }
            }

            override fun onPartial(partial: PartialDownload) {
                change(id, store = true) {
                    val received = partial.segments?.sumOf { s -> s.received } ?: it.receivedBytes
                    it.copy(partial = partial, receivedBytes = received)
                }
            }

            override fun onConnections(count: Int) {
                change(id) { it.copy(connections = count) }
            }
        }
        try {
            val result = if (item.videoChoice != null) {
                runVideo(id, item.url, item.videoChoice, listener)
            } else {
                downloader.download(item.url, workDir, listener, item.partial, keepPartial = true).let {
                    Finished(it.file, it.file.name, it.size, it.verifiedComplete, it.contentType)
                }
            }
            val saved = try {
                withContext(Dispatchers.IO) { publisher.publish(result.file, result.name, result.contentType) }
            } catch (e: IOException) {
                fail(id, "The file downloaded but could not be saved to Download/SDM.")
                return
            }
            result.file.delete()
            if (result.subtitles.isNotEmpty()) {
                try {
                    onSubtitles(saved.uri, result.subtitles)
                } catch (e: IOException) {
                    // The video is saved; subtitles are a bonus.
                }
            }
            forgetVideo(id)
            val done = change(id, store = true) {
                it.copy(
                    state = DownloadState.COMPLETED,
                    receivedBytes = result.size,
                    totalBytes = result.size,
                    savedName = saved.name,
                    savedUri = saved.uri,
                    unconfirmedSize = !result.verified,
                    partial = null,
                    connections = 0,
                    bytesPerSecond = null,
                )
            }
            done?.let(onFinished)
        } catch (e: CancellationException) {
            val paused = synchronized(lock) { pausing.remove(id) }
            if (paused) {
                change(id, store = true) { it.copy(state = DownloadState.PAUSED, connections = 0, bytesPerSecond = null) }
            } else {
                deletePartial(find(id)?.partial)
                forgetVideo(id)
                change(id, store = true) {
                    it.copy(state = DownloadState.CANCELLED, partial = null, connections = 0, bytesPerSecond = null)
                }
            }
            throw e
        } catch (e: SdmException) {
            fail(id, e.message ?: "The download failed.")
        } catch (e: VideoPageException) {
            fail(id, e.message ?: "SDM could not read this video page.")
        } catch (e: ConversionException) {
            fail(id, e.message ?: "The video could not be saved.")
        }
    }

    /** What a finished download hands to the publisher. */
    private class Finished(
        val file: File,
        val name: String,
        val size: Long,
        val verified: Boolean,
        val contentType: String?,
        val subtitles: List<Captions.Cue> = emptyList(),
    )

    /** A video page's streams across pauses (in memory: after a restart they start over). */
    private class VideoProgress(val count: Int) {
        val done = arrayOfNulls<File>(count)
        val partials = arrayOfNulls<PartialDownload>(count)
    }

    /**
     * A video page (M15b): yt-dlp lists the streams, SDM's own engine downloads them (network
     * policy, connections, resume), and Android joins or converts them into one file.
     */
    private suspend fun runVideo(id: Long, pageUrl: String, choice: VideoChoice, listener: DownloadListener): Finished {
        val tools = videoTools ?: throw VideoPageException("Video pages are not available in this version.")
        downloader.checkDestination(pageUrl) // yt-dlp reads the page: the same rules apply to it
        val info = VideoPages.parse(tools.extract(pageUrl))
        val plan = VideoPages.choose(info, choice, av1 = av1InMp4)
        // A title is not a path: "part 1/2" keeps both halves (sanitize alone would keep "2").
        val title = info.title.replace('/', '_').replace('\\', '_')
        val name = FileNames.sanitize(title, maxBytes = 180) + "." + choice.extension
        change(id, store = true) { it.copy(fileName = name) }
        val streams = plan.streams
        val progress = synchronized(lock) {
            videoProgress[id]?.takeIf { it.count == streams.size } ?: VideoProgress(streams.size).also { videoProgress[id] = it }
        }
        val total = if (streams.all { it.size > 0 }) streams.sumOf { it.size } else null
        var offset = 0L
        val files = streams.mapIndexed { index, stream ->
            val already = progress.done[index]?.takeIf { it.isFile }
            if (already != null) {
                offset += already.length()
                return@mapIndexed already
            }
            val base = offset
            val result = downloader.download(
                stream.url,
                workDir,
                object : DownloadListener {
                    override fun onProgress(receivedBytes: Long, totalBytes: Long?) =
                        listener.onProgress(base + receivedBytes, total)

                    override fun onPartial(partial: PartialDownload) {
                        progress.partials[index] = partial
                    }

                    override fun onConnections(count: Int) = listener.onConnections(count)
                },
                resumeFrom = progress.partials[index],
                keepPartial = true,
                requestHeaders = stream.headers,
            )
            progress.done[index] = result.file
            offset += result.size
            result.file
        }
        val output = File(workDir, "video-$id-${clock()}.${choice.extension}.sdmpart")
        when {
            choice.audioOnly -> tools.audio(files.last(), if (choice == VideoChoice.MP3) ConvertFormat.MP3 else ConvertFormat.M4A, output)
            plan.audio != null -> tools.merge(files[0], files[1], output)
            else -> files[0].copyTo(output)
        }
        val subtitles = if (choice.audioOnly || !wantSubtitles()) emptyList() else captions(id, info)
        return Finished(output, name, output.length(), verified = true, contentType = null, subtitles = subtitles)
    }

    /**
     * The page's English subtitles (M18b), fetched by the engine (network policy) and read as
     * untrusted text. Best effort: any problem just means no subtitles.
     */
    private suspend fun captions(id: Long, info: PageInfo): List<Captions.Cue> {
        val caption = VideoPages.chooseCaption(info.captions) ?: return emptyList()
        val directory = File(workDir, "captions-$id").apply { mkdirs() }
        return try {
            val file = downloader.download(caption.url, directory, object : DownloadListener {}).file
            if (file.length() > Captions.MAX_BYTES) emptyList() else Captions.parse(file.readText(), caption.ext)
        } catch (e: SdmException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Deletes a video page's stream files and partial files (SDM's own only). */
    private fun forgetVideo(id: Long) {
        val progress = synchronized(lock) { videoProgress.remove(id) } ?: return
        progress.done.filterNotNull().filter { it.parentFile == workDir }.forEach { it.delete() }
        progress.partials.forEach { deletePartial(it) }
    }

    private fun fail(id: Long, message: String) {
        val failed = change(id, store = true) {
            // The engine keeps the partial file only when the server can resume.
            val keep = it.partial?.takeIf { p -> p.partFile.isFile }
            it.copy(state = DownloadState.FAILED, error = message, partial = keep, connections = 0, bytesPerSecond = null)
        }
        failed?.let(onFinished)
    }

    /** Deletes a partial file, but only SDM's own: the path comes from the history. */
    private fun deletePartial(partial: PartialDownload?) {
        val file = partial?.partFile ?: return
        if (HttpDownloader.isOwnPartFile(file, workDir)) file.delete()
    }

    private fun find(id: Long): DownloadItem? = state.value.firstOrNull { it.id == id }

    private fun change(id: Long, store: Boolean = false, edit: (DownloadItem) -> DownloadItem): DownloadItem? {
        var changed: DownloadItem? = null
        synchronized(lock) {
            state.update { list -> list.map { if (it.id == id) edit(it).also { new -> changed = new } else it } }
            if (store) changed?.let(this.store::update)
        }
        return changed
    }
}

/** Limits screen updates to about four a second and works out the speed between them. */
internal class ProgressThrottle(private val clock: () -> Long, private val everyMs: Long = 250) {
    private var lastTime = -1L
    private var lastBytes = 0L
    private var speed: Double? = null

    fun sample(bytes: Long): Double? {
        val now = clock()
        if (lastTime < 0) {
            lastTime = now
            lastBytes = bytes
            return 0.0
        }
        val elapsed = now - lastTime
        if (elapsed < everyMs) return null
        val current = (bytes - lastBytes).coerceAtLeast(0) * 1000.0 / elapsed
        speed = speed?.let { it * 0.7 + current * 0.3 } ?: current // smoothed, so it doesn't jump
        lastTime = now
        lastBytes = bytes
        return speed
    }
}
