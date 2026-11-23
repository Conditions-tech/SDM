package io.github.conditionstech.sdm.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinksTest {
    @Test
    fun findsTheLinkInWhatBrowsersShare() {
        assertEquals("https://example.com/a.zip", SharedLinks.firstLink("https://example.com/a.zip"))
        assertEquals("https://example.com/a.zip", SharedLinks.firstLink("Great file\nhttps://example.com/a.zip"))
        assertEquals("https://example.com/a.zip", SharedLinks.firstLink("Get it here: https://example.com/a.zip."))
        assertEquals("http://example.com/x?y=1", SharedLinks.firstLink("(http://example.com/x?y=1)"))
        assertEquals("HTTPS://Example.com/a", SharedLinks.firstLink("HTTPS://Example.com/a"))
    }

    @Test
    fun skipsAddressesSdmRefusesAndTakesTheNextGoodOne() {
        assertEquals(
            "https://example.com/b.zip",
            SharedLinks.firstLink("https://user:pw@example.com/a.zip then https://example.com/b.zip"),
        )
    }

    @Test
    fun nothingUsable() {
        for (text in listOf(null, "", "   ", "no link here", "file:///sdcard/x", "ftp://example.com/a", "javascript:alert(1)")) {
            assertNull(text, SharedLinks.firstLink(text))
        }
    }

    @Test
    fun hugeSharesAreCutNotScannedWhole() {
        val text = "x".repeat(SharedLinks.MAX_SHARED_TEXT) + " https://example.com/late.zip"
        assertNull(SharedLinks.firstLink(text))
    }

    @Test
    fun videoPagesAreTellFromFiles() {
        for (page in listOf(
            "https://www.youtube.com/watch?v=jNQXAC9IVRw", "https://youtu.be/jNQXAC9IVRw",
            "https://m.youtube.com/shorts/abc", "https://vimeo.com/123", "https://example.com/videos/cats",
            "https://player.vimeo.com/video/1.2",
        )) assertEquals(page, true, SharedLinks.looksLikeVideoPage(page))
        for (file in listOf(
            "https://www.python.org/ftp/python/3.14.7/python-3.14.7-embed-amd64.zip",
            "https://example.com/clip.mp4", "https://cdn.example.com/a/b/report.pdf?x=1",
        )) assertEquals(file, false, SharedLinks.looksLikeVideoPage(file))
    }
}
