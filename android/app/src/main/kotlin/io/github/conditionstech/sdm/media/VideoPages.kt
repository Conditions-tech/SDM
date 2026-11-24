package io.github.conditionstech.sdm.media

import org.json.JSONObject
import java.io.File

/** What to make from a video page (M15b, ADR-0024): the same choices as the PC's Download as. */
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
    val order: Int = 0, // position in yt-dlp's list, which runs from worst to best
    val width: Int = 0,
    /** yt-dlp's score for the sound's language: 10 original, 5 default, -1 dubbed, -10 described. */
    val languagePreference: Int = 0,
) {
    /** "720p" means the shorter side, so a portrait reel (720 x 1280) counts as 720p. */
    val quality: Int get() = if (width > 0 && height > 0) minOf(width, height) else height
    // Known to have video / sound. Empty means "not known" (yt-dlp's None), not "none".
    val hasVideo get() = vcodec != "none" && vcodec.isNotEmpty()
    val hasAudio get() = acodec != "none" && acodec.isNotEmpty()
    /** A ready-made file whose codecs yt-dlp doesn't know (Facebook's "hd", Instagram's "1"...). */
    val codecsUnknown get() = vcodec.isEmpty() && acodec.isEmpty()
    val isH264 get() = vcodec.startsWith("avc1") || vcodec.startsWith("h264")
    val isAv1 get() = vcodec.startsWith("av01")
    val isAac get() = acodec.startsWith("mp4a") || acodec == "aac"
}

data class PageInfo(val title: String, val streams: List<Stream>, val captions: List<Caption> = emptyList())

/** An English subtitle file the page offers (M18b); [automatic] = made by the site's speech recognition or translation. */
data class Caption(val language: String, val ext: String, val url: String, val automatic: Boolean)

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
                vcodec = s.optString("vcodec", "").lowercase(),
                acodec = s.optString("acodec", "").lowercase(),
                height = s.optInt("height", 0),
                width = s.optInt("width", 0),
                languagePreference = s.optInt("lang_pref", 0),
                bitrate = s.optDouble("tbr", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                size = s.optLong("size", 0),
                headers = headers,
                order = index,
            )
        }
        val captionList = root.optJSONArray("captions")
        val captions = (0 until (captionList?.length() ?: 0)).mapNotNull { index ->
            val c = captionList!!.optJSONObject(index) ?: return@mapNotNull null
            val url = c.optString("url")
            val ext = c.optString("ext").lowercase()
            if (!(url.startsWith("https://") || url.startsWith("http://")) || ext !in CAPTION_EXTS) return@mapNotNull null
            Caption(c.optString("lang").take(20), ext, url, c.optBoolean("auto"))
        }
        return PageInfo(root.optString("title").ifBlank { "video" }.take(200), streams, captions)
    }

    private val CAPTION_EXTS = listOf("srt", "srv1", "vtt") // SDM's preference, best first

    /**
     * The English subtitles to save with a video: ones the site's people wrote first ("en"
     * before "en-GB"...), then automatic ones; SRT, then srv1 (clean timing), then WebVTT.
     */
    fun chooseCaption(captions: List<Caption>): Caption? = captions.minWithOrNull(
        compareBy<Caption>({ it.automatic }, { it.language != "en" }, { it.language == "en-orig" }, { CAPTION_EXTS.indexOf(it.ext) }),
    )

    /** [av1] is true where MediaMuxer can put AV1 into MP4 (Android 14+). */
    fun choose(info: PageInfo, choice: VideoChoice, av1: Boolean = false): StreamPlan {
        val streams = info.streams
        if (streams.isEmpty()) throw VideoPageException("No downloadable video was found on this page.")
        // The original language first (never a dub when the original exists), then quality.
        val bestSound = compareBy<Stream>({ it.languagePreference }, { it.bitrate })
        val aacAudio = streams.filter { !it.hasVideo && it.hasAudio && it.isAac }.maxWithOrNull(bestSound)
        if (choice.audioOnly) {
            val audio = when (choice) {
                VideoChoice.M4A -> aacAudio
                else -> {
                    // MP3 can start from any sound: the best original track, even if it isn't AAC.
                    val any = streams.filter { !it.hasVideo && it.hasAudio }.maxWithOrNull(bestSound)
                    if (aacAudio == null || (any != null && any.languagePreference > aacAudio.languagePreference)) any else aacAudio
                }
            } ?: streams.filter { it.hasAudio && (choice == VideoChoice.MP3 || it.isAac) }.minByOrNull { it.height }
                // MP3 can also start from a ready-made MP4's sound, whatever yt-dlp knows about it.
                ?: streams.filter { it.codecsUnknown && it.ext == "mp4" && choice == VideoChoice.MP3 }.minByOrNull { it.order }
            return StreamPlan(null, audio ?: throw VideoPageException("This page has no sound SDM can turn into ${choice.label}."))
        }
        val byQuality = compareBy<Stream>({ it.quality }, { it.bitrate }, { it.order })
        fun videoOnly(accept: (Stream) -> Boolean) =
            streams.filter { it.hasVideo && !it.hasAudio && accept(it) && it.ext == "mp4" && it.quality <= choice.maxHeight }
                .maxWithOrNull(byQuality)
        val h264 = videoOnly { it.isH264 }
        // A ready-made MP4 with video and sound, known or (Facebook, Instagram) unknown codecs.
        val together = streams.filter {
            it.ext == "mp4" && ((it.hasVideo && it.hasAudio) || it.codecsUnknown) && it.quality <= choice.maxHeight
        }.maxWithOrNull(byQuality)
        // Separate video + sound when it gives a better picture than a ready-made combined stream.
        if (h264 != null && aacAudio != null && (together == null || h264.quality > together.quality)) {
            return StreamPlan(h264, aacAudio)
        }
        if (together != null) return StreamPlan(together, null)
        val av1Video = if (av1) videoOnly { it.isAv1 } else null
        if (av1Video != null && aacAudio != null) return StreamPlan(av1Video, aacAudio)
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
