package io.github.conditionstech.sdm.downloads

import io.github.conditionstech.sdm.engine.DownloadSettings
import io.github.conditionstech.sdm.engine.HttpDownloader
import io.github.conditionstech.sdm.engine.InvalidUrlException
import io.github.conditionstech.sdm.engine.NetworkPolicy
import io.github.conditionstech.sdm.engine.PartialDownload
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

/** The download list's controller with a list for a history, a folder for Download/SDM and a local server. */
class DownloadsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val data = Random(31).nextBytes(300_000)
    private val server = MockWebServer()
    private val ranges = CopyOnWriteArrayList<String?>()
    @Volatile private var slow = false
    private lateinit var work: File
    private lateinit var saved: File
    private val store = MemoryStore()
    private val finished = CopyOnWriteArrayList<DownloadItem>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeCalls = 0

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/missing") return MockResponse.Builder().code(404).build()
                val range = request.headers["Range"]
                ranges.add(range)
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                val builder = MockResponse.Builder()
                    .code(if (range != null) 206 else 200)
                    .setHeader("ETag", "\"v1\"")
                    .setHeader("Accept-Ranges", "bytes")
                    .body(Buffer().write(data.copyOfRange(start, data.size)))
                if (range != null) builder.setHeader("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                if (slow) builder.throttleBody(30_000, 100, TimeUnit.MILLISECONDS)
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

    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun downloads(maxRunning: Int = 3) = Downloads(
        store,
        FolderPublisher(saved),
        work,
        HttpDownloader(NetworkPolicy(allowLoopback = true), DownloadSettings(backoffBaseMs = 10, chunkSize = 8 * 1024)),
        scope,
        onActive = { activeCalls += 1 },
        onFinished = { finished.add(it) },
        maxRunning = maxRunning,
    )

    private fun Downloads.waitFor(id: Long, predicate: (DownloadItem) -> Boolean): DownloadItem = runBlocking {
        withTimeout(10_000) { items.first { list -> list.any { it.id == id && predicate(it) } } }.first { it.id == id }
    }

    @Test
    fun aDownloadIsSavedToTheDownloadsFolderAndTheWorkCopyRemoved() {
        val list = downloads()
        val id = list.add(url("/files/report.pdf"))
        val done = list.waitFor(id) { it.state == DownloadState.COMPLETED }
        assertEquals("report.pdf", done.savedName)
        assertArrayEquals(data, File(saved, "report.pdf").readBytes())
        assertTrue(work.listFiles()!!.isEmpty())
        assertEquals(DownloadState.COMPLETED, store.rows[id]!!.state)
        assertFalse(done.unconfirmedSize)
        assertEquals(listOf(id), finished.map { it.id })
        assertEquals(1, activeCalls)
    }

    @Test
    fun aSecondCopyIsNumberedNotOverwritten() {
        val list = downloads()
        val first = list.add(url("/a.bin"))
        list.waitFor(first) { it.state == DownloadState.COMPLETED }
        val second = list.add(url("/a.bin"))
        assertEquals("a (1).bin", list.waitFor(second) { it.state == DownloadState.COMPLETED }.savedName)
    }

    @Test
    fun pauseKeepsWhatArrivedAndResumeAsksForTheRest() {
        slow = true
        val list = downloads()
        val id = list.add(url("/big.bin"))
        list.waitFor(id) { it.receivedBytes > 50_000 }
        list.pause(id)
        val paused = list.waitFor(id) { it.state == DownloadState.PAUSED }
        val kept = paused.partial!!.partFile.length()
        assertTrue(kept > 0)
        assertEquals(DownloadState.PAUSED, store.rows[id]!!.state)
        slow = false
        list.resume(id)
        list.waitFor(id) { it.state == DownloadState.COMPLETED }
        assertArrayEquals(data, File(saved, "big.bin").readBytes())
        assertEquals("bytes=$kept-", ranges.last())
    }

    @Test
    fun cancelDeletesWhatArrived() {
        slow = true
        val list = downloads()
        val id = list.add(url("/big.bin"))
        list.waitFor(id) { it.receivedBytes > 30_000 }
        list.cancel(id)
        list.waitFor(id) { it.state == DownloadState.CANCELLED }
        assertTrue(work.listFiles()!!.isEmpty())
        assertTrue(saved.listFiles()!!.isEmpty())
    }

    @Test
    fun aFailureShowsTheEnginesMessage() {
        val list = downloads()
        val id = list.add(url("/missing"))
        val failed = list.waitFor(id) { it.state == DownloadState.FAILED }
        assertTrue(failed.error!!, failed.error!!.contains("not found"))
        assertEquals(listOf(DownloadState.FAILED), finished.map { it.state })
    }

    @Test
    fun badAddressesAreRefusedBeforeAnythingIsAdded() {
        val list = downloads()
        assertThrows(InvalidUrlException::class.java) { list.add("file:///sdcard/secret") }
        assertTrue(list.items.value.isEmpty())
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun downloadsThatWereRunningComeBackPausedNotStarted() {
        val part = File(work, "big.bin.sdmpart").apply { writeBytes(data.copyOfRange(0, 1000)) }
        val partial = PartialDownload(part, "big.bin", data.size.toLong(), "\"v1\"", null, resumable = true)
        store.insert(DownloadItem(url = url("/big.bin"), state = DownloadState.DOWNLOADING, partial = partial))
        val list = downloads()
        val item = list.items.value.single()
        assertEquals(DownloadState.PAUSED, item.state)
        assertEquals(0, server.requestCount)
        list.resume(item.id)
        list.waitFor(item.id) { it.state == DownloadState.COMPLETED }
        assertEquals("bytes=1000-", ranges.single())
        assertArrayEquals(data, File(saved, "big.bin").readBytes())
    }

    @Test
    fun removeForgetsTheEntryButKeepsTheSavedFile() {
        val list = downloads()
        val id = list.add(url("/keep.txt"))
        list.waitFor(id) { it.state == DownloadState.COMPLETED }
        list.remove(id)
        assertTrue(list.items.value.isEmpty())
        assertNull(store.rows[id])
        assertTrue(File(saved, "keep.txt").isFile)
    }

    @Test
    fun aPartialFileFromTheHistoryOutsideTheWorkFolderIsNeverDeleted() {
        val outside = folder.newFile("precious.sdmpart")
        val partial = PartialDownload(outside, "x.bin", 10, null, null, resumable = false)
        store.insert(DownloadItem(url = url("/x.bin"), state = DownloadState.PAUSED, partial = partial))
        val list = downloads()
        list.cancel(list.items.value.single().id)
        assertTrue(outside.isFile)
    }

    @Test
    fun theQueueLimitKeepsTheRestWaitingAndStartsThemInOrder() {
        slow = true
        val list = downloads(maxRunning = 2)
        val ids = (1..4).map { list.add(url("/q$it.bin")) }
        list.waitFor(ids[1]) { it.state == DownloadState.DOWNLOADING }
        val states = list.items.value.associate { it.id to it.state }
        assertEquals(DownloadState.DOWNLOADING, states[ids[0]])
        assertEquals(DownloadState.QUEUED, states[ids[2]])
        assertEquals(DownloadState.QUEUED, states[ids[3]])
        // Pausing a running one frees its slot: the oldest waiting one starts.
        list.pause(ids[0])
        list.waitFor(ids[2]) { it.state == DownloadState.DOWNLOADING }
        assertEquals(DownloadState.QUEUED, list.items.value.first { it.id == ids[3] }.state)
        slow = false
        for (id in ids.drop(1)) list.waitFor(id) { it.state == DownloadState.COMPLETED }
        assertTrue(list.items.value.count { it.state == DownloadState.DOWNLOADING } == 0)
        assertEquals(DownloadState.PAUSED, list.items.value.first { it.id == ids[0] }.state)
    }

    @Test
    fun aWaitingDownloadCanBeCancelled() {
        slow = true
        val list = downloads(maxRunning = 1)
        list.add(url("/first.bin"))
        val waiting = list.add(url("/second.bin"))
        list.cancel(waiting)
        assertEquals(DownloadState.CANCELLED, list.items.value.first { it.id == waiting }.state)
    }

    @Test
    fun progressIsThrottledAndSpeedSmoothed() {
        var now = 0L
        val throttle = ProgressThrottle({ now })
        assertEquals(0.0, throttle.sample(0)!!, 0.0)
        now = 100
        assertNull(throttle.sample(10_000)) // too soon
        now = 1000
        assertEquals(100_000.0, throttle.sample(100_000)!!, 0.1)
    }
}

/** The history as a map, as the SQLite store keeps it (live fields dropped). */
class MemoryStore : HistoryStore {
    val rows = java.util.concurrent.ConcurrentHashMap<Long, DownloadItem>()
    private var next = 1L

    override fun load() = rows.values.toList()

    @Synchronized
    override fun insert(item: DownloadItem): Long {
        val id = next++
        rows[id] = item.copy(id = id)
        return id
    }

    override fun update(item: DownloadItem) {
        rows[item.id] = item.copy(connections = 0, bytesPerSecond = null)
    }

    override fun delete(id: Long) {
        rows.remove(id)
    }
}

/** Download/SDM as a folder: numbered names, never overwriting, as MediaStore does. */
class FolderPublisher(private val folder: File) : Publisher {
    override fun publish(file: File, name: String, contentType: String?): Publisher.Published {
        val target = io.github.conditionstech.sdm.engine.PartFiles.finish(
            file.copyTo(File(file.parentFile, "copy-of-" + file.name)),
            folder,
            name,
        )
        return Publisher.Published(target.name, target.toURI().toString())
    }
}
