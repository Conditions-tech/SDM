package io.github.conditionstech.sdm.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The downloader against a local MockWebServer only (never a real website), like the Windows
 * app's tests/integration/test_http_download.py and tests/security/test_unsafe_targets.py.
 */
class HttpDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    // Tests talk to 127.0.0.1, so they allow loopback; everything else stays blocked.
    private val testPolicy = NetworkPolicy(allowLoopback = true)
    private val fast = DownloadSettings(backoffBaseMs = 10, readTimeoutMs = 2_000, connectTimeoutMs = 2_000)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        dir = folder.newFolder("downloads")
    }

    @After
    fun tearDown() = server.close()

    private fun url(path: String, scheme: String = "http"): String =
        HttpUrl.Builder().scheme(scheme).host("127.0.0.1").port(server.port).encodedPath(path).build().toString()

    private fun ok(body: ByteArray) = MockResponse.Builder().body(Buffer().write(body))

    private fun download(
        url: String,
        policy: NetworkPolicy = testPolicy,
        settings: DownloadSettings = fast,
        dns: Dns = Dns.SYSTEM,
        listener: DownloadListener = object : DownloadListener {},
    ): DownloadResult = runBlocking { HttpDownloader(policy, settings, dns).download(url, dir, listener) }

    private inline fun <reified T : Throwable> failure(crossinline block: () -> Unit): T =
        assertThrows(T::class.java) { block() }

    private fun leftovers() = dir.listFiles()!!.filter { it.name.endsWith(PartFiles.PART_SUFFIX) }

    @Test
    fun downloadsAFileByteForByte() {
        val data = Random(1).nextBytes(300_000)
        server.enqueue(ok(data).build())
        var info: Pair<String, Long?>? = null
        var last = 0L
        val result = download(
            url("/files/data.bin"),
            listener = object : DownloadListener {
                override fun onFileInfo(fileName: String, totalBytes: Long?) {
                    info = fileName to totalBytes
                }

                override fun onProgress(receivedBytes: Long, totalBytes: Long?) {
                    assertTrue(receivedBytes >= last)
                    last = receivedBytes
                }
            },
        )
        assertEquals("data.bin", result.file.name)
        assertArrayEquals(data, result.file.readBytes())
        assertEquals(300_000L, result.size)
        assertTrue(result.verifiedComplete)
        assertEquals("data.bin" to 300_000L, info)
        assertEquals(300_000L, last)
        assertTrue(leftovers().isEmpty())
        val request = server.takeRequest()
        assertEquals("identity", request.headers["Accept-Encoding"])
        assertEquals("SDM (Android)", request.headers["User-Agent"])
    }

    @Test
    fun theServersNameWinsAndIsSanitised() {
        server.enqueue(ok("x".toByteArray()).addHeader("Content-Disposition", "attachment; filename=\"../../evil.apk\"").build())
        val result = download(url("/get?id=1"))
        assertEquals(dir, result.file.parentFile)
        assertEquals("evil.apk", result.file.name)
    }

    @Test
    fun neverOverwritesAnExistingFile() {
        File(dir, "report.pdf").writeText("mine")
        server.enqueue(ok("new".toByteArray()).build())
        server.enqueue(ok("newer".toByteArray()).build())
        assertEquals("report (1).pdf", download(url("/report.pdf")).file.name)
        assertEquals("report (2).pdf", download(url("/report.pdf")).file.name)
        assertEquals("mine", File(dir, "report.pdf").readText())
    }

    @Test
    fun followsRedirectsAndNamesTheFileAfterTheFinalUrl() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/real/name.zip").build())
        server.enqueue(ok("zip".toByteArray()).build())
        val result = download(url("/download?id=7"))
        assertEquals("name.zip", result.file.name)
        assertTrue(result.finalUrl.endsWith("/real/name.zip"))
    }

    @Test
    fun notFoundFailsWithoutRetrying() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val error = failure<DownloadException> { download(url("/missing.zip")) }
        assertTrue(error.message!!.contains("not found"))
        assertEquals(1, server.requestCount)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun temporaryServerErrorsAreRetried() {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").build())
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(ok("finally".toByteArray()).build())
        assertEquals("finally", download(url("/f.txt")).file.readText())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        repeat(3) { server.enqueue(MockResponse.Builder().code(503).build()) }
        val error = failure<DownloadException> { download(url("/f.txt")) }
        assertTrue(error.message!!.contains("temporary problem (503"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aDroppedConnectionIsRetriedAndLeavesNothingBehind() {
        val data = Random(2).nextBytes(200_000)
        repeat(3) { server.enqueue(ok(data).onResponseBody(SocketEffect.ShutdownConnection).build()) }
        val error = failure<DownloadException> { download(url("/big.bin")) }
        assertTrue(error.message, error.message!!.contains("interrupted after"))
        assertEquals(3, server.requestCount)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun aSilentServerTimesOut() {
        server.enqueue(ok("late".toByteArray()).headersDelay(3, TimeUnit.SECONDS).build())
        val settings = fast.copy(readTimeoutMs = 300, maxAttempts = 1)
        val error = failure<DownloadException> { download(url("/slow.txt"), settings = settings) }
        assertTrue(error.message, error.message!!.contains("took too long"))
    }

    @Test
    fun chunkedDownloadsCountAsComplete() {
        server.enqueue(MockResponse.Builder().chunkedBody("a".repeat(10_000), 1024).build())
        val result = download(url("/chunked.txt"))
        assertEquals(10_000L, result.size)
        assertTrue(result.verifiedComplete)
    }

    @Test
    fun cancellingStopsQuicklyAndDeletesThePartialFile() = runBlocking {
        val data = Random(3).nextBytes(1_000_000)
        server.enqueue(ok(data).throttleBody(10_000, 100, TimeUnit.MILLISECONDS).build()) // ~100 KB/s
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            HttpDownloader(testPolicy, fast).download(
                url("/slow.bin"),
                dir,
                object : DownloadListener {
                    override fun onProgress(receivedBytes: Long, totalBytes: Long?) {
                        if (receivedBytes > 0) started.complete(Unit)
                    }
                },
            )
        }
        withTimeout(5_000) { started.await() }
        val before = System.nanoTime()
        job.cancel()
        try {
            job.await()
        } catch (expected: CancellationException) {
        }
        assertTrue("took too long to stop", System.nanoTime() - before < 2_000_000_000L)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    // ---- Unsafe destinations (ADR-0007, ADR-0015) ----

    @Test
    fun thisDeviceIsBlockedBeforeAnyRequest() {
        val error = failure<BlockedDestinationException> { download(url("/a"), policy = NetworkPolicy()) }
        assertTrue(error.message!!.contains("this device"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aNameThatResolvesToThisDeviceIsBlockedBeforeConnecting() {
        val toLoopback = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val link = "http://files.test:${server.port}/a.txt"
        val error = failure<BlockedDestinationException> {
            download(link, policy = NetworkPolicy(), dns = toLoopback)
        }
        assertTrue(error.message!!.contains("files.test points to this device"))
        assertEquals(0, server.requestCount)
        // The same lookup is fine when the policy allows it: the hook only judges addresses.
        server.enqueue(ok("hi".toByteArray()).build())
        assertEquals("hi", download(link, dns = toLoopback).file.readText())
    }

    @Test
    fun redirectsToPrivateOrSpecialAddressesAreBlocked() {
        for (target in listOf("http://10.0.0.1/admin", "http://169.254.169.254/latest/meta-data/", "http://[::ffff:192.168.1.1]/")) {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", target).build())
            failure<BlockedDestinationException> { download(url("/go")) }
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun redirectsToOtherSchemesAreRefused() {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "file:///data/data/secret").build())
        val error = failure<InvalidUrlException> { download(url("/go")) }
        assertTrue(error.message!!.contains("redirected to an address SDM refuses"))
    }

    @Test
    fun redirectLoopsStop() {
        repeat(11) { server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/loop").build()) }
        val error = failure<DownloadException> { download(url("/loop")) }
        assertTrue(error.message!!.contains("Too many redirects"))
        assertEquals(11, server.requestCount)
    }

    @Test
    fun httpsToHttpRedirectsAreRefused() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", url("/plain.txt", "http")).build())
        val downloader = HttpDownloader(testPolicy, fast, configureClient = {
            it.sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
        })
        val error = failure<DownloadException> { runBlocking { downloader.download(url("/secure", "https"), dir) } }
        assertTrue(error.message!!.contains("secure (https) address to an insecure (http)"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theDownloadFolderMustExist() {
        val error = failure<DownloadException> {
            runBlocking { HttpDownloader(testPolicy, fast).download(url("/a"), File(dir, "missing")) }
        }
        assertTrue(error.message!!.contains("does not exist"))
    }

    @Test
    fun retryAfterAcceptsSecondsAndDates() {
        val now = java.time.ZonedDateTime.parse("Mon, 26 Oct 2026 10:00:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(120.0, HttpDownloader.parseRetryAfter("120", now)!!, 0.0)
        assertEquals(30.0, HttpDownloader.parseRetryAfter("Mon, 26 Oct 2026 10:00:30 GMT", now)!!, 0.0)
        assertEquals(0.0, HttpDownloader.parseRetryAfter("Mon, 26 Oct 2026 09:00:00 GMT", now)!!, 0.0)
        assertEquals(null, HttpDownloader.parseRetryAfter("soon", now))
        assertEquals(null, HttpDownloader.parseRetryAfter(null, now))
        assertFalse(HttpDownloader.parseRetryAfter("-5", now) != null)
    }
}
