package io.github.conditionstech.sdm.downloads

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.conditionstech.sdm.engine.PartialDownload
import io.github.conditionstech.sdm.engine.Segments
import io.github.conditionstech.sdm.media.VideoChoice
import java.io.File

/**
 * The download history in the app's private SQLite database (Android's built-in SQLite, no
 * library). Everything read back is checked before use: the engine re-checks a partial file's
 * path and parts map, and an unknown state counts as failed.
 */
class SqliteHistory(context: Context) :
    SQLiteOpenHelper(context, "sdm.db", null, SCHEMA_VERSION),
    HistoryStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE downloads (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                file_name TEXT,
                state TEXT NOT NULL,
                received INTEGER NOT NULL DEFAULT 0,
                total INTEGER,
                error TEXT,
                saved_name TEXT,
                saved_uri TEXT,
                unconfirmed_size INTEGER NOT NULL DEFAULT 0,
                part_path TEXT,
                etag TEXT,
                last_modified TEXT,
                resumable INTEGER NOT NULL DEFAULT 0,
                segments TEXT,
                created_at INTEGER NOT NULL,
                video_choice TEXT
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Each version adds what it needs, never dropping data.
        if (oldVersion < 2) db.execSQL("ALTER TABLE downloads ADD COLUMN video_choice TEXT") // M15b
    }

    override fun load(): List<DownloadItem> =
        readableDatabase.query("downloads", null, null, null, null, null, "created_at DESC").use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    override fun insert(item: DownloadItem): Long = writableDatabase.insertOrThrow("downloads", null, values(item))

    override fun update(item: DownloadItem) {
        writableDatabase.update("downloads", values(item), "id = ?", arrayOf(item.id.toString()))
    }

    override fun delete(id: Long) {
        writableDatabase.delete("downloads", "id = ?", arrayOf(id.toString()))
    }

    private fun values(item: DownloadItem) = ContentValues().apply {
        put("url", item.url)
        put("file_name", item.fileName)
        put("state", item.state.name)
        put("received", item.receivedBytes)
        put("total", item.totalBytes)
        put("error", item.error)
        put("saved_name", item.savedName)
        put("saved_uri", item.savedUri)
        put("unconfirmed_size", if (item.unconfirmedSize) 1 else 0)
        val partial = item.partial
        put("part_path", partial?.partFile?.path)
        put("etag", partial?.etag)
        put("last_modified", partial?.lastModified)
        put("resumable", if (partial?.resumable == true) 1 else 0)
        put("segments", partial?.segments?.let(Segments::encode))
        put("created_at", item.createdAt)
        put("video_choice", item.videoChoice?.name)
    }

    private fun read(c: Cursor): DownloadItem {
        fun text(name: String): String? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) }
        fun long(name: String): Long? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getLong(it) }
        val total = long("total")
        val fileName = text("file_name")
        val partial = text("part_path")?.let { path ->
            PartialDownload(
                partFile = File(path),
                fileName = fileName ?: "download",
                totalBytes = total,
                etag = text("etag"),
                lastModified = text("last_modified"),
                resumable = long("resumable") == 1L,
                segments = Segments.decode(text("segments"), total),
            )
        }
        val state = runCatching { DownloadState.valueOf(text("state").orEmpty()) }.getOrDefault(DownloadState.FAILED)
        return DownloadItem(
            id = long("id")!!,
            url = text("url").orEmpty(),
            fileName = fileName,
            state = state,
            receivedBytes = long("received") ?: 0,
            totalBytes = total,
            error = text("error"),
            savedName = text("saved_name"),
            savedUri = text("saved_uri"),
            unconfirmedSize = long("unconfirmed_size") == 1L,
            partial = partial,
            createdAt = long("created_at") ?: 0,
            videoChoice = text("video_choice")?.let { name -> VideoChoice.entries.firstOrNull { it.name == name } },
        )
    }

    companion object {
        const val SCHEMA_VERSION = 2
    }
}
