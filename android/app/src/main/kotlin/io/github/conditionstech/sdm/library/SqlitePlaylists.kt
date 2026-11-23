package io.github.conditionstech.sdm.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Playlists in their own small database (M17b), apart from the download history. */
class SqlitePlaylists(context: Context) : SQLiteOpenHelper(context, "playlists.db", null, 1), PlaylistStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE playlist_songs (playlist_id INTEGER NOT NULL, position INTEGER NOT NULL, " +
                "media_id INTEGER NOT NULL, PRIMARY KEY (playlist_id, position))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first; later versions add their migrations here, never dropping data.
    }

    override fun load(): List<Playlist> {
        val db = readableDatabase
        val songs = HashMap<Long, MutableList<Long>>()
        db.rawQuery("SELECT playlist_id, media_id FROM playlist_songs ORDER BY playlist_id, position", null).use { c ->
            while (c.moveToNext()) songs.getOrPut(c.getLong(0)) { mutableListOf() } += c.getLong(1)
        }
        return db.rawQuery("SELECT id, name FROM playlists", null).use { c ->
            buildList { while (c.moveToNext()) add(Playlist(c.getLong(0), c.getString(1), songs[c.getLong(0)].orEmpty())) }
        }
    }

    override fun insert(name: String): Long =
        writableDatabase.insertOrThrow("playlists", null, ContentValues().apply { put("name", name) })

    override fun rename(id: Long, name: String) {
        writableDatabase.update("playlists", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString()))
    }

    override fun delete(id: Long) {
        writableDatabase.inTransaction {
            delete("playlist_songs", "playlist_id = ?", arrayOf(id.toString()))
            delete("playlists", "id = ?", arrayOf(id.toString()))
        }
    }

    /** Replaces the playlist's songs in one transaction, so a crash never leaves half a list. */
    override fun setSongs(id: Long, songIds: List<Long>) {
        writableDatabase.inTransaction {
            delete("playlist_songs", "playlist_id = ?", arrayOf(id.toString()))
            songIds.forEachIndexed { position, mediaId ->
                insertOrThrow(
                    "playlist_songs",
                    null,
                    ContentValues().apply {
                        put("playlist_id", id)
                        put("position", position)
                        put("media_id", mediaId)
                    },
                )
            }
        }
    }

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }
}
