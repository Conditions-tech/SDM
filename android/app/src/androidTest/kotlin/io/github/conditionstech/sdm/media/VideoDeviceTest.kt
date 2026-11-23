package io.github.conditionstech.sdm.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress

/**
 * Video pages on a real phone (M15b): yt-dlp inside Chaquopy reads a page served by a test
 * server running on the phone itself (127.0.0.1, never a real site), and MediaMuxer joins a
 * video-only and an audio-only file.
 */
@RunWith(AndroidJUnit4::class)
class VideoDeviceTest {
    private val testContext: Context = InstrumentationRegistry.getInstrumentation().context
    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tools = AndroidVideoTools(AndroidMediaConverter(appContext))

    init {
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
    }

    private fun asset(name: String) = testContext.assets.open(name).use { it.readBytes() }

    private fun copy(name: String) = File(appContext.cacheDir, name).apply { writeBytes(asset(name)) }

    @Test
    fun ytDlpFindsTheVideoOnALocalPage() {
        val clip = asset("h264_aac.mp4")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/page" -> MockResponse.Builder().setHeader("Content-Type", "text/html")
                    .body("<html><head><title>Phone test clip</title></head><body><video src=\"/media/clip.mp4\"></video></body></html>")
                    .build()
                "/media/clip.mp4" -> MockResponse.Builder().setHeader("Content-Type", "video/mp4").body(Buffer().write(clip)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val base = "http://127.0.0.1:${server.port}"
            val page = VideoPages.parse(runBlocking { tools.extract("$base/page") })
            assertTrue(page.title, page.title.startsWith("Phone test clip"))
            assertEquals(listOf("$base/media/clip.mp4"), page.streams.map { it.url })
            val error = assertThrows(VideoPageException::class.java) { runBlocking { tools.extract("$base/missing") } }
            assertTrue(error.message!!, error.message!!.contains("404"))
        } finally {
            server.close()
        }
    }

    @Test
    fun separateVideoAndSoundAreJoinedIntoOneMp4() {
        val output = File(appContext.cacheDir, "joined.mp4")
        runBlocking { tools.merge(copy("video_only.mp4"), copy("audio_only.m4a"), output) }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            assertEquals(setOf("video/avc", "audio/mp4a-latm"), mimes.toSet())
        } finally {
            extractor.release()
        }
    }
}
