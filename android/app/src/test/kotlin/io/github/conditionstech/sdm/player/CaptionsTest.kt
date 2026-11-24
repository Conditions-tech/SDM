package io.github.conditionstech.sdm.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun srtIsReadAndWrittenBack() {
        val srt = Char(0xFEFF) + "1\r\n00:00:01,000 --> 00:00:03,500\r\nHello <i>there</i>\r\nsecond line\r\n\r\n2\r\n00:01:02,250 --> 00:01:04,000\r\nBye &amp; thanks\r\n"
        val cues = Captions.parse(srt)
        assertEquals(
            listOf(
                Captions.Cue(1_000, 3_500, "Hello <i>there</i>\nsecond line"),
                Captions.Cue(62_250, 64_000, "Bye & thanks"),
            ),
            cues,
        )
        assertEquals(cues, Captions.parseSrt(Captions.toSrt(cues)))
        assertTrue(Captions.toSrt(cues).startsWith("1\n00:00:01,000 --> 00:00:03,500\n"))
    }

    @Test
    fun brokenCuesAreSkippedNotFatal() {
        val srt = "1\nnot a time\nText\n\n2\n00:00:05,000 --> 00:00:04,000\nends before it starts\n\n3\n00:00:06,000 --> 00:00:07,000\nKept\n"
        assertEquals(listOf(Captions.Cue(6_000, 7_000, "Kept")), Captions.parse(srt))
        assertEquals(emptyList<Captions.Cue>(), Captions.parse("garbage"))
    }

    @Test
    fun youtubeRollingWebVttLosesItsRepeatsAndTags() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: en

            00:00:00.000 --> 00:00:02.000 align:start position:0%
            Hello<00:00:00.500><c> world</c>

            00:00:02.000 --> 00:00:02.010 align:start position:0%
            Hello world

            00:00:02.010 --> 00:00:04.000 align:start position:0%
            Hello world
            how are<00:00:03.000><c> you</c>
        """.trimIndent()
        assertEquals(
            listOf(Captions.Cue(0, 2_000, "Hello world"), Captions.Cue(2_010, 4_000, "how are you")),
            Captions.parse(vtt, "vtt"),
        )
    }

    @Test
    fun youtubeSrv1IsRead() {
        val xml = """<?xml version="1.0" encoding="utf-8" ?><transcript><text start="1.5" dur="2.25">It&amp;#39;s here</text><text start="4" dur="1">&lt;b&gt;bold&lt;/b&gt; one</text><text dur="1">no start</text></transcript>"""
        assertEquals(
            listOf(Captions.Cue(1_500, 3_750, "It's here"), Captions.Cue(4_000, 5_000, "bold one")),
            Captions.parse(xml, "srv1"),
        )
    }

    @Test
    fun shiftingMovesCuesAndDropsTheOnesBeforeTheStart() {
        val cues = listOf(Captions.Cue(200, 400, "a"), Captions.Cue(1_000, 2_000, "b"))
        assertEquals(listOf(Captions.Cue(700, 900, "a"), Captions.Cue(1_500, 2_500, "b")), Captions.shift(cues, 500))
        assertEquals(listOf(Captions.Cue(500, 1_500, "b")), Captions.shift(cues, -500))
    }

    @Test
    fun timesAreParsedAndFormatted() {
        assertEquals(3_723_456L, Captions.parseTime("01:02:03,456"))
        assertEquals(123_400L, Captions.parseTime("2:03.4"))
        assertNull(Captions.parseTime("x:03.4"))
        assertEquals("01:02:03,456", Captions.time(3_723_456))
    }

    @Test
    fun theStoreKeepsOneFilePerVideo() {
        val store = SubtitleStore(folder.newFolder("subs"))
        val key = SubtitleStore.keyFor(42)
        assertTrue(!store.has(key))
        store.save(key, listOf(Captions.Cue(0, 1_000, "Hi")))
        assertEquals(listOf(Captions.Cue(0, 1_000, "Hi")), store.load(key))
        store.save(key, listOf(Captions.Cue(5, 6_000, "Replaced")))
        assertEquals("Replaced", store.load(key).single().text)
        assertTrue(!store.has(SubtitleStore.keyFor(43)))
        store.delete(key)
        assertTrue(!store.has(key))
    }
}
