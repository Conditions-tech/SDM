package io.github.conditionstech.sdm.player

import io.github.conditionstech.sdm.engine.NetworkPolicy
import io.github.conditionstech.sdm.engine.policyClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** The OpenSubtitles client against a local pretend server (never the real site). */
class OpenSubtitlesTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After
    fun tearDown() = server.close()

    private fun client(token: String? = null, policy: NetworkPolicy = NetworkPolicy(allowLoopback = true)) = OpenSubtitles(
        policyClient(policy),
        apiKey = "KEY123",
        token = token,
        userAgent = "SDM v1.1.0",
        base = server.url("/api/v1/"),
    )

    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val searchJson = """
        {"total_count":3,"data":[
          {"id":"1","attributes":{"language":"en","download_count":900,"moviehash_match":false,"release":"Movie.2019.WEB","files":[{"file_id":11,"file_name":"a.srt"}]}},
          {"id":"2","attributes":{"language":"en","download_count":10,"moviehash_match":true,"release":"Movie.2019.1080p.BluRay","files":[{"file_id":22}]}},
          {"id":"3","attributes":{"language":"ko","download_count":5000,"files":[{"file_id":33}]}},
          {"id":"4","attributes":{"language":"en","download_count":99999,"machine_translated":true,"release":"MT","files":[{"file_id":44}]}},
          {"id":"5","attributes":{"language":"en","files":[]}}
        ]}
    """.trimIndent()

    @Test
    fun searchSendsKeyAndSortedLowercaseParametersAndRanksResults() = runBlocking {
        reply(200, searchJson)
        val results = client().search("8e245d9679d31e12", "Movie.2019.1080p.BluRay.mkv")
        val request = server.takeRequest()
        assertEquals("/api/v1/subtitles?languages=en&moviehash=8e245d9679d31e12&query=movie%202019%201080p%20bluray", request.target)
        assertEquals("KEY123", request.headers["Api-Key"])
        assertEquals("SDM v1.1.0", request.headers["User-Agent"])
        assertNull(request.headers["Authorization"])
        // Exact (hash) match first, people-made before machine translations, Korean dropped.
        assertEquals(listOf(22L, 11L, 44L), results.map { it.fileId })
        assertTrue(results[0].exactMatch)
        assertEquals("Movie.2019.1080p.BluRay", results[0].name)
    }

    @Test
    fun downloadPostsTheFileIdWithTheSignInAndReturnsTheLink() = runBlocking {
        reply(200, """{"link":"https://dl.example/file.srt","file_name":"file.srt","remaining":19}""")
        assertEquals("https://dl.example/file.srt", client(token = "TOKEN").downloadLink(22))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer TOKEN", request.headers["Authorization"])
        assertEquals(22L, JSONObject(request.body!!.utf8()).getLong("file_id"))
    }

    @Test
    fun aLinkThatIsNotHttpsIsRefused() = runBlocking {
        reply(200, """{"link":"file:///data/x"}""")
        expectMessage("unexpected download link") { client().downloadLink(1) }
    }

    @Test
    fun errorsBecomeReadableMessages() = runBlocking {
        reply(401, "{}")
        expectMessage("API key") { client().search(null, "x.mkv") }
        reply(401, "{}")
        expectMessage("sign-in expired") { client(token = "OLD").search(null, "x.mkv") }
        reply(406, """{"message":"You have downloaded your allowed 5 subtitles for 24h"}""")
        expectMessage("allowed 5 subtitles") { client().downloadLink(1) }
        reply(500, "not json")
        expectMessage("(500)") { client().search(null, "x.mkv") }
        reply(200, "not json")
        expectMessage("could not read") { client().search(null, "x.mkv") }
    }

    @Test
    fun loginReturnsTheTokenOnly() = runBlocking {
        reply(200, """{"user":{"allowed_downloads":20},"token":"eyJabc","status":200}""")
        assertEquals("eyJabc", client().login("me", "secret"))
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("me", body.getString("username"))
    }

    @Test
    fun theNetworkPolicyApplies() = runBlocking {
        // Without the test-only loopback permission, SDM refuses to talk to this computer.
        expectMessage("") { client(policy = NetworkPolicy()).search(null, "x.mkv") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun movieHashFollowsTheOpenSubtitlesRule() {
        val chunk = MovieHash.CHUNK
        assertEquals("0000000000020000", MovieHash.compute(2L * chunk, ByteArray(chunk), ByteArray(chunk)))
        val first = ByteArray(chunk).also { it[0] = 1; it[8] = 2 } // words 1 and 2
        val last = ByteArray(chunk).also { it[chunk - 1] = 0x80.toByte() } // top byte of the last word
        // size 0x30000 + 1 + 2 + 0x8000000000000000
        assertEquals("8000000000030003", MovieHash.compute(3L * chunk, first, last))
        val ones = ByteArray(chunk) { 0xFF.toByte() } // every word is -1: the sum wraps around
        assertEquals("%016x".format(2L * chunk - 2 * (chunk / 8)), MovieHash.compute(2L * chunk, ones, ones))
        assertNull(MovieHash.compute(100, ByteArray(chunk), ByteArray(chunk)))
    }

    @Test
    fun searchNamesAreCleanedFromTheFileName() {
        assertEquals("Parasite 2019 1080p BluRay x264", OpenSubtitles.searchName("Parasite.2019.1080p.BluRay.x264.mkv"))
        assertEquals("My Movie", OpenSubtitles.searchName("[My_Movie].mp4"))
    }

    private suspend fun expectMessage(part: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected a SubtitleException")
        } catch (e: SubtitleException) {
            assertTrue("'${e.message}' should contain '$part'", e.message.orEmpty().contains(part))
        }
    }
}
