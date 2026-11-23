package io.github.conditionstech.sdm.downloads

import io.github.conditionstech.sdm.engine.DownloadSettings
import io.github.conditionstech.sdm.engine.HttpDownloader
import io.github.conditionstech.sdm.engine.NetworkPolicy
import io.github.conditionstech.sdm.media.ConvertFormat
import io.github.conditionstech.sdm.media.VideoChoice
import io.github.conditionstech.sdm.media.VideoPageException
import io.github.conditionstech.sdm.media.VideoTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Video pages in the download list (M15b) with a pretend yt-dlp: it "finds" a video stream and
 * an audio stream on a local server, which SDM's own engine then downloads.
 */
class VideoDownloadsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val video = Random(41).nextBytes(200_000)
    private val audio = Random(42).nextBytes(60_000)
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var slow = false
    private val extracted = CopyOnWriteArrayList<String>()
    private lateinit var work: File
    private lateinit var saved: File
    private val store = MemoryStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val data = when (request.url.encodedPath) {
                    "/stream/video" -> video
                    "/stream/audio" -> audio
                    else -> return MockResponse.Builder().code(404).build()
                }
                val range = request.headers["Range"]
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                val builder = MockResponse.Builder()
                    .code(if (range != null) 206 else 200)
                    .setHeader("ETag", "\"s1\"")
                    .setHeader("Accept-Ranges", "bytes")
                    .body(Buffer().write(data.copyOfRange(start, data.size)))
                if (range != null) builder.setHeader("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                if (slow) builder.throttleBody(20_000, 100, TimeUnit.MILLISECONDS)
                return builder.build()
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        work = folder.newFolder("work")
        saved = folder.newFolder("Download-SDM")
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private val base get() = "http://127.0.0.1:${server.port}"

    /** Pretend yt-dlp and Android: lists two streams; joining = video bytes then audio bytes. */
    private val tools = object : VideoTools {
        override suspend fun extract(pageUrl: String): String {
            extracted.add(pageUrl)
            if (pageUrl.endsWith("/nothing")) throw VideoPageException("Unsupported URL: $pageUrl")
            fun stream(id: String, v: String, a: String, size: Int) =
                """{"id":"$id","url":"$base/stream/$id","ext":"${if (v == "none") "m4a" else "mp4"}","vcodec":"$v","acodec":"$a",""" +
                    """"height":${if (v == "none") 0 else 720},"tbr":1,"size":$size,"headers":{"User-Agent":"PageUA/1","Cookie":"no"}}"""
            return """{"title":"My clip: part 1/2","streams":[${stream("video", "avc1.4d401f", "none", video.size)},${stream("audio", "none", "mp4a.40.2", audio.size)}]}"""
        }

        override suspend fun merge(video: File, audio: File, output: File) = output.writeBytes(video.readBytes() + audio.readBytes())

        override suspend fun audio(source: File, format: ConvertFormat, output: File) =
            output.writeBytes(format.name.toByteArray() + source.readBytes())
    }

    private fun downloads(policy: NetworkPolicy = NetworkPolicy(allowLoopback = true)) = Downloads(
        store, FolderPublisher(saved), work,
        HttpDownloader(policy, DownloadSettings(backoffBaseMs = 10, chunkSize = 8 * 1024)),
        scope, videoTools = tools,
    )

    private fun Downloads.waitFor(id: Long, predicate: (DownloadItem) -> Boolean): DownloadItem = runBlocking {
        withTimeout(10_000) { items.first { list -> list.any { it.id == id && predicate(it) } } }.first { it.id == id }
    }

    @Test
    fun aVideoIsDownloadedAsTwoStreamsJoinedAndNamedAfterThePage() {
        val list = downloads()
        val id = list.add("$base/watch?v=1", VideoChoice.P720)
        val done = list.waitFor(id) { it.state == DownloadState.COMPLETED }
        assertEquals("My clip_ part 1_2.mp4", done.savedName) // the title, made safe
        assertArrayEquals(video + audio, File(saved, done.savedName!!).readBytes())
        assertTrue(work.listFiles()!!.isEmpty()) // stream files and the joined file are gone
        val streamRequests = requests.filter { it.url.encodedPath.startsWith("/stream/") }
        assertTrue(streamRequests.all { it.headers["User-Agent"] == "PageUA/1" && it.headers["Cookie"] == null })
        assertEquals(VideoChoice.P720, store.rows[id]!!.videoChoice)
    }

    @Test
    fun mp3TakesTheAudioStreamOnly() {
        val list = downloads()
        val id = list.add("$base/watch?v=2", VideoChoice.MP3)
        val done = list.waitFor(id) { it.state == DownloadState.COMPLETED }
        assertEquals("My clip_ part 1_2.mp3", done.savedName)
        assertArrayEquals("MP3".toByteArray() + audio, File(saved, done.savedName!!).readBytes())
        assertTrue(requests.none { it.url.encodedPath == "/stream/video" })
    }

    @Test
    fun pauseAndResumeContinueTheStreams() {
        slow = true
        val list = downloads()
        val id = list.add("$base/watch?v=3", VideoChoice.P720)
        list.waitFor(id) { it.receivedBytes > 50_000 }
        list.pause(id)
        list.waitFor(id) { it.state == DownloadState.PAUSED }
        slow = false
        list.resume(id)
        val done = list.waitFor(id) { it.state == DownloadState.COMPLETED }
        assertArrayEquals(video + audio, File(saved, done.savedName!!).readBytes())
        val ranged = requests.filter { it.url.encodedPath == "/stream/video" && it.headers["Range"] != null }
        assertTrue("the video stream continued with a Range request", ranged.isNotEmpty())
    }

    @Test
    fun aPageOnTheLocalNetworkIsRefusedBeforeYtDlpReadsIt() {
        val list = downloads(NetworkPolicy())
        val id = list.add("http://192.168.1.20/watch?v=1", VideoChoice.P720)
        val failed = list.waitFor(id) { it.state == DownloadState.FAILED }
        assertTrue(failed.error!!, failed.error!!.contains("local or private network"))
        assertTrue(extracted.isEmpty())
    }

    @Test
    fun ytDlpsReasonIsShown() {
        val list = downloads()
        val id = list.add("$base/nothing", VideoChoice.P1080)
        assertEquals("Unsupported URL: $base/nothing", list.waitFor(id) { it.state == DownloadState.FAILED }.error)
    }

    @Test
    fun cancelDeletesTheStreamsSoFar() {
        slow = true
        val list = downloads()
        val id = list.add("$base/watch?v=4", VideoChoice.P720)
        list.waitFor(id) { it.receivedBytes > 30_000 }
        list.cancel(id)
        list.waitFor(id) { it.state == DownloadState.CANCELLED }
        runBlocking { kotlinx.coroutines.delay(200) }
        assertTrue(work.listFiles()!!.toList().toString(), work.listFiles()!!.isEmpty())
        assertTrue(saved.listFiles()!!.isEmpty())
    }
}
