package io.github.conditionstech.sdm.media

import org.json.JSONObject
import java.io.File

/** What to make from a video page (M15b, ADR-0023): the same choices as the PC's Download as. */
enum class VideoChoice(val label: String, val extension: String, val maxHeight: Int) {
    P720("720p", "mp4", 720),
    P1080("1080p", "mp4", 1080),
    MP3("MP3", "mp3", 0),
    M4A("M4A", "m4a", 0),
    ;

    val audioOnly: Boolean get() = maxHeight == 0
}

/** A message for the user about a video page (no video found, no usable format...). */
class VideoPageException(message: String) : Exception(message)

/** One stream yt-dlp found on a page: a direct http(s) address SDM's engine downloads. */
data class Stream(
    val id: String,
    val url: String,
    val ext: String,
    val vcodec: String,
    val acodec: String,
    val height: Int,
    val bitrate: Double,
    val size: Long,
    val headers: Map<String, String>,
) {
    val hasVideo get() = vcodec != "none" && vcodec.isNotEmpty()
    val hasAudio get() = acodec != "none" && acodec.isNotEmpty()
    val isH264 get() = vcodec.startsWith("avc1") || vcodec.startsWith("h264")
    val isAac get() = acodec.startsWith("mp4a") || acodec == "aac"
}

data class PageInfo(val title: String, val streams: List<Stream>)

/** The streams to download: video and audio separately, or one stream that has both. */
data class StreamPlan(val video: Stream?, val audio: Stream?) {
    val streams: List<Stream> get() = listOfNotNull(video, audio)
}

/**
 * Reading yt-dlp's answer and choosing streams. The phone joins streams with Android's own
 * MediaMuxer, not ffmpeg, so only formats it can join are chosen: H.264 video in MP4 and AAC
 * audio in M4A. MP3 can start from any sound Android decodes (AAC, Opus...).
 */
object VideoPages {
    /** yt-dlp's JSON (from sdm_media.extract); untrusted page data, checked as it is read. */
    fun parse(json: String): PageInfo {
        val root = JSONObject(json)
        val list = root.optJSONArray("streams")
        val streams = (0 until (list?.length() ?: 0)).mapNotNull { index ->
            val s = list!!.optJSONObject(index) ?: return@mapNotNull null
            val url = s.optString("url")
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            val headers = s.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
            Stream(
                id = s.optString("id"),
                url = url,
                ext = s.optString("ext").lowercase(),
                vcodec = s.optString("vcodec", "none").lowercase(),
                acodec = s.optString("acodec", "none").lowercase(),
                height = s.optInt("height", 0),
                bitrate = s.optDouble("tbr", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                size = s.optLong("size", 0),
                headers = headers,
            )
        }
        return PageInfo(root.optString("title").ifBlank { "video" }.take(200), streams)
    }

    fun choose(info: PageInfo, choice: VideoChoice): StreamPlan {
        val streams = info.streams
        if (streams.isEmpty()) throw VideoPageException("No downloadable video was found on this page.")
        val aacAudio = streams.filter { !it.hasVideo && it.hasAudio && it.isAac }.maxByOrNull { it.bitrate }
        if (choice.audioOnly) {
            val audio = when (choice) {
                VideoChoice.M4A -> aacAudio
                else -> aacAudio ?: streams.filter { !it.hasVideo && it.hasAudio }.maxByOrNull { it.bitrate }
            } ?: streams.filter { it.hasAudio && (choice == VideoChoice.MP3 || it.isAac) }.minByOrNull { it.height }
            return StreamPlan(null, audio ?: throw VideoPageException("This page has no sound SDM can turn into ${choice.label}."))
        }
        val byQuality = compareBy<Stream>({ it.height }, { it.bitrate })
        val videoOnly = streams.filter { it.hasVideo && !it.hasAudio && it.isH264 && it.ext == "mp4" && it.height <= choice.maxHeight }
            .maxWithOrNull(byQuality)
        val together = streams.filter { it.hasVideo && it.hasAudio && it.ext == "mp4" && it.height <= choice.maxHeight }
            .maxWithOrNull(byQuality)
        // Separate video + sound when it gives a better picture than a ready-made combined stream.
        if (videoOnly != null && aacAudio != null && (together == null || videoOnly.height > together.height)) {
            return StreamPlan(videoOnly, aacAudio)
        }
        if (together != null) return StreamPlan(together, null)
        throw VideoPageException(
            "This page has no ${choice.label} (or smaller) MP4 video the phone can save without ffmpeg.",
        )
    }
}

/** The phone-only parts of video pages: yt-dlp (Python) and joining streams (MediaMuxer). */
interface VideoTools {
    /** yt-dlp's JSON for [pageUrl]; throws [VideoPageException] with yt-dlp's reason. */
    suspend fun extract(pageUrl: String): String

    /** Joins a video-only and an audio-only file into one MP4. */
    suspend fun merge(video: File, audio: File, output: File)

    /** Makes M4A or MP3 from a downloaded audio stream. */
    suspend fun audio(source: File, format: ConvertFormat, output: File)
}
