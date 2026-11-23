package io.github.conditionstech.sdm.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.chaquo.python.PyException
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/** Video pages on the phone (ADR-0024): yt-dlp in Python, and Android's MediaMuxer to join streams. */
class AndroidVideoTools(private val converter: AndroidMediaConverter) : VideoTools {
    override suspend fun extract(pageUrl: String): String = withContext(Dispatchers.IO) {
        try {
            Python.getInstance().getModule("sdm_media").callAttr("extract", pageUrl).toString()
        } catch (e: PyException) {
            // "PageError: <yt-dlp's reason>" is meant for the user; anything else is unexpected.
            val text = e.message.orEmpty()
            throw VideoPageException(
                if (text.startsWith("PageError: ")) text.removePrefix("PageError: ") else "yt-dlp could not read this page.",
            )
        }
    }

    override suspend fun audio(source: File, format: ConvertFormat, output: File) =
        converter.convert(ConvertInput(Uri.fromFile(source).toString(), source.name), format, output) {}

    /** Copies the video track of one file and the audio track of the other into one MP4, in time order. */
    override suspend fun merge(video: File, audio: File, output: File) = withContext(Dispatchers.IO) {
        val sources = listOf(video to "video/", audio to "audio/").map { (file, kind) ->
            val extractor = MediaExtractor().apply { setDataSource(file.path) }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(kind)
            } ?: run {
                extractor.release()
                throw ConversionException("The downloaded ${kind.trimEnd('/')} stream has no ${kind.trimEnd('/')}.")
            }
            extractor.selectTrack(track)
            extractor to extractor.getTrackFormat(track)
        }
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val tracks = sources.map { (_, format) -> muxer.addTrack(format) }
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocate(4 shl 20)
            val info = MediaCodec.BufferInfo()
            val finished = BooleanArray(2)
            while (!finished.all { it }) {
                currentCoroutineContext().ensureActive()
                // Write whichever stream is behind in time, so sound and picture stay interleaved.
                val next = (0..1).filter { !finished[it] }.minBy { sources[it].first.sampleTime }
                val extractor = sources[next].first
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    finished[next] = true
                    continue
                }
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, extractor.sampleTime, flags)
                muxer.writeSampleData(tracks[next], buffer, info)
                extractor.advance()
            }
        } finally {
            if (started) runCatching { muxer.stop() }
            muxer.release()
            sources.forEach { it.first.release() }
        }
    }
}
