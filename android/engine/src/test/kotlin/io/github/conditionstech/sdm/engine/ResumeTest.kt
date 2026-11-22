package io.github.conditionstech.sdm.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import kotlin.random.Random

/**
 * Resume on one connection (ADR-0011), like the Windows app's tests/integration/test_resume.py:
 * only an exact 206 continues the partial file; anything else starts over.
 */
class ResumeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val data = Random(11).nextBytes(400_000) // below the split size: one connection
    private val files = RangeFileServer(data)
    private val server = MockWebServer()
    private lateinit var dir: File
    private val settings = DownloadSettings(backoffBaseMs = 10, chunkSize = 8 * 1024)
    private val downloader = HttpDownloader(NetworkPolicy(allowLoopback = true), settings)

    @Before
    fun setUp() {
        server.dispatcher = files
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        dir = folder.newFolder("downloads")
    }

    @After
    fun tearDown() = server.close()

    private val url get() = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).encodedPath("/file.bin").build().toString()

    private fun pause(): PartialDownload {
        files.bytesPerSecond = 400_000
        val partial = pauseMidway(downloader, url, dir, RecordingListener(pauseAfter = 100_000))
        files.bytesPerSecond = null
        assertTrue(partial.partFile.isFile)
        val kept = partial.partFile.length()
        assertTrue("kept $kept bytes", kept in 100_000 until data.size)
        files.ranges.clear()
        files.ifRanges.clear()
        return partial
    }

    private fun resume(partial: PartialDownload) = runBlocking {
        downloader.download(url, dir, resumeFrom = partial, keepPartial = true)
    }

    private fun partFiles() = dir.listFiles()!!.filter { it.name.endsWith(PartFiles.PART_SUFFIX) }

    @Test
    fun pauseThenResumeAsksOnlyForTheRestWithIfRange() {
        val partial = pause()
        val kept = partial.partFile.length()
        val result = resume(partial)
        assertArrayEquals(data, result.file.readBytes())
        assertEquals("file.bin", result.file.name)
        assertEquals(listOf("bytes=$kept-"), files.ranges)
        assertEquals(listOf("\"v1\""), files.ifRanges)
        assertTrue(result.verifiedComplete)
        assertTrue(partFiles().isEmpty())
    }

    @Test
    fun withoutVersionTagsTheLast64BytesAreChecked() {
        files.etag = null
        val partial = pause()
        val kept = partial.partFile.length()
        assertArrayEquals(data, resume(partial).file.readBytes())
        assertEquals(listOf("bytes=${kept - 64}-"), files.ranges)
        assertEquals(listOf<String?>(null), files.ifRanges)
    }

    @Test
    fun aChangedFileComesBackWhole() {
        val partial = pause()
        val changed = Random(12).nextBytes(380_000)
        files.data = changed
        files.etag = "\"v2\"" // If-Range no longer matches: the server sends the whole file
        assertArrayEquals(changed, resume(partial).file.readBytes())
        assertEquals(1, files.ranges.size)
    }

    @Test
    fun aChangedFileWithoutTagsFailsTheOverlapCheckAndStartsOver() {
        files.etag = null
        val partial = pause()
        val changed = Random(13).nextBytes(data.size) // same size, different bytes
        files.data = changed
        assertArrayEquals(changed, resume(partial).file.readBytes())
        assertEquals(2, files.ranges.size)
        assertNull(files.ranges[1]) // the second request asks for the whole file
    }

    @Test
    fun aServerThatIgnoresRangesStartsOverInsteadOfStitching() {
        val partial = pause()
        files.honourRanges = false
        assertArrayEquals(data, resume(partial).file.readBytes())
    }

    @Test
    fun aWrongContentRangeStartsOver() {
        val partial = pause()
        val kept = partial.partFile.length()
        files.shiftRanges = true
        assertArrayEquals(data, resume(partial).file.readBytes())
        assertEquals(listOf("bytes=$kept-", null), files.ranges) // straight back to a whole GET
    }

    @Test
    fun aServerIgnoringIfRangeCantStitchTwoVersions() {
        val partial = pause()
        val changed = Random(14).nextBytes(data.size)
        files.data = changed
        files.etag = "\"v2\""
        files.ignoreIfRange = true // sends the rest of the *new* file as a 206
        assertArrayEquals(changed, resume(partial).file.readBytes())
        assertNull(files.ranges.last())
    }

    @Test
    fun everythingAlreadyOnDiskFinishesOnA416() {
        val part = File(dir, "file.bin" + PartFiles.PART_SUFFIX)
        part.writeBytes(data)
        val partial = PartialDownload(part, "file.bin", data.size.toLong(), "\"v1\"", null, resumable = true)
        val result = resume(partial)
        assertArrayEquals(data, result.file.readBytes())
        assertEquals(listOf("bytes=${data.size}-"), files.ranges)
    }

    @Test
    fun aDroppedConnectionContinuesWhereItStopped() {
        val drop = RangeFileServer(data).apply { dropOnceAtRangeStart = null }
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            @Volatile var first = true
            override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                if (first) { // the plain GET stops halfway through the body
                    first = false
                    drop.ranges.add(null)
                    return mockwebserver3.MockResponse.Builder()
                        .body(okio.Buffer().write(data))
                        .setHeader("ETag", "\"v1\"")
                        .setHeader("Accept-Ranges", "bytes")
                        .onResponseBody(mockwebserver3.SocketEffect.ShutdownConnection)
                        .build()
                }
                return drop.dispatch(request)
            }
        }
        val result = runBlocking { downloader.download(url, dir) }
        assertArrayEquals(data, result.file.readBytes())
        assertEquals(2, drop.ranges.size)
        val start = drop.ranges[1]!!.removePrefix("bytes=").removeSuffix("-").toLong()
        assertTrue("continued from $start", start > 0)
    }

    @Test
    fun aPartialFileOutsideTheFolderIsNeverUsed() {
        val elsewhere = folder.newFolder("elsewhere")
        val outside = File(elsewhere, "file.bin" + PartFiles.PART_SUFFIX).apply { writeBytes(ByteArray(1000)) }
        val partial = PartialDownload(outside, "file.bin", data.size.toLong(), "\"v1\"", null, resumable = true)
        assertArrayEquals(data, resume(partial).file.readBytes())
        assertEquals(listOf<String?>(null), files.ranges) // a fresh download
        assertEquals(1000L, outside.length()) // untouched
    }

    @Test
    fun withoutKeepPartialAPauseDeletesThePartialFile() {
        files.bytesPerSecond = 400_000
        val listener = RecordingListener(pauseAfter = 50_000)
        val partial = runBlocking {
            val job = async(Dispatchers.Default) { downloader.download(url, dir, listener) }
            withTimeout(10_000) { listener.reachedPausePoint.await() }
            job.cancel()
            runCatching { job.await() }
            listener.lastPartial!!
        }
        assertTrue(!partial.partFile.exists())
    }
}
