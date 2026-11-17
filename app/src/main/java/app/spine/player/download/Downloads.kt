package app.spine.player.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.spine.player.data.Song
import app.spine.player.data.SongTags
import app.spine.player.data.TagEditor
import app.spine.player.data.suggestFromName
import app.spine.player.data.videoSongId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class GetState { Waiting, Downloading, Finishing, Done, Failed }

/**
 * How one thing is to be fetched.
 *
 * As a song: the kind of file ([saveAs]), and, when one particular sound was picked from
 * what the link offers, which ([formatId]). As a video ([video]): how tall at most
 * ([height]), or the particular picture picked ([formatId], with [mp4] saying whether it
 * goes in an MP4 or an MKV), and whether subtitles go in. Either way, whether the cover
 * goes in the file.
 */
data class GetOptions(
    val saveAs: SaveAs = SaveAs.M4a,
    val cover: Boolean = true,
    val formatId: String? = null,
    val video: Boolean = false,
    val height: Int = 1080,
    val mp4: Boolean = true,
    val subtitles: Boolean = false,
)

/** A video that was downloaded, as it is listed in the Downloader tab: enough to name it and open it. */
data class SavedVideo(
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    /** The address it was downloaded from; empty for one saved before that was kept. */
    val source: String = "",
)

/** One song on its way to the phone, or just arrived, or failed. */
data class Getting(
    val song: Found,
    val options: GetOptions = GetOptions(),
    val state: GetState = GetState.Waiting,
    /** How much has arrived, from 0 to 1. */
    val progress: Float = 0f,
    /** The song's id in the phone's library, once it is there. */
    val songId: Long? = null,
    /** For a video: where it was saved, as an address the phone's player can open. */
    val videoUri: String? = null,
    /** Why it failed, in words for the user. */
    val problem: String? = null,
) {
    val busy: Boolean get() = state == GetState.Waiting || state == GetState.Downloading || state == GetState.Finishing
}

/**
 * The queue of downloads. Songs are fetched in the order they were asked for, a few at a
 * time. It lives for as long as the app's process does; [DownloadService] keeps that alive
 * while there is work left.
 */
object Downloads {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _items = MutableStateFlow<List<Getting>>(emptyList())

    /** Everything asked for since the app started, oldest first. */
    val items: StateFlow<List<Getting>> = _items.asStateFlow()

    private val _arrived = MutableStateFlow(0)

    /** Counts songs that reached the phone, so the library knows to read itself again. */
    val arrived: StateFlow<Int> = _arrived.asStateFlow()

    /**
     * How many songs download at the same time. Most of a song's wait is the engine getting
     * ready, which is work for the phone and not for the connection, so three side by side
     * finish an album in about a third of the time.
     */
    private const val AT_ONCE = 3
    private val working = AtomicInteger(0)

    private const val PREFS = "downloads"
    private const val KEY_SAVE_AS = "save_as"
    private const val KEY_COVER = "cover"
    private const val KEY_VIDEO = "video"
    private const val KEY_HEIGHT = "height"
    private const val KEY_SUBTITLES = "subtitles"
    private const val KEY_VIDEOS = "recent_videos"
    private const val KEY_SOURCES = "sources"
    private const val SOURCES_KEPT = 300
    const val VIDEO_FOLDER = "Spine"
    private const val KEY_RECENT = "recent"
    private const val RECENT_KEPT = 30
    const val FOLDER = "Spine"

    /** "As usual": how a song is fetched when nothing else is chosen for it. */
    fun preset(context: Context): GetOptions {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_SAVE_AS, null)
        return GetOptions(
            saveAs = SaveAs.entries.firstOrNull { it.name == name } ?: SaveAs.M4a,
            cover = prefs.getBoolean(KEY_COVER, true),
            video = prefs.getBoolean(KEY_VIDEO, false),
            height = prefs.getInt(KEY_HEIGHT, 1080),
            subtitles = prefs.getBoolean(KEY_SUBTITLES, false),
        )
    }

    fun setPreset(context: Context, options: GetOptions) {
        // A sound picked for one download is not the usual from then on; the kind of file
        // only becomes the usual when it was chosen as such.
        val saveAs = if (options.formatId == null) options.saveAs else preset(context).saveAs
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_SAVE_AS, saveAs.name)
            putBoolean(KEY_COVER, options.cover)
            // Audio or video, the tallest picture wanted, and subtitles are remembered too.
            putBoolean(KEY_VIDEO, options.video)
            putInt(KEY_HEIGHT, options.height)
            putBoolean(KEY_SUBTITLES, options.subtitles)
        }
    }

    /** The address a song was downloaded from, if this app fetched it; for "Copy the link" and "Open the page". */
    fun sourceOf(context: Context, songId: Long): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SOURCES, "").orEmpty()
            .lineSequence().firstOrNull { it.substringBefore('\t') == songId.toString() }?.substringAfter('\t', "")
            ?.takeIf { it.isNotBlank() }

    private fun rememberSource(context: Context, songId: Long, url: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kept = prefs.getString(KEY_SOURCES, "").orEmpty().lineSequence()
            .filter { it.isNotBlank() && it.substringBefore('\t') != songId.toString() }.take(SOURCES_KEPT - 1)
        prefs.edit { putString(KEY_SOURCES, (sequenceOf("$songId\t$url") + kept).joinToString("\n")) }
    }

    /** The videos downloaded lately, newest first. They outlive the app being closed. */
    fun recentVideos(context: Context): List<SavedVideo> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VIDEOS, "").orEmpty()
            .lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) null else SavedVideo(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: 0, parts.getOrNull(4).orEmpty())
            }.toList()

    /**
     * Every video this app has downloaded that is still on the phone, newest first, for the
     * Videos tab. It is read from the phone's Movies/Spine folder, so a video deleted in
     * another app is gone from here too; the name, the artist and the link come from what
     * the app noted when it downloaded each one.
     */
    suspend fun videos(context: Context): List<SavedVideo> = withContext(Dispatchers.IO) {
        // Android may write the same file's address with either name for the phone's storage.
        val noted = recentVideos(context).associateBy { it.uri.substringAfterLast('/') }
        val found = mutableListOf<SavedVideo>()
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.ARTIST,
                    MediaStore.Video.Media.DURATION,
                ),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_MOVIES}/$VIDEO_FOLDER/%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC, ${MediaStore.Video.Media._ID} DESC",
            )?.use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.getLong(0)
                    val known = noted[id.toString()]
                    val artist = rows.getString(2)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                    found += SavedVideo(
                        uri = android.content.ContentUris.withAppendedId(collection, id).toString(),
                        title = known?.title?.takeIf { it.isNotBlank() } ?: rows.getString(1).orEmpty().substringBeforeLast('.'),
                        artist = known?.artist?.takeIf { it.isNotBlank() } ?: artist.orEmpty(),
                        durationMs = rows.getLong(3).takeIf { it > 0 } ?: known?.durationMs ?: 0,
                        source = known?.source.orEmpty(),
                    )
                }
            }
        }
        found
    }

    /**
     * The same videos as [videos], each as a [Song] with a negative id, for the library:
     * that is the form in which a playlist, the queue and the play log hold a video.
     */
    suspend fun videoSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val noted = recentVideos(context).associateBy { it.uri.substringAfterLast('/') }
        val found = mutableListOf<Song>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.ARTIST,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE,
                ),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_MOVIES}/$VIDEO_FOLDER/%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC, ${MediaStore.Video.Media._ID} DESC",
            )?.use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.getLong(0)
                    val known = noted[id.toString()]
                    val name = rows.getString(1).orEmpty()
                    val artist = rows.getString(2)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                    found += Song(
                        id = videoSongId(id),
                        title = known?.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                        artist = known?.artist?.takeIf { it.isNotBlank() } ?: artist.orEmpty(),
                        album = "",
                        albumId = 0,
                        durationMs = rows.getLong(3).takeIf { it > 0 } ?: known?.durationMs ?: 0,
                        trackNumber = 0,
                        discNumber = 0,
                        folder = "${Environment.DIRECTORY_MOVIES}/$VIDEO_FOLDER",
                        fileName = name,
                        dateAddedSec = rows.getLong(4),
                        sizeBytes = rows.getLong(5),
                    )
                }
            }
        }
        found
    }

    private fun remember(context: Context, video: SavedVideo) {
        fun plain(text: String) = text.replace('\t', ' ').replace('\n', ' ')
        val all = (listOf(video) + recentVideos(context).filterNot { it.uri == video.uri }).take(SOURCES_KEPT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_VIDEOS, all.joinToString("\n") { "${it.uri}\t${plain(it.title)}\t${plain(it.artist)}\t${it.durationMs}\t${it.source}" })
        }
    }

    /** Drops a video from the list, for when its file has gone. */
    fun forgetVideo(context: Context, uri: String) {
        val left = recentVideos(context).filterNot { it.uri == uri }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_VIDEOS, left.joinToString("\n") { "${it.uri}\t${it.title}\t${it.artist}\t${it.durationMs}\t${it.source}" })
        }
        _arrived.value++
    }

    /** Library ids of the songs downloaded lately, newest first. They outlive the app being closed. */
    fun recent(context: Context): List<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, "").orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }

    private fun remember(context: Context, songId: Long) {
        val ids = (listOf(songId) + recent(context)).distinct().take(RECENT_KEPT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_RECENT, ids.joinToString(",")) }
    }

    /**
     * Adds [song] to the end of the queue, to be fetched as [options] say ("as usual" if
     * not given). Asking twice for a song that is on its way does nothing.
     */
    fun add(context: Context, song: Found, options: GetOptions = preset(context)) {
        val app = context.applicationContext
        val known = _items.value.firstOrNull { it.song.id == song.id }
        if (known != null && known.state != GetState.Failed) return
        _items.update { items -> items.filterNot { it.song.id == song.id } + Getting(song, options) }
        // The service keeps the app alive in the background; the work itself is done here.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
        startWorkers(app)
    }

    /** Makes sure enough downloads are running for what is waiting, up to [AT_ONCE]. */
    private fun startWorkers(context: Context) {
        while (_items.value.any { it.state == GetState.Waiting }) {
            val running = working.get()
            if (running >= AT_ONCE) return
            if (!working.compareAndSet(running, running + 1)) continue
            scope.launch {
                try {
                    work(context)
                } finally {
                    working.decrementAndGet()
                    // A song may have been added just as this one was finishing.
                    startWorkers(context)
                }
            }
        }
    }

    /** Takes the next waiting song and marks it as started, or returns null when none is left. */
    private fun claim(): Getting? {
        var claimed: Getting? = null
        _items.update { items ->
            val index = items.indexOfFirst { it.state == GetState.Waiting }
            claimed = items.getOrNull(index)
            if (index < 0) {
                items
            } else {
                items.toMutableList().also { it[index] = it[index].copy(state = GetState.Downloading, progress = 0f) }
            }
        }
        return claimed
    }

    /** Stops a download that is waiting or running and takes it off the list. */
    fun stop(id: String) {
        val item = _items.value.firstOrNull { it.song.id == id } ?: return
        when (item.state) {
            GetState.Downloading -> Engine.stop(id)
            // Too late to stop cleanly: the file is being put in place.
            GetState.Finishing -> Unit
            else -> _items.update { items -> items.filterNot { it.song.id == id } }
        }
    }

    /** Takes a failed download off the list. */
    fun dismiss(id: String) {
        _items.update { items -> items.filterNot { it.song.id == id && it.state == GetState.Failed } }
    }

    private fun change(id: String, change: (Getting) -> Getting) {
        _items.update { items -> items.map { if (it.song.id == id) change(it) else it } }
    }

    private suspend fun work(context: Context) {
        while (true) {
            val next = claim() ?: break
            val id = next.song.id
            val folder = File(context.cacheDir, "getting/$id")
            try {
                val saveAs = next.options.saveAs
                var shown = 0f
                val file = Engine.fetch(context, next.song, next.options, folder, processId = id) { progress ->
                    // The engine reports many times a second; the screen needs a few.
                    if (progress - shown >= 0.01f || progress >= 1f) {
                        shown = progress
                        change(id) { it.copy(progress = progress) }
                    }
                }
                change(id) { it.copy(state = GetState.Finishing, progress = 1f) }
                if (next.options.video) {
                    // A video is not music: it goes to the phone's Movies, not to the Shelf.
                    val known = Engine.known(next.song)
                    val uri = addVideo(context, file, known)
                    remember(context, SavedVideo(uri, known.title, known.artist, known.durationMs, next.song.url))
                    change(id) { it.copy(state = GetState.Done, videoUri = uri) }
                } else {
                    val named = name(file, Engine.known(next.song))
                    val songId = addToLibrary(context, file, named, saveAs)
                    remember(context, songId)
                    rememberSource(context, songId, next.song.url)
                    change(id) { it.copy(state = GetState.Done, songId = songId) }
                }
                _arrived.value++
            } catch (stopped: Stopped) {
                _items.update { items -> items.filterNot { it.song.id == id } }
            } catch (fault: Exception) {
                val problem = (fault as? EngineFault)?.message ?: "The download did not work. Try again in a moment."
                change(id) { it.copy(state = GetState.Failed, problem = problem) }
            } finally {
                folder.deleteRecursively()
            }
        }
    }

    /**
     * Writes the song's title, artist and album into [file] and returns them. A catalogue
     * song's names are used as they are; an upload's name goes through the Tidy names rules
     * first, so "Artist - Title (Official Video)" arrives as "Title" by "Artist".
     */
    private suspend fun name(file: File, song: Found): Found = withContext(Dispatchers.IO) {
        val tidied = if (song.tidy) null else suggestFromName(song.title, song.artist)
        val named = song.copy(title = tidied?.title ?: song.title, artist = tidied?.artist ?: song.artist)
        val changes = buildMap {
            put(SongTags.TITLE, named.title)
            if (named.artist.isNotBlank()) put(SongTags.ARTIST, named.artist)
            named.album?.takeIf { it.isNotBlank() }?.let { put(SongTags.ALBUM, it) }
        }
        // If the names cannot be written the song is still good; it keeps what the engine gave it.
        runCatching { TagEditor.editCopy(file, changes) }
        named
    }

    /**
     * Hands a finished video to Android's media index, under Movies/Spine, and returns the
     * address the phone's own player opens it by.
     */
    private suspend fun addVideo(context: Context, file: File, song: Found): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" - ")
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("""\s+"""), " ").trim()
            .take(120).ifEmpty { "Video" }
        val kind = file.extension.lowercase()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.$kind")
            put(MediaStore.Video.Media.MIME_TYPE, if (kind == "mp4") "video/mp4" else if (kind == "webm") "video/webm" else "video/x-matroska")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$VIDEO_FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw EngineFault("The phone would not take the new video.")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw EngineFault("The phone would not take the new video.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (fault: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw fault
        }
        uri.toString()
    }

    /**
     * Hands the finished file to Android's media index, under Music/Spine, and returns its
     * id there. A file the app adds this way belongs to it, so it can be renamed, edited
     * and deleted later without Android asking the user each time.
     */
    private suspend fun addToLibrary(context: Context, file: File, song: Found, saveAs: SaveAs): Long =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" - ")
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("""\s+"""), " ").trim()
                .take(120).ifEmpty { "Song" }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.${saveAs.extension}")
                put(MediaStore.Audio.Media.MIME_TYPE, saveAs.mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw EngineFault("The phone would not take the new song.")
            try {
                resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: throw EngineFault("The phone would not take the new song.")
                // Lifting "pending" is what makes Android read the file and list it.
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (fault: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw fault
            }
            ContentUris.parseId(uri)
        }
}
