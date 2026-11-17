package app.spine.player.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Finds the lyrics for a song. It looks, in this order, at lyrics the user added in the
 * app, at a .lrc file with the song's name in the song's folder, and inside the song file.
 *
 * Added lyrics are kept as text files in the app's own storage, named after the song's
 * id; the song file is never changed.
 */
class LyricsFinder(context: Context) {

    private val context = context.applicationContext
    private val addedFolder = File(this.context.filesDir, "lyrics")

    suspend fun find(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        fun lyrics(text: String?, origin: LyricsOrigin): Lyrics? =
            text?.let(::parseLyrics)?.takeIf { it.isNotEmpty() }?.let { Lyrics(it, origin) }

        lyrics(runCatching { addedFile(song.id).takeIf { it.isFile }?.readText() }.getOrNull(), LyricsOrigin.Added)
            ?: lyrics(runCatching { lrcBesideSong(song) }.getOrNull(), LyricsOrigin.LrcFile)
            ?: lyrics(runCatching { insideSongFile(song) }.getOrNull(), LyricsOrigin.SongFile)
    }

    /** Keeps [text] as the song's lyrics. False if it holds nothing that reads as lyrics. */
    suspend fun add(songId: Long, text: String): Boolean = withContext(Dispatchers.IO) {
        if (parseLyrics(text).isEmpty()) return@withContext false
        addedFolder.mkdirs()
        addedFile(songId).writeText(text)
        true
    }

    /** As [add], reading the text from a file the user picked. */
    suspend fun addFrom(songId: Long, file: Uri): Boolean = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(file)?.use { it.readLyricsText() }
        }.getOrNull() ?: return@withContext false
        add(songId, text)
    }

    suspend fun remove(songId: Long) = withContext(Dispatchers.IO) {
        addedFile(songId).delete()
        Unit
    }

    private fun addedFile(songId: Long) = File(addedFolder, "$songId.txt")

    /** "Song.mp3" is matched by "Song.lrc" in the same folder. */
    private fun lrcBesideSong(song: Song): String? {
        val lrcName = song.fileName.substringBeforeLast('.') + ".lrc"

        // The plain way first: by path, next to the song.
        @Suppress("DEPRECATION")
        val songPath = context.contentResolver.query(
            song.uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (songPath != null) {
            val beside = File(File(songPath).parentFile, lrcName)
            val text = runCatching {
                beside.takeIf { it.isFile && it.canRead() }?.inputStream()?.use { it.readLyricsText() }
            }.getOrNull()
            if (text != null) return text
        }

        // Some phones only hand such files out through the media index.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val id = context.contentResolver.query(
                files,
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
                arrayOf(song.folder + "/", lrcName),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            if (id != null) {
                val uri = Uri.withAppendedPath(files, id.toString())
                return context.contentResolver.openInputStream(uri)?.use { it.readLyricsText() }
            }
        }
        return null
    }

    /** Lyrics tags: USLT in MP3 files, the lyrics atom in M4A, LYRICS in FLAC, Ogg and Opus. */
    @OptIn(UnstableApi::class)
    private fun insideSongFile(song: Song): String? {
        MetadataRetriever.Builder(context, MediaItem.fromUri(song.uri)).build().use { retriever ->
            val groups = retriever.retrieveTrackGroups().get(RETRIEVE_SECONDS, TimeUnit.SECONDS)
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                for (formatIndex in 0 until group.length) {
                    val metadata = group.getFormat(formatIndex).metadata ?: continue
                    for (entryIndex in 0 until metadata.length()) {
                        lyricsIn(metadata[entryIndex])?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return null
    }

    @OptIn(UnstableApi::class)
    private fun lyricsIn(entry: Metadata.Entry): String? = when (entry) {
        is TextInformationFrame -> if (entry.id == "USLT") entry.values.joinToString("\n") else null
        is BinaryFrame -> if (entry.id == "USLT" || entry.id == "ULT") decodeUslt(entry.data) else null
        is VorbisComment ->
            if (entry.key.equals("LYRICS", ignoreCase = true) ||
                entry.key.equals("UNSYNCEDLYRICS", ignoreCase = true)
            ) {
                entry.value
            } else {
                null
            }

        else -> null
    }

    /**
     * An ID3 "unsynchronised lyrics" frame: one byte naming the text encoding, three for the
     * language, a description ending in a zero, then the lyrics.
     */
    private fun decodeUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val (charset, wide) = when (data[0].toInt()) {
            0 -> Charsets.ISO_8859_1 to false
            1 -> Charsets.UTF_16 to true
            2 -> Charsets.UTF_16BE to true
            else -> Charsets.UTF_8 to false
        }
        // Skip the description: it ends at the first zero, two zero bytes in 16-bit text.
        var index = 4
        while (index < data.size) {
            if (wide) {
                if (index + 1 < data.size && data[index].toInt() == 0 && data[index + 1].toInt() == 0) {
                    index += 2
                    break
                }
                index += 2
            } else {
                if (data[index].toInt() == 0) {
                    index += 1
                    break
                }
                index += 1
            }
        }
        if (index >= data.size) return null
        return String(data, index, data.size - index, charset).trim('\u0000', '﻿').replace("\r\n", "\n").replace('\r', '\n')
    }

    private companion object {
        const val RETRIEVE_SECONDS = 8L

        /** Far more than any lyrics need; stops a wrongly picked file from filling memory. */
        const val MAX_BYTES = 512 * 1024
    }

    /** Reads text, honouring a byte-order mark; without one, UTF-8. */
    private fun InputStream.readLyricsText(): String {
        val bytes = readNBytesCompat(MAX_BYTES)
        fun starts(vararg mark: Int) = bytes.size >= mark.size && mark.indices.all { bytes[it].toInt() and 0xFF == mark[it] }
        val charset: Charset = when {
            starts(0xFF, 0xFE) || starts(0xFE, 0xFF) -> Charsets.UTF_16
            else -> Charsets.UTF_8
        }
        return String(bytes, charset)
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
