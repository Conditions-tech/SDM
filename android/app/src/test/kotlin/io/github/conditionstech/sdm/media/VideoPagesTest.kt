package io.github.conditionstech.sdm.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPagesTest {
    private fun stream(id: String, ext: String, v: String, a: String, h: Int = 0, tbr: Double = 0.0) =
        """{"id":"$id","url":"https://cdn.test/$id","ext":"$ext","vcodec":"$v","acodec":"$a","height":$h,"tbr":$tbr,"size":0,"headers":{"User-Agent":"UA"}}"""

    // Like the YouTube list seen on 2026-11-23: separate streams, H.264 / VP9 / AV1 video, AAC / Opus audio.
    private val youtubeLike = VideoPages.parse(
        """{"title":"Me at the zoo","streams":[
            ${stream("140", "m4a", "none", "mp4a.40.2", tbr = 128.0)},
            ${stream("139", "m4a", "none", "mp4a.40.5", tbr = 48.0)},
            ${stream("251", "webm", "none", "opus", tbr = 160.0)},
            ${stream("137", "mp4", "avc1.640028", "none", 1080, 4000.0)},
            ${stream("136", "mp4", "avc1.4d401f", "none", 720, 2000.0)},
            ${stream("248", "webm", "vp9", "none", 1080, 3000.0)},
            ${stream("399", "mp4", "av01.0.08m.08", "none", 1080, 2500.0)},
            ${stream("18", "mp4", "avc1.42001e", "mp4a.40.2", 360, 500.0)}
        ]}""",
    )

    @Test
    fun parsesAndKeepsHeaders() {
        assertEquals("Me at the zoo", youtubeLike.title)
        assertEquals(8, youtubeLike.streams.size)
        assertEquals(mapOf("User-Agent" to "UA"), youtubeLike.streams[0].headers)
    }

    @Test
    fun videoChoicesTakeH264VideoAndAacSoundNoHigherThanAsked() {
        assertEquals(listOf("137", "140"), VideoPages.choose(youtubeLike, VideoChoice.P1080).streams.map { it.id })
        assertEquals(listOf("136", "140"), VideoPages.choose(youtubeLike, VideoChoice.P720).streams.map { it.id })
    }

    @Test
    fun audioChoices() {
        assertEquals(listOf("140"), VideoPages.choose(youtubeLike, VideoChoice.M4A).streams.map { it.id })
        assertEquals(listOf("140"), VideoPages.choose(youtubeLike, VideoChoice.MP3).streams.map { it.id })
        val opusOnly = VideoPages.parse("""{"title":"x","streams":[${stream("251", "webm", "none", "opus")}]}""")
        assertEquals("251", VideoPages.choose(opusOnly, VideoChoice.MP3).audio!!.id) // MP3 decodes Opus
        assertThrows(VideoPageException::class.java) { VideoPages.choose(opusOnly, VideoChoice.M4A) }
    }

    @Test
    fun aSimplePageWithOneMp4IsUsedAsItIs() {
        val page = VideoPages.parse("""{"title":"Local","streams":[${stream("0", "mp4", "avc1", "mp4a", 0)}]}""")
        val plan = VideoPages.choose(page, VideoChoice.P720)
        assertEquals("0", plan.video!!.id)
        assertNull(plan.audio)
        assertEquals("0", VideoPages.choose(page, VideoChoice.MP3).audio!!.id) // sound from the combined file
    }

    @Test
    fun hostileOrUselessAnswers() {
        val bad = VideoPages.parse(
            """{"title":"","streams":[{"url":"file:///sdcard/x","ext":"mp4"},{"url":"javascript:1"},5,null]}""",
        )
        assertEquals("video", bad.title)
        assertTrue(bad.streams.isEmpty())
        assertThrows(VideoPageException::class.java) { VideoPages.choose(bad, VideoChoice.P720) }
        val vp9Only = VideoPages.parse("""{"title":"x","streams":[${stream("248", "webm", "vp9", "none", 720)},${stream("140", "m4a", "none", "mp4a")}]}""")
        val error = assertThrows(VideoPageException::class.java) { VideoPages.choose(vp9Only, VideoChoice.P720) }
        assertTrue(error.message!!, error.message!!.contains("without ffmpeg"))
    }
}
