package io.github.conditionstech.sdm.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The converter on the phone (ADR-0024), with Android's own media tools instead of ffmpeg:
 * - MP4 and M4A copy the existing video and sound into a new MP4 container (MediaExtractor +
 *   MediaMuxer): fast and without quality loss, but only for formats MP4 can hold.
 * - MP3 decodes the sound with MediaCodec and encodes it with LAME (Python `sdm_media`).
 */
class AndroidMediaConverter(private val context: Context) : MediaConverter {
    override suspend fun convert(input: ConvertInput, format: ConvertFormat, output: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val file = context.contentResolver.openFileDescriptor(Uri.parse(input.uri), "r")
                ?: throw ConversionException("SDM can't read the downloaded file any more (was it moved or deleted?).")
            file.use {
                when (format) {
                    ConvertFormat.MP4 -> rewrap(it.fileDescriptor, output, audioOnly = false, onProgress)
                    ConvertFormat.M4A -> rewrap(it.fileDescriptor, output, audioOnly = true, onProgress)
                    ConvertFormat.MP3 -> toMp3(it.fileDescriptor, output, onProgress)
                }
            }
        }

    private suspend fun rewrap(source: FileDescriptor, output: File, audioOnly: Boolean, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source)
            val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                val trackMap = HashMap<Int, Int>()
                var duration = 0L
                var bufferSize = 1 shl 20
                var hasSound = false
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val isAudio = mime.startsWith("audio/")
                    val isVideo = mime.startsWith("video/")
                    hasSound = hasSound || isAudio
                    if (audioOnly && !isAudio) continue
                    if (!isAudio && !isVideo) continue // subtitles, data: left out
                    if (isAudio && mime !in MP4_AUDIO) {
                        throw ConversionException(
                            "This file's sound is ${short(mime)}, which ${if (audioOnly) "M4A" else "MP4"} can't hold without " +
                                "re-encoding. Choose MP3 instead.",
                        )
                    }
                    if (isVideo && mime !in mp4Video()) {
                        throw ConversionException(
                            "This file's video is ${short(mime)}, which MP4 can't hold without re-encoding.",
                        )
                    }
                    trackMap[index] = muxer.addTrack(format)
                    extractor.selectTrack(index)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, format.getLong(MediaFormat.KEY_DURATION))
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
                if (trackMap.isEmpty()) {
                    throw ConversionException(if (audioOnly && !hasSound) "This file has no sound." else "This file has no video or sound.")
                }
                muxer.start()
                started = true
                val buffer = ByteBuffer.allocate(bufferSize)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, extractor.sampleTime, flags)
                    muxer.writeSampleData(trackMap.getValue(extractor.sampleTrackIndex), buffer, info)
                    if (duration > 0) onProgress(extractor.sampleTime.toFloat() / duration)
                    extractor.advance()
                }
            } finally {
                if (started) runCatching { muxer.stop() }
                muxer.release()
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun toMp3(source: FileDescriptor, output: File, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw ConversionException("This file has no sound.")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val python = Python.getInstance().getModule("sdm_media")
            var encoder: PyObject? = null
            var floatSamples = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            output.outputStream().buffered().use { out ->
                fun startEncoder(outputFormat: MediaFormat) {
                    val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (channels > 2) throw ConversionException("MP3 holds stereo sound at most; this file has $channels channels.")
                    floatSamples = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    encoder = python.callAttr("Mp3Encoder", outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels)
                }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val size = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (encoder == null) startEncoder(decoder.outputFormat)
                    } else if (outIndex >= 0) {
                        if (encoder == null) startEncoder(decoder.outputFormat)
                        val data = decoder.getOutputBuffer(outIndex)!!
                        data.position(info.offset).limit(info.offset + info.size)
                        val pcm = if (floatSamples) floatToPcm16(data) else ByteArray(info.size).also { data.get(it) }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (pcm.isNotEmpty()) out.write(encoder!!.callAttr("encode", pcm).toJava(ByteArray::class.java))
                        if (duration > 0) onProgress(info.presentationTimeUs.toFloat() / duration)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                encoder?.let { out.write(it.callAttr("finish").toJava(ByteArray::class.java)) }
            }
        } finally {
            decoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    private fun floatToPcm16(data: ByteBuffer): ByteArray {
        val floats = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val pcm = ByteBuffer.allocate(floats.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (floats.hasRemaining()) pcm.putShort((floats.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        return pcm.array()
    }

    private fun short(mime: String) = mime.substringAfter('/').uppercase()

    private fun mp4Video(): Set<String> = buildSet {
        addAll(listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_MPEG4, MediaFormat.MIMETYPE_VIDEO_H263))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(MediaFormat.MIMETYPE_VIDEO_AV1)
    }

    companion object {
        private val MP4_AUDIO = setOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_AMR_NB, MediaFormat.MIMETYPE_AUDIO_AMR_WB)
    }
}
