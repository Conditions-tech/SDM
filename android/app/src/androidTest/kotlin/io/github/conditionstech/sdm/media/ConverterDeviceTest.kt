package io.github.conditionstech.sdm.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real converter on a phone, with three tiny synthetic files made by ffmpeg on the PC
 * (androidTest/assets): H.264 + AAC in MP4, VP9 + Opus in WebM, and a video without sound.
 * Run with: gradlew connectedDebugAndroidTest (a phone over USB).
 */
@RunWith(AndroidJUnit4::class)
class ConverterDeviceTest {
    private val testContext: Context = InstrumentationRegistry.getInstrumentation().context
    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val converter = AndroidMediaConverter(appContext)
    private val progress = mutableListOf<Float>()

    private fun input(asset: String): ConvertInput {
        val file = File(appContext.cacheDir, asset)
        testContext.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        return ConvertInput(Uri.fromFile(file).toString(), asset)
    }

    private fun convert(asset: String, format: ConvertFormat): File {
        val output = File(appContext.cacheDir, "out-${System.nanoTime()}.${format.extension}")
        runBlocking { converter.convert(input(asset), format, output) { synchronized(progress) { progress.add(it) } } }
        return output
    }

    /** The mime type and duration (in seconds) of each track, as Android reads the result back. */
    private fun tracks(file: File): List<Pair<String, Double>> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return (0 until extractor.trackCount).map {
                val format = extractor.getTrackFormat(it)
                format.getString(MediaFormat.KEY_MIME)!! to format.getLong(MediaFormat.KEY_DURATION) / 1e6
            }
        } finally {
            extractor.release()
        }
    }

    @Test
    fun mp4RewrapKeepsVideoAndSound() {
        val result = tracks(convert("h264_aac.mp4", ConvertFormat.MP4))
        assertEquals(setOf("video/avc", "audio/mp4a-latm"), result.map { it.first }.toSet())
        assertTrue(result.toString(), result.all { it.second in 2.5..3.5 })
        assertTrue(progress.isNotEmpty())
    }

    @Test
    fun m4aKeepsOnlyTheSound() {
        val result = tracks(convert("h264_aac.mp4", ConvertFormat.M4A))
        assertEquals(listOf("audio/mp4a-latm"), result.map { it.first })
        assertTrue(result.toString(), result[0].second in 2.5..3.5)
    }

    @Test
    fun mp3FromAacIsARealMp3OfTheSameLength() {
        val file = convert("h264_aac.mp4", ConvertFormat.MP3)
        val head = file.readBytes().take(3)
        // An MP3 frame starts with 11 set bits (0xFFE...); LAME may put an ID3 tag first.
        val looksMp3 = (head[0] == 0xFF.toByte() && head[1].toInt() and 0xE0 == 0xE0) ||
            String(head.toByteArray()) == "ID3"
        assertTrue("starts with $head", looksMp3)
        val result = tracks(file)
        assertEquals(listOf("audio/mpeg"), result.map { it.first })
        assertTrue(result.toString(), result[0].second in 2.5..3.6)
    }

    @Test
    fun mp3FromOpusInWebm() {
        val result = tracks(convert("vp9_opus.webm", ConvertFormat.MP3))
        assertEquals(listOf("audio/mpeg"), result.map { it.first })
        assertTrue(result.toString(), result[0].second in 1.5..2.6)
    }

    @Test
    fun opusCantGoIntoM4aWithoutReEncodingAndSaysSo() {
        val error = assertThrows(ConversionException::class.java) { convert("vp9_opus.webm", ConvertFormat.M4A) }
        assertTrue(error.message!!, error.message!!.contains("OPUS") && error.message!!.contains("MP3"))
    }

    @Test
    fun aVideoWithoutSoundSaysSo() {
        for (format in listOf(ConvertFormat.M4A, ConvertFormat.MP3)) {
            val error = assertThrows(ConversionException::class.java) { convert("video_only.mp4", format) }
            assertEquals("This file has no sound.", error.message)
        }
    }
}
