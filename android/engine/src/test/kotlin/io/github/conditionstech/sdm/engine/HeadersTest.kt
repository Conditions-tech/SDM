package io.github.conditionstech.sdm.engine

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress

/** Extra request headers for video streams (M15b): an allow-list, never cookies. */
class HeadersTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun onlyTheAllowedHeadersPass() {
        val result = HttpDownloader.allowedHeaders(
            mapOf(
                "user-agent" to "Mozilla/5.0 test",
                "Referer" to "https://example.com/watch",
                "Cookie" to "session=secret",
                "Authorization" to "Bearer x",
                "Accept-Encoding" to "gzip",
                "Accept-Language" to "en-us,en;q=0.5",
                "Origin" to "bad\r\nInjected: yes",
                "Accept" to "x".repeat(600),
            ),
        )
        assertEquals(
            mapOf(
                "User-Agent" to "Mozilla/5.0 test",
                "Referer" to "https://example.com/watch",
                "Accept-Language" to "en-us,en;q=0.5",
            ),
            result,
        )
    }

    @Test
    fun theyAreSentButAcceptEncodingStaysIdentity() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse.Builder().body("x").build())
            runBlocking {
                HttpDownloader(NetworkPolicy(allowLoopback = true)).download(
                    "http://127.0.0.1:${server.port}/s.bin",
                    folder.newFolder(),
                    requestHeaders = mapOf("User-Agent" to "Site/1", "Accept-Encoding" to "gzip", "Cookie" to "a=b"),
                )
            }
            val request = server.takeRequest()
            assertEquals("Site/1", request.headers["User-Agent"])
            assertEquals("identity", request.headers["Accept-Encoding"])
            assertNull(request.headers["Cookie"])
        } finally {
            server.close()
        }
    }

    @Test
    fun checkDestinationJudgesAddressesWithoutSendingAnything() = runBlocking {
        val toLoopback = okhttp3.Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val downloader = HttpDownloader(NetworkPolicy(), dns = toLoopback)
        org.junit.Assert.assertThrows(BlockedDestinationException::class.java) {
            runBlocking { downloader.checkDestination("https://video.test/watch?v=1") }
        }
        org.junit.Assert.assertThrows(BlockedDestinationException::class.java) {
            runBlocking { downloader.checkDestination("http://192.168.1.1/") }
        }
        org.junit.Assert.assertThrows(InvalidUrlException::class.java) {
            runBlocking { downloader.checkDestination("file:///sdcard/x") }
        }
        HttpDownloader(NetworkPolicy(), dns = okhttp3.Dns { listOf(InetAddress.getByName("93.184.215.14")) })
            .checkDestination("https://video.test/watch?v=1")
    }
}
