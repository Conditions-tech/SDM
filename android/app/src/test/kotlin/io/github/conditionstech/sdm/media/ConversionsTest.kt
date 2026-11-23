package io.github.conditionstech.sdm.media

import io.github.conditionstech.sdm.downloads.FolderPublisher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The conversion list with a fake converter (the real one needs a phone: ConverterDeviceTest). */
class ConversionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var work: File
    private lateinit var saved: File
    private val finished = mutableListOf<Conversion>()

    @Before
    fun setUp() {
        work = folder.newFolder("work")
        saved = folder.newFolder("Download-SDM")
    }

    @After
    fun tearDown() = scope.cancel()

    private fun conversions(converter: MediaConverter) =
        Conversions(converter, FolderPublisher(saved), work, scope, onFinished = { synchronized(finished) { finished.add(it) } })

    private fun Conversions.waitFor(id: Long, predicate: (Conversion) -> Boolean) = runBlocking {
        withTimeout(5_000) { items.first { list -> list.any { it.id == id && predicate(it) } } }.first { it.id == id }
    }

    private val input = ConvertInput("content://media/1", "clip.webm")

    @Test
    fun theResultIsSavedUnderTheNewExtensionAndTheWorkFileRemoved() {
        val list = conversions(object : MediaConverter {
            override suspend fun convert(input: ConvertInput, format: ConvertFormat, output: File, onProgress: (Float) -> Unit) {
                onProgress(0.5f)
                output.writeText("mp3 data")
            }
        })
        val id = list.start(input, ConvertFormat.MP3)
        val done = list.waitFor(id) { it.state == ConversionState.COMPLETED }
        assertEquals("clip.mp3", done.savedName)
        assertEquals("mp3 data", File(saved, "clip.mp3").readText())
        assertTrue(work.listFiles()!!.isEmpty())
        assertEquals(listOf(ConversionState.COMPLETED), synchronized(finished) { finished.map { it.state } })
    }

    @Test
    fun aTakenNameIsNumbered() {
        File(saved, "clip.mp4").writeText("mine")
        val list = conversions(object : MediaConverter {
            override suspend fun convert(input: ConvertInput, format: ConvertFormat, output: File, onProgress: (Float) -> Unit) =
                output.writeText("new")
        })
        val id = list.start(input, ConvertFormat.MP4)
        assertEquals("clip (1).mp4", list.waitFor(id) { it.state == ConversionState.COMPLETED }.savedName)
        assertEquals("mine", File(saved, "clip.mp4").readText())
    }

    @Test
    fun theConvertersMessageIsShownAndNothingIsLeftBehind() {
        val list = conversions(object : MediaConverter {
            override suspend fun convert(input: ConvertInput, format: ConvertFormat, output: File, onProgress: (Float) -> Unit) {
                output.writeText("half")
                throw ConversionException("This file has no sound.")
            }
        })
        val id = list.start(input, ConvertFormat.M4A)
        assertEquals("This file has no sound.", list.waitFor(id) { it.state == ConversionState.FAILED }.error)
        assertTrue(work.listFiles()!!.isEmpty())
        assertTrue(saved.listFiles()!!.isEmpty())
    }

    @Test
    fun unexpectedErrorsFromAndroidBecomeAPlainMessage() {
        val list = conversions(object : MediaConverter {
            override suspend fun convert(input: ConvertInput, format: ConvertFormat, output: File, onProgress: (Float) -> Unit) =
                throw IllegalStateException("codec died")
        })
        val id = list.start(input, ConvertFormat.MP3)
        val failed = list.waitFor(id) { it.state == ConversionState.FAILED }
        assertTrue(failed.error!!, failed.error!!.startsWith("Android could not read or convert this file"))
    }

    @Test
    fun cancelStopsItAndDeletesThePartialResult() {
        val started = CompletableDeferred<Unit>()
        val list = conversions(object : MediaConverter {
            override suspend fun convert(input: ConvertInput, format: ConvertFormat, output: File, onProgress: (Float) -> Unit) {
                output.writeText("partial")
                started.complete(Unit)
                awaitCancellation()
            }
        })
        val id = list.start(input, ConvertFormat.MP3)
        runBlocking { withTimeout(5_000) { started.await() } }
        list.cancel(id)
        list.waitFor(id) { it.state == ConversionState.CANCELLED }
        runBlocking { kotlinx.coroutines.delay(100) } // the cleanup runs as the job ends
        assertTrue(work.listFiles()!!.isEmpty())
        list.dismiss(id)
        assertTrue(list.items.value.isEmpty())
    }

    @Test
    fun outputNames() {
        assertEquals("clip.mp3", outputName("clip.webm", ConvertFormat.MP3))
        assertEquals("a.b.m4a", outputName("a.b.mp4", ConvertFormat.M4A))
        assertEquals("noext.mp4", outputName("noext", ConvertFormat.MP4))
        assertEquals(".hidden.mp3", outputName(".hidden", ConvertFormat.MP3))
    }
}
