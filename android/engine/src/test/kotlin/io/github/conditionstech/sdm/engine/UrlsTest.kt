package io.github.conditionstech.sdm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// The Windows app's tests/unit/test_urls.py, for the Android engine.
class UrlsTest {
    @Test
    fun acceptsHttpAndHttps() {
        assertEquals("example.com", Urls.validate("https://example.com/file.zip").host)
        assertEquals(8080, Urls.validate("  http://example.com:8080/a  ").port)
    }

    @Test
    fun refusesOtherSchemesAndBadShapes() {
        val cases = mapOf(
            "" to "empty",
            "ftp://example.com/a" to "'ftp:'",
            "file:///etc/passwd" to "'file:'",
            "javascript:alert(1)" to "'javascript:'",
            "example.com/a" to "no scheme",
            "https://exa mple.com/" to "spaces",
            "https://example.com/\u0000" to "control",
            "https://user:secret@example.com/a" to "user name or password",
            "https://example.com:99999/a" to "malformed",
            "http://[" to "malformed",
            "https://" + "a".repeat(9000) to "longer than",
        )
        for ((url, expected) in cases) {
            val error = assertThrows(url, InvalidUrlException::class.java) { Urls.validate(url) }
            assertTrue("$url -> ${error.message}", error.message!!.contains(expected))
        }
    }

    @Test
    fun redactRemovesTokensAndCredentials() {
        assertEquals(
            "https://example.com/files/a.zip?<redacted>",
            Urls.redact("https://user:pw@example.com/files/a.zip?token=SECRET#frag"),
        )
        assertEquals("http://example.com:8080/a", Urls.redact("http://example.com:8080/a"))
        assertEquals("<invalid URL>", Urls.redact("not a url"))
    }
}
