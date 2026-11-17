package app.spine.player.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** A playlist as stored: its name and the ids of its songs in order. */
data class StoredPlaylist(val id: Long, val name: String, val songIds: List<Long>)

/** How much one song was listened to over some period. */
data class PlayTotal(val plays: Int, val listenedMs: Long)

/**
 * The app's own data, kept in a small SQLite database: playlists the user made, and a log
 * of what was played for the stats. Songs are referred to by their MediaStore id; a song
 * that has since left the phone is simply skipped when the data is read.
 *
 * One instance is shared by the screens and the playback service, so [version] ticks for
 * both whenever anything is written.
 */
class LibraryStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "spine.db", null, 1) {

    private val _version = MutableStateFlow(0)

    /** Changes after every write; collect it to know when to read again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE playlist_songs (playlist_id INTEGER NOT NULL, song_id INTEGER NOT NULL, " +
                "position INTEGER NOT NULL, PRIMARY KEY (playlist_id, song_id))",
        )
        db.execSQL(
            "CREATE TABLE plays (id INTEGER PRIMARY KEY AUTOINCREMENT, song_id INTEGER NOT NULL, " +
                "played_at INTEGER NOT NULL, listened_ms INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX plays_by_time ON plays (played_at)")
        // Favourites is an ordinary playlist that always exists.
        db.insert(
            "playlists",
            null,
            ContentValues().apply {
                put("id", FAVOURITES_ID)
                put("name", "Favourites")
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    suspend fun playlists(): List<StoredPlaylist> = withContext(Dispatchers.IO) {
        val songIds = HashMap<Long, MutableList<Long>>()
        readableDatabase.rawQuery(
            "SELECT playlist_id, song_id FROM playlist_songs ORDER BY playlist_id, position",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                songIds.getOrPut(cursor.getLong(0)) { mutableListOf() } += cursor.getLong(1)
            }
        }
        val result = mutableListOf<StoredPlaylist>()
        readableDatabase.rawQuery("SELECT id, name FROM playlists ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                result += StoredPlaylist(id, cursor.getString(1), songIds[id].orEmpty())
            }
        }
        result
    }

    suspend fun createPlaylist(name: String): Long = write {
        insert(
            "playlists",
            null,
            ContentValues().apply {
                put("name", name)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    /** Gives a playlist a new name. Favourites keeps its own. */
    suspend fun renamePlaylist(id: Long, name: String) = write {
        if (id != FAVOURITES_ID) {
            update("playlists", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString()))
        }
    }

    suspend fun removeSongs(playlistId: Long, songIds: Collection<Long>) = write {
        for (songId in songIds) {
            delete(
                "playlist_songs",
                "playlist_id = ? AND song_id = ?",
                arrayOf(playlistId.toString(), songId.toString()),
            )
        }
    }

    suspend fun deletePlaylist(id: Long) = write {
        if (id != FAVOURITES_ID) {
            delete("playlist_songs", "playlist_id = ?", arrayOf(id.toString()))
            delete("playlists", "id = ?", arrayOf(id.toString()))
        }
    }

    /** Appends songs that are not in the playlist yet. */
    suspend fun addSongs(playlistId: Long, songIds: List<Long>) = write {
        var position = 0
        rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlist_id = ?",
            arrayOf(playlistId.toString()),
        ).use { if (it.moveToFirst()) position = it.getInt(0) }
        for (songId in songIds) {
            val values = ContentValues().apply {
                put("playlist_id", playlistId)
                put("song_id", songId)
                put("position", position)
            }
            if (insertWithOnConflict("playlist_songs", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                position++
            }
        }
    }

    suspend fun removeSong(playlistId: Long, songId: Long) = write {
        delete(
            "playlist_songs",
            "playlist_id = ? AND song_id = ?",
            arrayOf(playlistId.toString(), songId.toString()),
        )
    }

    /**
     * Songs the user took out of a smart playlist, by that playlist's id. A smart playlist is
     * worked out from a rule, so "removing" a song means remembering to leave it out. Smart
     * ids are negative and never clash with stored playlists, so these live in the same
     * table: a row there for a smart id reads as "hidden from it". [addSongs] hides a song,
     * [removeSong] brings one back, [clearSongs] brings them all back.
     */
    suspend fun hiddenFromSmart(): Map<Long, Set<Long>> = withContext(Dispatchers.IO) {
        val hidden = HashMap<Long, MutableSet<Long>>()
        readableDatabase.rawQuery(
            "SELECT playlist_id, song_id FROM playlist_songs WHERE playlist_id < 0",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                hidden.getOrPut(cursor.getLong(0)) { mutableSetOf() } += cursor.getLong(1)
            }
        }
        hidden
    }

    suspend fun clearSongs(playlistId: Long) = write {
        delete("playlist_songs", "playlist_id = ?", arrayOf(playlistId.toString()))
    }

    /**
     * Points everything that named [oldId] at [newId] instead: playlist entries and the play
     * log. For when one copy of a song is kept and another deleted, so favourites and play
     * counts stay with the song.
     */
    suspend fun replaceSong(oldId: Long, newId: Long) = write {
        val ids = arrayOf(newId.toString(), oldId.toString())
        execSQL("UPDATE OR IGNORE playlist_songs SET song_id = ? WHERE song_id = ?", ids)
        // Where the kept copy was already in a playlist, the old entry is simply dropped.
        delete("playlist_songs", "song_id = ?", arrayOf(oldId.toString()))
        execSQL("UPDATE plays SET song_id = ? WHERE song_id = ?", ids)
    }

    /** Logs that a song was listened to. Called by the playback service. */
    suspend fun recordPlay(songId: Long, listenedMs: Long) = write {
        insert(
            "plays",
            null,
            ContentValues().apply {
                put("song_id", songId)
                put("played_at", System.currentTimeMillis())
                put("listened_ms", listenedMs)
            },
        )
    }

    /** Every sitting of watching a video since [sinceMs]: videos are logged under negative ids. */
    suspend fun videoPlays(sinceMs: Long): List<WatchRow> = withContext(Dispatchers.IO) {
        val rows = ArrayList<WatchRow>()
        readableDatabase.rawQuery(
            "SELECT song_id, played_at, listened_ms FROM plays WHERE song_id < 0 AND played_at >= ?",
            arrayOf(sinceMs.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) rows += WatchRow(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
        }
        rows
    }

    /** Plays and listening time per song since [sinceMs] (epoch milliseconds; 0 for all time). */
    suspend fun playTotals(sinceMs: Long): Map<Long, PlayTotal> = withContext(Dispatchers.IO) {
        val totals = HashMap<Long, PlayTotal>()
        readableDatabase.rawQuery(
            "SELECT song_id, COUNT(*), SUM(listened_ms) FROM plays WHERE played_at >= ? GROUP BY song_id",
            arrayOf(sinceMs.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                totals[cursor.getLong(0)] = PlayTotal(cursor.getInt(1), cursor.getLong(2))
            }
        }
        totals
    }

    private suspend fun <T> write(block: SQLiteDatabase.() -> T): T = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = db.block()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }.also { _version.value++ }

    companion object {
        const val FAVOURITES_ID = 1L

        @Volatile
        private var instance: LibraryStore? = null

        fun get(context: Context): LibraryStore =
            instance ?: synchronized(this) {
                instance ?: LibraryStore(context.applicationContext).also { instance = it }
            }
    }
}
