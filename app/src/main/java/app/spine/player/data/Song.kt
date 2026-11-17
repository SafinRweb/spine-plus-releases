package app.spine.player.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * One file on the phone that can be played, as the system media index describes it. Nearly
 * always a song. A video the Downloader saved is carried as one of these too, so that a
 * playlist, the queue and the play log can hold it beside the songs with nothing new to
 * learn: it is told apart by its id, which is the video's own id made negative.
 */
data class Song(
    /** The MediaStore row id (negative for a video, see [videoSongId]); also the player's media id. */
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    /** Folder relative to shared storage, without a trailing slash, e.g. "Music/Artist/Album". */
    val folder: String,
    val fileName: String,
    /** When the file arrived on the phone, in seconds since 1970. */
    val dateAddedSec: Long,
    /** The artist an album is filed under, when the file names one apart from the song's artist. */
    val albumArtist: String? = null,
    val sizeBytes: Long = 0,
) {
    /** The name this song is filed under on the Shelf: its album artist if it has one. */
    val filedUnder: String get() = albumArtist ?: artist

    /** True for a video the Downloader saved, carried here as a song (see the class's note). */
    val isVideo: Boolean get() = id < 0

    val uri: Uri get() = songUri(id)

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build(),
        )
        .build()
}

/** The content URI the player opens for a song's id, or for a video's (which is negative). */
fun songUri(id: Long): Uri =
    if (id < 0) videoUri(-id) else ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

/** The content URI of a video, from its own MediaStore id. Written the way the Downloader writes it. */
fun videoUri(videoId: Long): Uri =
    ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), videoId)

/** The id a video goes by among the songs: its own, made negative, so the two can never clash. */
fun videoSongId(videoId: Long): Long = -videoId
