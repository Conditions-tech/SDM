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

    // Facebook and Instagram as yt-dlp listed them on 2026-11-23 (owner's links): ready-made MP4s
    // with unknown codecs, AV1 or VP9 video-only streams, and an M4A sound stream.
    // Facebook's reels are portrait: 720 x 1280, 1080 x 1920, 1440 x 2560.
    private fun raw(id: String, ext: String, v: String?, a: String?, h: Int?) =
        """{"id":"$id","url":"https://cdn.test/$id","ext":"$ext","vcodec":"${v.orEmpty()}","acodec":"${a.orEmpty()}",""" +
            """"height":${h ?: 0},"width":${if (h == null) 0 else h * 9 / 16},"tbr":0,"size":0,"headers":{}}"""

    private val facebookLike = VideoPages.parse(
        """{"title":"fb","streams":[${raw("a", "m4a", "none", "mp4a.40.5", null)},${raw("sd", "mp4", null, null, null)},""" +
            """${raw("hd", "mp4", null, null, null)},${raw("v1", "mp4", "av01.0.05m.08", "none", 1280)},""" +
            """${raw("v2", "mp4", "av01.0.08m.08", "none", 1920)}]}""",
    )
    private val instagramLike = VideoPages.parse(
        """{"title":"ig","streams":[${raw("a", "m4a", "none", "mp4a.40.5", null)},${raw("1", "mp4", null, null, null)},""" +
            """${raw("2", "mp4", null, null, null)},${raw("3", "mp4", null, null, null)},${raw("v", "mp4", "vp09.00.31.08", "none", 900)}]}""",
    )

    @Test
    fun readyMadeMp4sWithUnknownCodecsAreUsed() {
        // The bug the owner hit: these were taken for "no video", so nothing could be saved.
        assertEquals(listOf("hd"), VideoPages.choose(facebookLike, VideoChoice.P1080).streams.map { it.id })
        assertEquals(listOf("3"), VideoPages.choose(instagramLike, VideoChoice.P720).streams.map { it.id })
        assertEquals(listOf("a"), VideoPages.choose(instagramLike, VideoChoice.MP3).streams.map { it.id })
        assertEquals(listOf("a"), VideoPages.choose(facebookLike, VideoChoice.M4A).streams.map { it.id })
    }

    @Test
    fun av1IsUsedOnlyWhereTheMuxerTakesIt() {
        val av1Only = VideoPages.parse(
            """{"title":"x","streams":[${raw("a", "m4a", "none", "mp4a.40.2", null)},${raw("v1", "mp4", "av01.0.05m.08", "none", 1280)},""" +
                """${raw("v2", "mp4", "av01.0.08m.08", "none", 1920)}]}""",
        )
        assertEquals(listOf("v1", "a"), VideoPages.choose(av1Only, VideoChoice.P720, av1 = true).streams.map { it.id })
        assertEquals(listOf("v2", "a"), VideoPages.choose(av1Only, VideoChoice.P1080, av1 = true).streams.map { it.id })
        assertThrows(VideoPageException::class.java) { VideoPages.choose(av1Only, VideoChoice.P720, av1 = false) }
    }
}
