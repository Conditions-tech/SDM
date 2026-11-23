package io.github.conditionstech.sdm.downloads

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/**
 * Saves finished downloads to the phone's Download/SDM folder through MediaStore (Android 10+:
 * no storage permission). MediaStore never replaces an existing file: a taken name becomes
 * "name (1).ext". The entry stays hidden (IS_PENDING) until the copy is complete.
 */
class MediaStorePublisher(context: Context) : Publisher {
    private val resolver = context.contentResolver

    override fun publish(file: File, name: String, contentType: String?): Publisher.Published {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(name, contentType))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SDM")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the new file")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no output stream")
            out.use { file.inputStream().use { input -> input.copyTo(out, 256 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // no half-copied file in Downloads
            throw e as? IOException ?: IOException(e)
        }
        val shown = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        return Publisher.Published(shown ?: name, uri.toString())
    }

    /**
     * The type from the file's extension first, so MediaStore keeps the name as it is (it adds an
     * extension when the type and the name disagree); then the server's type; then binary.
     */
    private fun mimeType(name: String, contentType: String?): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        val served = contentType?.substringBefore(';')?.trim()?.lowercase()
        return if (served != null && served.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+"))) served else "application/octet-stream"
    }
}
