package io.github.conditionstech.sdm.engine

import kotlinx.coroutines.runBlocking
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
import java.io.RandomAccessFile
import java.net.InetAddress
import kotlin.random.Random

/**
 * Split downloads (ADR-0012), like the Windows app's test_segmented.py and test_dynamic_split.py.
 * Sizes are scaled down: split from 256 KiB, parts of at least 64 KiB, up to 4 connections.
 */
class SegmentedDownloadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val kib = 1024L
    private val data = Random(21).nextBytes(1024 * 1024)
    private val files = RangeFileServer(data)
    private val server = MockWebServer()
    private lateinit var dir: File
    private val settings = DownloadSettings(
        backoffBaseMs = 10,
        chunkSize = 16 * 1024,
        maxConnections = 4,
        minSplitSize = 256 * kib,
        minPartSize = 64 * kib,
        snapshotEveryMs = 100,
    )
    private val policy = NetworkPolicy(allowLoopback = true)

    @Before
    fun setUp() {
        server.dispatcher = files
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        dir = folder.newFolder("downloads")
    }

    @After
    fun tearDown() = server.close()

    private val url get() = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).encodedPath("/big.bin").build().toString()

    private fun download(
        settings: DownloadSettings = this.settings,
        listener: DownloadListener = RecordingListener(),
        resumeFrom: PartialDownload? = null,
    ) = runBlocking { HttpDownloader(policy, settings).download(url, dir, listener, resumeFrom, keepPartial = true) }

    private fun rangeStarts() = files.ranges.filterNotNull().map { it.removePrefix("bytes=").substringBefore('-').toLong() }

    private fun partFiles() = dir.listFiles()!!.filter { it.name.endsWith(PartFiles.PART_SUFFIX) }

    @Test
    fun splitsABigFileAndAssemblesItExactly() {
        val listener = RecordingListener()
        val result = download(settings.copy(dynamicSplitting = false), listener)
        assertArrayEquals(data, result.file.readBytes())
        assertTrue(result.verifiedComplete)
        assertNull(files.ranges[0]) // the first connection is the ordinary request
        assertEquals(listOf(256 * kib, 512 * kib, 768 * kib), rangeStarts().sorted()) // any order: parallel
        assertTrue(files.ranges.drop(1).all { it!!.matches(Regex("""bytes=\d+-\d+""")) })
        assertTrue(files.ifRanges.drop(1).all { it == "\"v1\"" })
        assertEquals(4, listener.maxConnections.get())
        assertTrue(partFiles().isEmpty())
    }

    @Test
    fun smallFilesAndServersWithoutTagsUseOneConnection() {
        files.etag = null
        assertArrayEquals(data, download().file.readBytes())
        assertEquals(listOf<String?>(null), files.ranges)
    }

    @Test
    fun lastModifiedIsEnoughToSplit() {
        files.etag = "W/\"weak\"" // weak ETags don't count
        files.lastModified = "Mon, 26 Oct 2026 10:00:00 GMT"
        assertArrayEquals(data, download().file.readBytes())
        assertTrue(files.ifRanges.drop(1).all { it == "Mon, 26 Oct 2026 10:00:00 GMT" })
        assertTrue(files.ranges.size > 1)
    }

    @Test
    fun rangesRefusedFallBackToTheFirstConnection() {
        files.honourRanges = false // range requests get the whole file (200): can't be trusted
        files.bytesPerSecond = 2_000_000
        assertArrayEquals(data, download().file.readBytes())
        assertTrue(partFiles().isEmpty())
    }

    @Test
    fun aWrongContentRangeFallsBackToo() {
        files.shiftRanges = true
        assertArrayEquals(data, download().file.readBytes())
    }

    @Test
    fun aDroppedPartRetriesOnlyItsOwnRange() {
        files.dropOnceAtRangeStart = 512 * kib
        files.bytesPerSecond = 2_000_000
        assertArrayEquals(data, download(settings.copy(dynamicSplitting = false)).file.readBytes())
        val retries = rangeStarts().filter { it in 512 * kib until 768 * kib }
        assertEquals("first request and one retry for that part: $retries", 2, retries.size)
        assertTrue(files.ranges.count { it == null } == 1) // never restarted from byte 0
    }

    @Test
    fun splittingBeatsAPerConnectionSpeedLimit() {
        files.bytesPerSecond = 500_000 // per connection: one would need about 2 s
        val started = System.nanoTime()
        assertArrayEquals(data, download().file.readBytes())
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 1.5)
    }

    @Test
    fun pauseAndResumeContinuePartByPart() {
        files.bytesPerSecond = 400_000
        val partial = pauseMidway(
            HttpDownloader(policy, settings),
            url,
            dir,
            RecordingListener(pauseAfter = 300 * kib),
        )
        val segments = partial.segments!!
        assertTrue(Segments.cover(segments, data.size.toLong()))
        val kept = segments.sumOf { it.received }
        assertTrue("kept $kept", kept in 1 until data.size.toLong())
        assertEquals(data.size.toLong(), partial.partFile.length()) // pre-sized
        // Every byte the map claims is really on disk (the map is saved after the data).
        RandomAccessFile(partial.partFile, "r").use { file ->
            for (segment in segments) {
                val bytes = ByteArray(segment.received.toInt())
                file.seek(segment.start)
                file.readFully(bytes)
                assertArrayEquals(data.copyOfRange(segment.start.toInt(), segment.nextByte.toInt()), bytes)
            }
        }
        files.ranges.clear()
        files.bytesPerSecond = null
        val result = download(resumeFrom = partial)
        assertArrayEquals(data, result.file.readBytes())
        assertTrue(files.ranges.none { it == null }) // no request for the whole file
        assertTrue(rangeStarts().containsAll(segments.filter { !it.done }.map { it.nextByte }))
        assertTrue(partFiles().isEmpty())
    }

    @Test
    fun aFileThatChangedWhilePausedDownloadsAgainOnOneConnection() {
        files.bytesPerSecond = 400_000
        val partial = pauseMidway(HttpDownloader(policy, settings), url, dir, RecordingListener(pauseAfter = 300 * kib))
        files.bytesPerSecond = null
        val changed = Random(22).nextBytes(data.size)
        files.data = changed
        files.etag = "\"v2\""
        files.ranges.clear()
        assertArrayEquals(changed, download(resumeFrom = partial).file.readBytes())
        assertTrue(files.ranges.contains(null)) // started over with a plain request
    }

    @Test
    fun aServerIgnoringIfRangeCantStitchTwoVersionsOfParts() {
        files.bytesPerSecond = 400_000
        val partial = pauseMidway(HttpDownloader(policy, settings), url, dir, RecordingListener(pauseAfter = 300 * kib))
        files.bytesPerSecond = null
        val changed = Random(23).nextBytes(data.size)
        files.data = changed
        files.etag = "\"v2\""
        files.ignoreIfRange = true // the parts come back as 206s of the new file
        assertArrayEquals(changed, download(resumeFrom = partial).file.readBytes())
    }

    @Test
    fun aResumeWithOnePartLeftLendsFreeConnectionsAHand() {
        val part = File(dir, "big.bin" + PartFiles.PART_SUFFIX)
        RandomAccessFile(part, "rw").use { it.setLength(data.size.toLong()) }
        val whole = listOf(Segment(0, data.size - 1L, 0))
        val partial = PartialDownload(part, "big.bin", data.size.toLong(), "\"v1\"", null, resumable = true, segments = whole)
        val listener = RecordingListener()
        assertArrayEquals(data, download(listener = listener, resumeFrom = partial).file.readBytes())
        assertTrue("connections ${listener.connectionCounts}", listener.maxConnections.get() >= 3)
        assertTrue(rangeStarts().size >= 3)
    }

    @Test
    fun aDamagedPartsMapStartsOver() {
        val part = File(dir, "big.bin" + PartFiles.PART_SUFFIX)
        part.writeBytes(ByteArray(10)) // not the pre-sized file the map describes
        val partial = PartialDownload(
            part, "big.bin", data.size.toLong(), "\"v1\"", null, resumable = true,
            segments = listOf(Segment(0, data.size - 1L, 500)),
        )
        assertArrayEquals(data, download(resumeFrom = partial).file.readBytes())
        assertNull(files.ranges[0])
    }
}
