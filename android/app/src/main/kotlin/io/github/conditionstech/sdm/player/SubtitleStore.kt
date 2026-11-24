package io.github.conditionstech.sdm.player

import java.io.File
import java.security.MessageDigest

/**
 * Each video's subtitles (M18), as SRT in the app's own storage, named after the video (its
 * MediaStore id, else its address). The player loads them automatically. Kept apart from the
 * user's folders, so no storage permission is needed and nothing else can change them.
 */
class SubtitleStore(private val directory: File) {
    fun file(key: String): File = File(directory, sha1(key) + ".srt")

    fun has(key: String): Boolean = file(key).isFile

    /** Saves [cues] for [key]; returns the file, or null when there was nothing to save. */
    fun save(key: String, cues: List<Captions.Cue>): File? {
        if (cues.isEmpty()) return null
        directory.mkdirs()
        val target = file(key)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeText(Captions.toSrt(cues))
        if (!temporary.renameTo(target)) {
            target.delete()
            if (!temporary.renameTo(target)) return null
        }
        return target
    }

    fun load(key: String): List<Captions.Cue> = file(key).takeIf { it.isFile && it.length() <= Captions.MAX_BYTES }
        ?.let { Captions.parseSrt(it.readText()) }.orEmpty()

    fun delete(key: String) {
        file(key).delete()
    }

    companion object {
        /** The key for a MediaStore video, the same id whichever collection it was saved through. */
        fun keyFor(mediaStoreId: Long) = "id:$mediaStoreId"

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
