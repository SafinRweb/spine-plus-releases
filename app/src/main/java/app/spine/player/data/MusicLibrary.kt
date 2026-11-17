package app.spine.player.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the phone's music from the system media index. Needs the audio read permission. */
object MusicLibrary {

    /** The index has had this column on every Android version the app runs on, though it was only named publicly later. */
    private const val ALBUM_ARTIST = "album_artist"

    private val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Where the file lives: a relative folder from Android 10, the full path before it. */
    @Suppress("DEPRECATION")
    private val pathColumn =
        if (hasRelativePath) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA

    private val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.SIZE,
        ALBUM_ARTIST,
        pathColumn,
    )

    /** All music files, sorted by title. Ringtones, alarms and notification sounds are left out. */
    suspend fun loadSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val collection = if (hasRelativePath) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val songs = mutableListOf<Song>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val added = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val albumArtist = cursor.getColumnIndexOrThrow(ALBUM_ARTIST)
            val path = cursor.getColumnIndexOrThrow(pathColumn)
            while (cursor.moveToNext()) {
                // MediaStore packs the disc number into the thousands: 2003 is disc 2, track 3.
                val rawTrack = cursor.getInt(track)
                songs += Song(
                    id = cursor.getLong(id),
                    title = cursor.getString(title).orEmpty().ifBlank { "Untitled" },
                    artist = cursor.getString(artist).known() ?: "Unknown artist",
                    album = cursor.getString(album).known() ?: "Unknown album",
                    albumId = cursor.getLong(albumId),
                    durationMs = cursor.getLong(duration),
                    trackNumber = rawTrack % 1000,
                    discNumber = rawTrack / 1000,
                    folder = folderOf(cursor.getString(path).orEmpty()),
                    fileName = cursor.getString(name).orEmpty(),
                    dateAddedSec = cursor.getLong(added),
                    albumArtist = cursor.getString(albumArtist).known(),
                    sizeBytes = cursor.getLong(size),
                )
            }
        }
        songs
    }

    /** MediaStore writes the literal "<unknown>" when a tag is missing. */
    private fun String?.known(): String? =
        this?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }

    /** Normalises either path form to "Music/Artist/Album". */
    private fun folderOf(path: String): String {
        if (hasRelativePath) return path.trim('/')
        @Suppress("DEPRECATION")
        val storageRoot = Environment.getExternalStorageDirectory().path
        return path.substringBeforeLast('/', "").removePrefix(storageRoot).trim('/')
    }
}
