package io.github.conditionstech.sdm.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/** A subtitle search or download failed; the message is fit to show the user. */
class SubtitleException(message: String) : Exception(message)

/** One subtitle file found online. */
data class SubtitleResult(
    val fileId: Long,
    val name: String,
    val language: String,
    val downloads: Int,
    /** Made for exactly this file (the movie hash matched), so it should already be in sync. */
    val exactMatch: Boolean,
    val machineTranslated: Boolean,
)

/**
 * OpenSubtitles.com's REST API (M18c): search by movie hash and by name, then ask for a
 * download link. The user's own free API key is required; signing in (a token) is optional and
 * raises the daily download limit. The key and token are never logged.
 *
 * [client] must be a [io.github.conditionstech.sdm.engine.policyClient] (network policy).
 * The subtitle file itself is fetched by SDM's downloader, through the same policy.
 */
class OpenSubtitles(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val token: String? = null,
    private val userAgent: String = "SDM v1",
    private val base: HttpUrl = "https://api.opensubtitles.com/api/v1/".toHttpUrl(),
) {
    /** English subtitles for the video: hash matches first, then the most downloaded. */
    suspend fun search(movieHash: String?, fileName: String, language: String = "en"): List<SubtitleResult> {
        val query = searchName(fileName)
        // The API asks for parameters in alphabetical order and lower case (else it redirects).
        val url = base.newBuilder().addPathSegment("subtitles").apply {
            addQueryParameter("languages", language)
            if (movieHash != null) addQueryParameter("moviehash", movieHash)
            if (query.isNotEmpty()) addQueryParameter("query", query.lowercase())
        }.build()
        val json = call(Request.Builder().url(url).get())
        return parseSearch(json, language)
    }

    /** A temporary link to the subtitle file. */
    suspend fun downloadLink(fileId: Long): String {
        val body = JSONObject().put("file_id", fileId).put("sub_format", "srt").toString()
        val json = call(Request.Builder().url(base.newBuilder().addPathSegment("download").build()).post(body.toRequestBody(JSON)))
        return try {
            JSONObject(json).getString("link").takeIf { it.startsWith("https://") }
                ?: throw SubtitleException("OpenSubtitles sent an unexpected download link.")
        } catch (e: JSONException) {
            throw SubtitleException("OpenSubtitles sent an answer SDM could not read.")
        }
    }

    /** Signs in and returns the token to keep (the password itself is not kept). */
    suspend fun login(username: String, password: String): String {
        val body = JSONObject().put("username", username).put("password", password).toString()
        val json = call(Request.Builder().url(base.newBuilder().addPathSegment("login").build()).post(body.toRequestBody(JSON)))
        return try {
            JSONObject(json).getString("token").ifBlank { throw SubtitleException("OpenSubtitles did not sign you in.") }
        } catch (e: JSONException) {
            throw SubtitleException("OpenSubtitles did not sign you in.")
        }
    }

    private suspend fun call(builder: Request.Builder): String = withContext(Dispatchers.IO) {
        builder.header("Api-Key", apiKey)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body.byteStream().readCapped(1 shl 20)
                when {
                    response.isSuccessful -> text
                    response.code == 401 -> throw SubtitleException(
                        if (token == null) "OpenSubtitles did not accept the API key (check it in Settings)." else "Your OpenSubtitles sign-in expired: sign in again in Settings.",
                    )
                    response.code == 403 -> throw SubtitleException("OpenSubtitles refused: sign in to OpenSubtitles in Settings.")
                    response.code == 406 || response.code == 429 -> throw SubtitleException(
                        message(text) ?: "Today's OpenSubtitles download limit is used up. Try again tomorrow.",
                    )
                    else -> throw SubtitleException(message(text) ?: "OpenSubtitles answered with an error (${response.code}).")
                }
            }
        } catch (e: SubtitleException) {
            throw e
        } catch (e: IOException) {
            throw SubtitleException("Could not reach OpenSubtitles. Check your connection.")
        } catch (e: io.github.conditionstech.sdm.engine.SdmException) {
            throw SubtitleException(e.message ?: "Could not reach OpenSubtitles.")
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** "Parasite.2019.1080p.BluRay.x264.mkv" → "Parasite 2019 1080p BluRay x264". */
        fun searchName(fileName: String): String =
            fileName.substringBeforeLast('.').replace(Regex("[._\\[\\]()-]+"), " ").trim().take(100)

        fun parseSearch(json: String, language: String = "en"): List<SubtitleResult> = try {
            val data = JSONObject(json).optJSONArray("data")
            (0 until (data?.length() ?: 0)).mapNotNull { index ->
                val attributes = data!!.optJSONObject(index)?.optJSONObject("attributes") ?: return@mapNotNull null
                val file = attributes.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
                val id = file.optLong("file_id", 0).takeIf { it > 0 } ?: return@mapNotNull null
                val lang = attributes.optString("language")
                if (!lang.startsWith(language, ignoreCase = true)) return@mapNotNull null
                SubtitleResult(
                    fileId = id,
                    name = attributes.optString("release").ifBlank { file.optString("file_name") }.ifBlank { "Subtitles" }.take(200),
                    language = lang,
                    downloads = attributes.optInt("download_count"),
                    exactMatch = attributes.optBoolean("moviehash_match"),
                    machineTranslated = attributes.optBoolean("machine_translated") || attributes.optBoolean("ai_translated"),
                )
            }.distinctBy { it.fileId }
                .sortedWith(compareByDescending<SubtitleResult> { it.exactMatch }.thenBy { it.machineTranslated }.thenByDescending { it.downloads })
        } catch (e: JSONException) {
            throw SubtitleException("OpenSubtitles sent an answer SDM could not read.")
        }

        private fun message(text: String): String? = try {
            JSONObject(text).optString("message").takeIf { it.isNotBlank() }?.take(200)
        } catch (e: JSONException) {
            null
        }

        private fun InputStream.readCapped(limit: Int): String {
            val bytes = Captions.readCapped(this, limit) ?: throw SubtitleException("OpenSubtitles sent too much data.")
            return String(bytes, Charsets.UTF_8)
        }
    }
}

/**
 * The OpenSubtitles movie hash: the file size plus the sum of the first and last 64 KiB read as
 * little-endian 64-bit numbers (overflow wraps), as 16 hex digits. Files under 128 KiB get none.
 */
object MovieHash {
    const val CHUNK = 65_536

    fun compute(size: Long, first: ByteArray, last: ByteArray): String? {
        if (size < 2L * CHUNK || first.size != CHUNK || last.size != CHUNK) return null
        var hash = size
        for (chunk in listOf(first, last)) {
            for (i in 0 until CHUNK step 8) {
                var word = 0L
                for (b in 7 downTo 0) word = (word shl 8) or (chunk[i + b].toLong() and 0xFF)
                hash += word
            }
        }
        return "%016x".format(hash)
    }
}
